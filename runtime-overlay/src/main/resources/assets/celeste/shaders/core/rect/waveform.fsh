#version 330

#moj_import <celeste:common.glsl>

in vec2 FragCoord;
in vec4 FragColor;

out vec4 fragColor;

void main() {
    // FragCoord spans one pixel-aligned bar. Work in framebuffer pixels so
    // rounded caps retain a crisp, one-pixel antialias at every HUD scale.
    vec2 sizePx = 1.0 / max(fwidth(FragCoord), vec2(0.0001));
    float radius = min(sizePx.x, sizePx.y) * 0.5;
    vec2 position = (FragCoord - vec2(0.5)) * sizePx;
    vec2 corner = abs(position) - (sizePx * 0.5 - vec2(radius));
    float distancePx = length(max(corner, vec2(0.0)))
        + min(max(corner.x, corner.y), 0.0) - radius;
    float coverage = clamp(0.5 - distancePx, 0.0, 1.0);
    fragColor = vec4(FragColor.rgb, FragColor.a * coverage * uSize.x);
}
