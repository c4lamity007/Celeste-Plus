package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/** Bounded warning scan distilled from Volt/Silk Trap Save. */
public final class TrapSaveFeature implements ModuleFeature {
    private static final Set<Block> TRAPS = Set.of(
        Blocks.TNT, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.DISPENSER, Blocks.DROPPER,
        Blocks.OBSERVER, Blocks.REDSTONE_WIRE, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
        Blocks.TRIPWIRE_HOOK, Blocks.STONE_PRESSURE_PLATE, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
        Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE
    );
    private int cooldown;

    @Override public String name() { return "Trap Save"; }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.currentScreen != null) return;
        if (cooldown > 0) { cooldown--; return; }
        int range = Math.max(2, Math.min(8, (int) Math.round(context.slider("Range", 5))));
        BlockPos center = client.player.getBlockPos();
        int checked = 0;
        for (int y = -2; y <= 2; y++) {
            for (int x = -range; x <= range; x++) {
                for (int z = -range; z <= range; z++) {
                    if (++checked > 1800 || x * x + z * z > range * range) continue;
                    BlockPos pos = center.add(x, y, z);
                    Block block = client.world.getBlockState(pos).getBlock();
                    if (TRAPS.contains(block)) {
                        Text message = Text.literal("Celeste ").formatted(Formatting.LIGHT_PURPLE)
                            .append(Text.literal("Trap block: ").formatted(Formatting.GRAY))
                            .append(block.getName().copy().formatted(Formatting.WHITE))
                            .append(Text.literal(" (" + x + ", " + y + ", " + z + ")").formatted(Formatting.GRAY));
                        client.player.sendMessage(message, false);
                        cooldown = 100;
                        return;
                    }
                }
            }
        }
        cooldown = 20;
    }

    @Override public void onDisable(ModuleContext context) { cooldown = 0; }
    @Override public void onWorldChanged(ModuleContext context) { cooldown = 0; }
}
