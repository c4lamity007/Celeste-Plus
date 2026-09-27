package dev.c4lam.celeste.hud;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.media.MediaArtwork;
import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import dev.c4lam.celeste.media.TimedLyrics;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiPipelines;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.GuiUniform;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.Color;

/** Compact top-center media/lyrics capsule with latest-only module interrupts. */
public final class DynamicIslandRenderer {
    private static final float TEXT_SIZE = 6.65F;
    private static final float ART_LIMIT = 14.0F;
    private static final float MIN_WIDTH = 44.0F;
    private static final float MAX_WIDTH = 210.0F;
    private static final Color ENABLED = new Color(98, 232, 151);
    private static final Color DISABLED = new Color(255, 103, 121);
    private final DynamicIslandTimeline timeline = new DynamicIslandTimeline();
    private final DynamicIslandPlaybackClock playbackClock = new DynamicIslandPlaybackClock();
    private final HudPolishPolicy.Interaction interaction = new HudPolishPolicy.Interaction();

    private ArtworkMetrics mediaArtwork = ArtworkMetrics.NONE;
    private ArtworkMetrics previousMediaArtwork = ArtworkMetrics.NONE;
    private String mediaArtworkKey = "";
    private String previousMediaArtworkKey = "";
    private boolean active;
    private long lastUpdateNanos;

    /**
     * Advances immutable presentation state. Call once before layout so
     * {@link #width()} is the centered capsule width for this frame.
     */
    public void update(MinecraftClient client, boolean elementEnabled, MediaPlaybackSnapshot snapshot,
                       float deltaSeconds, float viewportWidth) {
        if (!elementEnabled) {
            reset();
            return;
        }

        long now = System.nanoTime();
        // Notification lifetime follows elapsed time, not the HUD's capped animation delta.
        float elapsed = active ? Math.clamp((now - lastUpdateNanos) / 1_000_000_000.0F, 0.0F, 1.0F) : 0.0F;
        lastUpdateNanos = now;
        active = true;
        interaction.update(elapsed);
        boolean inWorld = client != null && client.player != null && client.world != null;
        DynamicIslandEvents.setAccepting(inWorld);

        float safeViewport = Float.isFinite(viewportWidth) && viewportWidth > 16.0F ? viewportWidth : 320.0F;
        float maximum = Math.max(8.0F, Math.min(MAX_WIDTH, safeViewport - 8.0F));
        float minimum = Math.min(MIN_WIDTH, maximum);

        MediaPlaybackSnapshot immutableSnapshot = snapshot;
        ArtworkMetrics art = resolveArtwork(immutableSnapshot);
        PreparedMedia media = prepareMedia(immutableSnapshot, minimum, maximum, art);
        updateArtwork(media.state().key(), art);
        DynamicIslandEvents.Toggle toggle = DynamicIslandEvents.latestAfter(timeline.lastEventSequence());
        DynamicIslandTimeline.Notice notice = prepareNotice(toggle, minimum, maximum);
        timeline.update(media.state(), notice, elapsed, minimum, maximum);
    }

    public float width() {
        return timeline.width();
    }

    public float height() {
        return timeline.height();
    }

    public boolean active() {
        return active;
    }

    public void hovered(boolean hovered) { interaction.hovered(hovered); }
    public void clicked() { interaction.click(); }

    /** Draws only the capsule. The caller owns its fixed top-center position and external clock. */
    public void draw(MatrixStack matrices, float x, float y, Color fontColor, float opacity) {
        if (!active || matrices == null || opacity <= 0.001F) {
            return;
        }
        float alpha = Math.clamp(opacity, 0.0F, 1.0F);
        float width = timeline.width();
        float height = timeline.height();
        Color textColor = fontColor == null ? Color.WHITE : fontColor;
        matrices.push();
        float scale = interaction.scale();
        matrices.translate(x + width * .5F, y + height * .5F, 0);
        matrices.scale(scale, scale, 1);
        matrices.translate(-x - width * .5F, -y - height * .5F, 0);
        try {
        RenderUtil.RECT.draw(matrices, x, y, width, height,
            HudSurface.radius(HudElementType.DYNAMIC_ISLAND, width, height), HudSurface.color(alpha));

        float blend = timeline.blend();
        drawLayerWithChrome(matrices, timeline.previous(), x, y - blend * 2.2F, width, height,
            textColor, alpha * timeline.outgoingOpacity());
        drawLayerWithChrome(matrices, timeline.current(), x, y + (1.0F - blend) * 2.2F,
            width, height, textColor, alpha * blend);
        } finally {
            matrices.pop();
        }
    }

