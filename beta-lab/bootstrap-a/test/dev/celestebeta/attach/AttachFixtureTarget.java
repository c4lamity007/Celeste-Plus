package dev.celestebeta.attach;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Plain, self-created non-Minecraft JVM. Its nonce and unchanged behavior bind the fixture target. */
public final class AttachFixtureTarget {
    private AttachFixtureTarget() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[0-9a-f]{32}")) throw new IllegalArgumentException("fixture nonce required");
        String nonce = args[0];
        System.out.println("READY:" + nonce + ":" + ProcessHandle.current().pid()); System.out.flush();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String command = input.readLine(); command != null; command = input.readLine()) {
                if (command.equals("STOP:" + nonce)) { System.out.println("STOPPED:" + nonce); System.out.flush(); return; }
                if (!command.equals("CHECK:" + nonce)) throw new IllegalArgumentException("wrong fixture nonce");
                boolean absent = missing("net.minecraft.client.MinecraftClient", ClassLoader.getSystemClassLoader())
                    && missing("dev.c4lam.celeste.beta.lifecycle.BetaPayloadSession", ClassLoader.getSystemClassLoader())
                    && missing("dev.celestebeta.host.ResourceReloadBridge", null);
                System.out.println("SAFE:" + nonce + ":" + absent + ":" + baseline(13)); System.out.flush();
            }
        }
    }
    private static int baseline(int value) { return value * 3 + 1; }
    private static boolean missing(String name, ClassLoader loader) {
        try { Class.forName(name, false, loader); return false; }
        catch (ClassNotFoundException expected) { return true; }
    }
}
