#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;
float bell(float x) { return exp(-x * x); }

void main() {
    float time = GameTime * 1200.0;
    vec2 uv = texCoord;
    float y = uv.y - 0.5;
    // UVs retain the ribbon silhouette; the luminous field does not ride the mesh.
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    vec4 fibers = texture(Sampler0, 0.5 + 0.43 * sin(screen * vec2(4.0, 3.0)
        + vec2(time * 0.24, -time * 0.18)));
    float detail = dot(fibers.rgb, vec3(0.299, 0.587, 0.114)) * fibers.a;
    float body = bell(y / 0.235);
    float core = bell(y / 0.095);
    float silk = 0.5 + 0.5 * sin(screen.x * 12.0 - time * 2.2 + screen.y * 8.0);
    float current = 0.80 + 0.20 * sin(screen.x * 14.0 + screen.y * 3.0 - time * 2.8);
    float edges = smoothstep(0.0, 0.16, uv.y) * (1.0 - smoothstep(0.84, 1.0, uv.y));
    float alpha = tint.a * edges * (body * (0.32 + silk * 0.12) + core * 0.29
                  + detail * body * 0.27) * current;
    if (alpha < 0.003) discard;
    vec3 color = mix(tint.rgb, vec3(1.0), core * 0.19 + detail * 0.09);
    fragColor = vec4(color, min(alpha, 0.78));
}
