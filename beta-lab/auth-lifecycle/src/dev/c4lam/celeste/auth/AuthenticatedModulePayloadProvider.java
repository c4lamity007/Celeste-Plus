package dev.c4lam.celeste.auth;
import dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
/** Module adapter is absent in legacy authentication scenarios, never a simulated successful delivery. */
final class AuthenticatedModulePayloadProvider implements AutoCloseable {
 private final ModuleDeliveryClient failingExpiry;
 boolean closed;
 AuthenticatedModulePayloadProvider(ModuleDeliveryClient delivery,Consumer<Throwable> failure){
  throw new AssertionError("Legacy lifecycle fixture must not install module provider");
 }
 private AuthenticatedModulePayloadProvider(){failingExpiry=new ModuleDeliveryClient();}
 static AuthenticatedModulePayloadProvider failingExpiry(){return new AuthenticatedModulePayloadProvider();}
 boolean quarantined;
 void quarantine(){quarantined=true;}
 void verifyRetainedModules() throws java.security.GeneralSecurityException {throw new AssertionError("Unexpected module provider");}
 static final class RetainedModuleMismatch extends java.security.GeneralSecurityException {
  private static final long serialVersionUID=1L;
  SecurityIncidentProtocol.Observation observation(){return new SecurityIncidentProtocol.Observation(SecurityIncidentProtocol.Kind.MODULE_MUTATION,"a".repeat(32),"a".repeat(64),"b".repeat(64));}
  SecurityIncidentProtocol.Observation sample(){return observation();}
  public void close(){ }
 }
 CompletionStage<Void> prefetchCatalog(){throw new AssertionError("Unexpected module catalog prefetch");}
 ModuleDeliveryClient delivery(){return failingExpiry;}
 CompletionStage<Void> stopped(){return CompletableFuture.completedFuture(null);}
 @Override public void close(){closed=true;}
}
