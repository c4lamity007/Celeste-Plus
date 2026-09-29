#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>

uniform sampler2D SceneDepth;
in vec4 nearPlane;
in vec4 farPlane;
out vec4 fragColor;

// ColorModulator: effective Atmosphere/local RGB and intensity.
// TextureMat: inverse actual terrain view matrix, including view bob/tilt.
// RGB blending is destination-color multiplication; alpha and depth are unchanged.
// Minecraft terrain geometry already determines which block faces are exposed.
void main() {
    float intensity = clamp(ColorModulator.a, 0.0, 1.0);
    if (intensity <= 0.0) discard;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    // The copied depth contains terrain only. Cleared sky is exactly one.
    if (depth <= 0.0 || depth >= 1.0) discard;
    vec4 point = mix(nearPlane, farPlane, depth);
    if (abs(point.w) < .00000001) discard;
    vec3 viewPosition = point.xyz / point.w;
    vec3 relativeWorld = (TextureMat * vec4(viewPosition, 1.0)).xyz;
    if (any(isnan(relativeWorld)) || any(isinf(relativeWorld))) discard;

    // Match terrain fog before the view transform; no independent range cutoff.
    float visibility = 1.0 - total_fog_value(length(relativeWorld),
        max(length(relativeWorld.xz), abs(relativeWorld.y)),
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float amount = intensity * clamp(visibility, 0.0, 1.0);
    if (amount <= 0.0) discard;

    vec3 tint = clamp(ColorModulator.rgb, 0.0, 1.0);
    // Keep the strongest channel's existing lighting, tint the others, and retain
    // every surface's texture/shadow contrast. White is identity; black only dims.
    float peak = max(tint.r, max(tint.g, tint.b));
    vec3 ambient = tint / max(peak, .000001);
    fragColor = vec4(mix(vec3(1.0), ambient, amount), 1.0);
}
