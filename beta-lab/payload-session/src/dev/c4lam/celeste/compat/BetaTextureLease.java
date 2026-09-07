package dev.c4lam.celeste.compat;
import java.util.Set;
public final class BetaTextureLease implements AutoCloseable {
    public static int opens, closes;
    public static BetaTextureLease open(net.minecraft.client.texture.TextureManager manager, Set<String> entries) { opens++; return new BetaTextureLease(); }
    @Override public void close() { closes++; }
}
