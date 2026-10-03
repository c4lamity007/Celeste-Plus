package dev.c4lam.celeste.module.delivery;

import dev.c4lam.celeste.module.ModuleFeature;

/** One verified artifact and its isolated loader; never a factory for resident feature code. */
public interface ModulePayloadLease extends AutoCloseable {
    ModuleFeature create(ModuleServices services) throws Exception;
    ClassLoader definingLoader();
    /** Must include the current host session and this exact module capability. */
    boolean authorized();
    /** Called only after feature, callbacks, worker tasks and render resources retire. */
    @Override void close() throws Exception;
}

