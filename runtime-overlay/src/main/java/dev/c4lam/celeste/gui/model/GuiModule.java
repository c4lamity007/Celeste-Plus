package dev.c4lam.celeste.gui.model;

import dev.c4lam.celeste.hud.DynamicIslandEvents;
import dev.c4lam.celeste.hud.ModuleToggleSoundQueue;

import java.util.List;

public final class GuiModule {
    private final String name;
    private final String description;
    private final CelestePage page;
    private final List<GuiSetting> settings;
    private final List<GuiSetting> visibleSettings;
    private final boolean conditionalSettings;
    private boolean enabled;

    public GuiModule(String name, String description, CelestePage page, boolean enabled, GuiSetting... settings) {
        if (!page.feature()) {
            throw new IllegalArgumentException("Modules can only belong to feature pages");
        }
        this.name = name;
        this.description = description;
        this.page = page;
        this.enabled = enabled;
        this.settings = List.of(settings);
        this.visibleSettings = this.settings.stream()
            .filter(setting -> setting.visibleInMenu() || setting.hasVisibilityCondition()).toList();
        this.conditionalSettings = this.settings.stream().anyMatch(GuiSetting::hasVisibilityCondition);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public CelestePage page() {
        return page;
    }

    public List<GuiSetting> settings() {
        return settings;
    }

    public List<GuiSetting> visibleSettings() {
        return conditionalSettings ? visibleSettings.stream().filter(GuiSetting::visibleInMenu).toList() : visibleSettings;
    }

    public boolean enabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        DynamicIslandEvents.publishModuleToggle(name, enabled);
        ModuleToggleSoundQueue.getInstance().publish(enabled);
    }
}
