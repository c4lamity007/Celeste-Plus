package dev.c4lam.celeste.auth;
public final class AuthBootstrap {
    public static boolean configured;
    private AuthBootstrap() { }
    public static void configureBetaRuntime(String catalog) { configured = true; }
}
