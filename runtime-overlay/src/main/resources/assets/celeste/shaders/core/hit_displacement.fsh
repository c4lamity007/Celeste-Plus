#version 330
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D SceneColor;
in vec2 texCoord;
flat in vec4 projectedLenses[4];
out vec4 fragColor;

void main() {
    vec2 dimensions = vec2(textureSize(SceneColor, 0));
    vec2 aspect = vec2(dimensions.x / dimensions.y, 1.0);
    vec2 offset = vec2(0.0);
    vec3 rimColor = vec3(0.0);
    float rimWeight = 0.0;
    float coverage = 0.0;
    // Centers use the exact scene projection calculated in the vertex stage.
    // TextureMat remains RGB / release for each of the four pulses.
    for (int i = 0; i < 4; ++i) {
        vec4 lens = projectedLenses[i];
        float strength = lens.w;
        if (lens.z <= 0.00001 || strength <= 0.0) continue;
        vec2 p = (texCoord - lens.xy) * aspect;
        float r = length(p) / lens.z;
        if (r >= 1.0) continue;
        float softEdge = 1.0 - smoothstep(0.72, 1.0, r);
        float bowl = (1.0 - r * r) * softEdge;
        // Sampling farther from the center pulls scene detail inward. The lens
        // gently releases that compression before it contracts and disappears.
        float pull = 0.25 * strength * mix(1.0, -0.20, TextureMat[i].w);
        offset += (p / aspect) * bowl * pull;
        float rim = exp(-pow((r - 0.81) / 0.09, 2.0)) * strength * 0.105;
        rimColor += mix(vec3(0.72), TextureMat[i].rgb, 0.36) * rim;
        rimWeight += rim;
        coverage = max(coverage, strength * softEdge);
    }
    // No tint, filtering change or write at all outside the animated circle.
    if (coverage <= 0.0 && rimWeight <= 0.00001) discard;
    vec2 halfTexel = 0.5 / dimensions;
    vec2 uv = clamp(texCoord + offset, halfTexel, vec2(1.0) - halfTexel);
    vec3 refracted = texture(SceneColor, uv).rgb;
    float rimAlpha = min(rimWeight, 0.18);
    vec3 result = refracted * (1.0 - rimAlpha) + rimColor * (rimAlpha / max(rimWeight, 0.00001));
    fragColor = vec4(result, 1.0);
}
