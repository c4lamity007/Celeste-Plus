package dev.c4lam.celeste.auth;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Synthetic malformed-input checks plus an explicit read-only local parity probe. */
public final class HardwareFingerprintFallbackCheck {
    private static int checks;
    private HardwareFingerprintFallbackCheck() { }
    public static void main(String[] args) {
        byte[] raw = fixture();
        var identity = WindowsFirmwareIdentity.parse(raw);
        check(identity.system().equals(List.of("Maker", "Model", "System Serial", "00112233-4455-6677-8899-aabbccddeeff")), "system fields");
        check(identity.board().equals(List.of("Board Maker", "Board", "Rev", "Board Serial")), "board fields");
        check(identity.processorId().equals("efcdab9078563412"), "processor field");
        byte[] old = raw.clone(); old[1] = 2; old[2] = 5;
        check(WindowsFirmwareIdentity.parse(old).system().get(3).equals("33221100-5544-7766-8899-aabbccddeeff"), "older UUID order");
        for (int i = 0; i < raw.length; i++) {
            byte[] shortInput = Arrays.copyOf(raw, i); reject(() -> WindowsFirmwareIdentity.parse(shortInput));
        }
        byte[] bad = raw.clone(); bad[12] = 40; reject(() -> WindowsFirmwareIdentity.parse(bad));
        byte[] zero = raw.clone(); Arrays.fill(zero, 16, 32, (byte)0); reject(() -> WindowsFirmwareIdentity.parse(zero));
        byte[] missing = raw.clone(); missing[8] = 3; reject(() -> WindowsFirmwareIdentity.parse(missing));
        requestLocalEvidence();
        if (args.length == 1 && args[0].equals("--local-parity")) {
            var wmi = HardwareFingerprint.collectWindowsUsingWmi();
            var firmware = HardwareFingerprint.collectWindowsUsingFirmware();
            check(wmi.system().equals(firmware.system()), "local system parity");
            check(wmi.board().equals(firmware.board()), "local board parity");
            check(wmi.processor().equals(firmware.processor()), "local processor parity");
            check(wmi.monitors().equals(firmware.monitors()), "local monitor parity");
            check(firmware.equals(HardwareFingerprint.collectWindowsUsingFirmware()), "stable repeated fallback");
        } else if (args.length != 0) throw new IllegalArgumentException("Unknown test mode");
        System.out.println("PASS: " + checks + " hardware fallback checks; no identifiers recorded");
    }
    private static byte[] fixture() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[]{1,24,0,0,1,2,0,3,0x33,0x22,0x11,0,0x55,0x44,0x77,0x66,(byte)0x88,(byte)0x99,(byte)0xaa,(byte)0xbb,(byte)0xcc,(byte)0xdd,(byte)0xee,(byte)0xff});
        body.writeBytes("Maker\0Model\0System Serial\0\0".getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(new byte[]{2,8,0,0,1,2,3,4});
        body.writeBytes("Board Maker\0Board\0Rev\0Board Serial\0\0".getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(new byte[]{4,16,0,0,0,0,0,0,0x12,0x34,0x56,0x78,(byte)0x90,(byte)0xab,(byte)0xcd,(byte)0xef,0,0});
        ByteBuffer result = ByteBuffer.allocate(body.size()+8).order(ByteOrder.LITTLE_ENDIAN);
        result.put(new byte[]{0,3,0,0}).putInt(body.size()).put(body.toByteArray()); return result.array();
    }
    private static void requestLocalEvidence() {
        byte[] one=fixture(),multiple=Arrays.copyOf(one,one.length+36);
        ByteBuffer buffer=ByteBuffer.wrap(multiple).order(ByteOrder.LITTLE_ENDIAN);
        // A second, distinct CPU and a duplicate exercise the v2 all-CPU set.
        for(int offset:new int[]{one.length,one.length+18}){
            buffer.put(offset,(byte)4).put(offset+1,(byte)16).putLong(offset+8,1L);
        }
        buffer.putInt(4,multiple.length-8);
        var legacy=WindowsFirmwareIdentity.parse(multiple);
        check(legacy.processorId().equals("efcdab9078563412"),"legacy fingerprint keeps original first CPU semantics");
        check(HardwareIdentityV2.firmwareIds(multiple).equals(List.of("0000000000000001","efcdab9078563412")),"v2 sorts and deduplicates every CPU");
        var expected=new HardwareFingerprint("1".repeat(64),"2".repeat(64),"3".repeat(64),List.of("4".repeat(64)));
        String expectedHwid=HardwareIdentityV2.digest(String.join("+",HardwareIdentityV2.firmwareIds(multiple)),"Disk","Guid");
        int[] reads=new int[4];java.util.List<byte[]> captured=new java.util.ArrayList<>();
        for(int request=0;request<2;request++){
            var evidence=HardwareIdentityV2.freshWindowsEvidence(()->{
                reads[0]++;byte[] owned=multiple.clone();captured.add(owned);return owned;
            },identity->{reads[1]++;check(identity.equals(legacy),"shared legacy firmware is byte-compatible");return expected;},
                ()->{reads[2]++;return "Disk";},()->{reads[3]++;return "Guid";});
            check(evidence.fingerprint().equals(expected)&&evidence.hwid().equals(expectedHwid),"one snapshot preserves exact component and v2 hashes");
        }
        check(Arrays.equals(reads,new int[]{2,2,2,2}),"every request rereads firmware, registry fingerprint, disk and machine identity once");
        check(captured.stream().allMatch(HardwareFingerprintFallbackCheck::zeroed),"request-owned raw SMBIOS buffers wiped after success");
        byte[] failed=multiple.clone();
        reject(()->HardwareIdentityV2.freshWindowsEvidence(()->failed,identity->{throw new IllegalStateException("registry unavailable");},()->"Disk",()->"Guid"));
        check(zeroed(failed),"raw SMBIOS wiped when later hardware evidence fails");
        var changed=HardwareIdentityV2.freshWindowsEvidence(one::clone,identity->expected,()->"Disk",()->"Guid");
        check(!changed.hwid().equals(expectedHwid),"later request sees a changed CPU population instead of a cached snapshot");
    }
    private static boolean zeroed(byte[] bytes){for(byte value:bytes)if(value!=0)return false;return true;}
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Malformed firmware accepted");
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name + " failed"); checks++;
    }
}
