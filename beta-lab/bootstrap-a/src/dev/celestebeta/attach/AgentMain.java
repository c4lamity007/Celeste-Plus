package dev.celestebeta.attach;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;

/** Small JDK-only Attach entrypoint. No payload classes are appended to a game/system classpath. */
public final class AgentMain {
    private static URLClassLoader hostLoader;
    private static String residentDigest;
    private static ResidentControl control;
    private AgentMain() { }

    public static synchronized void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        String[] fields = arguments == null ? new String[0] : arguments.split("\\|", -1);
        if (fields.length != 4 || !fields[0].matches("[0-9]{1,5}") || !fields[1].matches("[0-9a-f]{64}")
            || !fields[2].matches("[0-9]{1,12}") || !fields[3].matches("[0-9]{1,16}")) {
            throw new IOException("Invalid explicit beta attachment request");
        }
        int port = Integer.parseInt(fields[0]);
        ProcessHandle self = ProcessHandle.current();
        if (port <= 0 || port > 65535 || Long.parseLong(fields[2]) != self.pid()
            || Long.parseLong(fields[3]) != self.info().startInstant().orElseThrow().toEpochMilli()) {
            throw new IOException("Beta attachment target identity changed");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000);
            socket.setSoTimeout(180_000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write(fields[1].getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String request = readFrame(input);
            String response;
            try {
                if (hostLoader == null) {
                    URL own = AgentMain.class.getProtectionDomain().getCodeSource().getLocation();
                    byte[] original = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(own.toURI()));
                    residentDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
                    java.util.Arrays.fill(original, (byte) 0);
                    hostLoader = new URLClassLoader("celeste-beta-agent-host", new URL[] { own }, ClassLoader.getPlatformClassLoader());
                }
                if (control == null) control = new ResidentControl(self.pid(), self.info().startInstant().orElseThrow().toEpochMilli(),
                    residentDigest, value -> dispatch(instrumentation, value));
                response = dispatch(instrumentation, request);
            } catch (Exception | LinkageError failure) {
                Throwable cause = failure instanceof InvocationTargetException call ? call.getCause() : failure;
                String reason = cause.getMessage();
                response = "{\"phase\":\"failed\",\"error\":\"" + escape(reason == null ? cause.getClass().getSimpleName() : reason) + "\"}";
            }
            if (control != null) response = response.substring(0, response.length() - 1) + ",\"_control\":" + control.descriptor() + "}";
            writeFrame(output, response);
        }
    }

    private static synchronized String dispatch(Instrumentation instrumentation, String request) {
        try {
            Class<?> host = Class.forName("dev.celestebeta.bootstrap.external.BootstrapHost", true, hostLoader);
            return (String) host.getMethod("command", Instrumentation.class, String.class, String.class).invoke(null, instrumentation, request, residentDigest);
        } catch (ReflectiveOperationException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException call ? call.getCause() : failure;
            String reason = cause.getMessage();
            return "{\"phase\":\"failed\",\"error\":\"" + escape(reason == null ? cause.getClass().getSimpleName() : reason) + "\"}";
        }
    }

    static String readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > 65_536) throw new IOException("Beta handoff frame exceeds bounds");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Beta handoff frame is truncated");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    static void writeFrame(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 65_536) throw new IOException("Beta handoff response exceeds bounds");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(1000).forEach(code -> {
            if (code == '"' || code == '\\') result.append('\\').appendCodePoint(code);
            else if (code < 32) result.append(' ');
            else result.appendCodePoint(code);
        });
        return result.toString();
    }
}
