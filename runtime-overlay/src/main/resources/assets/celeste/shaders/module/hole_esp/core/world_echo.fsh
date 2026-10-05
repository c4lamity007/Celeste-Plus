#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
in vec3 viewNormal;
in vec3 viewPosition;
out vec4 fragColor;

void main() {
    float time = GameTime * 1200.0;
    vec3 normal = normalize(viewNormal);
    vec3 eye = normalize(-viewPosition + vec3(0.00001));
    float rim = pow(1.0 - abs(dot(normal, eye)), 2.2);
    // Snapshot age/limb UVs must not advance or re-anchor the overlay animation.
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    float band = pow(0.5 + 0.5 * sin(screen.y * 12.0 + screen.x * 2.0 - time * 2.2), 12.0);
    vec4 fibers = texture(Sampler0, 0.5 + 0.43 * sin(screen * vec2(3.3, 6.0)
        + vec2(-time * 0.22, time * 0.55)));
    float detail = dot(fibers.rgb, vec3(0.299, 0.587, 0.114)) * fibers.a;
    // A readable glass-like surface; rim and energy bands carry the brightness.
    float alpha = tint.a * (0.08 + rim * 0.53 + band * 0.13 + detail * 0.12);
    vec3 color = mix(tint.rgb, vec3(1.0), clamp(rim * 0.18 + band * 0.2, 0.0, 0.32));
    if (alpha < 0.003) discard;
    fragColor = vec4(color, min(alpha, 0.65));
}
