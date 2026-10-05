package fixture.runtimeaccess;
public class GenericLease<Lease extends AccessApi> { private java.util.List<Lease> leases; public Lease first(Lease lease) { return lease; } }
