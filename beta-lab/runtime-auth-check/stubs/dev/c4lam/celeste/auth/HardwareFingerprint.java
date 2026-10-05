package dev.c4lam.celeste.auth;
/** Protocol fixtures supply explicit synthetic fingerprints; no machine hardware is read. */
record HardwareFingerprint(String system, String board, String processor, java.util.List<String> monitors) {
    static HardwareFingerprint collect() { throw new AssertionError("Hardware reads forbidden in protocol fixture"); }
    static HardwareFingerprint collectWindowsUsingFirmware() { throw new AssertionError("Firmware reads forbidden in protocol fixture"); }
    static HardwareFingerprint collectWindowsUsingFirmware(WindowsFirmwareIdentity.Identity firmware) { throw new AssertionError("Hardware registry reads forbidden in protocol fixture"); }
}
