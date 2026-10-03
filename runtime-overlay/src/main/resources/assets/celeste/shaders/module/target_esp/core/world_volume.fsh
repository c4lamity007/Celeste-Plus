#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
in vec3 viewPosition;
in vec3 viewNormal;
out vec4 fragColor;

// Reconstruct only the broad image tones. Sampling the original fine streaks
// along U stretched them across every tube section like ribbed plastic.
float softPlasma(vec2 uv) {
    vec2 blend = uv * uv * (3.0 - 2.0 * uv);
    vec3 top = mix(texture(Sampler0, vec2(0.22, 0.28)).rgb,
        texture(Sampler0, vec2(0.76, 0.28)).rgb, blend.x);
    vec3 bottom = mix(texture(Sampler0, vec2(0.22, 0.74)).rgb,
        texture(Sampler0, vec2(0.76, 0.74)).rgb, blend.x);
    return dot(mix(top, bottom, blend.y), vec3(0.30, 0.55, 0.15));
}

void main() {
    if (tint.a < 0.003) discard;
    float time = GameTime * 1200.0;
    // Overlay light is anchored to framebuffer pixels, not UVs, world position,
    // camera projection or mesh dimensions. Height normalization keeps its scale
    // stable across aspect ratios and render resolutions; only time moves it.
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    float angle = dot(screen, vec2(6.2831853, 1.65));
    // Two broad currents pass over the silhouette without following its facets.
    float flow = angle - time * 5.2;
    float current = 0.5 + 0.5 * sin(flow + 0.30 * sin(angle * 2.0 + time * 2.6));
    current = smoothstep(0.08, 0.96, current);
    float undertow = 0.5 + 0.5 * cos(angle * 2.0 + time * 3.5);
    float energy = softPlasma(0.5 + 0.46 * vec2(sin(flow), cos(angle + time * 2.1)));

    // Interpolated welded normals keep lighting continuous across face/triangle joins.
    vec3 normal = viewNormal * inversesqrt(max(dot(viewNormal, viewNormal), 0.00000001));
    vec3 view = -viewPosition * inversesqrt(max(dot(viewPosition, viewPosition), 0.00000001));
    float facing = clamp(abs(dot(normal, view)), 0.0, 1.0);
    float rim = pow(1.0 - facing, 2.0);
    vec3 light = normalize(vec3(-0.35, 0.85, 0.42));
    float diffuse = 0.72 + 0.28 * max(dot(normal, light), 0.0);

    // Enchanted-glass color and simple directional shade suit block geometry.
    // Keep the chosen palette: no oily hue rotation or broad white plastic gloss.
    vec3 base = mix(tint.rgb, tint.rgb * tint.rgb, 0.55);
    vec3 secondary = mix(base, tint.rgb, 0.18 * undertow);
    vec3 body = secondary * (0.62 + diffuse * 0.26 + current * 0.24 + energy * 0.025);
    body = mix(body, tint.rgb, current * 0.36 + rim * 0.14);
    float enchantment = smoothstep(0.40, 0.94, current) * 0.18 + rim * 0.055;
    body = mix(body, mix(tint.rgb, vec3(1.0), 0.35), enchantment);
    // Private-depth replacement resolves coverage. The composite applies this once;
    // neither hidden faces nor a facet's lighting can change the chosen opacity.
    fragColor = vec4(clamp(body, 0.0, 1.0), clamp(tint.a, 0.0, 1.0));
}
