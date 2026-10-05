package fixture.runtimeaccess;
public class AccessApi { public AccessApi() {} protected int instanceValue = 4; static int hidden() { return 1; } protected static int protectedValue = 2; public static int publicValue() { return 3; } }
