package dev.celestebeta.attach;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Local synthetic command handler only: never instrumentation, Minecraft, or authentication. */
public final class ResidentControlCheck {
    private static int checks;
    private ResidentControlCheck() { }
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("bridge")) {
            ProcessHandle self=ProcessHandle.current();
            try(ResidentControl server=new ResidentControl(self.pid(),self.info().startInstant().orElseThrow().toEpochMilli(),"b".repeat(64),request -> "{\"phase\":\"inactive\",\"release\":\"fixture\"}")) {
                System.out.println(server.descriptor()); System.out.flush();
                System.in.read();
            }
            return;
        }
        run();
    }
    public static void run() throws Exception {
        byte[] key=new byte[32],aad=new byte[81]; Arrays.fill(key,(byte)7);
        byte[] frame=seal(key,aad,"fixture".getBytes(StandardCharsets.UTF_8));
        check(Arrays.equals(open(key,aad,frame),"fixture".getBytes(StandardCharsets.UTF_8)),"AES-GCM frame roundtrip");
        byte[] wrong=key.clone();wrong[0]++;
        reject(() -> open(wrong,aad,frame),"wrong capability denied");
        byte[] changed=aad.clone();changed[48]++;
        reject(() -> open(key,changed,frame),"direction and identity are authenticated");
        reject(() -> open(key,aad,Arrays.copyOf(frame,frame.length-1)),"truncated ciphertext denied");
        reject(() -> seal(key,aad,new byte[0]),"empty frame denied");
        reject(() -> seal(key,aad,new byte[65_537]),"oversize frame denied");
        AtomicInteger calls=new AtomicInteger();
        ProcessHandle self=ProcessHandle.current();
        try(ResidentControl server=new ResidentControl(self.pid(),self.info().startInstant().orElseThrow().toEpochMilli(),"b".repeat(64),request -> {calls.incrementAndGet();return "{\"phase\":\"inactive\"}";})) {
            var endpoint=JsonParser.parseString(server.descriptor()).getAsJsonObject();
            int port=endpoint.get("port").getAsInt();
            byte[] capability=HexFormat.of().parseHex(endpoint.get("key").getAsString());
            byte[] binding=ByteBuffer.allocate(48).putLong(endpoint.get("pid").getAsLong()).putLong(endpoint.get("started").getAsLong()).put(HexFormat.of().parseHex(endpoint.get("digest").getAsString())).array();
            byte[] challenge=new byte[32];Arrays.fill(challenge,(byte)42);
            byte[] replay=null,priorProof=null;
            for(String action:new String[]{"inspect","start","status","stop"}) {
                try(Socket socket=new Socket("127.0.0.1",port)) {
                    socket.setSoTimeout(5000);var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
                    output.write(challenge);output.flush();
                    byte[] proof=ResidentControl.readSealed(input,capability,ResidentControl.aad(binding,(byte)1,challenge));
                    check(proof.length==64&&Arrays.equals(Arrays.copyOf(proof,32),challenge),"resident proves client challenge before request");
                    if(priorProof!=null)check(!Arrays.equals(proof,priorProof),"fresh server challenge prevents replay");priorProof=proof;
                    byte[] round=MessageDigest.getInstance("SHA-256").digest(proof);
                    replay=seal(capability,ResidentControl.aad(binding,(byte)2,round),("{\"action\":\""+action+"\"}").getBytes(StandardCharsets.UTF_8));
                    output.write(replay);output.flush();
                    check(new String(ResidentControl.readSealed(input,capability,ResidentControl.aad(binding,(byte)3,round)),StandardCharsets.UTF_8).contains("inactive"),"authenticated command response "+action);
                }
            }
            check(calls.get()==4,"exactly four authorized handler calls");
            try(Socket socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(5000);var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
                output.write(challenge);output.flush();ResidentControl.readSealed(input,capability,ResidentControl.aad(binding,(byte)1,challenge));
                output.write(replay);output.flush();check(input.read()==-1,"captured prior request rejected on new connection");
            }
            check(calls.get()==4,"replay never reaches command handler");
        }
        System.out.println("PASS resident control: "+checks+" checks; synthetic loopback only");
    }
    static com.google.gson.JsonObject request(com.google.gson.JsonObject endpoint,String request) throws Exception {
        byte[] key=HexFormat.of().parseHex(endpoint.get("key").getAsString());
        byte[] binding=ByteBuffer.allocate(48).putLong(endpoint.get("pid").getAsLong()).putLong(endpoint.get("started").getAsLong()).put(HexFormat.of().parseHex(endpoint.get("digest").getAsString())).array();
        byte[] challenge=new byte[32];new java.security.SecureRandom().nextBytes(challenge);
        try(Socket socket=new Socket("127.0.0.1",endpoint.get("port").getAsInt())) {
            socket.setSoTimeout(5000);var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
            output.write(challenge);output.flush();
            byte[] proof=ResidentControl.readSealed(input,key,ResidentControl.aad(binding,(byte)1,challenge));
            if(proof.length!=64||!Arrays.equals(Arrays.copyOf(proof,32),challenge))throw new IOException("Resident fixture challenge mismatch");
            byte[] round=MessageDigest.getInstance("SHA-256").digest(proof);
            ResidentControl.writeSealed(output,key,ResidentControl.aad(binding,(byte)2,round),request.getBytes(StandardCharsets.UTF_8));
            return JsonParser.parseString(new String(ResidentControl.readSealed(input,key,ResidentControl.aad(binding,(byte)3,round)),StandardCharsets.UTF_8)).getAsJsonObject();
        } finally {Arrays.fill(key,(byte)0);}
    }
    private static byte[] seal(byte[] key,byte[] aad,byte[] plain)throws Exception {var bytes=new ByteArrayOutputStream();ResidentControl.writeSealed(new DataOutputStream(bytes),key,aad,plain);return bytes.toByteArray();}
    private static byte[] open(byte[] key,byte[] aad,byte[] frame)throws Exception {return ResidentControl.readSealed(new DataInputStream(new ByteArrayInputStream(frame)),key,aad);}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    @FunctionalInterface private interface Action {void run()throws Exception;}
    private static void reject(Action action,String label)throws Exception {try{action.run();}catch(IOException|java.security.GeneralSecurityException expected){checks++;return;}throw new AssertionError(label);}
}
