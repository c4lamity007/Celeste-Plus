#version 330
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

// Broad glowing annulus; animation and world placement are owned by the CPU.
// Keep RGB unattenuated: SRC_ALPHA/ONE applies the soft profile exactly once.
const float PROFILE[21] = float[21](
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, .01336, .07730, .17286, .30116, .56180, .90953, 1.0, .87019, .49081, .24096, .12678, .04975, 0.0);

void main() {
    float radius = length(texCoord * 2.0 - 1.0);
    if (radius >= 1.0) discard;
    float sampleAt = radius * 20.0;
    int edge = min(19, int(sampleAt));
    float alpha = tint.a * mix(PROFILE[edge], PROFILE[edge + 1], fract(sampleAt));
    if (alpha < 0.003) discard;
    fragColor = vec4(tint.rgb, alpha);
}
