package dev.c4lam.celeste.module.impl.visual;

public final class StorageEspFeature extends StorageBlockEspFeature {
    @Override public String name() { return "Storage ESP"; }
    @Override protected boolean useStorageIndex() { return true; }
}
