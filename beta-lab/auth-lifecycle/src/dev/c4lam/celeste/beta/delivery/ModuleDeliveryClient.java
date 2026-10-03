package dev.c4lam.celeste.beta.delivery;
import java.security.GeneralSecurityException;
/** Boundary double: legacy lifecycle scenarios have no modular catalog. Real delivery is checked separately. */
public final class ModuleDeliveryClient {
 public static final class Session { }
 @FunctionalInterface public interface SessionSource { Session current() throws GeneralSecurityException; }
 public static Session verifiedSession(String token,long deadline,byte[] key) {
  throw new AssertionError("Legacy lifecycle fixture must not authorize modules");
 }
 public void renewActive(){throw new AssertionError("Unexpected module renewal");}
 public void expire(){throw new AssertionError("Unexpected module expiry");}
}