    public void reset() {
        active = false;
        lastUpdateNanos = 0L;
        mediaArtwork = ArtworkMetrics.NONE;
        previousMediaArtwork = ArtworkMetrics.NONE;
        mediaArtworkKey = "";
        previousMediaArtworkKey = "";
        timeline.reset();
        playbackClock.reset();
        interaction.reset();
        DynamicIslandEvents.reset();
    }

    private void drawLayerWithChrome(MatrixStack matrices, DynamicIslandTimeline.Layer layer,
                                     float x, float y, float width, float height, Color textColor,
                                     float opacity) {
        if (opacity <= 0.001F) {
            return;
        }
        float textX;
        float textRight;
        if (layer.kind() == DynamicIslandTimeline.Kind.TOGGLE) {
            float labelWidth = Fonts.semibold().width(layer.text(), TEXT_SIZE);
            DynamicIslandTimeline.NoticeLayout layout = DynamicIslandTimeline.noticeLayout(x, width, labelWidth);
            drawToggleMarker(matrices, layout, y + height * 0.5F, layer.enabled(), opacity);
            textX = layout.textX();
            textRight = layout.textRight();
        } else {
            ArtworkMetrics art = artworkFor(layer);
            if (layer.artwork() && art.present()) {
                float artX = x + 4.0F;
                float artY = y + (height - art.height()) * 0.5F;
                drawArtwork(matrices, art, artX, artY, opacity);
                textX = artX + art.width() + 5.0F;
            } else {
                textX = x + 8.0F;
            }
            boolean mediaAvailable = !"idle".equals(layer.key());
            textRight = x + width - HudGlyphLayout.mediaRightReserve(mediaAvailable);
        }

        boolean mediaAvailable = layer.kind() == DynamicIslandTimeline.Kind.MEDIA
            && !"idle".equals(layer.key());
        if (textRight > textX) {
            pushTextScissor(matrices, textX, y, textRight, y + height);
            try {
                float scroll = layer.key().contains("\u0000lyric:")
                    ? HudPolishPolicy.lyricScroll(Fonts.semibold().width(layer.text(), TEXT_SIZE),
                        textRight - textX, layer.highlight()) : 0;
                drawLayer(matrices, layer, textX - scroll, y, height, textColor, opacity);
            } finally {
                GuiRenderer.getInstance().popScissor();
            }
        }
        if (mediaAvailable) {
            float glyphCenter = HudGlyphLayout.mediaGlyphCenter(x, width) - 0.5F;
            if (layer.paused()) {
                drawPausedMarker(matrices, glyphCenter, y + height * .5F, textColor, opacity);
            } else {
                drawWaveform(matrices, glyphCenter, y + height * .5F, textColor, opacity);
            }
        }
    }

    private PreparedMedia prepareMedia(MediaPlaybackSnapshot snapshot, float minimum, float maximum,
                                       ArtworkMetrics art) {
        Font font = Fonts.semibold();
        boolean available = snapshot != null && snapshot.available();
        boolean paused = available && !snapshot.playing();
        long progress = playbackClock.positionMs(snapshot);
        TimedLyrics.WordCue cue = available
            ? snapshot.lyrics().wordCue(progress, snapshot.durationMs()) : TimedLyrics.WordCue.EMPTY;

        String trackKey;
        String rawText;
        float rawHighlight;
        if (!cue.text().isBlank()) {
            trackKey = trackKey(snapshot) + "\u0000lyric:" + cue.id();
            rawText = oneLine(cue.text());
            rawHighlight = Float.NaN; // Retain the phrase; move its viewport with the spoken words.
        } else if (available) {
            trackKey = trackKey(snapshot);
            rawText = firstPresent(snapshot.title(), snapshot.artist(), snapshot.source(), "Media");
            rawHighlight = 1.0F;
        } else {
            trackKey = "idle";
            rawText = "Celeste";
            rawHighlight = 1.0F;
        }

        boolean hasArtwork = art.present();
        float leftChrome = hasArtwork ? 4.0F + art.width() + 5.0F : 8.0F;
        float rightChrome = HudGlyphLayout.mediaRightReserve(available);
        float textLimit = Math.max(1.0F, maximum - leftChrome - rightChrome);
        String fitted = Float.isNaN(rawHighlight) ? HudPolishPolicy.lyricText(rawText)
            : fitText(font, rawText, textLimit);
        float highlight = Float.isNaN(rawHighlight)
            ? lyricHighlight(font, cue, fitted, progress) : rawHighlight;
        float desired = Math.clamp(leftChrome + font.width(fitted, TEXT_SIZE) + rightChrome, minimum, maximum);
        return new PreparedMedia(new DynamicIslandTimeline.Media(
            trackKey, fitted, highlight, paused, hasArtwork, desired
        ));
    }

