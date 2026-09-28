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
    // A broad rising fold changes brightness gently without cutting the aura apart.
    vec2 field = pixel / (vec2(58.0, 88.0) * scale) - vec2(0.0, time * 0.16);
    field.x += sin(field.y * 2.0 + time * 0.65) * 0.10;
    float fold = smoothNoise(field) * 0.8 + smoothNoise(field * 2.0 + 13.0) * 0.2;
    return vec2(fold, smoothstep(0.45, 0.9, fold));
}
float flameCoverage(vec2 uv, vec2 pixel, vec2 size, float scale, float time, float nearCoverage) {
    // Keep the original silhouette as the root of one continuous flame.
    // Small offsets bend only the exterior; no detached tongues or bright islands.
    float sway = sin(pixel.y / (54.0 * scale) - time * 0.85) * 1.8
        + sin(pixel.y / (93.0 * scale) + time * 0.55) * 0.8;
    vec2 bend = vec2(sway, 0.0) * scale / size;
    float flowing = glowCoverage(uv - bend);
    float rising = glowCoverage(uv - bend * 1.2 - vec2(0.0, 8.0 * scale) / size);
    return clamp(max(nearCoverage, max(flowing * 0.98, rising * 0.82)), 0.0, 1.0);
}
vec4 attachedFlame(vec4 material, vec2 size) {
    float scale = clamp(size.y / 540.0, 1.0, 3.0);
    float density = flameCoverage(texCoord, gl_FragCoord.xy, size, scale,
        HandEffect.w, glowCoverage(texCoord));
    vec2 fold = flowingFlame(gl_FragCoord.xy, scale, HandEffect.w);
    float opacity = clamp(HandColor.a, 0.05, 1.0);
    float strength = clamp(HandEffect.y, 0.0, 2.0);
    vec3 tint = clamp(HandColor.rgb, 0.0, 1.0);
    float envelope = smoothstep(0.0005, 0.018, density);
    float flameAlpha = (1.0 - exp(-density * (7.0 + fold.x * 1.8)))
        * envelope * opacity * min(1.0, strength);
    float core = smoothstep(0.08, 0.5, density);
    vec3 energy = mix(tint, vec3(1.0), core * 0.16);
    energy *= 0.84 + fold.x * 0.16;
    // The aura surrounds the current item; it never paints over its texture.
    float alpha = material.a + flameAlpha * (1.0 - material.a);
    vec3 premul = material.rgb + energy * flameAlpha * (1.0 - material.a);
    return alpha > 0.001 ? vec4(clamp(premul / alpha, 0.0, 1.0), alpha) : vec4(0.0);
}
void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 material = texelFetch(InSampler, pixel, 0);
    if (HandEffect.x > 2.5) { fragColor = attachedFlame(material, vec2(size)); return; }
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
