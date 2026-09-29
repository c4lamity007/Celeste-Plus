package dev.c4lam.celeste.hud;

/** The small, borderless widgets available from Celeste's HUD editor. */
public enum HudElementType {
    COORDINATES("Coordinates", 132, 20),
    TARGET_HUD("Target HUD", 136, 44),
    EFFECTS("Effects", 116, 27),
    ARMOR("Armor", 120, 28),
    KEYBINDS("Keybinds", 122, 27),
    ARRAYLIST("Arraylist", 110, 80),
    WATERMARK("Watermark", 110, 20),
    FPS("Performance", 150, 17),
    SPOTIFY("Media HUD", 130, 72),
    DYNAMIC_ISLAND("Dynamic Island", 76, 22);

    private final String label;
    private final float width;
    private final float height;

    HudElementType(String label, float width, float height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }

    public String label() {
        return label;
    }

    public boolean fixed() {
        return this == DYNAMIC_ISLAND;
    }

    /** Chat keeps only controls that are useful there and the small brand mark. */
    public boolean visibleInChat() {
        return this == SPOTIFY || this == DYNAMIC_ISLAND || this == WATERMARK;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }
}
