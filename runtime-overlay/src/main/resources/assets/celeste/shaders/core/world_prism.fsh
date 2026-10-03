#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    if (tint.a < 0.003) discard;
    vec2 uv = clamp(texCoord, 0.0, 1.0);
    float time = GameTime * 1200.0;
    // Animated light is a screen overlay; face UVs only describe the box borders.
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    vec2 flow = 0.5 + 0.46 * sin(screen * vec2(2.4, 3.6) + vec2(time * 0.19, -time * 0.15));
    float plasma = texture(Sampler0, flow).r;
    float undercurrent = texture(Sampler0, 0.5 + 0.44 * cos(screen.yx * 3.1
        + vec2(time * 0.11, time * 0.09))).r;
    float energy = smoothstep(0.13, 0.78, plasma * 0.72 + undercurrent * 0.28);
    // Screen-space falloff gives readable luminous edges without GL line-width dependence.
    vec2 pixels = min(uv, 1.0 - uv) / max(fwidth(uv), vec2(0.0001));
    float edge = min(pixels.x, pixels.y);
    float core = 1.0 - smoothstep(0.9, 3.9, edge);
    float bloom = exp(-edge * edge / 210.0);
    float corner = exp(-dot(pixels, pixels) / 240.0);
    float sweep = pow(0.5 + 0.5 * sin(screen.y * 6.2831853 + screen.x * 1.3 - time * 1.25), 5.0);
    float band = sweep * (0.10 + energy * 0.15);
    float face = 0.14 + energy * 0.25;
    float alpha = tint.a * (face + core * 0.37 + bloom * 0.20 + corner * 0.21 + band);

    float high = max(tint.r, max(tint.g, tint.b));
    float low = min(tint.r, min(tint.g, tint.b));
    float chroma = smoothstep(0.05, 0.60, (high - low) / max(high, 0.0001));
    vec3 cyan = mix(tint.rgb, vec3(0.02, 0.88, 1.0), chroma * 0.75);
    vec3 violet = mix(tint.rgb, vec3(0.61, 0.07, 1.0), chroma * 0.81);
    float iridescence = 0.5 + 0.5 * sin(screen.y * 6.0 + screen.x * 2.7 + plasma * 3.2 - time * 0.8);
    vec3 energyColor = mix(violet, cyan, smoothstep(0.14, 0.88, iridescence));
    vec3 pearl = mix(tint.rgb * 0.30, energyColor, 0.32 + energy * 0.65);
    pearl += energyColor * sweep * 0.24;
    vec3 whiteHot = mix(vec3(1.0), vec3(0.87, 0.98, 1.0), chroma * 0.40);
    float hot = clamp(core * 0.84 + corner * 0.49 + sweep * energy * 0.53 + energy * energy * 0.28, 0.0, 0.97);
    pearl = mix(pearl, whiteHot, hot);
    fragColor = vec4(clamp(pearl, 0.0, 1.0), clamp(alpha, 0.0, 0.84));
}
