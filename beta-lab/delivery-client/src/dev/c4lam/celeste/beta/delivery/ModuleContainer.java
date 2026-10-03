package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Parses authenticated personalized containers; decoys never enter a classloader. */
public final class ModuleContainer {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final byte[] MAGIC = "CSTMC1".getBytes(StandardCharsets.US_ASCII);
    private ModuleContainer() { }
    static byte[] open(byte[] ciphertext, byte[] key, JsonObject manifest, String catalogDigest)
            throws GeneralSecurityException {
        require(key.length == 32 && ciphertext.length >= 28 && ciphertext.length <= MAX_BYTES, "Invalid module encryption parameters");
        require(ciphertext.length == number(manifest,"ciphertext_size")
            && hash(ciphertext).equals(text(manifest,"ciphertext_sha256")), "Module ciphertext integrity mismatch");
        JsonObject aad = new JsonObject();
        aad.addProperty("schema","celeste-module-aead-1"); aad.addProperty("product","celeste_plus");
        for (String name : new String[]{"release_id","module_id","artifact_id"}) aad.addProperty(name,text(manifest,name));
        aad.addProperty("catalog_sha256",catalogDigest);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,ciphertext,0,12));
        cipher.updateAAD(BetaDeliveryClient.canonical(aad).getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(ciphertext,12,ciphertext.length-12);
        try { return extract(plaintext,manifest); }
        finally { Arrays.fill(plaintext,(byte)0); }
    }
    static byte[] extract(byte[] plaintext, JsonObject manifest) throws GeneralSecurityException {
        require(plaintext.length <= MAX_BYTES && plaintext.length >= 14
            && plaintext.length == number(manifest,"container_size")
            && hash(plaintext).equals(text(manifest,"container_sha256")), "Module container integrity mismatch");
        ByteBuffer input = ByteBuffer.wrap(plaintext);
        byte[] magic = new byte[MAGIC.length]; input.get(magic);
        require(Arrays.equals(magic,MAGIC),"Invalid module container header");
        int length = input.getInt();
        require(length > 0 && length <= 8192 && length <= input.remaining()-4,"Invalid module metadata size");
        byte[] metadataBytes = new byte[length]; input.get(metadataBytes);
        JsonObject metadata;
        try { metadata = BetaDeliveryClient.object(metadataBytes); }
        finally { Arrays.fill(metadataBytes,(byte)0); }
        Set<String> fields = Set.of("schema","release_id","host_abi","module_id","version","artifact_id",
            "executable_sha256","executable_size","entries_sha256","watermark","real_record","transform_version","decoy_bytes");
        require(metadata.keySet().equals(fields),"Unexpected module metadata fields");
        require(text(metadata,"schema").equals("celeste-module-container-1"),"Unknown module container schema");
        for (String field : fields) if (!field.equals("schema")) require(metadata.get(field).equals(manifest.get(field)),"Module container binding mismatch");
        int count = input.getInt();
        require(count >= 1 && count <= 64,"Invalid module record count");
        String real = text(manifest,"real_record");
        Set<String> ids = new HashSet<>();
        byte[] jar = null;
        long decoyBytes = 0;
        boolean complete = false;
        try {
            for (int index=0;index<count;index++) {
                require(input.remaining() >= 20,"Truncated module record");
                byte[] id = new byte[16]; input.get(id);
                String encoded = HexFormat.of().formatHex(id);
                require(ids.add(encoded),"Duplicate module record");
                int size=input.getInt();
                require(size>=0 && size<=input.remaining(),"Truncated module record data");
                if (encoded.equals(real)) {
                    require(jar==null && size==number(manifest,"executable_size"),"Invalid executable module record");
                    jar=new byte[size];input.get(jar);
                } else { decoyBytes += 20L+size;input.position(input.position()+size); }
            }
            require(!input.hasRemaining() && jar!=null,"Incomplete module container");
            require(decoyBytes == number(manifest,"decoy_bytes") && decoyBytes <= jar.length/10L,"Module decoys exceed distribution budget");
            require(hash(jar).equals(text(manifest,"executable_sha256")),"Module executable integrity mismatch");
            complete=true;
            return jar;
        } finally { if (!complete && jar!=null) Arrays.fill(jar,(byte)0); }
    }
    static String text(JsonObject value,String key) throws GeneralSecurityException { return BetaDeliveryClient.text(value,key); }
    static long number(JsonObject value,String key) throws GeneralSecurityException { return BetaDeliveryClient.integer(value,key); }
    static String hash(byte[] bytes) throws GeneralSecurityException { return BetaDeliveryClient.sha256(bytes); }
    static void require(boolean condition,String message) throws GeneralSecurityException {
        if (!condition) throw new GeneralSecurityException(message);
    }
}
