package dev.c4lam.celeste.module.delivery;

import dev.c4lam.celeste.module.ModuleFeature;

/** Public, parameterless child-defined factory named by the authenticated module catalog. */
public interface ModuleEntrypoint {
    ModuleFeature create(ModuleServices services) throws Exception;
}

