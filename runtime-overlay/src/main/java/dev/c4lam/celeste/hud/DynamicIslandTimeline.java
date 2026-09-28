package dev.c4lam.celeste.hud;

/** Pure, frame-rate-independent presentation state for the Dynamic Island. */
public final class DynamicIslandTimeline {
    public static final float NOTIFICATION_SECONDS = 1.0F;
    public static final float EFFECT_SECONDS = 2.0F;
    public static final float HEIGHT = 22.0F;
    private static final float TRANSITION_SECONDS = 0.18F;
    private static final float TOGGLE_BASE_WIDTH = 96.0F;
    private static final float NOTICE_LEFT_RESERVE = 25.5F;
    private static final float NOTICE_RIGHT_INSET = 8.5F;
    private static final float NOTICE_MARKER_INSET = 12.5F;
    private static final float NOTICE_MARKER_RADIUS = 5.25F;
    private static final float NOTICE_MARKER_STROKE = 1.1F;
    // A one-second cap lets a notification expire after a real one-second hitch
    // while still rejecting absurd pause/debugger deltas.
    private static final float MAX_FRAME_SECONDS = 1.0F;

    private Media media = Media.idle(76.0F);
    private Layer current = Layer.from(media);
    private Layer previous = current;
    private float transition = 1.0F;
    private float previousOpacity = 1.0F;
    private float noticeRemaining;
    private float noticeDuration = NOTIFICATION_SECONDS;
    private float width = current.desiredWidth();
    private float widthVelocity;
    private long lastEventSequence;
    private boolean initialized;

    public void update(Media nextMedia, Notice notice, float deltaSeconds, float minWidth, float maxWidth) {
        float lower = finiteOr(minWidth, 8.0F);
        float upper = finiteOr(maxWidth, Math.max(lower, 300.0F));
        if (upper < lower) {
            float swap = lower;
            lower = upper;
            upper = swap;
        }
        lower = Math.max(1.0F, lower);
        upper = Math.max(lower, upper);
        float dt = Math.clamp(finiteOr(deltaSeconds, 0.0F), 0.0F, MAX_FRAME_SECONDS);

        media = nextMedia == null ? Media.idle(lower) : nextMedia.clamped(lower, upper);
        if (!initialized && notice == null) {
            current = Layer.from(media);
            previous = current;
            transition = 1.0F;
        }
        String beforeKey = current.key();
        boolean acceptedNotice = notice != null && notice.sequence() > lastEventSequence;
        if (acceptedNotice) {
            lastEventSequence = notice.sequence();
            noticeDuration = notice.effect() ? EFFECT_SECONDS : NOTIFICATION_SECONDS;
            noticeRemaining = noticeDuration;
            transitionTo(Layer.from(notice.clamped(lower, upper)));
        }

        if (noticeRemaining > 0.0F) {
            // The notification gets its complete lifetime beginning
            // at this update; the preceding frame delta must not consume it.
            if (!acceptedNotice) {
                noticeRemaining = Math.max(0.0F, noticeRemaining - dt);
            }
            if (noticeRemaining <= 0.0F) {
                transitionTo(Layer.from(media));
            }
        } else {
            Layer desired = Layer.from(media);
            if (current.kind() != Kind.MEDIA || !current.key().equals(desired.key())) {
                transitionTo(desired);
            } else {
                // Highlight/playback/artwork state changes must not restart the phrase transition.
                current = desired;
            }
        }

        float animationDelta = current.key().equals(beforeKey) ? dt : 0.0F;
        if (transition < 1.0F && animationDelta > 0.0F) {
            transition = Math.min(1.0F, transition + animationDelta / TRANSITION_SECONDS);
            if (transition >= 1.0F) {
                previous = current;
            }
        }

        // Module notices have their own layout. The spring starts at the visible
        // media width, but its destination never depends on the previous lyric.
        float desiredWidth = current.kind() != Kind.MEDIA
            ? Math.max(current.desiredWidth(), TOGGLE_BASE_WIDTH) : current.desiredWidth();
        float targetWidth = Math.clamp(desiredWidth, lower, upper);
        if (!initialized) {
            width = targetWidth;
            widthVelocity = 0.0F;
            initialized = true;
        } else if (animationDelta > 0.0F) {
            springWidth(targetWidth, animationDelta, lower, upper);
        }
    }

    public void reset() {
        media = Media.idle(76.0F);
        current = Layer.from(media);
        previous = current;
        transition = 1.0F;
        previousOpacity = 1.0F;
        noticeRemaining = 0.0F;
        noticeDuration = NOTIFICATION_SECONDS;
        width = current.desiredWidth();
        widthVelocity = 0.0F;
        lastEventSequence = 0L;
        initialized = false;
    }

    public Layer current() {
        return current;
    }

    public Layer previous() {
        return previous;
    }

