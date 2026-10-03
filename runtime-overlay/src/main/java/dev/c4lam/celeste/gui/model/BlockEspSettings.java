package dev.c4lam.celeste.gui.model;

import dev.c4lam.celeste.gui.BlockPickerScreen;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import java.util.ArrayList;
import java.util.List;

/** Independent factory keeps picker state in the normal module persistence path. */
public final class BlockEspSettings {
    public static final List<String> CONTAINERS = ids("chest", "trapped_chest", "ender_chest", "barrel",
        "hopper", "dispenser", "dropper", "furnace", "blast_furnace", "smoker", "brewing_stand",
        "crafter", "decorated_pot", "shulker_box", "white_shulker_box", "orange_shulker_box",
        "magenta_shulker_box", "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box",
        "pink_shulker_box", "gray_shulker_box", "light_gray_shulker_box", "cyan_shulker_box",
        "purple_shulker_box", "blue_shulker_box", "brown_shulker_box", "green_shulker_box",
        "red_shulker_box", "black_shulker_box", "chiseled_bookshelf",
        "copper_chest", "exposed_copper_chest", "weathered_copper_chest", "oxidized_copper_chest",
        "waxed_copper_chest", "waxed_exposed_copper_chest", "waxed_weathered_copper_chest", "waxed_oxidized_copper_chest",
        "oak_shelf", "spruce_shelf", "birch_shelf", "jungle_shelf", "acacia_shelf", "dark_oak_shelf",
        "mangrove_shelf", "cherry_shelf", "pale_oak_shelf", "bamboo_shelf", "crimson_shelf", "warped_shelf");
    public static final List<String> WORKSTATIONS = ids("barrel", "blast_furnace", "brewing_stand",
        "cartography_table", "cauldron", "composter", "fletching_table", "grindstone", "lectern",
        "loom", "smithing_table", "smoker", "stonecutter");
    public static final List<String> STORAGE_OPTIONS;
    static {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(CONTAINERS);
        all.addAll(WORKSTATIONS);
        STORAGE_OPTIONS = List.copyOf(all);
    }
    private BlockEspSettings() { }
    private static List<String> ids(String... names) {
        return java.util.Arrays.stream(names).map(name -> "minecraft:" + name).toList();
    }
    public static GuiModule storage() {
        return create("Storage ESP", "Highlight storage and work blocks", true, CONTAINERS);
    }
    public static GuiModule blocks() {
        return create("Block ESP", "Highlight the blocks you choose", false,
            ids("diamond_ore", "deepslate_diamond_ore", "ancient_debris"));
    }
    private static GuiModule create(String name, String description, boolean storage, List<String> defaults) {
        GuiSetting.BlockList selection = new GuiSetting.BlockList("Blocks", "Blocks to highlight", defaults);
        List<GuiSetting> settings = new ArrayList<>();
        settings.add(selection);
        settings.add(new GuiSetting.Action("Choose blocks", "Search and choose more than one block",
            () -> BlockPickerScreen.open(name, selection, storage)));
        var loaded = new GuiSetting.Toggle("Loaded chunks", "Show matches throughout loaded terrain", true);
        settings.add(loaded);
        settings.add(new GuiSetting.Slider("Range", "Limit highlight distance",
            64, 8, 512, 4)
            .visibleWhen(() -> !loaded.value()));
        settings.add(new GuiSetting.ColorPick("Color", "Highlight color", storage ? 0xFFBD68 : 0x74DFFF));
        settings.add(new GuiSetting.Slider("Opacity", "Outline opacity", 0.85, 0.1, 1, .05));
        settings.add(new GuiSetting.Slider("Fill opacity", "Block fill opacity", .08, 0, .3, .01));
        settings.add(new GuiSetting.Slider("Line width", "Outline thickness", 1.3, .75, 3, .05));
        settings.add(new GuiSetting.Keybind("Keybind", "Toggle module", -1));
        return new GuiModule(name, description, CelestePage.VISUALS, false, settings.toArray(GuiSetting[]::new));
    }
}
