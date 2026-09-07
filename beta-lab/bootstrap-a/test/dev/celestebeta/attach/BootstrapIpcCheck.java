package dev.celestebeta.attach;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Bounded real AttachCli/AgentMain IPC against only a nonce-bound child created by this check. */
public final class BootstrapIpcCheck {
    private static int checks;
    private BootstrapIpcCheck() { }

    public static void run(Path directory) throws Exception {
        frames();
        ResidentControlCheck.run();
        long self = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        for (String argument : List.of("", "1|bad|1|1", "0|" + "a".repeat(64) + "|" + self + "|" + started,
            "1|" + "a".repeat(64) + "|" + (self + 1) + "|" + started,
            "1|" + "a".repeat(64) + "|" + self + "|" + (started + 1))) {
            expect(IOException.class, () -> AgentMain.agentmain(argument, null), "agent rejects malformed/mismatched target tuple before socket or instrumentation");
        }
        expect(IOException.class, () -> AttachCli.main(new String[0]), "CLI requires explicit PID/agent/start-time shape");
        expect(IOException.class, () -> AttachCli.main(new String[] { "--pid", Long.toString(self), "--agent", "unused", "--started-at", Long.toString(started) }), "CLI refuses self attachment before reading credentials");
        Path agent = directory.resolve("ipc-fixture-agent.jar"), targetJar = directory.resolve("ipc-fixture-target.jar");
        assembleAgent(agent); assembleTarget(targetJar);
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Process target = new ProcessBuilder(java, "-jar", targetJar.toString(), nonce).redirectError(directory.resolve("ipc-target.stderr").toFile()).start();
        long selectedStarted = target.info().startInstant().orElseThrow().toEpochMilli();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8))) {
            check(readLine(reader).equals("READY:" + nonce + ":" + target.pid()), "target PID is bound to newly-created child and fresh nonce");
            unchanged(target, reader, nonce);
            String[] arguments = { "--pid", Long.toString(target.pid()), "--agent", agent.toString(), "--started-at", Long.toString(selectedStarted) };
            InputStream originalInput = System.in;
            try {
                System.setIn(new InputStream() {
                    @Override public int read() { throw new AssertionError("stale process identity must reject before reading installer stdin"); }
                });
                String[] mismatch = arguments.clone(); mismatch[5] = Long.toString(selectedStarted + 1);
                expect(IOException.class, () -> AttachCli.main(mismatch), "selected start-time mismatch rejects PID reuse before credentials/attachment");
                expect(IOException.class, () -> AttachCli.main(Arrays.copyOf(arguments, 4)), "legacy four-argument request cannot bypass selected start-time binding");
                for (byte[] invalid : List.of(new byte[0], new byte[65_537], new byte[] { (byte) 0xc3, 0x28 })) {
                    System.setIn(new ByteArrayInputStream(invalid));
                    expect(IOException.class, () -> AttachCli.main(arguments), "CLI rejects empty/oversized/malformed UTF-8 stdin before attaching");
                }
            } finally { System.setIn(originalInput); }
            JsonObject response = request(java, agent, target, selectedStarted, "{\"action\":\"status\"}");
            check(response.get("phase").getAsString().equals("inactive"), "real agent socket and isolated host delegation return inactive status");
            JsonObject endpoint = response.getAsJsonObject("_control");
            check(endpoint.get("pid").getAsLong() == target.pid() && endpoint.get("started").getAsLong() == selectedStarted
                && endpoint.get("digest").getAsString().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(agent)))),
                "resident control binds exact selected process, start and bootstrap bytes");
            JsonObject inspected = ResidentControlCheck.request(endpoint, "{\"action\":\"inspect\"}");
            check(inspected.get("phase").getAsString().equals("failed") && inspected.get("error").getAsString().contains("running Minecraft client"),
                "read-only inspect denies plain child before game delivery or hook installation");
            JsonObject missingDirectory = ResidentControlCheck.request(endpoint,
                "{\"action\":\"start\",\"installer\":\"fixture-not-a-grant\",\"device\":\"fixture\",\"support_directory\":\".\"}");
            check(missingDirectory.get("phase").getAsString().equals("failed") && missingDirectory.get("error").getAsString().contains("Invalid beta start request"),
                "start requires expected game directory before installation");
            JsonObject start = new JsonObject(); start.addProperty("action", "start"); start.addProperty("installer", "fixture-not-a-grant");
            start.addProperty("uid", 42); start.addProperty("device", "fixture-not-a-device"); start.addProperty("support_directory", directory.toString());
            start.addProperty("expected_game_directory", directory.toString());
            start.addProperty("expected_release", "ipc-fixture");
            start.addProperty("expected_catalog_sha256", "0".repeat(64));
            JsonObject denied = ResidentControlCheck.request(endpoint, start.toString());
            check(denied.get("phase").getAsString().equals("failed") && denied.get("error").getAsString().contains("running Minecraft client"),
                "non-Minecraft start denies before examining fixture grant or fetching a release");
            check(ResidentControlCheck.request(endpoint, "{\"action\":\"stop\"}").get("phase").getAsString().equals("inactive"),
                "denied start leaves no payload lifetime to stop");
            unchanged(target, reader, nonce);
            target.getOutputStream().write(("STOP:" + nonce + "\n").getBytes(StandardCharsets.UTF_8)); target.getOutputStream().flush();
            check(readLine(reader).equals("STOPPED:" + nonce) && target.waitFor(10, TimeUnit.SECONDS) && target.exitValue() == 0,
                "only self-created child exits normally after fixture checks");
            check(Files.readAllLines(directory.resolve("ipc-target.stderr")).stream().filter(line -> line.contains("A Java agent has been loaded dynamically")).count() <= 1,
                "resident commands never cause repeated dynamic agent load warnings");
        } finally {
            if (target.isAlive()) { target.destroy(); if (!target.waitFor(5, TimeUnit.SECONDS)) { target.destroyForcibly(); target.waitFor(5, TimeUnit.SECONDS); } }
        }
        System.out.println("PASS bootstrap IPC: " + checks + " checks; actual Attach API on one self-created non-Minecraft child only");
    }

    private static void frames() throws Exception {
        for (String value : List.of("{\"action\":\"status\"}", "snow \u2603 / \ud83c\udf0c", "x".repeat(65_536))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); AgentMain.writeFrame(new DataOutputStream(output), value);
            check(AgentMain.readFrame(new DataInputStream(new ByteArrayInputStream(output.toByteArray()))).equals(value), "framed UTF-8 round trip and exact maximum boundary");
        }
        for (int size : List.of(-1, 0, 65_537)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); new DataOutputStream(output).writeInt(size);
            expect(IOException.class, () -> AgentMain.readFrame(new DataInputStream(new ByteArrayInputStream(output.toByteArray()))), "invalid frame length rejected before body read");
        }
        ByteArrayOutputStream truncated = new ByteArrayOutputStream(); DataOutputStream header = new DataOutputStream(truncated);
        header.writeInt(3); header.write(new byte[] { 1, 2 });
        expect(IOException.class, () -> AgentMain.readFrame(new DataInputStream(new ByteArrayInputStream(truncated.toByteArray()))), "truncated frame body rejected");
        ByteArrayOutputStream malformed = new ByteArrayOutputStream(); header = new DataOutputStream(malformed);
        header.writeInt(2); header.write(new byte[] { (byte) 0xc3, 0x28 });
        expect(IOException.class, () -> AgentMain.readFrame(new DataInputStream(new ByteArrayInputStream(malformed.toByteArray()))), "malformed frame UTF-8 rejected");
        expect(IOException.class, () -> AgentMain.writeFrame(new DataOutputStream(new ByteArrayOutputStream()), ""), "empty outgoing frame rejected");
        expect(IOException.class, () -> AgentMain.writeFrame(new DataOutputStream(new ByteArrayOutputStream()), "\u2603".repeat(21_846)), "outgoing bound is UTF-8 bytes, not character count");
    }
    private static JsonObject request(String java, Path agent, Process target, long selectedStarted, String request) throws Exception {
        if (!target.isAlive()) throw new IOException("fixture target exited");
        Process cli = new ProcessBuilder(java, "--add-modules", "jdk.attach", "-jar", agent.toString(),
            "--pid", Long.toString(target.pid()), "--agent", agent.toString(), "--started-at", Long.toString(selectedStarted)).redirectErrorStream(true).start();
        try {
            cli.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); cli.getOutputStream().close();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try { return new String(cli.getInputStream().readNBytes(262_145), StandardCharsets.UTF_8); }
                catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            if (!cli.waitFor(35, TimeUnit.SECONDS)) throw new IOException("fixture Attach CLI timed out");
            String captured = output.get(5, TimeUnit.SECONDS);
            check(cli.exitValue() == 0 && captured.length() <= 262_144, "bounded Attach CLI completed its response handoff");
            String json = captured.lines().filter(line -> line.startsWith("{")).reduce((first, second) -> second).orElseThrow(() -> new IOException("fixture response missing: " + captured));
            return JsonParser.parseString(json).getAsJsonObject();
        } finally {
            if (cli.isAlive()) { cli.destroy(); if (!cli.waitFor(5, TimeUnit.SECONDS)) { cli.destroyForcibly(); cli.waitFor(5, TimeUnit.SECONDS); } }
        }
    }
    private static void unchanged(Process target, BufferedReader reader, String nonce) throws Exception {
        target.getOutputStream().write(("CHECK:" + nonce + "\n").getBytes(StandardCharsets.UTF_8)); target.getOutputStream().flush();
        check(readLine(reader).equals("SAFE:" + nonce + ":true:40"), "child behavior unchanged, no Minecraft/payload/canonical bootstrap bridge injected");
    }
    private static String readLine(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try { return reader.readLine(); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }).get(10, TimeUnit.SECONDS);
    }
    private static void assembleAgent(Path output) throws Exception {
        Path classes = Path.of(AgentMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isDirectory(classes)) throw new IOException("IPC fixture requires current compiled agent classes");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).toList()) {
                entries.put(classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        Path gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(gson.toFile())) {
            for (JarEntry entry : jar.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")).toList()) {
                try (InputStream input = jar.getInputStream(entry)) { entries.put(entry.getName(), input.readAllBytes()); }
            }
        }
        Manifest manifest = manifest(AttachCli.class.getName());
        manifest.getMainAttributes().putValue("Agent-Class", AgentMain.class.getName());
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        writeJar(output, entries, manifest);
    }
    private static void assembleTarget(Path output) throws Exception {
        String resource = AttachFixtureTarget.class.getName().replace('.', '/') + ".class";
        try (InputStream input = BootstrapIpcCheck.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("fixture target bytecode missing");
            writeJar(output, Map.of(resource, input.readAllBytes()), manifest(AttachFixtureTarget.class.getName()));
        }
    }
    private static Manifest manifest(String main) {
        Manifest result = new Manifest(); result.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        result.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main); return result;
    }
    private static void writeJar(Path output, Map<String, byte[]> entries, Manifest manifest) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output), manifest)) {
            for (var entry : entries.entrySet()) {
                JarEntry member = new JarEntry(entry.getKey()); member.setTime(0); jar.putNextEntry(member); jar.write(entry.getValue()); jar.closeEntry();
            }
        }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(Class<? extends Throwable> type, Throwing action, String label) {
        checks++; try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError(label, failure); }
        throw new AssertionError(label + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
