package dev.c4lam.celeste.gui;

import java.util.EnumSet;

/** Progress reported by normal GUI handlers after an action changes real state. */
public final class FirstUseTourState {
    public enum Step { WELCOME, BROWSE, MODULES, SETTINGS, SEARCH, CONFIGS, HUD, READY }
    public static final String MODULE = "ReBuff Notifier";
    public static final String SETTING = "Alert duration";
    private final EnumSet<Step> practiced = EnumSet.noneOf(Step.class);
    private Step step = Step.WELCOME;
    private boolean active = true, settingsOpen, moved, resized, added;
    private boolean configNamed;
    private boolean hudWidgetAvailable = true, hudWidgetSelected = true, hudMenuOpen, hudMenuAdds;
    public Step step() { return step; }
    public boolean active() { return active; }
    public boolean complete() { return practiced.contains(step); }
    public boolean settingsOpen() { return settingsOpen; }
    public boolean moved() { return moved; }
    public boolean resized() { return resized; }
    public boolean added() { return added; }
    public void configName(String name) { configNamed = name != null && !name.isBlank(); }
    public void hudContext(boolean available, boolean selected, boolean menuOpen, boolean menuAdds) {
        hudWidgetAvailable = available;
        hudWidgetSelected = selected;
        hudMenuOpen = menuOpen;
        hudMenuAdds = menuAdds;
    }
    public int number() { return Math.max(1, Math.min(6, step.ordinal())); }
    public void next() {
        if (!active) return;
        if (step == Step.READY) active = false;
        else step = Step.values()[step.ordinal() + 1];
    }
    public void back() {
        if (active && step != Step.WELCOME) step = Step.values()[step.ordinal() - 1];
    }
    public void dismiss() { active = false; }
    private boolean on(Step expected) { return active && step == expected; }
    private void credit(Step expected) { if (on(expected)) practiced.add(expected); }
    public void selectedPage(String page) {
        if ("Visuals".equals(page)) credit(Step.BROWSE);
    }
    public void moduleChanged(String module, boolean before, boolean after) {
        if (MODULE.equals(module) && before != after) credit(Step.MODULES);
    }
    public void settingsOpened(String module) {
        if (on(Step.SETTINGS) && MODULE.equals(module)) settingsOpen = true;
    }
    public void settingsClosed() {
        if (on(Step.SETTINGS)) settingsOpen = false;
    }
    public void settingChanged(String module, String setting, double before, double after) {
        if (settingsOpen && MODULE.equals(module) && SETTING.equals(setting)
                && Double.isFinite(before) && Double.isFinite(after) && Math.abs(before - after) > .001)
            credit(Step.SETTINGS);
    }
    public void search(String query, int results) {
        if (query != null && query.strip().equalsIgnoreCase("esp") && results > 0) credit(Step.SEARCH);
    }
    public void configSaved(String name, boolean success) {
        if (success && name != null && !name.isBlank()) credit(Step.CONFIGS);
    }
    public void hudMoved(String widget, float x0, float y0, float x1, float y1) {
        if (on(Step.HUD) && validWidget(widget) && Float.isFinite(x0) && Float.isFinite(y0)
                && Float.isFinite(x1) && Float.isFinite(y1) && Math.hypot(x1 - x0, y1 - y0) >= 8) {
            moved = true; updateHud();
        }
    }
    public void hudResized(String widget, float before, float after) {
        if (on(Step.HUD) && validWidget(widget) && Float.isFinite(before) && Float.isFinite(after)
                && before > 0 && after > 0 && Math.abs(before - after) > .01f) {
            resized = true; updateHud();
        }
    }
    public void hudToggled(String widget, boolean before, boolean after) {
        if (on(Step.HUD) && validWidget(widget) && before != after) {
            added = true; updateHud();
        }
    }
    private static boolean validWidget(String widget) { return widget != null && !widget.isBlank(); }
    private void updateHud() { if (moved && resized && added) credit(Step.HUD); }
    public boolean dismissShortcut(boolean escape, boolean menuKey, boolean textFocused) {
        return escape || menuKey && !textFocused;
    }
    public String title() {
        return switch (step) {
            case WELCOME -> "Quick tour";
            case BROWSE -> "Pages";
            case MODULES -> "Modules";
            case SETTINGS -> "Settings";
            case SEARCH -> "Search";
            case CONFIGS -> "Configs";
            case HUD -> "HUD";
            case READY -> "All set";
        };
    }
    public String[] instruction(boolean inEditor) {
        return switch (step) {
            case WELCOME -> new String[]{"Try the controls as you go.", "Changes are saved normally."};
            case BROWSE -> new String[]{"Click Visuals in the sidebar."};
            case MODULES -> new String[]{"Click this switch to toggle ReBuff Notifier."};
            case SETTINGS -> settingsOpen ? new String[]{"Drag this slider to change Alert duration."}
                : new String[]{"Right-click ReBuff Notifier."};
            case SEARCH -> new String[]{"Click search and type ESP."};
            case CONFIGS -> configNamed ? new String[]{"Click Save."}
                : new String[]{"Click here and enter a config name."};
            case HUD -> !inEditor ? new String[]{"Open HUD in the sidebar."}
                : hudMenuOpen ? new String[]{hudMenuAdds ? "Click + to add this widget." : "Click - to hide this widget."}
                : !hudWidgetAvailable ? new String[]{"Right-click here to open the widget list."}
                : !moved ? new String[]{"Drag this widget to move it."}
                : !resized ? hudWidgetSelected ? new String[]{"Drag this corner to resize.", "You can also scroll to resize."}
                    : new String[]{"Click this widget to show its corners."}
                : !added ? new String[]{"Right-click here to open the widget list."}
                : new String[]{"Your layout is saved."};
            case READY -> new String[]{"Replay this from Settings anytime."};
        };
    }
}
