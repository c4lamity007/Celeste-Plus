package dev.c4lam.celeste.auth;

import java.util.concurrent.atomic.AtomicInteger;

record AuthConfig(String releaseDigest) {
    static final AtomicInteger loads = new AtomicInteger();
    static AuthConfig load() { loads.incrementAndGet(); return new AuthConfig("fixture-digest"); }
}
