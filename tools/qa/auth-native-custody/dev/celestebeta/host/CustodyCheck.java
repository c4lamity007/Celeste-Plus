package dev.celestebeta.host;

import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone synthetic fixture. No auth, media, game, device IDs or network. */
public final class CustodyCheck {
    private static int checks;
    private static final byte[] SAMPLE = "isolated-native-custody-fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private record Staged(Path path, WeakReference<ClassLoader> owner) { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && arguments[0].equals("child")) {
            try (var custody = new AuthNativeBridge.Custodian(Path.of(arguments[1]), false, Files::delete);
                 var owner = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
                Path file = custody.stage(owner, SAMPLE, AuthNativeBridge.digest(SAMPLE));
                System.out.println(file); Reference.reachabilityFence(owner);
            }
            return;
        }
        check(AuthNativeBridge.class.getClassLoader() == null, "bridge is canonical bootstrap-defined");
        Path base = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectory(base);
        activeAndGuard(base.resolve("active"));
        invalidInput(base.resolve("invalid"));
        tamperedReceipt(base.resolve("receipt"));
        copiedReceipt(base.resolve("copied"));
        unownedEntry(base.resolve("unowned"));
        replacedFile(base.resolve("replaced"));
        deferredDelete(base.resolve("deferred"));
        staleProcess(base.resolve("stale"));
        activeStaleOwner(base.resolve("active-stale"));
        hostileRoot(base.resolve("hostile"));
        configuredRoot(base.resolve("configured"), base.resolve("other-root"));
        if (arguments.length >= 3) actualDll(base.resolve("actual"), Path.of(arguments[1]), Path.of(arguments[2]));
        if (arguments.length == 4) {
            Path junction = Path.of(arguments[3]);
            expectIo(() -> new AuthNativeBridge.Custodian(junction, false, Files::delete), "junction root rejected");
            expectIo(() -> new AuthNativeBridge.Custodian(junction.resolve("child"), false, Files::delete), "junction ancestor rejected");
            check(Files.readString(junction.resolve("unowned.txt")).equals("preserve"), "junction target remains untouched");
            check(!Files.exists(junction.resolve("custody.key")) && !Files.exists(junction.resolve("child")), "hostile path creates no custody state");
        }
        System.out.println("PASS checks=" + checks + " actual_native=" + (arguments.length >= 3));
    }

    private static void activeAndGuard(Path root) throws Exception {
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete);
             var owner = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            Path dll = custody.stage(owner, SAMPLE, AuthNativeBridge.digest(SAMPLE));
            check(custody.sweep() == 0 && Files.exists(dll), "active owner preserved");
            expectIo(() -> Files.write(dll, SAMPLE), "active file write denied");
            expectIo(() -> Files.move(dll, dll.resolveSibling("replacement.dll")), "active file replacement denied");
            check(Arrays.equals(Files.readAllBytes(dll), SAMPLE), "stage exact bytes");
            Reference.reachabilityFence(owner);
        }
    }

    private static void invalidInput(Path root) throws Exception {
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete);
             var owner = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            expectIo(() -> custody.stage(owner, SAMPLE, "0".repeat(64)), "wrong resource digest denied");
            expectIo(() -> custody.stage(owner, SAMPLE, "../x"), "malformed digest denied");
            expectIo(() -> custody.stage(null, SAMPLE, AuthNativeBridge.digest(SAMPLE)), "no owner denied");
            expectIo(() -> custody.stage(AuthNativeBridge.class.getClassLoader(), SAMPLE, AuthNativeBridge.digest(SAMPLE)), "resident owner denied");
            try (var paths = Files.list(root)) { check(paths.count() == 1, "bad inputs create no native path"); }
        }
    }

    private static Staged stageAndDrop(AuthNativeBridge.Custodian custody) throws Exception {
        try (var owner = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            return new Staged(custody.stage(owner, SAMPLE, AuthNativeBridge.digest(SAMPLE)), new WeakReference<>(owner));
        }
    }
    private static void collected(WeakReference<?> reference) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (!reference.refersTo(null) && System.nanoTime() < deadline) { System.gc(); Thread.sleep(25); }
        check(reference.refersTo(null), "payload owner is collectible");
    }

    private static void tamperedReceipt(Path root) throws Exception {
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete)) {
            Staged staged = stageAndDrop(custody); collected(staged.owner);
            Path receipt = staged.path.resolveSibling("owner.cst"); byte[] bytes = Files.readAllBytes(receipt);
            bytes[bytes.length - 1] ^= 1; Files.write(receipt, bytes);
            check(custody.sweep() == 0 && Files.exists(staged.path), "tampered ownership preserved");
        }
    }
    private static void copiedReceipt(Path root) throws Exception {
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete)) {
            Staged staged = stageAndDrop(custody); collected(staged.owner);
            Path copy = Files.createDirectory(root.resolve("auth-native-copied"));
            Files.copy(staged.path, copy.resolve("auth.dll"));
            Files.copy(staged.path.resolveSibling("owner.cst"), copy.resolve("owner.cst"));
            check(custody.sweep() == 1 && Files.exists(copy.resolve("auth.dll")), "copied receipt cannot authorize another directory");
        }
    }
    private static void unownedEntry(Path root) throws Exception {
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete)) {
            Staged staged = stageAndDrop(custody); collected(staged.owner);
            Path unrelated = staged.path.resolveSibling("user.txt"); Files.writeString(unrelated, "keep");
            check(custody.sweep() == 0 && Files.exists(staged.path) && Files.readString(unrelated).equals("keep"), "unowned entry preserves directory");
            Files.delete(unrelated);
            check(custody.sweep() == 1 && !Files.exists(staged.path), "unchanged owned file retires after obstruction removed");
        }
    }
    private static void replacedFile(Path root) throws Exception {
        AtomicBoolean deny = new AtomicBoolean(true);
        try (var custody = new AuthNativeBridge.Custodian(root, false, file -> { if (deny.get()) throw new AccessDeniedException(file.toString()); Files.delete(file); })) {
            Staged staged = stageAndDrop(custody); collected(staged.owner);
            check(custody.sweep() == 0, "cleanup releases guard before deletion attempt");
            Files.delete(staged.path); Files.write(staged.path, SAMPLE, StandardOpenOption.CREATE_NEW);
            deny.set(false);
            check(custody.sweep() == 0 && Files.exists(staged.path), "same-byte replacement identity preserved");
        }
    }
    private static void deferredDelete(Path root) throws Exception {
        AtomicBoolean deny = new AtomicBoolean(true);
        try (var custody = new AuthNativeBridge.Custodian(root, false, file -> { if (deny.get()) throw new AccessDeniedException(file.toString()); Files.delete(file); })) {
            Staged staged = stageAndDrop(custody); collected(staged.owner);
            check(custody.sweep() == 0 && Files.exists(staged.path.resolveSibling("owner.cst")), "locked DLL defers without losing receipt");
            deny.set(false);
            check(custody.sweep() == 1 && !Files.exists(staged.path.getParent()), "retry deletes only unchanged owned scope");
        }
    }
    private static void staleProcess(Path root) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), CustodyCheck.class.getName(), "child", root.toString())
            .redirectErrorStream(true).start();
        if (!child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(); throw new AssertionError("owned fixture timed out"); }
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        check(child.exitValue() == 0, "owned child creates authenticated receipt");
        Path staged = Path.of(output); check(staged.startsWith(root) && Files.exists(staged), "child left owned fixture file");
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete)) {
            check(!Files.exists(staged) && custody.tracked() == 0, "next-start cleanup follows exact dead process identity");
        }
    }
    private static void activeStaleOwner(Path root) throws Exception {
        try (var first = new AuthNativeBridge.Custodian(root, false, Files::delete);
             var owner = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            Path dll = first.stage(owner, SAMPLE, AuthNativeBridge.digest(SAMPLE));
            try (var second = new AuthNativeBridge.Custodian(root, false, Files::delete)) {
                check(second.sweep() == 0 && Files.exists(dll), "untracked live process is never treated as dead");
            }
            Reference.reachabilityFence(owner);
        }
    }
    private static void hostileRoot(Path path) throws Exception {
        Files.writeString(path, "user data");
        expectIo(() -> new AuthNativeBridge.Custodian(path, false, Files::delete), "regular-file root rejected");
        check(Files.readString(path).equals("user data"), "hostile root untouched");
    }
    private static void configuredRoot(Path root, Path other) throws Exception {
        AuthNativeBridge.configure(root); AuthNativeBridge.configure(root);
        expectIo(() -> AuthNativeBridge.configure(other), "parent cannot silently reconfigure custody");
        Staged staged = stageViaBridgeAndDrop(); collected(staged.owner);
        Thread cleaner = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals("celeste-auth-native-custody")).findFirst().orElseThrow();
        check(cleaner.isDaemon() && cleaner.getContextClassLoader() == AuthNativeBridge.class.getClassLoader(), "cleanup thread has parent context and daemon lifetime");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(staged.path) && System.nanoTime() < deadline) Thread.sleep(50);
        check(!Files.exists(staged.path.getParent()), "parent daemon retires collected owner without payload callback");
    }
    private static Staged stageViaBridgeAndDrop() throws Exception {
        try (var owner = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            return new Staged(Path.of(AuthNativeBridge.stage(owner, SAMPLE, AuthNativeBridge.digest(SAMPLE))), new WeakReference<>(owner));
        }
    }

    private static void actualDll(Path root, Path source, Path fixtureClasses) throws Exception {
        byte[] dll = Files.readAllBytes(source); String digest = AuthNativeBridge.digest(dll);
        try (var custody = new AuthNativeBridge.Custodian(root, false, Files::delete)) {
            for (int pass = 0; pass < 3; pass++) {
                Staged staged = loadAndDrop(custody, dll, digest, fixtureClasses);
                collected(staged.owner);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                while (Files.exists(staged.path) && System.nanoTime() < deadline) { custody.sweep(); System.gc(); Thread.sleep(50); }
                check(!Files.exists(staged.path.getParent()), "real Windows DLL unload and custody cleanup pass " + pass);
            }
        }
    }
    private static Staged loadAndDrop(AuthNativeBridge.Custodian custody, byte[] bytes, String digest, Path fixtureClasses) throws Exception {
        try (var owner = new URLClassLoader(new URL[] {fixtureClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Path file = custody.stage(owner, bytes, digest);
            Class<?> type = Class.forName("fixture.NativeOwner", true, owner);
            type.getMethod("load", String.class).invoke(null, file.toString());
            check(type.getClassLoader() == owner && Files.exists(file), "real System.load retains payload ownership");
            check(custody.sweep() == 0, "real active native owner preserved");
            return new Staged(file, new WeakReference<>(owner));
        }
    }
    @FunctionalInterface interface IoAction { void run() throws Exception; }
    private static void expectIo(IoAction action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (IOException expected) { checks++; }
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
}
