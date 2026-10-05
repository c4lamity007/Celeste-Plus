#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float radius = length(p);
    float aa = max(fwidth(radius), 1.0 / max(ScreenSize.y, 1.0));
    float ring = 1.0 - smoothstep(0.032, 0.032 + aa, abs(radius - 0.64));
    float dot = 1.0 - smoothstep(0.07, 0.07 + aa, radius);
    float fill = (1.0 - smoothstep(0.38, 0.62, radius)) * 0.075 * texture(Sampler0, texCoord).a;
    float alpha = tint.a * min(1.0, ring * 0.82 + dot + fill);
    if (alpha < 0.002) discard;
    fragColor = vec4(mix(tint.rgb, vec3(1.0), dot * 0.68 + ring * 0.18), alpha);
}
