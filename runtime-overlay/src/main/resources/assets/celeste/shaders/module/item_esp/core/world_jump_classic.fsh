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
    // The supplied sprite remains a stationary coverage mask, never time-warped.
    vec4 image = texture(Sampler0, 0.5 + p * 0.54);
    float luminance = dot(image.rgb, vec3(0.299, 0.587, 0.114));
    float core = bell((r - 0.67) / 0.085);
    float bloom = bell((r - 0.65) / 0.19);
    float flow = 0.78 + 0.22 * sin(screen.x * 8.0 + screen.y * 12.0 - t * 1.3);
    float edges = smoothstep(0.27, 0.44, r) * (1.0 - smoothstep(0.87, 0.99, r));
    float alpha = tint.a * edges * (image.a * (0.25 + luminance * 0.44) * flow
                  + core * 0.18 + bloom * 0.20);
    if (alpha < 0.003) discard;
    vec3 color = mix(tint.rgb, vec3(1.0), clamp(luminance * image.a * 0.28 + core * 0.08, 0.0, 0.38));
    fragColor = vec4(color, min(alpha, 0.86));
}
