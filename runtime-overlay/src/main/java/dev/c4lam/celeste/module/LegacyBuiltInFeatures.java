package dev.c4lam.celeste.module;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import net.minecraft.client.MinecraftClient;

/** Development/monolithic adapter using the exact same factories as independent delivery. */
public final class LegacyBuiltInFeatures {
    private static boolean registered;
    private LegacyBuiltInFeatures() { }
    public static synchronized void register(ModuleManager modules) {
        if (registered) return;
        dev.c4lam.celeste.render.ModuleVisualBudget.initialize();
        registerOne(modules, "sword_aura", "Sword Aura", new dev.c4lam.celeste.module.payload.SwordAuraEntrypoint());
        registerOne(modules, "auto_sword", "Auto Sword", new dev.c4lam.celeste.module.payload.AutoSwordEntrypoint());
        registerOne(modules, "shield_break", "Shield Break", new dev.c4lam.celeste.module.payload.ShieldBreakEntrypoint());
        registerOne(modules, "trigger_bot", "Trigger Bot", new dev.c4lam.celeste.module.payload.TriggerBotEntrypoint());
        registerOne(modules, "fake_lag", "Fake Lag", new dev.c4lam.celeste.module.payload.FakeLagEntrypoint());
        registerOne(modules, "mace_aura", "Mace Aura", new dev.c4lam.celeste.module.payload.MaceAuraEntrypoint());
        registerOne(modules, "wind_charge", "Wind Charge", new dev.c4lam.celeste.module.payload.WindChargeEntrypoint());
        registerOne(modules, "catch", "Catch", new dev.c4lam.celeste.module.payload.CatchEntrypoint());
        registerOne(modules, "horizontal_pearl_catch", "Horizontal Pearl Catch", new dev.c4lam.celeste.module.payload.HorizontalPearlCatchEntrypoint());
        registerOne(modules, "mace_swap", "Mace Swap", new dev.c4lam.celeste.module.payload.MaceSwapEntrypoint());
        registerOne(modules, "smash_guide", "Smash Guide", new dev.c4lam.celeste.module.payload.SmashGuideEntrypoint());
        registerOne(modules, "auto_crystal_aura", "Auto Crystal Aura", new dev.c4lam.celeste.module.payload.AutoCrystalAuraEntrypoint());
        registerOne(modules, "surround", "Surround", new dev.c4lam.celeste.module.payload.SurroundEntrypoint());
        registerOne(modules, "auto_trap", "Auto Trap", new dev.c4lam.celeste.module.payload.AutoTrapEntrypoint());
        registerOne(modules, "anchor_aura", "Anchor Aura", new dev.c4lam.celeste.module.payload.AnchorAuraEntrypoint());
        registerOne(modules, "auto_crystal", "Auto Crystal", new dev.c4lam.celeste.module.payload.AutoCrystalEntrypoint());
        registerOne(modules, "auto_hit_crystal", "Auto Hit Crystal", new dev.c4lam.celeste.module.payload.AutoHitCrystalEntrypoint());
        registerOne(modules, "anchor_macro", "Anchor Macro", new dev.c4lam.celeste.module.payload.AnchorMacroEntrypoint());
        registerOne(modules, "double_anchor_macro", "Double Anchor Macro", new dev.c4lam.celeste.module.payload.DoubleAnchorMacroEntrypoint());
        registerOne(modules, "silent_safe_anchor", "Silent Safe Anchor", new dev.c4lam.celeste.module.payload.SilentSafeAnchorEntrypoint());
        registerOne(modules, "safe_anchor", "Safe Anchor", new dev.c4lam.celeste.module.payload.SafeAnchorEntrypoint());
        registerOne(modules, "sprint", "Sprint", new dev.c4lam.celeste.module.payload.SprintEntrypoint());
        registerOne(modules, "safe_walk", "Safe Walk", new dev.c4lam.celeste.module.payload.SafeWalkEntrypoint());
        registerOne(modules, "elytra", "Elytra", new dev.c4lam.celeste.module.payload.ElytraEntrypoint());
        registerOne(modules, "freecam", "Freecam", new dev.c4lam.celeste.module.payload.FreecamEntrypoint());
        registerOne(modules, "fullbright", "Fullbright", new dev.c4lam.celeste.module.payload.FullbrightEntrypoint());
        registerOne(modules, "player_models", "Player Models", new dev.c4lam.celeste.module.payload.PlayerModelsEntrypoint());
        registerOne(modules, "entity_esp", "Entity ESP", new dev.c4lam.celeste.module.payload.EntityEspEntrypoint());
        registerOne(modules, "hole_esp", "Hole ESP", new dev.c4lam.celeste.module.payload.HoleEspEntrypoint());
        registerOne(modules, "storage_esp", "Storage ESP", new dev.c4lam.celeste.module.payload.StorageEspEntrypoint());
        registerOne(modules, "block_esp", "Block ESP", new dev.c4lam.celeste.module.payload.BlockEspEntrypoint());
        registerOne(modules, "suspicious_chunk_finder", "Suspicious Chunk Finder", new dev.c4lam.celeste.module.payload.SuspiciousChunkFinderEntrypoint());
        registerOne(modules, "auto_head_hitter", "Auto Head Hitter", new dev.c4lam.celeste.module.payload.AutoHeadHitterEntrypoint());
        registerOne(modules, "auto_firework", "Auto Firework", new dev.c4lam.celeste.module.payload.AutoFireworkEntrypoint());
        registerOne(modules, "auto_tool", "Auto Tool", new dev.c4lam.celeste.module.payload.AutoToolEntrypoint());
        registerOne(modules, "keep_sprint", "Keep Sprint", new dev.c4lam.celeste.module.payload.KeepSprintEntrypoint());
        registerOne(modules, "auto_mlg", "Auto MLG", new dev.c4lam.celeste.module.payload.AutoMlgEntrypoint());
        registerOne(modules, "web_breaker", "Web Breaker", new dev.c4lam.celeste.module.payload.WebBreakerEntrypoint());
        registerOne(modules, "cover_up", "Cover Up", new dev.c4lam.celeste.module.payload.CoverUpEntrypoint());
        registerOne(modules, "trap_save", "Trap Save", new dev.c4lam.celeste.module.payload.TrapSaveEntrypoint());
        registerOne(modules, "trajectories", "Trajectories", new dev.c4lam.celeste.module.payload.TrajectoriesEntrypoint());
        registerOne(modules, "trails", "Trails", new dev.c4lam.celeste.module.payload.TrailsEntrypoint());
        registerOne(modules, "ore_esp", "Ore ESP", new dev.c4lam.celeste.module.payload.OreEspEntrypoint());
        registerOne(modules, "arrow_esp", "Arrow ESP", new dev.c4lam.celeste.module.payload.ArrowEspEntrypoint());
        registerOne(modules, "item_esp", "Item ESP", new dev.c4lam.celeste.module.payload.ItemEspEntrypoint());
        registerOne(modules, "target_esp", "Target ESP", new dev.c4lam.celeste.module.payload.TargetEspEntrypoint());
        registerOne(modules, "jump_circles", "Jump Circles", new dev.c4lam.celeste.module.payload.JumpCirclesEntrypoint());
        registerOne(modules, "player_esp", "Player ESP", new dev.c4lam.celeste.module.payload.PlayerEspEntrypoint());
        registerOne(modules, "chams", "Chams", new dev.c4lam.celeste.module.payload.ChamsEntrypoint());
        registerOne(modules, "block_highlight", "Block Highlight", new dev.c4lam.celeste.module.payload.BlockHighlightEntrypoint());
        registerOne(modules, "ambient_effects", "Ambient Effects", new dev.c4lam.celeste.module.payload.AmbientEffectsEntrypoint());
        registerOne(modules, "hit_effects", "Hit Effects", new dev.c4lam.celeste.module.payload.HitEffectsEntrypoint());
        registerOne(modules, "sky_lyrics", "Sky Lyrics", new dev.c4lam.celeste.module.payload.SkyLyricsEntrypoint());
        registerOne(modules, "atmosphere", "Atmosphere", new dev.c4lam.celeste.module.payload.AtmosphereEntrypoint());
        registerOne(modules, "custom_sky", "Custom Sky", new dev.c4lam.celeste.module.payload.CustomSkyEntrypoint());
        registerOne(modules, "custom_fog", "Custom Fog", new dev.c4lam.celeste.module.payload.CustomFogEntrypoint());
        registerOne(modules, "ground_shaders", "Ground Shaders", new dev.c4lam.celeste.module.payload.GroundShadersEntrypoint());
        registerOne(modules, "swing_speed", "Swing Speed", new dev.c4lam.celeste.module.payload.SwingSpeedEntrypoint());
        registerOne(modules, "hand_models", "Hand Models", new dev.c4lam.celeste.module.payload.HandModelsEntrypoint());
        registerOne(modules, "swing_animations", "Swing Animations", new dev.c4lam.celeste.module.payload.SwingAnimationsEntrypoint());
        registerOne(modules, "hand_shaders", "Hand Shaders", new dev.c4lam.celeste.module.payload.HandShadersEntrypoint());
        registerOne(modules, "fps_counter", "FPS Counter", new dev.c4lam.celeste.module.payload.FpsCounterEntrypoint());
        registerOne(modules, "watermark", "Watermark", new dev.c4lam.celeste.module.payload.WatermarkEntrypoint());
        registerOne(modules, "array_list", "Array List", new dev.c4lam.celeste.module.payload.ArrayListEntrypoint());
        registerOne(modules, "fake_gamble", "Fake Gamble", new dev.c4lam.celeste.module.payload.FakeGambleEntrypoint());
        registerOne(modules, "auto_pot", "Auto Pot", new dev.c4lam.celeste.module.payload.AutoPotEntrypoint());
        registerOne(modules, "throw_pot", "Throw Pot", new dev.c4lam.celeste.module.payload.ThrowPotEntrypoint());
        registerOne(modules, "silent_totem", "Silent Totem", new dev.c4lam.celeste.module.payload.SilentTotemEntrypoint());
        registerOne(modules, "auto_extinguish", "Auto Extinguish", new dev.c4lam.celeste.module.payload.AutoExtinguishEntrypoint());
        registerOne(modules, "auto_double_hand", "Auto Double Hand", new dev.c4lam.celeste.module.payload.AutoDoubleHandEntrypoint());
        registerOne(modules, "elytra_swap", "Elytra Swap", new dev.c4lam.celeste.module.payload.ElytraSwapEntrypoint());
        registerOne(modules, "hover_totem", "Hover Totem", new dev.c4lam.celeste.module.payload.HoverTotemEntrypoint());
        registerOne(modules, "legit_auto_totem", "Legit Auto Totem", new dev.c4lam.celeste.module.payload.LegitAutoTotemEntrypoint());
        registerOne(modules, "auto_web", "Auto Web", new dev.c4lam.celeste.module.payload.AutoWebEntrypoint());
        registerOne(modules, "auto_drain", "Auto Drain", new dev.c4lam.celeste.module.payload.AutoDrainEntrypoint());
        registerOne(modules, "potion_refill", "Potion Refill", new dev.c4lam.celeste.module.payload.PotionRefillEntrypoint());
        registerOne(modules, "fast_place", "Fast Place", new dev.c4lam.celeste.module.payload.FastPlaceEntrypoint());
        registerOne(modules, "fast_exp", "Fast EXP", new dev.c4lam.celeste.module.payload.FastExpEntrypoint());
        registerOne(modules, "aim_assist", "Aim Assist", new dev.c4lam.celeste.module.payload.AimAssistEntrypoint());
        registerOne(modules, "w_tap", "W Tap", new dev.c4lam.celeste.module.payload.WTapEntrypoint());
        registerOne(modules, "s_tap", "S Tap", new dev.c4lam.celeste.module.payload.STapEntrypoint());
        registerOne(modules, "jump_reset", "Jump Reset", new dev.c4lam.celeste.module.payload.JumpResetEntrypoint());
        registerOne(modules, "anti_miss", "Anti Miss", new dev.c4lam.celeste.module.payload.AntiMissEntrypoint());
        registerOne(modules, "breach_swap", "Breach Swap", new dev.c4lam.celeste.module.payload.BreachSwapEntrypoint());
        registerOne(modules, "totem_hit", "Totem Hit", new dev.c4lam.celeste.module.payload.TotemHitEntrypoint());
        registerOne(modules, "lunge_swap", "Lunge Swap", new dev.c4lam.celeste.module.payload.LungeSwapEntrypoint());
        registered = true;
    }
    private static void registerOne(ModuleManager modules, String id, String name, ModuleEntrypoint factory) {
        var module = modules.module(name);
        if (module == null) throw new IllegalStateException("Missing module descriptor: " + id);
        var client = MinecraftClient.getInstance();
        var scope = new ModuleScope(id, () -> CelesteClient.active() && module.enabled(), client::execute);
        try (var ownership = scope.enter()) {
            var services = new ModuleServices(id, 0, scope, modules.hooks(), () -> new ModuleContext(client, module));
            ModuleFeature feature = factory.create(services);
            if (!name.equals(feature.name())) throw new IllegalStateException("Module factory identity mismatch: " + id);
            modules.registerScoped(feature, scope);
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        } catch (Exception failure) {
            scope.close();
            throw new IllegalStateException("Module factory failed: " + id, failure);
        }
    }
}
