#version 330

uniform sampler2D SkinSampler;
in vec2 skinUv;
flat in vec4 fillColor;
out vec4 fragColor;

void main() {
    // Match vanilla entity texture cutout. Invisible hat/jacket/sleeve texels
    // must neither inflate the contour nor occlude an actual body pixel.
    if (texture(SkinSampler, skinUv).a < 0.1) discard;
    fragColor = fillColor;
}