    private DynamicIslandTimeline.Notice prepareNotice(DynamicIslandEvents.Toggle toggle,
                                                        float minimum, float maximum) {
        if (toggle == null) {
            return null;
        }
        Font font = Fonts.semibold();
        String fitted = fitText(font, toggle.moduleName(),
            DynamicIslandTimeline.noticeTextCapacity(maximum));
        float desired = Math.clamp(DynamicIslandTimeline.noticeWidth(font.width(fitted, TEXT_SIZE)),
            minimum, maximum);
        return new DynamicIslandTimeline.Notice(toggle.sequence(), fitted, toggle.enabled(), desired);
    }

    private void drawLayer(MatrixStack matrices, DynamicIslandTimeline.Layer layer, float x, float y,
                           float height, Color mediaColor, float opacity) {
        if (layer.text().isBlank() || opacity <= 0.001F) {
            return;
        }
        Font font = Fonts.semibold();
        float textY = y + (height - font.height(TEXT_SIZE)) * 0.5F;
        if (layer.kind() == DynamicIslandTimeline.Kind.TOGGLE) {
            Color toggleColor = layer.enabled() ? ENABLED : DISABLED;
            font.draw(matrices, layer.text(), x, textY, TEXT_SIZE, withAlpha(toggleColor, opacity));
            return;
        }

        Color dim = new Color(mediaColor.getRed(), mediaColor.getGreen(), mediaColor.getBlue(),
            Math.max(24, Math.round(mediaColor.getAlpha() * 0.34F)));
        font.draw(matrices, layer.text(), x, textY, TEXT_SIZE, withAlpha(dim, opacity));
        float brightWidth = font.width(layer.text(), TEXT_SIZE) * layer.highlight();
        if (brightWidth <= 0.05F) {
            return;
        }
        pushTransformedScissor(matrices, x - HudGlyphLayout.TEXT_SCISSOR_X_BLEED,
            textY - HudGlyphLayout.TEXT_SCISSOR_Y_BLEED, x + brightWidth,
            textY + font.height(TEXT_SIZE) + HudGlyphLayout.TEXT_SCISSOR_Y_BLEED);
        try {
            font.draw(matrices, layer.text(), x, textY, TEXT_SIZE, withAlpha(mediaColor, opacity));
        } finally {
            GuiRenderer.getInstance().popScissor();
        }
    }

    private void drawArtwork(MatrixStack matrices, ArtworkMetrics art, float x, float y, float opacity) {
        try {
            GpuTextureView view = GuiTextures.view(art.texture());
            if (view == null || view.isClosed()) {
                return;
            }
            RenderUtil.TEXTURE_RECT.draw(matrices, x, y, art.width(), art.height(),
                new Vector4f(Math.min(4.2F, Math.min(art.width(), art.height()) * 0.28F)),
                withAlpha(Color.WHITE, opacity), 0.0F, 0.0F, 1.0F, 1.0F,
                view, GuiTextures.linear(), true);
        } catch (RuntimeException ignored) {
            // A resource reload can retire the dynamic texture between update and draw.
        }
    }

    private void updateArtwork(String key, ArtworkMetrics latest) {
        if (!key.equals(mediaArtworkKey)) {
            previousMediaArtworkKey = mediaArtworkKey;
            previousMediaArtwork = mediaArtwork;
            mediaArtworkKey = key;
        }
        mediaArtwork = latest;
    }

