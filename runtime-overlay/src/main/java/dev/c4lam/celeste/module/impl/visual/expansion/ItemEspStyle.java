package dev.c4lam.celeste.module.impl.visual.expansion;

/** The three visible Item ESP contracts. Unknown legacy values migrate to model highlighting. */
public enum ItemEspStyle {
    BOX("Box", true, false),
    ITEM_HIGHLIGHT("Item Highlight", false, true),
    BOTH("Both", true, true);

    private final String label;
    private final boolean box;
    private final boolean highlight;

    ItemEspStyle(String label, boolean box, boolean highlight) {
        this.label = label;
        this.box = box;
        this.highlight = highlight;
    }

    public boolean showsBox() { return box; }
    public boolean showsHighlight() { return highlight; }
    public String label() { return label; }

    public static ItemEspStyle from(String value) {
        for (ItemEspStyle style : values()) {
            if (style.label.equals(value)) return style;
        }
        return ITEM_HIGHLIGHT;
    }
}
