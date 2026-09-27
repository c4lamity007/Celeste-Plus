package dev.c4lam.celeste.auth;

import java.util.concurrent.atomic.AtomicInteger;

final class HardwareFingerprint {
    static final AtomicInteger collections = new AtomicInteger();
    static HardwareFingerprint collect() { collections.incrementAndGet(); return new HardwareFingerprint(); }
}