    private ArtworkMetrics artworkFor(DynamicIslandTimeline.Layer layer) {
        if (layer.key().equals(mediaArtworkKey)) {
            return mediaArtwork;
        }
        if (layer.key().equals(previousMediaArtworkKey)) {
            return previousMediaArtwork;
        }
        // During a long toggle a lyric can advance more than once. Album art
        // is still track-scoped, so the newest art is the safest fallback.
        return mediaArtwork;
    }

    private void drawToggleMarker(MatrixStack matrices, DynamicIslandTimeline.NoticeLayout layout,
                                  float centerY, boolean enabled,
                                  float opacity) {
        Color color = enabled ? ENABLED : DISABLED;
        RenderUtil.CIRCULAR_COUNTDOWN.drawMarked(matrices,
            layout.markerCenterX(), centerY, layout.markerRadius(), layout.markerStroke(),
            timeline.notificationFraction(), enabled, withAlpha(color, opacity));
    }

    private static void drawPausedMarker(MatrixStack matrices, float centerX, float centerY,
                                         Color textColor, float opacity) {
        Color quiet = withAlpha(textColor, opacity * .075F);
        RenderUtil.RECT.draw(matrices, centerX - 4.5F, centerY - 4.5F, 9.0F, 9.0F, 4.5F, quiet);
        Color bars = withAlpha(textColor, opacity * .72F);
        RenderUtil.RECT.draw(matrices, centerX - 2.15F, centerY - 2.7F, 1.25F, 5.4F, .625F, bars);
        RenderUtil.RECT.draw(matrices, centerX + .9F, centerY - 2.7F, 1.25F, 5.4F, .625F, bars);
    }

