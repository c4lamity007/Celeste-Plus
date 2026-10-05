#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float r = length(p);
    if (r >= 0.97 || tint.a < 0.003) discard;
    float lens = sqrt(max(0.0, 1.0 - r * r));
    float highlight = exp(-dot(p - vec2(-0.23, -0.29), p - vec2(-0.23, -0.29)) * 18.0);
    float body = (0.31 + lens * 0.36 + highlight * 0.22) * (1.0 - smoothstep(0.60, 0.97, r));
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    float sparkle = 0.94 + 0.06 * sin(GameTime * 1200.0 * 2.0 + screen.y * 3.0);
    vec3 color = mix(tint.rgb, vec3(0.93, 0.99, 1.0), highlight * 0.65);
    fragColor = vec4(color, min(tint.a * body * sparkle, 0.78));
}
