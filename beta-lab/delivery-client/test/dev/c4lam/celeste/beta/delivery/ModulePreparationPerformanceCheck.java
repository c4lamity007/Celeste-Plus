package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonObject;
import dev.c4lam.celeste.beta.memory.ModuleMemoryClassLoader;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local allocation/latency diagnostics; synthetic release-size archives, no network or timing gates. */
public final class ModulePreparationPerformanceCheck {
    private record Fixture(byte[] cipher,byte[] key,JsonObject manifest,String catalog,String inventory) { }
    private static String hash(byte[] data)throws Exception{return BetaDeliveryClient.sha256(data);}
    private static byte[] utf(String text){return text.getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        int[][] shapes={{32,4096,86},{1024,128,86},{64,65536,12}};
        for(int[] shape:shapes){
            Fixture fixture=fixture(shape[0],shape[1]);
            for(int warmup=0;warmup<12;warmup++)cycle(fixture);
            var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
            long[] millis=new long[3],allocated=new long[3];
            for(int run=0;run<3;run++){
                long before=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
                for(int n=0;n<shape[2];n++)cycle(fixture);
                millis[run]=System.nanoTime()-start;
                allocated[run]=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
            }
            Arrays.sort(millis);Arrays.sort(allocated);
            System.out.printf(Locale.ROOT,"entries=%d entry_bytes=%d modules=%d ciphertext_bytes=%d median_ms=%.3f allocated_mib=%.3f mean_ms_per_module=%.3f%n",
                shape[0]+1,shape[1],shape[2],fixture.cipher.length,millis[1]/1e6,allocated[1]/1048576.0,millis[1]/1e6/shape[2]);
            Arrays.fill(fixture.key,(byte)0);
        }
    }
    private static void cycle(Fixture fixture)throws Exception{
        byte[] jar=ModuleContainer.open(fixture.cipher,fixture.key,fixture.manifest,fixture.catalog);
        try(var loader=ModuleMemoryClassLoader.open(ModulePreparationPerformanceCheck.class.getClassLoader(),jar,
            fixture.manifest.get("executable_sha256").getAsString(),fixture.inventory)){
            loader.verifyIntegrity();
        }finally{Arrays.fill(jar,(byte)0);}
    }
    private static Fixture fixture(int count,int size)throws Exception{
        Map<String,byte[]> entries=new TreeMap<>();Random random=new Random(0xCE1E57E);
        // The loader authenticates these bytes without defining a fixture class in this measurement.
        entries.put("fixture/Entry.class",new byte[]{(byte)0xca,(byte)0xfe,(byte)0xba,(byte)0xbe,0,0,0,65});
        for(int n=0;n<count;n++){byte[] bytes=new byte[size];random.nextBytes(bytes);entries.put(String.format(Locale.ROOT,"assets/fixture/%04d.bin",n),bytes);}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)){
            for(var entry:entries.entrySet()){
                byte[] content=entry.getValue();CRC32 crc=new CRC32();crc.update(content);
                Deflater deflater=new Deflater(6,true);deflater.setInput(content);deflater.finish();
                byte[] compressed=new byte[content.length+128];int compressedSize=deflater.deflate(compressed);deflater.end();
                ZipEntry record=new ZipEntry(entry.getKey());record.setMethod(ZipEntry.DEFLATED);
                record.setSize(content.length);record.setCompressedSize(compressedSize);record.setCrc(crc.getValue());
                zip.putNextEntry(record);zip.write(content);zip.closeEntry();Arrays.fill(compressed,(byte)0);
            }
        }
        byte[] jar=output.toByteArray();MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(var entry:entries.entrySet()){
            byte[] name=utf(entry.getKey());digest.update(ByteBuffer.allocate(4).putInt(name.length).array());digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(entry.getValue().length).array());digest.update(entry.getValue());
        }
        String inventory=HexFormat.of().formatHex(digest.digest()),catalog="a".repeat(64);
        JsonObject metadata=new JsonObject();metadata.addProperty("schema","celeste-module-container-1");
        metadata.addProperty("release_id","fixture-perf");metadata.addProperty("host_abi",1);metadata.addProperty("module_id","fixture");
        metadata.addProperty("version","1");metadata.addProperty("artifact_id","1".repeat(32));metadata.addProperty("executable_sha256",hash(jar));
        metadata.addProperty("executable_size",jar.length);metadata.addProperty("entries_sha256",inventory);metadata.addProperty("watermark","2".repeat(32));
        metadata.addProperty("real_record","3".repeat(32));metadata.addProperty("transform_version",1);metadata.addProperty("decoy_bytes",0);
        byte[] json=utf(BetaDeliveryClient.canonical(metadata));ByteBuffer container=ByteBuffer.allocate(6+4+json.length+4+20+jar.length);
        container.put(utf("CSTMC1")).putInt(json.length).put(json).putInt(1).put(HexFormat.of().parseHex("3".repeat(32))).putInt(jar.length).put(jar);
        JsonObject manifest=metadata.deepCopy();manifest.addProperty("schema","celeste-module-personalization-1");
        manifest.addProperty("container_sha256",hash(container.array()));manifest.addProperty("container_size",container.capacity());
        JsonObject aad=new JsonObject();aad.addProperty("schema","celeste-module-aead-1");aad.addProperty("product","celeste_plus");
        for(String name:List.of("release_id","module_id","artifact_id"))aad.add(name,metadata.get(name));aad.addProperty("catalog_sha256",catalog);
        byte[] key=new byte[32],nonce=new byte[12];random.nextBytes(key);random.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(utf(BetaDeliveryClient.canonical(aad)));byte[] encrypted=cipher.doFinal(container.array());
        byte[] ciphertext=ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array();
        manifest.addProperty("ciphertext_size",ciphertext.length);manifest.addProperty("ciphertext_sha256",hash(ciphertext));
        Arrays.fill(jar,(byte)0);Arrays.fill(container.array(),(byte)0);entries.values().forEach(bytes->Arrays.fill(bytes,(byte)0));
        return new Fixture(ciphertext,key,manifest,catalog,inventory);
    }
}