    private static void drawWaveform(MatrixStack matrices, float centerX, float centerY,
                                     Color textColor, float opacity) {
        long now = System.nanoTime();
        Color bars = withAlpha(textColor, opacity * 0.86F);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float guiScale = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        float pixelsPerX = matrix.m00() * guiScale;
        float pixelsPerY = matrix.m11() * guiScale;
        // HUD transforms are translation + positive scale; unusual transforms use
        // the unscaled GUI grid rather than risking invalid inverse coordinates.
        if (!Float.isFinite(pixelsPerX) || pixelsPerX <= 0.0F) pixelsPerX = guiScale;
        if (!Float.isFinite(pixelsPerY) || pixelsPerY <= 0.0F) pixelsPerY = guiScale;
        float offsetX = matrix.m30() * guiScale;
        float offsetY = matrix.m31() * guiScale;
        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.WAVEFORM);
        for (int bar = 0; bar < HudGlyphLayout.WAVEFORM_BAR_COUNT; bar++) {
            float height = HudGlyphLayout.waveformHeight(bar, now);
            WaveformPixelGeometry.Bar shape = WaveformPixelGeometry.bar(bar,
                centerX, centerY, height, pixelsPerX, offsetX, pixelsPerY, offsetY);
            int argb = bars.getRGB();
            builder.vertex(matrix, shape.left(), shape.top(), 0.0F).color(argb);
            builder.vertex(matrix, shape.left(), shape.bottom(), 0.0F).color(argb);
            builder.vertex(matrix, shape.right(), shape.bottom(), 0.0F).color(argb);
            builder.vertex(matrix, shape.right(), shape.top(), 0.0F).color(argb);
        }
        renderer.submit(builder, GuiPipelines.WAVEFORM, GuiUniform.of(0, 0, 0, 0, 1));
    }

    private ArtworkMetrics resolveArtwork(MediaPlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.available()) {
            return ArtworkMetrics.NONE;
        }
        Identifier texture = MediaArtwork.getInstance().texture(snapshot);
        if (texture == null) {
            return ArtworkMetrics.NONE;
        }
        try {
            GpuTextureView view = GuiTextures.view(texture);
            if (view == null || view.isClosed() || view.getWidth(0) <= 0 || view.getHeight(0) <= 0) {
                return ArtworkMetrics.NONE;
            }
            float ratio = view.getWidth(0) / (float) view.getHeight(0);
            float width = ratio >= 1.0F ? ART_LIMIT : ART_LIMIT * ratio;
            float height = ratio >= 1.0F ? ART_LIMIT / ratio : ART_LIMIT;
            return new ArtworkMetrics(texture, Math.max(1.0F, width), Math.max(1.0F, height));
        } catch (RuntimeException ignored) {
            return ArtworkMetrics.NONE;
        }
    }

    private static float lyricHighlight(Font font, TimedLyrics.WordCue cue, String fitted, long progressMs) {
        if (fitted.isEmpty() || cue.text().isEmpty()) {
            return 0.0F;
        }
        // Word offsets belong to the original phrase. Measure cleaned prefixes,
        // not offsets into a string shortened by punctuation filtering.
        int retained = cue.text().length();
        float highlightedWidth = 0.0F;
        for (TimedLyrics.Word word : cue.words()) {
            int start = Math.min(retained, word.startChar());
            int end = Math.min(retained, word.endChar());
            if (progressMs >= word.endMs()) {
                highlightedWidth = font.width(HudPolishPolicy.lyricPrefix(cue.text(), end), TEXT_SIZE);
                continue;
            }
            if (progressMs >= word.startMs() && end > start) {
                float before = font.width(HudPolishPolicy.lyricPrefix(cue.text(), start), TEXT_SIZE);
                float through = font.width(HudPolishPolicy.lyricPrefix(cue.text(), end), TEXT_SIZE);
                highlightedWidth = before + (through - before) * (float) word.progress(progressMs);
            }
            break;
        }
        float fullWidth = Math.max(0.001F, font.width(fitted, TEXT_SIZE));
        if (!cue.words().isEmpty() && progressMs >= cue.words().getLast().endMs()) {
            highlightedWidth = fullWidth;
        }
        return Math.clamp(highlightedWidth / fullWidth, 0.0F, 1.0F);
    }

    private static String fitText(Font font, String text, float maximumWidth) {
        String clean = oneLine(text);
        if (font.width(clean, TEXT_SIZE) <= maximumWidth) {
            return clean;
        }
        int codePoints = clean.codePointCount(0, clean.length());
        int low = 0;
        int high = codePoints;
        String best = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = clean.offsetByCodePoints(0, middle);
            String candidate = clean.substring(0, end).stripTrailing() + "\u2026";
            if (font.width(candidate, TEXT_SIZE) <= maximumWidth) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(Math.min(180, value.length()));
        int count = 0;
        for (int offset = 0; offset < value.length() && count < 180; ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\u00a7' || Character.isISOControl(codePoint)) {
                continue;
            }
            clean.appendCodePoint(codePoint);
            count++;
        }
        return clean.toString().strip();
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            String clean = oneLine(value);
            if (!clean.isEmpty()) {
                return clean;
            }
        }
        return "";
    }

    private static String trackKey(MediaPlaybackSnapshot snapshot) {
        return oneLine(snapshot.sourceId()) + '\u0000' + oneLine(snapshot.title()) + '\u0000'
            + oneLine(snapshot.artist()) + '\u0000' + snapshot.durationMs();
    }

    private static void pushTransformedScissor(MatrixStack matrices, float left, float top,
                                               float right, float bottom) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float x1 = matrix.m00() * left + matrix.m10() * top + matrix.m30();
        float y1 = matrix.m01() * left + matrix.m11() * top + matrix.m31();
        float x2 = matrix.m00() * right + matrix.m10() * bottom + matrix.m30();
        float y2 = matrix.m01() * right + matrix.m11() * bottom + matrix.m31();
        GuiRenderer.getInstance().pushScissor(Math.min(x1, x2), Math.min(y1, y2),
            Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    private static void pushTextScissor(MatrixStack matrices, float left, float top,
                                        float right, float bottom) {
        pushTransformedScissor(matrices,
            left - HudGlyphLayout.TEXT_SCISSOR_X_BLEED,
            top - HudGlyphLayout.TEXT_SCISSOR_Y_BLEED,
            right + HudGlyphLayout.TEXT_SCISSOR_X_BLEED,
            bottom + HudGlyphLayout.TEXT_SCISSOR_Y_BLEED);
    }

    private static Color withAlpha(Color color, float opacity) {
        int alpha = Math.clamp(Math.round(color.getAlpha() * Math.clamp(opacity, 0.0F, 1.0F)), 0, 255);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private record PreparedMedia(DynamicIslandTimeline.Media state) {
    }

    private record ArtworkMetrics(Identifier texture, float width, float height) {
        private static final ArtworkMetrics NONE = new ArtworkMetrics(null, 0.0F, 0.0F);

        boolean present() {
            return texture != null && width > 0.0F && height > 0.0F;
        }
    }
}
