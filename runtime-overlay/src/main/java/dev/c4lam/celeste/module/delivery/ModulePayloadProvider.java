package dev.c4lam.celeste.module.delivery;

import java.util.concurrent.CompletionStage;

/** Resident delivery boundary. Implementations authenticate before defining any child code. */
public interface ModulePayloadProvider {
    CompletionStage<ModulePayloadLease> acquire(String moduleId, long activationGeneration);
    /** Request cooperative cancellation of one exact activation; acquire completes only after its work drains. */
    default void cancel(String moduleId, long activationGeneration) { }
    /** A failed disposal keeps the activation quarantined; it is not ordinary canceled work. */
    final class CleanupFailure extends IllegalStateException {
        public CleanupFailure(String message,Throwable cause){super(message,cause);}
    }
}
