#version 330
#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;
uniform sampler2D EnergySampler;
out vec4 fragColor;

float covered(ivec2 pixel, ivec2 size) {
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size))) return 0.0;
    // Occupancy alone defines the union. Differences in limb color/fade cannot make an edge.
    return step(0.001, texelFetch(InSampler, pixel, 0).a);
}

float bell(float value) { return exp(-value * value); }

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    vec4 fill = texelFetch(InSampler, pixel, 0);
    if (fill.a < 0.001) {
        // Empty screen stays empty and cheap. One private mask and one composite, no blur pyramid.
        fragColor = vec4(0.0);
        return;
    }
    int radius = int(floor(clamp(float(size.y) / 550.0, 1.5, 4.0) + 0.5));
    float neighbors = covered(pixel + ivec2(radius, 0), size)
        + covered(pixel + ivec2(-radius, 0), size)
        + covered(pixel + ivec2(0, radius), size)
        + covered(pixel + ivec2(0, -radius), size)
        + covered(pixel + ivec2(radius, radius), size)
        + covered(pixel + ivec2(-radius, radius), size)
        + covered(pixel + ivec2(radius, -radius), size)
        + covered(pixel + ivec2(-radius, -radius), size);
    float rim = clamp((1.0 - neighbors * 0.125) * 2.4, 0.0, 1.0);
    int haloRadius = radius * 4;
    float innerHalo = 1.0 - 0.25 * (covered(pixel + ivec2(haloRadius, 0), size)
        + covered(pixel + ivec2(-haloRadius, 0), size)
        + covered(pixel + ivec2(0, haloRadius), size)
        + covered(pixel + ivec2(0, -haloRadius), size));

    // A single flowing aurora across the already-resolved body: no normals, skin UVs,
    // face gradients, per-part outlines or camera-dependent mesh lighting.
    vec2 screen = gl_FragCoord.xy / float(size.y);
    float time = GameTime * 1200.0;
    // Every material layer shares one upward scroll. X never receives a time
    // offset, so the plasma, ribbons and color currents cannot drift sideways.
    // This remains a screen-space overlay, independent of player/camera motion.
    vec2 field = screen - vec2(0.0, time * 0.16);
    float drift = sin(field.y * 5.0 + field.x * 2.5) * 0.045;
    float phase = field.y * 1.6 - field.x * 0.16 + drift;
    float bandDistance = abs(fract(phase + 0.5) - 0.5);
    float aurora = bell(bandDistance / 0.17);
    float crest = bell(bandDistance / 0.052);
    float flow = 0.5 + 0.5 * sin(6.2831853
        * (field.y * 0.7 + field.x * 0.35));
    float filament = bell(sin(field.x * 10.0 + field.y * 7.0 + drift * 12.0) / 0.16);
    // The generated plasma contributes texture, not coverage. Sampling stays inside
    // the image and does not require a seamless tile or an additional framebuffer.
    vec2 fieldUv = 0.5 + 0.44 * sin(field * vec2(3.7, 5.1));
    float plasma = smoothstep(0.10, 0.78, texture(EnergySampler, fieldUv).r);

    // Saturated tints can carry cyan/violet interference; neutral/custom greys stay neutral.
    float high = max(fill.r, max(fill.g, fill.b));
    float low = min(fill.r, min(fill.g, fill.b));
    float chroma = smoothstep(0.05, 0.60, (high - low) / max(high, 0.0001));
    vec3 cyan = mix(fill.rgb, vec3(0.025, 0.88, 1.0), chroma * 0.74);
    vec3 violet = mix(fill.rgb, vec3(0.62, 0.08, 1.0), chroma * 0.80);
    float iridescence = 0.5 + 0.5 * sin(field.y * 5.6 - field.x * 3.1 + plasma * 3.4);
    vec3 auroraColor = mix(violet, cyan, smoothstep(0.12, 0.88, iridescence));
    float charge = smoothstep(0.18, 0.83, plasma);
    // A dark saturated body gives the broad energy ribbons real contrast instead of a pale wash.
    vec3 body = fill.rgb * (0.28 + flow * 0.15);
    body = mix(body, auroraColor, clamp(charge * 0.91 + aurora * 0.29, 0.0, 0.96));
    body += auroraColor * (aurora * 0.19 + filament * charge * 0.13);
    float hot = clamp(crest * (0.50 + charge * 0.34) + charge * charge * 0.52, 0.0, 0.91);
    vec3 whiteHot = mix(vec3(1.0), vec3(0.87, 0.97, 1.0), chroma * 0.45);
    body = mix(body, whiteHot, hot);
    body = mix(body, mix(cyan, whiteHot, 0.55), innerHalo * 0.70);
    vec3 edge = mix(cyan, whiteHot, 0.89 + aurora * 0.10);
    fragColor = vec4(clamp(mix(body, edge, rim), 0.0, 1.0), fill.a);
}
