package dev.c4lam.celeste.gui.model;

import java.util.List;
import java.util.Objects;

public sealed interface GuiSetting permits GuiSetting.Action, GuiSetting.Toggle, GuiSetting.Slider, GuiSetting.Range, GuiSetting.Mode, GuiSetting.Keybind, GuiSetting.ColorPick, GuiSetting.ItemPick {
    String label();

    String description();

    /** A local UI command, not a configurable or serializable value. */
    final class Action implements GuiSetting {
        private final String label;
        private final String description;
        private final Runnable action;

        public Action(String label, String description, Runnable action) {
            this.label = Objects.requireNonNull(label, "label");
            this.description = Objects.requireNonNull(description, "description");
            this.action = Objects.requireNonNull(action, "action");
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public void invoke() {
            action.run();
        }
    }

    final class Toggle implements GuiSetting {
        private final String label;
        private final String description;
        private boolean value;

        public Toggle(String label, String description, boolean value) {
            this.label = label;
            this.description = description;
            this.value = value;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public boolean value() {
            return value;
        }

        public void toggle() {
            value = !value;
        }

        public void setValue(boolean value) {
            this.value = value;
        }
    }

    final class Slider implements GuiSetting {
        private final String label;
        private final String description;
        private final double minimum;
        private final double maximum;
        private final double step;
        private double value;

        public Slider(String label, String description, double value, double minimum, double maximum, double step) {
            this.label = label;
            this.description = description;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            setValue(value);
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public double minimum() {
            return minimum;
        }

        public double maximum() {
            return maximum;
        }

        public double step() {
            return step;
        }

        public double value() {
            return value;
        }

        public void setValue(double value) {
            double clamped = Math.max(minimum, Math.min(maximum, value));
            this.value = Math.round(clamped / step) * step;
        }

        public double progress() {
            return (value - minimum) / (maximum - minimum);
        }
    }

    final class Range implements GuiSetting {
        private final String label;
        private final String description;
        private final double minimum;
        private final double maximum;
        private final double step;
        private double low;
        private double high;

        public Range(String label, String description, double low, double high,
                     double minimum, double maximum, double step) {
            this.label = label;
            this.description = description;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.low = snap(Math.min(low, high));
            this.high = snap(Math.max(low, high));
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public double minimum() {
            return minimum;
        }

        public double maximum() {
            return maximum;
        }

        public double step() {
            return step;
        }

        public double low() {
            return low;
        }

        public double high() {
            return high;
        }

        public void setLow(double value) {
            double snapped = snap(value);
            low = Math.min(snapped, high);
        }

        public void setHigh(double value) {
            double snapped = snap(value);
            high = Math.max(snapped, low);
        }

        public double lowProgress() {
            return (low - minimum) / (maximum - minimum);
        }

        public double highProgress() {
            return (high - minimum) / (maximum - minimum);
        }

        public String serialized() {
            return sliderString(low) + "-" + sliderString(high);
        }

        public void setSerialized(String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            int dash = value.indexOf('-');
            if (dash <= 0) {
                setLow(Double.parseDouble(value));
                setHigh(Double.parseDouble(value));
                return;
            }
            setLow(Double.parseDouble(value.substring(0, dash).trim()));
            setHigh(Double.parseDouble(value.substring(dash + 1).trim()));
        }

        private double snap(double value) {
            double clamped = Math.max(minimum, Math.min(maximum, value));
            return Math.round(clamped / step) * step;
        }

        private static String sliderString(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return Integer.toString((int) Math.rint(value));
            }
            return Double.toString(value);
        }
    }

    final class Mode implements GuiSetting {
        private final String label;
        private final String description;
        private final List<String> values;
        private int index;

        public Mode(String label, String description, List<String> values, String selected) {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A mode setting needs at least one option");
            }
            this.label = label;
            this.description = description;
            this.values = List.copyOf(values);
            this.index = Math.max(0, this.values.indexOf(selected));
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public String value() {
            return values.get(index);
        }

        public List<String> values() {
            return values;
        }

        public void setValue(String value) {
            int found = values.indexOf(value);
            if (found >= 0) {
                index = found;
            }
        }

