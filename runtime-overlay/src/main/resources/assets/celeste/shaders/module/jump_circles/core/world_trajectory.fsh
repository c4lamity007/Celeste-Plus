#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    // One continuous cross-section. No per-segment caps, moving noise or screen-space stripes.
    // U is the module's Glow setting; V is the position across the ribbon.
    float glow = clamp(texCoord.x, 0.0, 1.0);
    float across = abs(texCoord.y * 2.0 - 1.0);
    float aa = max(fwidth(across), 1.0 / max(ScreenSize.y, 1.0));
    float core = 1.0 - smoothstep(0.085, 0.085 + aa, across);
    float body = exp(-across * across / 0.036) * 0.36;
    float soft = texture(Sampler0, vec2(0.5, texCoord.y)).a;
    float bloom = exp(-across * across / 0.20) * glow * 0.26 * soft;
    float edge = 1.0 - smoothstep(0.78, 1.0, across);
    float alpha = tint.a * edge * min(1.0, core * 0.86 + body + bloom);
    if (alpha < 0.002) discard;
    fragColor = vec4(mix(tint.rgb, vec3(1.0), core * 0.52), alpha);
}
