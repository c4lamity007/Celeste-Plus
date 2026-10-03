package dev.c4lam.celeste.module.impl.alpha;
import fixture.Api; import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
public final class Alpha implements ModuleEntrypoint {
 public Alpha() {} public Object create(Object services) { return new Algorithm(); }
 private static class Algorithm { int tick() { return Api.neutral(1); } }
}
