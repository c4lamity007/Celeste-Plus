package dev.c4lam.celeste.auth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ArtifactHardwareIdentityCheck {
    private static int checks;
    private ArtifactHardwareIdentityCheck() { }
    public static void main(String[] args)throws Exception {
        if(args.length==1) {
            String expected=Files.readString(Path.of(args[0])).strip();
            check(expected.matches("[0-9a-f]{64}")&&ArtifactHardwareIdentity.forGame(ArtifactHardwareIdentityCheck.class.getClassLoader()).get().equals(expected),"Java/native actual host v2 digests differ");
            System.out.println("PASS: actual local Java/native hardware-v2 parity; no identifiers recorded");return;
        }
        String hash=WindowsArtifactHardware.digest(" CPU ","Disk","Guid");
        check(hash.equals(WindowsArtifactHardware.digest("cpu","disk","guid")),"ASCII normalization stable");
        for(String[] values:new String[][]{{"new","disk","guid"},{"cpu","new","guid"},{"cpu","disk","new"}})
            check(!hash.equals(WindowsArtifactHardware.digest(values[0],values[1],values[2])),"all hardware parts bound");
        for(String bad:new String[]{"","unknown","none","default string","a:b","a\nb","a\0b"})reject(()->WindowsArtifactHardware.digest(bad,"disk","guid"));
        byte[] raw=new byte[26];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(4,18).put(8,(byte)4).put(9,(byte)16).putLong(16,0xBFEBFBFF000906EAL);
        check(WindowsArtifactHardware.firmwareIds(raw).equals(java.util.List.of("bfebfbff000906ea")),"firmware processor bytes match native/WMI hex");
        reject(()->WindowsArtifactHardware.firmwareIds(Arrays.copyOf(raw,25)));
        byte[] descriptor=new byte[44];ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN).putInt(4,44).putInt(24,36);
        System.arraycopy(" SERIAL".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,descriptor,36,7);
        check(WindowsArtifactHardware.storageSerial(descriptor).equals("SERIAL"),"bounded descriptor serial parser");
        descriptor[43]=1;reject(()->WindowsArtifactHardware.storageSerial(descriptor));descriptor[43]=0;
        ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN).putInt(24,-1);reject(()->WindowsArtifactHardware.storageSerial(descriptor));
        check(ArtifactHardwareIdentity.forGame(ArtifactHardwareIdentityCheck.class.getClassLoader()) != null,
            "isolated reader resolves actual game-owned JNA without collecting host facts");
        try {
            ArtifactHardwareIdentity.forGame(ClassLoader.getPlatformClassLoader());
            throw new AssertionError("Missing JNA was accepted");
        } catch (java.security.GeneralSecurityException expected) { checks++; }
        System.out.println("PASS: delivery hardware parsers and isolated JNA linkage ("+checks+" checks; synthetic inputs)");
    }
    private static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Accepted invalid hardware facts");}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
}
