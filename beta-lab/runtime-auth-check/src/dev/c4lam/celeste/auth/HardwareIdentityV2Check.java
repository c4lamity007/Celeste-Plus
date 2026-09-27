package dev.c4lam.celeste.auth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class HardwareIdentityV2Check {
    private static int checks;
    private HardwareIdentityV2Check() { }
    public static void main(String[] args)throws Exception {
        if(args.length==1) {
            String expected=Files.readString(Path.of(args[0])).strip();
            check(expected.matches("[0-9a-f]{64}")&&HardwareIdentityV2.current().equals(expected),"Java/native actual host v2 digests differ");
            System.out.println("PASS: actual local Java/native hardware-v2 parity; no identifiers recorded");return;
        }
        String hash=HardwareIdentityV2.digest(" CPU ","Disk","Guid");
        check(hash.equals(HardwareIdentityV2.digest("cpu","disk","guid")),"ASCII normalization stable");
        for(String[] values:new String[][]{{"new","disk","guid"},{"cpu","new","guid"},{"cpu","disk","new"}})
            check(!hash.equals(HardwareIdentityV2.digest(values[0],values[1],values[2])),"all hardware parts bound");
        for(String bad:new String[]{"","unknown","none","default string","a:b","a\nb","a\0b"})reject(()->HardwareIdentityV2.digest(bad,"disk","guid"));
        byte[] raw=new byte[26];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(4,18).put(8,(byte)4).put(9,(byte)16).putLong(16,0xBFEBFBFF000906EAL);
        check(HardwareIdentityV2.firmwareIds(raw).equals(java.util.List.of("bfebfbff000906ea")),"firmware processor bytes match native/WMI hex");
        reject(()->HardwareIdentityV2.firmwareIds(Arrays.copyOf(raw,25)));
        byte[] descriptor=new byte[44];ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN).putInt(4,44).putInt(24,36);
        System.arraycopy(" SERIAL".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,descriptor,36,7);
        check(HardwareIdentityV2.storageSerial(descriptor).equals("SERIAL"),"bounded descriptor serial parser");
        descriptor[43]=1;reject(()->HardwareIdentityV2.storageSerial(descriptor));descriptor[43]=0;
        ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN).putInt(24,-1);reject(()->HardwareIdentityV2.storageSerial(descriptor));
        String session=dev.celestebeta.host.ResourceReloadBridge.processSessionId();
        check(session.matches("[A-Za-z0-9_-]{32}")&&session.equals(dev.celestebeta.host.ResourceReloadBridge.processSessionId()),"canonical resident bridge session identity stable");
        System.out.println("PASS: hardware v2 parsers and resident session identity ("+checks+" checks; synthetic inputs)");
    }
    private static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Accepted invalid hardware facts");}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
}
