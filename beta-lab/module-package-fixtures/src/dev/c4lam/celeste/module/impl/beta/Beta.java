package dev.c4lam.celeste.module.impl.beta;
import fixture.Api; import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
public final class Beta implements ModuleEntrypoint { public Beta() {} public Object create(Object services) { return Api.neutral(2); } }
