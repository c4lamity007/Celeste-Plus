#version 330

#moj_import <celeste:common.glsl>

uniform sampler2D Sampler0;

in vec2 FragCoord;
in vec2 TexCoord;
in vec4 FragColor;

out vec4 fragColor;

void main() {
    // A square image needs its entire texture quad. roundedAlpha reserves a
    // one-pixel shape border even at radius/smoothness zero, which clips small
    // HUD artwork before the texture's own transparent padding can be sampled.
    float alpha = all(equal(uRadius, vec4(0.0))) && uSmoothness <= 0.0
        ? 1.0 : roundedAlpha(uSize, FragCoord, uRadius, uSmoothness);
    vec4 color = texture(Sampler0, TexCoord) * FragColor;
    color.a *= alpha;
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
