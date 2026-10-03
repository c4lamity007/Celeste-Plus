package fixture.runtimeaccess;
public class ProtectedReceiverChild extends AccessApi { public int read(AccessApi peer) { return peer.instanceValue; } }
