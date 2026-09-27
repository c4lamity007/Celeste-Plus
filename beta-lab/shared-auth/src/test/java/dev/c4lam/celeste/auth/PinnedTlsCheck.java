package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Public server vectors and real loopback TLS: pin rejection occurs before HTTP credentials. */
public final class PinnedTlsCheck {
    private static int checks;
    private PinnedTlsCheck() { }
    public static void main(String[] args) throws Exception {
        JsonObject golden = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        String token = golden.getAsJsonObject("response").get("policy").getAsString();
        byte[] key = decode(golden.get("public_key").getAsString());
        String origin = golden.get("origin").getAsString(), product = "celeste_plus", nonce = golden.get("request_nonce").getAsString();
        long now = golden.get("now").getAsLong();
        check(PinnedTls.verify(token, key, origin, product, nonce, now).pins().equals(Set.of("a".repeat(64), "b".repeat(64))), "actual server signed pin rotation vector");
        reject(() -> PinnedTls.verify(token,key,origin,product,"Z".repeat(32),now));
        reject(() -> PinnedTls.verify(token,key,"https://other.invalid",product,nonce,now));
        reject(() -> PinnedTls.verify(token,key,origin,"celeste",nonce,now));
        reject(() -> PinnedTls.verify(token,key,origin,product,nonce,now+120));
        byte[] wrong = key.clone(); wrong[0] ^= 1; reject(() -> PinnedTls.verify(token,wrong,origin,product,nonce,now));
        reject(() -> PinnedTls.verify(token+"=",key,origin,product,nonce,now));
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] raw = keys.getPublic().getEncoded(); raw = Arrays.copyOfRange(raw,raw.length-32,raw.length); final byte[] publicKey = raw;
        JsonObject original = JsonParser.parseString(new String(decode(token.split("\\.")[1]),StandardCharsets.UTF_8)).getAsJsonObject();
        check(PinnedTls.verify(sign(keys,original),publicKey,origin,product,nonce,now).pins().size()==2,"synthetic signer baseline accepted before mutations");
        for (Consumer<JsonObject> change : List.<Consumer<JsonObject>>of(
            c->c.addProperty("exp",now+121), c->c.addProperty("iat",now+6),c->c.addProperty("nbf",now-1),
            c->c.addProperty("exp",now),c->c.addProperty("exp",now+0.5),c->c.addProperty("extra",true),
            c->c.remove("origin"),c->c.addProperty("request_nonce",nonce+"="),
            c->c.getAsJsonArray("leaf_sha256").add("a".repeat(64)),c->c.getAsJsonArray("leaf_sha256").set(0,new com.google.gson.JsonPrimitive("A".repeat(64))),
            c->c.add("leaf_sha256",new com.google.gson.JsonArray()))) {
            JsonObject value=original.deepCopy();change.accept(value);String changed=sign(keys,value);
            reject(()->PinnedTls.verify(changed,publicKey,origin,product,nonce,now));
        }
        realTls();
        System.out.println("PASS: signed TLS policy and handshake pin checks ("+checks+" checks; public fixtures + loopback TLS only)");
    }
    private static void realTls() throws Exception {
        Path directory=Files.createTempDirectory("celeste-tls-fixture-");
        try {
            Path store=directory.resolve("fixture.p12");
            Path keytool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool");
            Process process=new ProcessBuilder(keytool.toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN=localhost","-ext","SAN=dns:localhost","-storetype","PKCS12","-keystore",store.toString(),"-storepass","public-fixture-only","-noprompt")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("keytool fixture timeout");}
            check(process.exitValue()==0,"public TLS fixture certificate generated");
            KeyStore ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){ks.load(in,"public-fixture-only".toCharArray());}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,"public-fixture-only".toCharArray());
            SSLContext server=SSLContext.getInstance("TLS");server.init(km.getKeyManagers(),null,null);
            KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("fixture",ks.getCertificate("fixture"));
            TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
            var delegate=(X509ExtendedTrustManager)tm.getTrustManagers()[0];
            String pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("fixture").getEncoded()));
            handshake(server,delegate,pin,true);handshake(server,delegate,"0".repeat(64),false);handshake(server,delegate,pin,false,true);
            TrustManagerFactory system=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());system.init((KeyStore)null);
            handshake(server,(X509ExtendedTrustManager)system.getTrustManagers()[0],pin,false);
        } finally { try(var files=Files.walk(directory)){ for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } }
    }
    private static void handshake(SSLContext server, X509ExtendedTrustManager trust, String pin, boolean accepted) throws Exception {
        handshake(server,trust,pin,accepted,false);
    }
    private static void handshake(SSLContext server, X509ExtendedTrustManager trust, String pin, boolean accepted, boolean expired) throws Exception {
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{new PinnedTls.LeafTrust(trust,Set.of(pin),System.nanoTime()+(expired?-1:Duration.ofSeconds(120).toNanos()))},null);
        AtomicBoolean httpBytes=new AtomicBoolean();
        try(SSLServerSocket socket=(SSLServerSocket)server.getServerSocketFactory().createServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            socket.setSoTimeout(8000);
            Thread responder=Thread.ofVirtual().start(()->{
                try(SSLSocket peer=(SSLSocket)socket.accept()) {
                    peer.setSoTimeout(5000);peer.startHandshake();
                    int seen=0;StringBuilder head=new StringBuilder();
                    while(seen++<4096){int b=peer.getInputStream().read();if(b<0)break;httpBytes.set(true);head.append((char)b);if(head.toString().endsWith("\r\n\r\n"))break;}
                    peer.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }catch(java.io.IOException expected){ }
            });
            try(HttpClient client=HttpClient.newBuilder().sslContext(context).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()) {
                HttpRequest request=HttpRequest.newBuilder(URI.create("https://localhost:"+socket.getLocalPort()+"/credential-fixture")).timeout(Duration.ofSeconds(5))
                    .header("Authorization","DPoP public-test-token").GET().build();
                try { check(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==204 && accepted,"only PKIX plus correct leaf permits request"); }
                catch(java.io.IOException failure){check(!accepted,"incorrect pin/untrusted certificate is rejected");}
            }
            responder.join(8000);check(!responder.isAlive(),"TLS fixture terminates");check(httpBytes.get()==accepted,"failed pin/PKIX sends zero HTTP credential bytes");
        }
    }
    private static String sign(KeyPair keys,JsonObject value)throws Exception {
        String text=encode("{\"typ\":\"CST\",\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\"}".getBytes(StandardCharsets.US_ASCII))+"."+encode(value.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer=Signature.getInstance("Ed25519");signer.initSign(keys.getPrivate());signer.update(text.getBytes(StandardCharsets.US_ASCII));return text+"."+encode(signer.sign());
    }
    private static byte[] decode(String text){return Base64.getUrlDecoder().decode(text);}
    private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();}catch(java.security.GeneralSecurityException expected){checks++;return;}throw new AssertionError("Accepted invalid TLS policy");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