    /** Smoothed cross-fade amount for {@link #current()}. */
    public float blend() {
        float t = Math.clamp(transition, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public float width() {
        return width;
    }

    public float outgoingOpacity() {
        return previousOpacity * (1.0F - blend());
    }

    public float height() {
        return HEIGHT;
    }

    public float notificationFraction() {
        return Math.clamp(noticeRemaining / noticeDuration, 0.0F, 1.0F);
    }

    public boolean notificationActive() { return noticeRemaining > 0.0F; }

    static float idleWidth(float originalWidth, float minimum, float maximum) {
        return Math.clamp(originalWidth * 2.0F, minimum, maximum);
    }

    static float centeredTextX(float x, float width, float textWidth) {
        return x + Math.max(0.0F, (width - textWidth) * 0.5F);
    }

    public long lastEventSequence() {
        return lastEventSequence;
    }

    /** The countdown/check occupies the left slot; the label is balanced to its right. */
    static float noticeTextCapacity(float width) {
        return Math.max(1.0F, finiteOr(width, 0.0F)
            - NOTICE_LEFT_RESERVE - NOTICE_RIGHT_INSET);
    }

    static float noticeWidth(float textWidth) {
        return Math.max(TOGGLE_BASE_WIDTH, NOTICE_LEFT_RESERVE + NOTICE_RIGHT_INSET
            + Math.max(0.0F, finiteOr(textWidth, 0.0F)));
    }

    static NoticeLayout noticeLayout(float x, float width, float textWidth) {
        float safeX = finiteOr(x, 0.0F);
        float safeWidth = Math.max(0.0F, finiteOr(width, 0.0F));
        float safeTextWidth = Math.max(0.0F, finiteOr(textWidth, 0.0F));
        float laneLeft = safeX + Math.min(NOTICE_LEFT_RESERVE, safeWidth);
        float laneRight = Math.max(laneLeft, safeX + safeWidth - NOTICE_RIGHT_INSET);
        float textX = laneLeft + Math.max(0.0F, (laneRight - laneLeft - safeTextWidth) * 0.5F);
        float markerCenterX = safeX + Math.min(safeWidth * 0.5F, NOTICE_MARKER_INSET);
        return new NoticeLayout(textX, laneRight, markerCenterX,
            Math.min(NOTICE_MARKER_RADIUS, Math.max(0.0F, safeWidth * 0.5F - 1.0F)),
            NOTICE_MARKER_STROKE);
    }

    record NoticeLayout(float textX, float textRight, float markerCenterX,
                        float markerRadius, float markerStroke) {
    }

    private void transitionTo(Layer next) {
        if (current.key().equals(next.key()) && current.kind() == next.kind()) {
            current = next;
            return;
        }
        // A second change can arrive during the 180 ms crossfade. Continue
        // from the visually dominant layer rather than promoting a barely
        // visible intermediate toggle to full opacity.
        float outgoingWeight = outgoingOpacity();
        float incomingWeight = blend();
        boolean keepPrevious = outgoingWeight > incomingWeight;
        Layer outgoing = keepPrevious ? previous : current;
        float retainedOpacity = keepPrevious ? outgoingWeight : incomingWeight;
        if (outgoing.key().equals(next.key()) && outgoing.kind() == next.kind()) {
            previous = next;
            current = next;
            transition = 1.0F;
            return;
        }
        previous = outgoing;
        previousOpacity = retainedOpacity;
        current = next;
        transition = 0.0F;
    }

    /** Critically damped-enough bounded spring, integrated in stable 120 Hz slices. */
    private void springWidth(float target, float deltaSeconds, float lower, float upper) {
        int steps = Math.max(1, (int) Math.ceil(deltaSeconds * 120.0F));
        float step = deltaSeconds / steps;
        for (int index = 0; index < steps; index++) {
            float acceleration = (target - width) * 260.0F - widthVelocity * 30.0F;
            widthVelocity += acceleration * step;
            width += widthVelocity * step;
            if (width <= lower || width >= upper) {
                width = Math.clamp(width, lower, upper);
                if (width == lower && widthVelocity < 0.0F || width == upper && widthVelocity > 0.0F) {
                    widthVelocity = 0.0F;
                }
            }
        }
        if (Math.abs(target - width) < 0.01F && Math.abs(widthVelocity) < 0.02F) {
            width = target;
            widthVelocity = 0.0F;
        }
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    public enum Kind {
        MEDIA,
        TOGGLE,
        EFFECT
    }

    /** Prepared single-row media state; no mutable snapshot is retained here. */
    public record Media(String key, String text, float highlight, boolean paused, boolean artwork,
                        float desiredWidth) {
        public Media {
            key = key == null || key.isBlank() ? "media" : key;
            text = text == null ? "" : text;
            highlight = Math.clamp(finiteOr(highlight, 0.0F), 0.0F, 1.0F);
            desiredWidth = Math.max(1.0F, finiteOr(desiredWidth, 76.0F));
        }

        static Media idle(float desiredWidth) {
            return new Media("idle", "Celeste", 1.0F, false, false, desiredWidth);
        }

        Media clamped(float lower, float upper) {
            return new Media(key, text, highlight, paused, artwork,
                Math.clamp(desiredWidth, lower, upper));
        }
    }

    /** A module toggle or an expired effect, ready for the shared transition. */
    public record Notice(long sequence, String moduleName, boolean enabled, float desiredWidth,
                         String effectTexture) {
        public Notice(long sequence, String moduleName, boolean enabled, float desiredWidth) {
            this(sequence, moduleName, enabled, desiredWidth, "");
        }

        public Notice {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            moduleName = moduleName == null ? "" : moduleName;
            desiredWidth = Math.max(1.0F, finiteOr(desiredWidth, 76.0F));
            effectTexture = effectTexture == null ? "" : effectTexture;
        }

        boolean effect() { return !effectTexture.isEmpty(); }

        Notice clamped(float lower, float upper) {
            return new Notice(sequence, moduleName, enabled, Math.clamp(desiredWidth, lower, upper), effectTexture);
        }
    }

    public record Layer(String key, String text, Kind kind, boolean enabled, float highlight,
                        boolean paused, boolean artwork, float desiredWidth, String effectTexture) {
        static Layer from(Media media) {
            return new Layer(media.key(), media.text(), Kind.MEDIA, false, media.highlight(),
                media.paused(), media.artwork(), media.desiredWidth(), "");
        }

        static Layer from(Notice notice) {
            return new Layer("notice:" + notice.sequence(), notice.moduleName(),
                notice.effect() ? Kind.EFFECT : Kind.TOGGLE,
                notice.enabled(), 1.0F, false, false, notice.desiredWidth(), notice.effectTexture());
        }
    }
}
