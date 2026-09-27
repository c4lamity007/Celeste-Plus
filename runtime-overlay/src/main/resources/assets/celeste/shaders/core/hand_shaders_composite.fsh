#version 330
uniform sampler2D InSampler;
uniform sampler2D GlowSampler;
layout(std140) uniform HandShaderData { vec4 HandColor; vec4 HandEffect; };
in vec2 texCoord;
out vec4 fragColor;

float coverage(ivec2 pixel, ivec2 size) {
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size))) return 0.0;
    return smoothstep(0.005, 0.03, texelFetch(InSampler, pixel, 0).a);
}
float glowCoverage(vec2 uv) {
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return 0.0;
    vec4 glow = texture(GlowSampler, uv);
    return HandEffect.x > 1.5 ? dot(glow.rg, vec2(256.0 / 257.0, 1.0 / 257.0)) : glow.a;
}
float hash(vec2 point) {
    return fract(sin(dot(point, vec2(127.1, 311.7))) * 43758.5453);
}
float smoothNoise(vec2 point) {
    vec2 cell = floor(point);
    vec2 f = fract(point);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1.0, 0.0)), f.x),
        mix(hash(cell + vec2(0.0, 1.0)), hash(cell + vec2(1.0)), f.x), f.y);
}
vec2 flowingCloud(vec2 pixel, float scale, float time) {
    vec2 field = pixel / (26.0 * scale) - vec2(time * 0.035, time * 0.23);
    field += vec2(sin(field.y * 1.15 + time * 0.21) * 0.55,
        sin(field.x * 0.8 - time * 0.17) * 0.25);
    float cloud = smoothNoise(field) * 0.58
        + smoothNoise(field * 2.17 + vec2(7.0, time * 0.06)) * 0.28
        + smoothNoise(field * 4.39 - vec2(time * 0.08, 3.0)) * 0.14;
    float charge = smoothNoise(field * 0.61 + vec2(13.0, 5.0));
    float hot = pow(smoothstep(0.48, 0.80, charge * 0.55 + cloud * 0.45), 1.5);
    return vec2(cloud, hot);
}
vec2 flowingFlame(vec2 pixel, float scale, float time) {
    // Vertically stretched, continuously rising folds make tongues of flame;
    // no point sprites, radial blobs, quantized clocks, or randomly born sparks.
    vec2 field = vec2(pixel.x / (15.0 * scale), pixel.y / (42.0 * scale) - time * 1.25);
    field.x += sin(field.y * 1.7 + time * 0.43) * 0.36
        + (smoothNoise(field * 0.73 + 8.0) - 0.5) * 0.65;
    float fire = smoothNoise(field) * 0.72 + smoothNoise(field * vec2(2.05, 1.45) + 13.0) * 0.28;
    return vec2(smoothstep(0.14, 0.83, fire), smoothstep(0.48, 0.86, fire));
}
float flameCoverage(vec2 uv, vec2 pixel, vec2 size, float scale, float time, float nearCoverage) {
    float drift = (smoothNoise(vec2(pixel.x / (28.0 * scale), pixel.y / (48.0 * scale) - time * 0.9)) - 0.5);
    float plume = nearCoverage * 0.62;
    // Rising tongues escape exposed upper edges, not enclosed transparent
    // cutouts underneath another part of the item. Use the smooth mask rather
    // than hard item-color taps so the ceiling cannot introduce an extra rim.
    float ceiling = max(glowCoverage(uv + vec2(0.0, 26.0 * scale) / size),
        glowCoverage(uv + vec2(0.0, 46.0 * scale) / size));
    float openAbove = 1.0 - smoothstep(0.02, 0.45, ceiling);
    // Integrate the delayed silhouette from below, with diminishing energy.
    // All taps use the same continuous field and bounded sampler.
    for (int i = 1; i <= 4; ++i) {
        float step = float(i);
        vec2 offset = vec2(drift * step * 3.0, step * 10.0) * scale / size;
        plume += glowCoverage(uv - offset) * (0.21 - step * 0.027) * openAbove;
    }
    return clamp(plume, 0.0, 1.0);
}
void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 material = texelFetch(InSampler, pixel, 0);
    float body = coverage(pixel, size);
    int radius = int(clamp(HandEffect.z, 1.0, 3.0) + 0.5);
    float outer = body;
    float inner = body;
    for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
        float neighbor = coverage(pixel + ivec2(x, y) * radius, size);
        outer = max(outer, neighbor);
        inner = min(inner, neighbor);
    }
    float edge = max(outer - body, body - inner);
    float glow = clamp(HandEffect.y, 0.0, 2.0);
    float mode = HandEffect.x;
    float haloCoverage = glowCoverage(texCoord);
    float scale = clamp(float(size.y) / 540.0, 1.0, 3.0);
    bool flame = mode > 2.5;
    if (flame) haloCoverage = flameCoverage(texCoord, gl_FragCoord.xy, vec2(size), scale, HandEffect.w, haloCoverage);
    if (mode > 0.5 && mode < 1.5) {
        vec2 spread = vec2(4.0) / vec2(size);
        float wide = 0.25 * (glowCoverage(texCoord + vec2(spread.x, 0.0))
            + glowCoverage(texCoord - vec2(spread.x, 0.0))
            + glowCoverage(texCoord + vec2(0.0, spread.y))
            + glowCoverage(texCoord - vec2(0.0, spread.y)));
        haloCoverage = max(haloCoverage, wide);
    }
    float halo = clamp(haloCoverage - body, 0.0, 1.0);
    float opacity = clamp(HandColor.a, 0.05, 1.0);
    vec3 tint = clamp(HandColor.rgb, 0.0, 1.0);
    // Raw capture is premultiplied where vanilla used translucent blending.
    vec3 original = material.a > 0.001 ? clamp(material.rgb / material.a, 0.0, 1.0) : vec3(0.0);
    // Every style keeps the true original item texture, shading and transparency.
    // Opacity belongs only to the effect, never to a replacement flat material.
    float bodyAlpha = material.a;
    vec3 bodyColor = original;
    vec2 cloud = vec2(0.0);
    float auraEnvelope = 0.0;
    if (mode > 1.5 && (body > 0.001 || haloCoverage > 0.0003)) {
        cloud = flame ? flowingFlame(gl_FragCoord.xy, scale, HandEffect.w)
            : flowingCloud(gl_FragCoord.xy, scale, HandEffect.w);
        auraEnvelope = smoothstep(0.0003, 0.025, haloCoverage);
        if (body > 0.001 && edge < 0.001) {
            float textureShade = dot(original, vec3(0.2126, 0.7152, 0.0722));
            float surface = opacity * (flame ? (0.08 + cloud.x * 0.40 + cloud.y * 0.62)
                : (0.10 + cloud.x * 0.45 + cloud.y * 0.80))
                * (0.4 + min(glow, 1.5) * 0.6);
            vec3 energyColor = mix(tint, vec3(1.0), flame ? (0.16 + cloud.y * 0.68) : (0.24 + cloud.y * 0.70));
            // The moving coat carries the chosen aura hue across the blade,
            // instead of merely brightening (and clipping) its existing color.
            // Original luminance still supplies the item texture and shading.
            float coat = opacity * (flame ? (0.46 + cloud.x * 0.22 + cloud.y * 0.10)
                : (0.62 + cloud.x * 0.20 + cloud.y * 0.18));
            vec3 coated = mix(original, tint * (0.20 + textureShade * 0.85), coat);
            bodyColor = clamp(coated + energyColor * surface * (0.30 + textureShade * 0.70), 0.0, 1.0);
        }
    }
    float rimAlpha = edge * opacity;
    float haloAlpha = mode < 0.5 ? 0.0 : halo * glow * (mode < 1.5 ? 0.95 : 0.70) * opacity;
    vec3 edgeColor = mix(tint, vec3(1.0), 0.68);
    // Compose into one straight-alpha result; GPU TRANSLUCENT blends exactly once.
    float alpha = bodyAlpha + rimAlpha * (1.0 - bodyAlpha);
    vec3 premul = bodyColor * bodyAlpha * (1.0 - rimAlpha) + edgeColor * rimAlpha;
    premul += tint * haloAlpha * (1.0 - alpha);
    alpha += haloAlpha * (1.0 - alpha);
    if (mode > 1.5 && body < 0.001) {
        // Only the exterior density is delayed; the actual item and crisp rim
        // always come from this frame's untouched capture.
        float available = (1.0 - edge) * auraEnvelope;
        float density = flame ? (2.2 + cloud.x * 12.0) : (4.0 + cloud.x * 7.0);
        float fog = (1.0 - exp(-haloCoverage * density))
            * opacity * min(1.0, 0.3 + glow * 0.7) * available;
        vec3 fogColor = flame
            ? mix(tint * (0.52 + cloud.x * 0.48), mix(tint, vec3(1.0), 0.85), cloud.y)
            : mix(tint * (0.60 + cloud.x * 0.40), mix(tint, vec3(1.0), 0.80), cloud.y);
        // Dense mist near the blade is luminous; the thin outer wisps stay
        // darker and saturated. Alpha still fades smoothly into the scene.
        float edgeShade = mix(0.22, 1.0, smoothstep(0.01, 0.32, haloCoverage));
        fogColor *= edgeShade;
        premul = premul * (1.0 - fog) + fogColor * fog;
        alpha += fog * (1.0 - alpha);
        // Both retained styles use their own continuous density/hot-patch field.
        float lights = cloud.y * (1.0 - exp(-haloCoverage * 10.0)) * opacity * available
            * edgeShade * edgeShade;
        vec3 lightColor = mix(tint, vec3(1.0), 0.80);
        premul = premul * (1.0 - lights) + lightColor * lights;
        alpha += lights * (1.0 - alpha);
    }
    fragColor = alpha > 0.001 ? vec4(clamp(premul / alpha, 0.0, 1.0), alpha) : vec4(0.0);
}
