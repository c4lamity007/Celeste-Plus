package dev.celestebeta.host.fixture;

import dev.celestebeta.host.ResourceReloadBridge;
import dev.celestebeta.host.instrument.ResourceReloadTransformer;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Offline actual-bytecode checks plus isolated JDK-only execution fixtures. */
public final class ReloadHooksCheck {
    private static final String NAMED = "net/minecraft/client/MinecraftClient";
    private static final String INTERMEDIARY = "net/minecraft/class_310";
    private static final String CALLBACKS = "dev/celestebeta/host/fixture/ReloadHooksCheck$Callbacks";
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static int assertions;

    private ReloadHooksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected cached intermediary JAR path");
        byte[] named = resource(NAMED + ".class");
        byte[] intermediary;
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile());
             InputStream stream = jar.getInputStream(jar.getJarEntry(INTERMEDIARY + ".class"))) {
            intermediary = stream.readAllBytes();
        }
        actualBytecode(NAMED, named);
        actualBytecode(INTERMEDIARY, intermediary);
        bridgeOwnershipAndGuards();
        bridgeMalformedHandlers();
        visibility();
        executableFixture(NAMED);
        executableFixture(INTERMEDIARY);
        pendingStackCancellationFixture(NAMED);
        pendingStackCancellationFixture(INTERMEDIARY);
        System.out.println("PASS reload hooks: " + assertions + " assertions; real named/intermediary bytecode and isolated JVM fixtures; no installation/game launch");
    }

    private static void actualBytecode(String owner, byte[] original) throws Exception {
        byte[] originalSnapshot = original.clone();
        byte[] prepared = ResourceReloadTransformer.prepare(owner, original);
        ClassNode before = read(original);
        ClassNode after = read(prepared);
        check(schema(before).equals(schema(after)), owner + " fields/methods/class schema unchanged");
        int changed = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i);
            MethodNode replacement = after.methods.get(i);
            if (!text(old).equals(text(replacement))) {
                changed++;
                check(old.name.equals(inputName(owner)) || old.name.equals("method_24228"), "only intended methods change");
                new Analyzer<>(new BasicVerifier()).analyze(owner, replacement);
                assertions++;
            }
        }
        check(changed == 2, owner + " exactly two method bodies changed");
        check(calls(after, "inputs") == 1 && calls(after, "beforeResult") == 1 && calls(after, "afterResult") == 2,
                "unique input/head/normal/error hook sites");
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare(owner, prepared), "repeated preparation rejected");
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare("other/Client", original), "unsupported owner rejected");
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare(owner.equals(NAMED) ? INTERMEDIARY : NAMED, original),
                "owner and class bytes mismatch rejected");

        ClassNode missing = read(original);
        missing.methods.removeIf(method -> method.name.equals("method_24228") && method.desc.equals(resultDescriptor(owner)));
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare(owner, write(missing)), "missing target rejected");
        ClassNode duplicateMethod = read(original);
        duplicateMethod.methods.add(method(duplicateMethod, "method_24228", resultDescriptor(owner)));
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare(owner, write(duplicateMethod)), "duplicate method rejected");
        ClassNode duplicateCall = read(original);
        MethodNode input = method(duplicateCall, inputName(owner), inputDescriptor(owner));
        MethodInsnNode packCall = null;
        for (var instruction : input.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(manager(owner)) && call.name.equals(packMethod(owner))) {
                packCall = call;
                break;
            }
        }
        if (packCall == null) throw new AssertionError("fixture missing actual input call");
        input.instructions.insertBefore(packCall, new MethodInsnNode(packCall.getOpcode(), packCall.owner, packCall.name, packCall.desc, packCall.itf));
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare(owner, write(duplicateCall)), "duplicate insertion anchor rejected");
        ClassNode mutatedArgument = read(original);
        method(mutatedArgument, "method_24228", resultDescriptor(owner)).instructions.insert(new VarInsnNode(Opcodes.ASTORE, 3));
        expect(IllegalArgumentException.class, () -> ResourceReloadTransformer.prepare(owner, write(mutatedArgument)), "unsafe callback argument rewrite rejected");
        check(java.util.Arrays.equals(original, originalSnapshot), "preparation leaves caller bytes unchanged");
    }

    @SuppressWarnings("try") // Intentional close-while-live tests.
    private static void bridgeOwnershipAndGuards() {
        Object client = new EqualOwner();
        Object otherClient = new EqualOwner();
        List<?> original = new ArrayList<>(List.of(new Object(), new Object()));
        CompletableFuture<?> unowned = new CompletableFuture<>();
        check(ResourceReloadBridge.inputs(client, false, unowned, original) == original, "unregistered inputs exact passthrough");
        check(!ResourceReloadBridge.beforeResult(client, false, unowned, Optional.of(new Exception("fixture"))), "unregistered result not handled");
        ResourceReloadBridge.afterResult(client, false, unowned, Optional.empty(), null);
        Recording handler = new Recording();
        try (var registration = ResourceReloadBridge.install(client, handler)) {
            expect(IllegalStateException.class, () -> ResourceReloadBridge.install(new Object(), handler), "one exact client registration");
            check(ResourceReloadBridge.inputs(otherClient, false, unowned, original) == original && handler.augments == 0,
                    "equal-but-not-identical client is not owned");
            CompletableFuture<?> first = new EqualFuture();
            CompletableFuture<?> second = new EqualFuture();
            check(ResourceReloadBridge.inputs(client, false, first, original) == original, "unchanged handler output retains caller list identity");
            check(ResourceReloadBridge.inputs(client, true, second, original) == original, "second identity-distinct equal future registered");
            expect(IllegalStateException.class, registration::close, "close refuses live attempt");
            check(!ResourceReloadBridge.beforeResult(client, true, first, Optional.empty()), "wrong forced flag not owned");
            ResourceReloadBridge.afterResult(client, true, first, Optional.empty(), null);
            check(handler.befores == 0 && handler.afters == 0, "wrong forced result not routed");
            terminal(client, false, first, Optional.empty(), null);
            check(handler.afters == 1, "first token reaches observer");
            expect(IllegalStateException.class, registration::close, "second token stays live independently");
            terminal(client, true, second, Optional.empty(), null);
            check(handler.afters == 2, "second token completes independently");
            terminal(client, false, first, Optional.empty(), null);
            check(handler.afters == 2, "completed token duplicates ignored");
            registration.close();
            registration.close();
        }
        try (var registration = ResourceReloadBridge.install(client, new Recording())) {
            CompletableFuture<?> token = new CompletableFuture<>();
            ResourceReloadBridge.inputs(client, false, token, original);
            Optional<?> success = Optional.empty();
            ResourceReloadBridge.beforeResult(client, false, token, success);
            check(!ResourceReloadBridge.beforeResult(client, false, token, success), "nested duplicate result refuses handling");
            ResourceReloadBridge.afterResult(client, false, token, success, null);
            expect(IllegalStateException.class, registration::close, "nested duplicate cannot retire original callback");
            ResourceReloadBridge.afterResult(client, false, token, success, null);
        }
        expect(NullPointerException.class, () -> ResourceReloadBridge.install(null, handler), "null client rejected");
        expect(NullPointerException.class, () -> ResourceReloadBridge.install(client, null), "null handler rejected");
    }

    @SuppressWarnings("try") // Registration scope is the tested behavior.
    private static void bridgeMalformedHandlers() {
        Object client = new Object();
        List<Object> original = new ArrayList<>(List.of(new Object(), new Object()));
        List<Function<List<?>, List<?>>> malformed = List.of(
                values -> null,
                values -> List.of(values.get(1), values.get(0)),
                values -> List.of(values.get(0)),
                values -> List.of(values.get(0), new Object()),
                values -> java.util.Arrays.asList(values.get(0), values.get(1), null),
                values -> List.of(values.get(0), values.get(1), new Object(), new Object()),
                values -> { values.clear(); return values; },
                values -> { throw new IllegalStateException("fixture augment error"); });
        for (var bad : malformed) {
            Recording handler = new Recording();
            handler.augmentation = bad;
            try (var registration = ResourceReloadBridge.install(client, handler)) {
                CompletableFuture<?> token = new CompletableFuture<>();
                check(ResourceReloadBridge.inputs(client, false, token, original) == original, "malformed augmentation returns original");
                check(original.size() == 2, "handler cannot mutate original input list");
                terminal(client, false, token, Optional.empty(), null);
                check(handler.lastFailure instanceof ResourceReloadBridge.HookFailureException, "malformed augmentation cannot masquerade as success");
            }
        }
        Recording handler = new Recording();
        handler.handle = true;
        try (var registration = ResourceReloadBridge.install(client, handler)) {
            CompletableFuture<?> token = new CompletableFuture<>();
            ResourceReloadBridge.inputs(client, false, token, original);
            check(!ResourceReloadBridge.beforeResult(client, false, token, Optional.empty()), "success cannot be short-circuited");
            ResourceReloadBridge.afterResult(client, false, token, Optional.empty(), null);
            check(handler.lastFailure instanceof ResourceReloadBridge.HookFailureException, "illegal success handling marked");
        }
        handler = new Recording();
        handler.beforeFailure = new IllegalStateException("fixture handler error");
        try (var registration = ResourceReloadBridge.install(client, handler)) {
            CompletableFuture<?> token = new CompletableFuture<>();
            ResourceReloadBridge.inputs(client, false, token, original);
            Optional<?> failure = Optional.of(new IllegalStateException("game reload error"));
            check(!ResourceReloadBridge.beforeResult(client, false, token, failure), "handler exception preserves vanilla path");
            RuntimeException originalError = new RuntimeException("exact callback error");
            ResourceReloadBridge.afterResult(client, false, token, failure, originalError);
            check(handler.lastFailure == originalError, "original callback Throwable identity retained");
            check(originalError.getSuppressed().length == 1
                            && originalError.getSuppressed()[0] instanceof ResourceReloadBridge.HookFailureException,
                    "hook failure suppressed onto original only");
        }
        handler = new Recording();
        handler.afterFailure = new IllegalStateException("fixture observer error");
        try (var registration = ResourceReloadBridge.install(client, handler)) {
            CompletableFuture<?> token = new CompletableFuture<>();
            ResourceReloadBridge.inputs(client, false, token, original);
            terminal(client, false, token, Optional.of(new Exception("failure")), null);
            check(handler.afters == 1, "observer errors cannot escape bridge");
        }
        handler = new Recording();
        try (var registration = ResourceReloadBridge.install(client, handler)) {
            CompletableFuture<?> token = new CompletableFuture<>();
            ResourceReloadBridge.inputs(client, false, token, original);
            ResourceReloadBridge.inputs(client, false, token, original);
            check(handler.augments == 1, "duplicate input token never calls handler twice");
            terminal(client, false, token, Optional.empty(), null);
            check(handler.lastFailure instanceof ResourceReloadBridge.HookFailureException, "duplicate input is reported");
        }
    }

    private static void visibility() throws Exception {
        ClassLoader canonical = ReloadHooksCheck.class.getClassLoader();
        ResourceReloadBridge.assertVisibleFrom(canonical);
        assertions++;
        expect(IllegalStateException.class, () -> ResourceReloadBridge.assertVisibleFrom(ClassLoader.getPlatformClassLoader()), "invisible bridge rejected");
        String bridge = ResourceReloadBridge.class.getName();
        MapLoader duplicate = new MapLoader(Map.of(bridge, resource(bridge.replace('.', '/') + ".class")), canonical);
        expect(IllegalStateException.class, () -> ResourceReloadBridge.assertVisibleFrom(duplicate), "duplicate bridge identity rejected");
    }

    @SuppressWarnings("try") // Explicit lifetime checks and intentional closes.
    private static void executableFixture(String owner) throws Exception {
        Map<String, byte[]> classes = fixtureClasses(owner);
        classes.put(owner.replace('/', '.'), ResourceReloadTransformer.prepare(owner, classes.get(owner.replace('/', '.'))));
        MapLoader loader = new MapLoader(classes, ReloadHooksCheck.class.getClassLoader());
        ResourceReloadBridge.assertVisibleFrom(loader);
        Class<?> clientType = loader.loadClass(owner.replace('/', '.'));
        Class<?> context = loader.loadClass(context(owner).replace('/', '.'));
        Object client = clientType.getConstructor().newInstance();
        Method reload = clientType.getMethod(inputName(owner), boolean.class, context);
        Method result = clientType.getMethod("method_24228", boolean.class, context, CompletableFuture.class, Optional.class);
        Callbacks.original = new ArrayList<>(List.of(new Object(), new Object()));
        Callbacks.vanillaCalls = 0;
        Callbacks.throwNext = null;
        CompletableFuture<?> unregistered = (CompletableFuture<?>) reload.invoke(client, false, null);
        check(Callbacks.seenInputs == Callbacks.original, owner + " JVM unregistered input unchanged");
        invoke(result, client, false, null, unregistered, Optional.empty());
        check(Callbacks.vanillaCalls == 1, "JVM unregistered callback original body runs");
        Object appended = new Object();
        Recording handler = new Recording();
        handler.augmentation = values -> {
            List<Object> combined = new ArrayList<>(values);
            combined.add(appended);
            return combined;
        };
        try (var registration = ResourceReloadBridge.install(client, handler)) {
            CompletableFuture<?> token = (CompletableFuture<?>) reload.invoke(client, false, null);
            check(Callbacks.seenInputs.size() == 3 && Callbacks.seenInputs.get(2) == appended, "JVM hook stores augmented list");
            check(Callbacks.original.size() == 2 && Callbacks.seenInputs.get(0) == Callbacks.original.get(0), "JVM other inputs untouched");
            expect(IllegalStateException.class, registration::close, "JVM attempt lifetime tracked");
            invoke(result, client, false, null, token, Optional.empty());
            check(handler.befores == 1 && handler.afters == 1 && handler.lastFailure == null, "JVM normal after callback");
            handler.handle = true;
            token = (CompletableFuture<?>) reload.invoke(client, false, null);
            int vanillaBefore = Callbacks.vanillaCalls;
            invoke(result, client, false, null, token, Optional.of(new IllegalStateException("owned failure")));
            check(Callbacks.vanillaCalls == vanillaBefore && handler.afters == 2, "JVM handled failure short-circuits body and still observes after");
            handler.handle = false;
            token = (CompletableFuture<?>) reload.invoke(client, false, null);
            RuntimeException exact = new RuntimeException("exact body failure");
            Callbacks.throwNext = exact;
            CompletableFuture<?> throwingToken = token;
            Throwable escaped = expect(Throwable.class, () -> invoke(result, client, false, null, throwingToken, Optional.empty()), "JVM callback body error rethrows");
            check(escaped == exact && handler.lastFailure == exact, "JVM observer and caller receive identical original Throwable");
            expect(IllegalStateException.class, registration::close, "success-body throw retains same-token replay ownership");
            int beforeReplay = handler.befores;
            int afterThrow = handler.afters;
            // A duplicate success outcome cannot consume the awaited failure replay.
            invoke(result, client, false, null, token, Optional.empty());
            check(handler.befores == beforeReplay && handler.afters == afterThrow, "duplicate success result ignored during replay wait");
            invoke(result, client, false, null, token, Optional.of(exact));
            check(handler.befores == beforeReplay + 1 && handler.afters == afterThrow + 1, "same-token Optional failure replay routed once");
            invoke(result, client, false, null, token, Optional.of(exact));
            check(handler.afters == afterThrow + 1, "terminal replay duplicate ignored");
            token = (CompletableFuture<?>) reload.invoke(client, true, null);
            IllegalArgumentException terminalError = new IllegalArgumentException("terminal failure-body error");
            Callbacks.throwNext = terminalError;
            handler.afterFailure = new RuntimeException("observer also failed");
            CompletableFuture<?> terminalToken = token;
            Optional<?> gameFailure = Optional.of(new Exception("original resource failure"));
            escaped = expect(Throwable.class, () -> invoke(result, client, true, null, terminalToken, gameFailure), "JVM failure-body error escapes");
            check(escaped == terminalError && terminalError.getSuppressed().length == 1,
                    "observer failure cannot replace terminal original exception");
            handler.afterFailure = null;
        }
        check(((Integer) clientType.getMethod("untouchedSentinel").invoke(client)) == 73, "unrelated fixture method preserved");
    }

    @SuppressWarnings("try")
    private static void pendingStackCancellationFixture(String owner) throws Exception {
        // First execute the baseline to prove that RETURN with pending values
        // is valid JVM bytecode, then require identical mod/body behavior after
        // the transformer merges its exits into the empty-stack epilogue.
        for (boolean prepare : List.of(false, true)) {
            Map<String, byte[]> classes = fixtureClasses(owner, true);
            byte[] original = classes.get(owner.replace('/', '.'));
            byte[] output = prepare ? ResourceReloadTransformer.prepare(owner, original) : original;
            ClassNode before = read(original), after = read(output);
            check(schema(before).equals(schema(after)), "pending-stack cancellation preserves schema");
            check(text(method(before, "arbitrary$pendingCancellation", "(L" + CI + ";)V"))
                    .equals(text(method(after, "arbitrary$pendingCancellation", "(L" + CI + ";)V"))),
                    "pending-stack mod helper remains byte-for-byte equivalent");
            new Analyzer<>(new BasicVerifier()).analyze(owner, method(after, "method_24228", resultDescriptor(owner)));
            assertions++;
            classes.put(owner.replace('/', '.'), output);
            MapLoader loader = new MapLoader(classes, ReloadHooksCheck.class.getClassLoader());
            Class<?> clientType = loader.loadClass(owner.replace('/', '.'));
            Class<?> contextType = loader.loadClass(context(owner).replace('/', '.'));
            Object client = clientType.getConstructor().newInstance();
            Method reload = clientType.getMethod(inputName(owner), boolean.class, contextType);
            Method result = clientType.getMethod("method_24228", boolean.class, contextType, CompletableFuture.class, Optional.class);
            Recording handler = new Recording();
            Callbacks.original = new ArrayList<>(List.of(new Object()));
            Callbacks.throwNext = null;
            try (var registration = ResourceReloadBridge.install(client, handler)) {
                for (boolean cancel : List.of(false, true)) {
                    Callbacks.cancelPending = cancel;
                    Callbacks.cancellationCalls = Callbacks.vanillaCalls = 0;
                    int beforeCalls = handler.befores, afterCalls = handler.afters;
                    CompletableFuture<?> token = (CompletableFuture<?>) reload.invoke(client, false, null);
                    invoke(result, client, false, null, token, Optional.empty());
                    check(Callbacks.cancellationCalls == 1 && Callbacks.vanillaCalls == (cancel ? 0 : 1),
                            "pending reference/long/double stack retains cancellation and original body behavior");
                    check(handler.befores == beforeCalls + (prepare ? 1 : 0)
                            && handler.afters == afterCalls + (prepare ? 1 : 0) && handler.lastFailure == null,
                            "merged reload cancellation completes the owned attempt exactly once");
                }
            }
            Callbacks.cancelPending = false;
            Callbacks.cancellationCalls = Callbacks.vanillaCalls = 0;
            CompletableFuture<?> token = (CompletableFuture<?>) reload.invoke(client, false, null);
            invoke(result, client, false, null, token, Optional.empty());
            check(Callbacks.cancellationCalls == 1 && Callbacks.vanillaCalls == 1,
                    "retired reload bridge retains the original pending-stack path");
        }
    }

    public static final class Callbacks {
        public static List<?> original;
        public static List<?> seenInputs;
        public static int vanillaCalls;
        public static RuntimeException throwNext;
        public static boolean cancelPending;
        public static int cancellationCalls;

        private Callbacks() { }
        public static List<?> packs() { return original; }
        public static void observeInputs(List<?> value) { seenInputs = value; }
        public static void cancelPending(CallbackInfo info) {
            cancellationCalls++;
            if (cancelPending) info.cancel();
        }
        public static void pendingVanilla(Optional<?> value, long wide, double other) {
            if (wide != 1L || other != 1.0) throw new AssertionError("pending category-two arguments changed");
            vanilla(value);
        }
        public static void vanilla(Optional<?> ignored) {
            vanillaCalls++;
            RuntimeException failure = throwNext;
            throwNext = null;
            if (failure != null) throw failure;
        }
    }

    private static Map<String, byte[]> fixtureClasses(String owner) {
        return fixtureClasses(owner, false);
    }

    private static Map<String, byte[]> fixtureClasses(String owner, boolean pendingCancellation) {
        Map<String, byte[]> result = new HashMap<>();
        ClassWriter manager = begin(manager(owner));
        MethodVisitor packs = manager.visitMethod(Opcodes.ACC_PUBLIC, packMethod(owner), "()Ljava/util/List;", null, null);
        packs.visitCode();
        packs.visitMethodInsn(Opcodes.INVOKESTATIC, CALLBACKS, "packs", "()Ljava/util/List;", false);
        packs.visitInsn(Opcodes.ARETURN);
        packs.visitMaxs(0, 0);
        packs.visitEnd();
        manager.visitEnd();
        result.put(manager(owner).replace('/', '.'), manager.toByteArray());
        ClassWriter context = begin(context(owner));
        context.visitEnd();
        result.put(context(owner).replace('/', '.'), context.toByteArray());
        ClassWriter client = begin(owner);
        client.visitField(Opcodes.ACC_PRIVATE, "existingField", "I", null, null).visitEnd();
        MethodVisitor input = client.visitMethod(Opcodes.ACC_PUBLIC, inputName(owner), inputDescriptor(owner), null, null);
        input.visitCode();
        input.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/CompletableFuture");
        input.visitInsn(Opcodes.DUP);
        input.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/CompletableFuture", "<init>", "()V", false);
        input.visitVarInsn(Opcodes.ASTORE, 3);
        input.visitTypeInsn(Opcodes.NEW, manager(owner));
        input.visitInsn(Opcodes.DUP);
        input.visitMethodInsn(Opcodes.INVOKESPECIAL, manager(owner), "<init>", "()V", false);
        input.visitMethodInsn(Opcodes.INVOKEVIRTUAL, manager(owner), packMethod(owner), "()Ljava/util/List;", false);
        input.visitVarInsn(Opcodes.ASTORE, 4);
        input.visitVarInsn(Opcodes.ALOAD, 4);
        input.visitMethodInsn(Opcodes.INVOKESTATIC, CALLBACKS, "observeInputs", "(Ljava/util/List;)V", false);
        input.visitVarInsn(Opcodes.ALOAD, 3);
        input.visitInsn(Opcodes.ARETURN);
        input.visitMaxs(0, 0);
        input.visitEnd();
        MethodVisitor callback = client.visitMethod(Opcodes.ACC_PUBLIC, "method_24228", resultDescriptor(owner), null, null);
        callback.visitCode();
        callback.visitVarInsn(Opcodes.ALOAD, 4);
        if (pendingCancellation) {
            // Mixin inserts its guard immediately before an INVOKE, after the
            // target invocation's arguments have already been evaluated.
            callback.visitInsn(Opcodes.LCONST_1);
            callback.visitInsn(Opcodes.DCONST_1);
            callback.visitTypeInsn(Opcodes.NEW, CI);
            callback.visitInsn(Opcodes.DUP);
            callback.visitLdcInsn("pending-invoke");
            callback.visitInsn(Opcodes.ICONST_1);
            callback.visitMethodInsn(Opcodes.INVOKESPECIAL, CI, "<init>", "(Ljava/lang/String;Z)V", false);
            callback.visitVarInsn(Opcodes.ASTORE, 5);
            callback.visitVarInsn(Opcodes.ALOAD, 0);
            callback.visitVarInsn(Opcodes.ALOAD, 5);
            callback.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "arbitrary$pendingCancellation", "(L" + CI + ";)V", false);
            callback.visitVarInsn(Opcodes.ALOAD, 5);
            callback.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CI, "isCancelled", "()Z", false);
            Label continuation = new Label();
            callback.visitJumpInsn(Opcodes.IFEQ, continuation);
            callback.visitInsn(Opcodes.RETURN);
            callback.visitLabel(continuation);
            callback.visitMethodInsn(Opcodes.INVOKESTATIC, CALLBACKS, "pendingVanilla", "(Ljava/util/Optional;JD)V", false);
        } else {
            callback.visitMethodInsn(Opcodes.INVOKESTATIC, CALLBACKS, "vanilla", "(Ljava/util/Optional;)V", false);
        }
        callback.visitInsn(Opcodes.RETURN);
        callback.visitMaxs(0, 0);
        callback.visitEnd();
        if (pendingCancellation) {
            MethodVisitor helper = client.visitMethod(Opcodes.ACC_PRIVATE, "arbitrary$pendingCancellation", "(L" + CI + ";)V", null, null);
            var merged = helper.visitAnnotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", true);
            merged.visit("mixin", "example.compat.PendingInvocationMixin");
            merged.visit("priority", 1000);
            merged.visitEnd();
            helper.visitCode();
            helper.visitVarInsn(Opcodes.ALOAD, 1);
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, CALLBACKS, "cancelPending", "(L" + CI + ";)V", false);
            helper.visitInsn(Opcodes.RETURN);
            helper.visitMaxs(0, 0);
            helper.visitEnd();
        }
        MethodVisitor sentinel = client.visitMethod(Opcodes.ACC_PUBLIC, "untouchedSentinel", "()I", null, null);
        sentinel.visitCode();
        sentinel.visitIntInsn(Opcodes.BIPUSH, 73);
        sentinel.visitInsn(Opcodes.IRETURN);
        sentinel.visitMaxs(0, 0);
        sentinel.visitEnd();
        client.visitEnd();
        result.put(owner.replace('/', '.'), client.toByteArray());
        return result;
    }

    private static ClassWriter begin(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return writer;
    }

    private static final class MapLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        MapLoader(Map<String, byte[]> definitions, ClassLoader parent) { super(parent); this.definitions = definitions; }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = definitions.get(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    private static final class Recording implements ResourceReloadBridge.Handler {
        private Function<List<?>, List<?>> augmentation = values -> values;
        private int augments;
        private int befores;
        private int afters;
        private boolean handle;
        private RuntimeException beforeFailure;
        private RuntimeException afterFailure;
        private Throwable lastFailure;
        public List<?> augment(Object client, boolean forced, CompletableFuture<?> attempt, List<?> original) {
            augments++;
            return augmentation.apply(original);
        }
        public boolean beforeResult(Object client, boolean forced, CompletableFuture<?> attempt, Optional<?> failure) {
            befores++;
            if (beforeFailure != null) throw beforeFailure;
            return handle;
        }
        public void afterResult(Object client, boolean forced, CompletableFuture<?> attempt, Optional<?> failure, Throwable callbackFailure) {
            afters++;
            lastFailure = callbackFailure;
            if (afterFailure != null) throw afterFailure;
        }
    }

    private static final class EqualOwner {
        @Override public boolean equals(Object other) { return other instanceof EqualOwner; }
        @Override public int hashCode() { return 1; }
    }
    private static final class EqualFuture extends CompletableFuture<Object> {
        @Override public boolean equals(Object other) { return other instanceof EqualFuture; }
        @Override public int hashCode() { return 1; }
    }

    private static void terminal(Object client, boolean forced, CompletableFuture<?> token, Optional<?> failure, Throwable callbackFailure) {
        ResourceReloadBridge.beforeResult(client, forced, token, failure);
        ResourceReloadBridge.afterResult(client, forced, token, failure, callbackFailure);
    }
    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static byte[] resource(String name) throws Exception {
        try (InputStream stream = ReloadHooksCheck.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing cached resource " + name);
            return stream.readAllBytes();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static List<String> schema(ClassNode node) {
        List<String> schema = new ArrayList<>();
        schema.add(node.version + ":" + node.access + ":" + node.name + ":" + node.superName + ":" + node.signature + ":" + node.interfaces);
        node.fields.forEach(field -> schema.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature));
        node.methods.forEach(method -> schema.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions));
        return schema;
    }
    private static String text(MethodNode method) {
        Textifier printer = new Textifier();
        method.accept(new TraceMethodVisitor(printer));
        StringWriter output = new StringWriter();
        printer.print(new PrintWriter(output));
        return output.toString();
    }
    private static long calls(ClassNode node, String name) {
        long count = 0;
        for (MethodNode method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/celestebeta/host/ResourceReloadBridge") && call.name.equals(name)) count++;
        }
        return count;
    }
    private static String manager(String owner) { return owner.equals(NAMED) ? "net/minecraft/resource/ResourcePackManager" : "net/minecraft/class_3283"; }
    private static String packMethod(String owner) { return owner.equals(NAMED) ? "createResourcePacks" : "method_29211"; }
    private static String inputName(String owner) { return owner.equals(NAMED) ? "reloadResources" : "method_36561"; }
    private static String context(String owner) { return owner + (owner.equals(NAMED) ? "$LoadingContext" : "$class_8764"); }
    private static String inputDescriptor(String owner) { return "(ZL" + context(owner) + ";)Ljava/util/concurrent/CompletableFuture;"; }
    private static String resultDescriptor(String owner) { return "(ZL" + context(owner) + ";Ljava/util/concurrent/CompletableFuture;Ljava/util/Optional;)V"; }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String message) {
        assertions++;
        try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(message + ": wrong exception " + failure, failure); }
        throw new AssertionError(message + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
