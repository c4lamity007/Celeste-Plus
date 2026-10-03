package dev.c4lam.celeste.module.impl.alpha;
public final class ReflectionLeak { public Class<?> forbidden() throws Exception { return Class.forName("dev.c4lam.celeste.module.impl.beta.Beta"); } }
