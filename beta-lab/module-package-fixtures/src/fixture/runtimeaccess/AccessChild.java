package fixture.runtimeaccess;
public class AccessChild extends AccessApi { public int read() { return protectedValue + publicValue(); } }
