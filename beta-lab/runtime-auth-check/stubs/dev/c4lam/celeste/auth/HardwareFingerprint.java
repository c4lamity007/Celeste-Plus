package dev.c4lam.celeste.auth;
record HardwareFingerprint(String system, String board, String processor, java.util.List<String> monitors) { static HardwareFingerprint collect() { throw new AssertionError("Hardware reads forbidden in protocol fixture"); } }
