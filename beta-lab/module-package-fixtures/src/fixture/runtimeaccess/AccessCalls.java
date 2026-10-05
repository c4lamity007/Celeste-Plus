package fixture.runtimeaccess;
public class AccessCalls { public int read() { return AccessApi.hidden() + AccessApi.protectedValue + AccessApi.publicValue(); } }
