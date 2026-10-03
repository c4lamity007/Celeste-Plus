#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
in vec3 viewPosition;
out vec4 fragColor;

void main() {
    if (tint.a < 0.003) discard;
    float height = clamp(texCoord.y, 0.0, 1.0);
    float time = GameTime * 1200.0;
    // Keep base/crest coverage on the wave; caustics pass over it in screen space.
    vec2 screen = gl_FragCoord.xy / max(ScreenSize.y, 1.0);
    vec2 flowUv = 0.5 + 0.43 * sin(screen * vec2(5.6, 4.9)
        + vec2(time * 0.42, -time * 0.31));
    float caustic = smoothstep(0.10, 0.83, texture(Sampler0, flowUv).r);
    vec3 derivatives = cross(dFdx(viewPosition), dFdy(viewPosition));
    // Derivatives shrink with pixel size: a large epsilon changes opacity at
    // higher resolutions instead of merely guarding degenerate triangles.
    vec3 normal = derivatives * inversesqrt(max(dot(derivatives, derivatives), 1e-20));
    vec3 viewDirection = -viewPosition * inversesqrt(max(dot(viewPosition, viewPosition), 0.00000001));
    float grazing = pow(1.0 - clamp(abs(dot(normal, viewDirection)), 0.0, 1.0), 2.5);
    vec3 halfway = normalize(viewDirection + vec3(-0.32, 0.76, 0.57));
    float specular = pow(clamp(abs(dot(normal, halfway)), 0.0, 1.0), 18.0);
    float foamVariation = 0.34 + caustic * 0.48
        + 0.18 * (0.5 + 0.5 * sin(screen.x * 11.0 + screen.y * 4.0 - time * 2.0));
    float foam = smoothstep(0.84, 0.995, height) * foamVariation;
    float ripple = 0.5 + 0.5 * sin(screen.y * 9.0 - time * 2.4 + sin(screen.x * 7.0));

    // Glassy blue body and opaque-looking foam, not another additive glow cylinder.
    vec3 deep = tint.rgb * vec3(0.20, 0.39, 0.59);
    vec3 water = mix(deep, tint.rgb, 0.24 + grazing * 0.47 + caustic * 0.18);
    water = mix(water, vec3(0.70, 0.94, 1.0), caustic * (0.16 + ripple * 0.16));
    water = mix(water, vec3(0.94, 0.995, 1.0), clamp(foam * 0.66 + specular * 0.28, 0.0, 0.80));
    float alpha = tint.a * smoothstep(0.0, 0.10, height)
        * (0.10 + grazing * 0.25 + height * 0.08 + caustic * 0.10 + foam * 0.26);
    if (alpha < 0.003) discard;
    fragColor = vec4(clamp(water, 0.0, 1.0), clamp(alpha, 0.0, 0.82));
}
