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
 void verifyRetainedModules(){throw new AssertionError("Unexpected module provider");}
 CompletionStage<Void> prefetchCatalog(){throw new AssertionError("Unexpected module catalog prefetch");}
 ModuleDeliveryClient delivery(){return failingExpiry;}
 CompletionStage<Void> stopped(){return CompletableFuture.completedFuture(null);}
 @Override public void close(){closed=true;}
}
