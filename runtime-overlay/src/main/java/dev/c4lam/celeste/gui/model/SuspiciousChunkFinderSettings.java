package dev.c4lam.celeste.gui.model;

import java.util.ArrayList;
import java.util.List;

/** Resident settings metadata; does not load the scanner implementation. */
public final class SuspiciousChunkFinderSettings {
    private static final String NAME = "Suspicious Chunk Finder";
    private SuspiciousChunkFinderSettings() { }
    private static final String[] LABELS = {"Rotated deepslate", "Mismatched soil", "Grounded vines",
        "Mature geode", "Missing budding", "Surface kelp", "Mature kelp",
        "Buried paths", "Open shafts", "Stripped geodes"};
    public static GuiModule settings() {
        List<GuiSetting> settings = new ArrayList<>();
        settings.add(new GuiSetting.Slider("Flag score", "Higher scores need more evidence", 35, 5, 175, 5));
        for (String label : LABELS) settings.add(new GuiSetting.Toggle(label,
            label.equals("Open shafts") ? "Subtract 20 for a deep shaft within 3 chunks of an estimate"
                : label.equals("Missing budding") ? "Add 25 for missing budding blocks in a complete amethyst geode"
                : label.equals("Stripped geodes") ? "Add 65 for a complete calcite and basalt shell with no amethyst remaining"
                : "Include this evidence", true));
        settings.add(new GuiSetting.Toggle("Path blocks", "Outline the blocks in suspected buried paths", true));
        var activity = new GuiSetting.Toggle("Activity estimate", "Red activity estimates; amber direct land evidence", true);
        settings.add(activity);
        settings.add(new GuiSetting.Slider("Tick radius", "Estimated activity radius in chunks", 7, 2, 12, 1)
            .visibleWhen(activity::value));
        settings.add(new GuiSetting.Toggle("Show reasons", "Show the nearest candidate's score and evidence", false));
        var advanced = new GuiSetting.Toggle("Advanced", "Server scan boundary", false);
        settings.add(advanced);
        settings.add(new GuiSetting.Slider("Scan floor Y", "Lowest real block data sent by this server", 0, -64, 320, 1)
            .visibleWhen(advanced::value));
        settings.add(new GuiSetting.Keybind("Keybind", "Toggle module", -1));
        return new GuiModule(NAME, "Find signs of nearby activity", CelestePage.VISUALS, false,
            settings.toArray(GuiSetting[]::new));
    }

}
