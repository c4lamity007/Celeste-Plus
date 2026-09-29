package dev.c4lam.celeste.auth;

import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.XECPrivateKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic keys only; no existing activation file, license, hardware probe, game or network. */
@SuppressWarnings("try")
public final class CredentialSecretCheck {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid/closed credential accepted"); }
        catch (GeneralSecurityException expected) { checks++; }
    }
    private static byte[] field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return (byte[]) field.get(value);
    }
    private static boolean zero(byte[] value) { for(byte b:value) if(b!=0)return false; return true; }
    private static void validSignature(KeyPair key, byte[] message, byte[] signature) throws Exception {
        Signature verifier=Signature.getInstance("Ed25519"); verifier.initVerify(key.getPublic()); verifier.update(message);
        check(verifier.verify(signature), "Signature still verifies with the enrolled public key");
    }
    private static void cachedCertificateExpiry(KeyPair install, KeyPair encryption, byte[] tokenSeed, String device) throws Exception {
        KeyPair server=Crypto.generateEd25519();
        long expiration=java.time.Instant.now().getEpochSecond()+2;
        var claims=new com.google.gson.JsonObject();
        claims.addProperty("aud","celeste-installation"); claims.addProperty("sub","42"); claims.addProperty("device",device);
        claims.addProperty("install_key",Crypto.b64(Crypto.rawPublic(install.getPublic())));
        claims.addProperty("nbf",expiration-20); claims.addProperty("exp",expiration);
        String header=Crypto.b64("{\"alg\":\"EdDSA\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8));
        String unsigned=header+"."+Crypto.b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        String certificate=unsigned+"."+Crypto.b64(Crypto.sign(server.getPrivate(),unsigned.getBytes(StandardCharsets.US_ASCII)));
        var config=new AuthConfig(URI.create("https://fixture.invalid"),Crypto.rawPublic(server.getPublic()),"fixture","a".repeat(64),"fixture");
        try(InstallCredential credential=new InstallCredential(device,42,install.getPrivate().getEncoded(),encryption.getPrivate().getEncoded(),tokenSeed,certificate,"fixture","fixture");
            AuthServiceClient service=new AuthServiceClient(config,new GameIdentity("fixture","fixture"))) {
            DpopProofSigner first=service.bindCredential(credential);
            check(service.bindCredential(credential)==first,"Same credential reuses its live signer");
            while(java.time.Instant.now().getEpochSecond()<expiration)Thread.sleep(20);
            rejects(()->service.bindCredential(credential));
            service.close();check(first.isDestroyed(),"Service closes its cached signer");
        } finally { CredentialMaterial.destroy(server.getPrivate()); }
    }
    public static void main(String[] args) throws Exception {
        KeyPair install=Crypto.generateEd25519(), encryption=Crypto.generateX25519(), token=Crypto.generateEd25519();
        byte[] tokenSeed=((EdECPrivateKey)token.getPrivate()).getBytes().orElseThrow();
        byte[] message="synthetic credential check".getBytes(StandardCharsets.UTF_8);
        String device="00000000-0000-0000-0000-000000000042";
        byte[] previous=null;
        for(boolean raw:List.of(false,true)) {
            byte[] a=raw?((EdECPrivateKey)install.getPrivate()).getBytes().orElseThrow():install.getPrivate().getEncoded();
            byte[] b=raw?((XECPrivateKey)encryption.getPrivate()).getScalar().orElseThrow():encryption.getPrivate().getEncoded();
            try(CredentialMaterial material=new CredentialMaterial(device,42,a,b,tokenSeed,"signed-fixture-certificate","uuid","Björk Русский \"name\"")) {
                material.validate(); Arrays.fill(a,(byte)0); Arrays.fill(b,(byte)0);
                validSignature(install,message,material.signInstallation(message));
                validSignature(token,message,material.signToken(message));
                byte[] encoded=material.encode();
                try(CredentialMaterial restored=CredentialMaterial.decode(encoded)) {
                    restored.validate(); check(restored.gameName().equals(material.gameName()),"UTF-8 metadata preserved");
                    validSignature(install,message,restored.signInstallation(message));
                    validSignature(token,message,restored.signToken(message));
                    var publicSchema=JsonParser.parseString(new String(encoded,StandardCharsets.UTF_8)).getAsJsonObject();
                    check(publicSchema.keySet().equals(java.util.Set.of("deviceId","uid","signingPrivate","encryptionPrivate","tokenSeed","certificate","gameUuid","gameName")),"Existing schema unchanged");
                    check(Base64.getUrlDecoder().decode(publicSchema.get("tokenSeed").getAsString()).length==32,"Legacy base64 wire representation retained");
                    try(CredentialMaterial independent=restored.withGameIdentity("other","other")) {
                        restored.close(); validSignature(install,message,independent.signInstallation(message));
                    }
                }
                if(previous!=null)Arrays.fill(previous,(byte)0);previous=encoded;
                for(Field f:CredentialMaterial.class.getDeclaredFields()) {
                    if(f.getType()==String.class) { f.setAccessible(true); check(!Base64.getUrlEncoder().withoutPadding().encodeToString(tokenSeed).equals(f.get(material)),"No private String retained"); }
                }
                byte[] ownedSigning=field(material,"signing"),ownedEncryption=field(material,"encryption"),ownedToken=field(material,"token");
                material.close(); material.close();
                check(zero(ownedSigning)&&zero(ownedEncryption)&&zero(ownedToken),"Owned secret arrays wiped on close");
                check(material.isDestroyed(),"Destroyed state is explicit");
                rejects(()->material.signInstallation(message)); rejects(()->material.signToken(message)); rejects(material::encode);
            }
        }
        byte[] good=previous;
        String fixture=new String(good,StandardCharsets.UTF_8); // Synthetic fixture mutations only.
        for(String bad:List.of("{}",fixture+"x",fixture.replace("\"uid\":42","\"uid\":01"),
            fixture.replace("\"uid\":42","\"uid\":9223372036854775808"),
            "{\"uid\":42,"+fixture.substring(1),fixture.replace("\"tokenSeed\":\"","\"tokenSeed\":\"!"),
            fixture.replace("\"gameName\":", "\"unknown\":"))) {
            rejects(()->CredentialMaterial.decode(bad.getBytes(StandardCharsets.UTF_8)));
        }
        rejects(()->CredentialMaterial.decode(new byte[]{'{','"',(byte)0xc0,(byte)0xaf,'"',':','1','}'}));
        rejects(()->CredentialMaterial.decode(new byte[CredentialMaterial.MAX_PLAINTEXT+1]));
        // JSON escaped base64 characters remain compatible without allocating secret Strings.
        // Directly escape the first key character by replacing the complete token value.
        String encodedToken=Base64.getUrlEncoder().withoutPadding().encodeToString(tokenSeed);
        String escaped=fixture.replace(encodedToken,"\\u"+String.format("%04x",(int)encodedToken.charAt(0))+encodedToken.substring(1));
        try(CredentialMaterial value=CredentialMaterial.decode(escaped.getBytes(StandardCharsets.UTF_8))) { validSignature(token,message,value.signToken(message)); }
        try(DpopProofSigner signer=DpopProofSigner.fromEncoded(install.getPrivate().getEncoded(),Crypto.rawPublic(install.getPublic()),Clock.systemUTC())) {
            byte[] owned=field(signer,"privateEncoding");
            check(signer.proof("POST",URI.create("https://fixture.invalid/v1/check"),"fixture-token").split("\\.").length==3,"Opaque DPoP proof still produced");
            CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread worker=new Thread(()->{try {start.await(); for(int i=0;i<100;i++)try{signer.proof("POST",URI.create("https://fixture.invalid/v1/check"),null);}catch(GeneralSecurityException closed){break;}}catch(Throwable t){failure.set(t);}});
            worker.start();start.countDown();signer.close();worker.join();
            check(failure.get()==null&&zero(owned)&&signer.isDestroyed(),"Close/sign race leaves no owned secret or unchecked failure");
            rejects(()->signer.proof("POST",URI.create("https://fixture.invalid/v1/check"),null));
        }
        try(InstallCredential credential=InstallCredential.decode(good)) {
            validSignature(install,message,credential.signInstallation(message));
            Path dir=Files.createTempDirectory(Path.of(args[0]),"credential-fixture-");Path file=dir.resolve("device.bin");
            try {
                CredentialStore store=new CredentialStore(file);store.save(credential);
                byte[] protectedBytes=Files.readAllBytes(file);
                check(protectedBytes[0]=='C'&&protectedBytes[1]=='S'&&protectedBytes[2]=='T'&&protectedBytes[3]=='1',"Same account DPAPI envelope retained");
                try(InstallCredential restored=store.load()) { validSignature(install,message,restored.signInstallation(message));validSignature(token,message,restored.signToken(message)); }
            } finally { Files.deleteIfExists(file);Files.deleteIfExists(dir.resolve("device.bin.tmp"));Files.deleteIfExists(dir); }
        }
        cachedCertificateExpiry(install,encryption,tokenSeed,device);
        Arrays.fill(tokenSeed,(byte)0);Arrays.fill(good,(byte)0);
        dev.c4lam.celeste.beta.delivery.RuntimeInstallationSignerCheck.run();
        System.out.println("PASS credential handling: "+checks+" schema/signature/destruction/race/DPAPI checks; synthetic keys only");
    }
}