        public void next() {
            index = (index + 1) % values.size();
        }

        public void previous() {
            index = (index - 1 + values.size()) % values.size();
        }
    }

    final class Keybind implements GuiSetting {
        private final String label;
        private final String description;
        private int keyCode;

        public Keybind(String label, String description, int keyCode) {
            this.label = label;
            this.description = description;
            this.keyCode = keyCode;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public int keyCode() {
            return keyCode;
        }

        public void setKeyCode(int keyCode) {
            this.keyCode = keyCode;
        }
    }

    final class ColorPick implements GuiSetting {
        private final String label;
        private final String description;
        private int rgb;

        public ColorPick(String label, String description, int rgb) {
            this.label = label;
            this.description = description;
            setRgb(rgb);
        }

        public ColorPick(String label, String description, int red, int green, int blue) {
            this(label, description, pack(red, green, blue));
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public int rgb() {
            return rgb;
        }

        public int red() {
            return rgb >> 16 & 0xFF;
        }

        public int green() {
            return rgb >> 8 & 0xFF;
        }

        public int blue() {
            return rgb & 0xFF;
        }

        public void setRgb(int rgb) {
            this.rgb = rgb & 0xFFFFFF;
        }

        public void setRgb(int red, int green, int blue) {
            setRgb(pack(red, green, blue));
        }

        public static Integer parse(String value) {
            if (value == null) {
                return null;
            }
            String text = value.trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                if (text.charAt(0) == '#') {
                    String hex = text.substring(1);
                    if (hex.length() == 6) {
                        return Integer.parseInt(hex, 16);
                    }
                    return null;
                }
                int comma = text.indexOf(',');
                int last = text.lastIndexOf(',');
                if (comma > 0 && last > comma) {
                    int red = Integer.parseInt(text.substring(0, comma).trim());
                    int green = Integer.parseInt(text.substring(comma + 1, last).trim());
                    int blue = Integer.parseInt(text.substring(last + 1).trim());
                    return pack(red, green, blue);
                }
            } catch (NumberFormatException ignored) {
            }
            return null;
        }

        public String serialized() {
            return red() + "," + green() + "," + blue();
        }

        private static int pack(int red, int green, int blue) {
            return clamp(red) << 16 | clamp(green) << 8 | clamp(blue);
        }

        private static int clamp(int channel) {
            return Math.max(0, Math.min(255, channel));
        }
    }

    final class ItemPick implements GuiSetting {
        private static final List<String> CYCLE = List.of(
            "minecraft:dirt", "minecraft:oak_log", "minecraft:oak_planks", "minecraft:iron_ore",
            "minecraft:iron_block", "minecraft:diamond_ore", "minecraft:netherite_sword",
            "minecraft:trident", "minecraft:mace", "minecraft:cobblestone", "minecraft:stone",
            "minecraft:grass_block", "minecraft:sand", "minecraft:gravel", "minecraft:glass",
            "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_chest",
            "minecraft:chest", "minecraft:tnt", "minecraft:gold_block", "minecraft:diamond_block",
            "minecraft:netherite_block", "minecraft:ancient_debris", "minecraft:white_wool",
            "minecraft:oak_leaves", "minecraft:deepslate", "minecraft:netherrack", "minecraft:end_stone"
        );

        private final String label;
        private final String description;
        private String itemId;

        public ItemPick(String label, String description, String itemId) {
            this.label = label;
            this.description = description;
            this.itemId = normalize(itemId);
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public String itemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = normalize(itemId);
        }

        public void next() {
            int index = CYCLE.indexOf(itemId);
            itemId = CYCLE.get((index + 1 + CYCLE.size()) % CYCLE.size());
        }

        public void previous() {
            int index = CYCLE.indexOf(itemId);
            if (index < 0) {
                index = 0;
            }
            itemId = CYCLE.get((index - 1 + CYCLE.size()) % CYCLE.size());
        }

        private static String normalize(String itemId) {
            if (itemId == null || itemId.isBlank()) {
                return CYCLE.getFirst();
            }
            String trimmed = itemId.trim().toLowerCase();
            return trimmed.indexOf(':') < 0 ? "minecraft:" + trimmed : trimmed;
        }
    }
}
