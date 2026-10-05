#version 330
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

// Round bloom with a broad bright center and a continuous soft shoulder.
// Keep RGB unattenuated: SRC_ALPHA/ONE applies the soft profile exactly once.
const float PROFILE[21] = float[21](
    1.0, 1.0, 1.0, .99638, .97319, .91348, .80778, .67022, .52837, .40426, .30196, .22273, .15833, .10953, .06939, .04127, .02218, .01063, .00450, .00123, 0.0);

void main() {
    float radius = length(texCoord * 2.0 - 1.0);
    if (radius >= 1.0) discard;
    float sampleAt = radius * 20.0;
    int edge = min(19, int(sampleAt));
    float alpha = tint.a * mix(PROFILE[edge], PROFILE[edge + 1], fract(sampleAt));
    if (alpha < 0.003) discard;
    fragColor = vec4(tint.rgb, alpha);
}
