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
    if (r <= 0.27 || r >= 0.99 || tint.a < 0.003) discard;
    float t = GameTime * 1200.0;
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    // Keep the water sprite mask fixed; only overlay shimmer advances with time.
    vec4 image = texture(Sampler0, 0.5 + p * 0.54);
    float detail = dot(image.rgb, vec3(0.299, 0.587, 0.114));
    float crest = bell((r - 0.66) / 0.08);
    float mist = bell((r - 0.65) / 0.20);
    float shimmer = 0.77 + 0.23 * sin(screen.y * 10.0 - t * 2.5 + sin(screen.x * 7.0));
    float edges = smoothstep(0.27, 0.42, r) * (1.0 - smoothstep(0.85, 0.99, r));
    float alpha = tint.a * edges * (image.a * (0.29 + detail * 0.50) * shimmer
                  + crest * 0.15 + mist * 0.10);
    if (alpha < 0.003) discard;
    vec3 color = mix(tint.rgb, vec3(0.90, 0.99, 1.0), clamp(detail * image.a * 0.46 + crest * 0.12, 0.0, 0.65));
    fragColor = vec4(color, min(alpha, 0.80));
}
