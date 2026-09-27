package dev.c4lam.celeste.gui.model;

import java.util.Properties;

/** Local navigation preference; HUD is an editor action, not a content page. */
public final class SidebarPagePreference {
    private static final String KEY = "lastSidebarPage";

    private SidebarPagePreference() { }

    public static CelestePage read(Properties values) {
        if (values == null) return CelestePage.SWORD;
        try {
            CelestePage page = CelestePage.valueOf(values.getProperty(KEY, "SWORD"));
            return page == CelestePage.HUD ? CelestePage.SWORD : page;
        } catch (IllegalArgumentException invalid) {
            return CelestePage.SWORD;
        }
    }

    public static CelestePage remember(CelestePage current, CelestePage selected, boolean temporary) {
        if (temporary || selected == null || selected == CelestePage.HUD) return current;
        return selected;
    }

    public static void write(Properties values, CelestePage page) {
        values.setProperty(KEY, (page == null || page == CelestePage.HUD ? CelestePage.SWORD : page).name());
    }
}
