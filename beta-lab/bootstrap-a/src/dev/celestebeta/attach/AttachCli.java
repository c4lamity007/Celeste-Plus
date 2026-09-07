package dev.celestebeta.attach;

import com.sun.tools.attach.VirtualMachine;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit selected-PID launcher bridge; the request arrives on stdin, never in command-line arguments. */
public final class AttachCli {
    private AttachCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !args[0].equals("--pid") || !args[2].equals("--agent") || !args[4].equals("--started-at")
            || !args[1].matches("[0-9]{1,12}") || !args[5].matches("[0-9]{1,16}")) {
            throw new IOException("Usage: --pid <selected Minecraft PID> --agent <verified bootstrap-a.jar> --started-at <selected Unix milliseconds>; JSON request on stdin");
        }
        long pid = Long.parseLong(args[1]);
        if (pid <= 0 || pid == ProcessHandle.current().pid()) throw new IOException("Select an external Minecraft process");
        ProcessHandle target = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Selected Minecraft process exited"));
        long started = Long.parseLong(args[5]);
        if (started != target.info().startInstant().orElseThrow().toEpochMilli()) throw new IOException("Selected Minecraft process identity changed");
        Path agent = Path.of(args[3]).toRealPath();
        if (!Files.isRegularFile(agent) || Files.size(agent) > 64 * 1024 * 1024) throw new IOException("Invalid bootstrap agent file");
        byte[] requestBytes = System.in.readNBytes(65_537);
        if (requestBytes.length == 0 || requestBytes.length > 65_536) throw new IOException("Beta launcher request exceeds bounds");
        String request = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(requestBytes)).toString();
        java.util.Arrays.fill(requestBytes, (byte) 0);
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        byte[] nonce = HexFormat.of().formatHex(random).getBytes(StandardCharsets.US_ASCII);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(180_000);
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread handoff = new Thread(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(180_000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    if (!MessageDigest.isEqual(nonce, input.readNBytes(64))) throw new IOException("Beta local handoff identity mismatch");
                    AgentMain.writeFrame(output, request);
                    result.complete(AgentMain.readFrame(input));
                } catch (Exception error) { result.completeExceptionally(error); }
            }, "Celeste beta launcher handoff");
            handoff.setDaemon(true); handoff.start();
            VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
            try {
                if (!"21".equals(vm.getSystemProperties().getProperty("java.specification.version"))) {
                    throw new IOException("Selected Minecraft must use Java 21");
                }
                vm.loadAgent(agent.toString(), server.getLocalPort() + "|" + new String(nonce, StandardCharsets.US_ASCII) + "|" + pid + "|" + started);
            } finally { vm.detach(); }
            System.out.println(result.get(180, TimeUnit.SECONDS));
        } finally { java.util.Arrays.fill(nonce, (byte) 0); java.util.Arrays.fill(random, (byte) 0); }
    }
}
