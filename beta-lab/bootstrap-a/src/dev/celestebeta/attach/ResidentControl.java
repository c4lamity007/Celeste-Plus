package dev.celestebeta.attach;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Resident loopback capability. Keys exist only in the private initial handoff and memory. */
final class ResidentControl implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key = new byte[32];
    private final byte[] binding;
    private final long pid, started;
    private final String digest;
    private final ServerSocket server;
    private final ThreadPoolExecutor workers;
    private final java.util.concurrent.ScheduledExecutorService deadlines;
    private final Function<String,String> command;
    private final java.util.Set<Socket> sockets=java.util.concurrent.ConcurrentHashMap.newKeySet();

    ResidentControl(long pid, long started, String digest, Function<String,String> command) throws IOException {
        if (pid <= 0 || started <= 0 || !digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid resident identity");
        this.pid=pid; this.started=started; this.digest=digest; this.command=command;
        binding=ByteBuffer.allocate(48).putLong(pid).putLong(started).put(HexFormat.of().parseHex(digest)).array();
        RANDOM.nextBytes(key);
        server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),action -> daemon(action,"Celeste local control"));
        deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(action -> daemon(action,"Celeste local control deadline"));
        daemon(this::accept,"Celeste local control accept").start();
    }
    private static Thread daemon(Runnable action,String name) {
        Thread thread=new Thread(action,name); thread.setDaemon(true);
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader()); return thread;
    }
    String descriptor() {
        return "{\"schema\":1,\"port\":"+server.getLocalPort()+",\"pid\":"+pid+",\"started\":"+started
            +",\"digest\":\""+digest+"\",\"key\":\""+HexFormat.of().formatHex(key)+"\"}";
    }
    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket=server.accept();
                sockets.add(socket);
                try {workers.execute(() -> handle(socket));}
                catch (java.util.concurrent.RejectedExecutionException rejected) {sockets.remove(socket);socket.close();}
            } catch (IOException stopped) { if (!server.isClosed()) close(); }
        }
    }
    private void handle(Socket socket) {
        java.util.concurrent.ScheduledFuture<?> deadline=null;
        try (socket) {
            deadline=deadlines.schedule(() -> closeSocket(socket),3,TimeUnit.SECONDS);
            socket.setSoTimeout(3000);
            DataInputStream input=new DataInputStream(socket.getInputStream());
            DataOutputStream output=new DataOutputStream(socket.getOutputStream());
            byte[] challenge=input.readNBytes(32);
            if(challenge.length!=32)throw new IOException("Truncated resident challenge");
            byte[] proof=new byte[64], serverChallenge=new byte[32]; RANDOM.nextBytes(serverChallenge);
            System.arraycopy(challenge,0,proof,0,32); System.arraycopy(serverChallenge,0,proof,32,32);
            writeSealed(output,key,aad(binding,(byte)1,challenge),proof);
            byte[] round=java.security.MessageDigest.getInstance("SHA-256").digest(proof);
            byte[] bytes=readSealed(input,key,aad(binding,(byte)2,round));
            String request;
            try {request=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();}
            finally {Arrays.fill(bytes,(byte)0);}
            deadline.cancel(false);
            deadline=deadlines.schedule(() -> closeSocket(socket),180,TimeUnit.SECONDS);
            socket.setSoTimeout(180_000);
            String response=command.apply(request);
            writeSealed(output,key,aad(binding,(byte)3,round),response.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | GeneralSecurityException | RuntimeException rejected) {
            // Invalid/expired local requests fail closed at the private launcher
            // connection. Never send request/key contents into Minecraft logging.
        } finally {if(deadline!=null)deadline.cancel(false);sockets.remove(socket);}
    }
    private static void closeSocket(Socket socket) {try {socket.close();} catch(IOException ignored) { }}
    static byte[] aad(byte[] binding,byte direction,byte[] challenge) {
        return ByteBuffer.allocate(81).put(binding).put(direction).put(challenge).array();
    }
    static void writeSealed(DataOutputStream output,byte[] key,byte[] aad,byte[] plain) throws IOException,GeneralSecurityException {
        if(plain.length==0 || plain.length>65_536)throw new IOException("Resident frame exceeds bounds");
        byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(aad); byte[] encrypted=cipher.doFinal(plain);
        output.writeInt(12+encrypted.length); output.write(nonce); output.write(encrypted); output.flush();
    }
    static byte[] readSealed(DataInputStream input,byte[] key,byte[] aad) throws IOException,GeneralSecurityException {
        int length=input.readInt(); if(length<29 || length>65_564)throw new IOException("Resident frame exceeds bounds");
        byte[] frame=input.readNBytes(length); if(frame.length!=length)throw new IOException("Truncated resident frame");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,frame,0,12));
        cipher.updateAAD(aad); return cipher.doFinal(frame,12,frame.length-12);
    }
    @Override public void close() {
        try {server.close();} catch(IOException ignored) { }
        workers.shutdownNow();
        deadlines.shutdownNow();
        sockets.forEach(ResidentControl::closeSocket); sockets.clear();
        // In-flight workers may be finishing a bounded response. The capability
        // is unreachable once the listener closes; do not race key mutation.
    }
}
