import dev.c4lam.celeste.auth.CredentialMaterial;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;

/** Run after the actual release renamer with no original helper on the classpath. */
public final class AuthNamespaceCheck {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(CredentialMaterial.class.getName().matches("org\\.w3c\\.dom\\.utility\\.misc\\.[A-Z]"), "Real package camouflage and short alias");
        check(Class.forName("org.w3c.dom.Node").getModule().getName().equals("java.xml"), "Standard XML type remains the JDK type");
        try { Class.forName("dev.c4lam.celeste.auth.CredentialMaterial"); throw new AssertionError("Original helper still present"); }
        catch (ClassNotFoundException expected) { checks++; }
        var signing=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var encryption=KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] sign=signing.getPrivate().getEncoded(), encrypt=encryption.getPrivate().getEncoded(), token=new byte[32];
        byte[] encoded, message={3,1,4,1,5};
        try(CredentialMaterial material=new CredentialMaterial("a".repeat(32),1,sign,encrypt,token,"public-certificate","uuid","Renée")) {
            Arrays.fill(sign,(byte)0);Arrays.fill(encrypt,(byte)0);Arrays.fill(token,(byte)0);
            material.validate();encoded=material.encode();
            var verifier=Signature.getInstance("Ed25519"); verifier.initVerify(signing.getPublic());verifier.update(message);
            check(verifier.verify(material.signInstallation(message)), "Actual installation signature survives aliasing");
            try(CredentialMaterial decoded=CredentialMaterial.decode(encoded)) {
                decoded.validate();check(decoded.gameName().equals("Renée"),"Existing UTF-8 credential schema preserved");
                check(Arrays.equals(encoded,decoded.encode()),"Serialization survives relocation");
                decoded.close();check(decoded.isDestroyed(),"Destroyable state survives relocation");
                try{decoded.signInstallation(message);throw new AssertionError("Destroyed key signed");}
                catch(java.security.GeneralSecurityException expected){checks++;}
            } finally { Arrays.fill(encoded,(byte)0); }
        }
        System.out.println("PASS auth namespace: "+checks+" real JVM/crypto/schema/destruction checks; no JNI added");
    }
}
