#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;
float bell(float x) { return exp(-x * x); }

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float r = length(p);
    if (r <= 0.19 || r >= 0.99 || tint.a < 0.003) discard;
    float time = GameTime * 1200.0;
    // Radial UVs mask the shape only. Its overlay light never rotates with the ring.
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    float crest = bell((r - 0.69) / 0.078);
    float body = bell((r - 0.67) / 0.18);
    float inner = bell((r - 0.46) / 0.062);
    float current = 0.78 + 0.22 * sin(screen.x * 8.0 + screen.y * 9.0 - time * 1.4);
    float sweep = pow(max(0.0, cos(screen.y * 5.0 + screen.x * 2.0 - time * 0.85)), 6.0);
    float imageGlow = texture(Sampler0, texCoord).a;
    float boundary = (1.0 - smoothstep(0.87, 0.99, r)) * smoothstep(0.19, 0.36, r);
    float alpha = tint.a * boundary * (crest * current * 0.65 + body * 0.29
                  + inner * (0.13 + sweep * 0.12) + imageGlow * 0.07);
    if (alpha < 0.003) discard;
    vec3 color = mix(tint.rgb, vec3(1.0), crest * (0.16 + sweep * 0.13));
    fragColor = vec4(color, min(alpha, 0.82));
}
