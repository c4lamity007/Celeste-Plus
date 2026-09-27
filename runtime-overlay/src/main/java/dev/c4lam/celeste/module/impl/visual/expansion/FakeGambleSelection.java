package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;
import java.util.Objects;

/** The same explicit selection is used for eligibility, A/B ordering, and markers. */
final class FakeGambleSelection<T> {
    private volatile List<T> selected = List.of();
    private T lastUsed;

    List<T> positions() {
        return selected;
    }

    boolean contains(T position) {
        return position != null && selected.contains(position);
    }

    void toggle(T position) {
        Objects.requireNonNull(position, "position");
        List<T> previous = selected;
        if (previous.contains(position)) {
            selected = previous.stream().filter(value -> !value.equals(position)).toList();
        } else if (previous.isEmpty()) {
            selected = List.of(position);
        } else {
            selected = List.of(previous.getFirst(), position);
        }
        if (!contains(lastUsed)) lastUsed = null;
    }

    /** Every block interaction replaces the candidate, including unselected blocks. */
    void noteUsed(T position) {
        lastUsed = contains(position) ? position : null;
    }

    T openTarget(boolean dispenserScreen) {
        return dispenserScreen && contains(lastUsed) ? lastUsed : null;
    }

    void clear() {
        selected = List.of();
        lastUsed = null;
    }
}
