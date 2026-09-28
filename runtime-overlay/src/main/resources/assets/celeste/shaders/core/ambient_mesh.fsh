#version 330
#moj_import <minecraft:fog.glsl>

in vec2 texCoord;
in vec4 tint;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 viewPosition;
in vec2 fogDistance;
out vec4 fragColor;

void main() {
    float diffuse = max(dot(normalize(worldNormal), normalize(vec3(-0.35, 0.82, 0.45))), 0.0);
    float facing = abs(dot(normalize(viewNormal), normalize(-viewPosition)));
    float edge = pow(1.0 - clamp(facing, 0.0, 1.0), 2.0);
    // Broad facet lighting, a pearly core and a restrained rim: no screen-space stripes.
    vec3 color = mix(tint.rgb * (0.35 + diffuse * 0.55), vec3(0.9,0.97,1.0), 0.17 + edge * 0.32);
    color += tint.rgb * 0.15;
    if (texCoord.x > 3.5) {
        color = tint.rgb * (0.76 + diffuse * 0.24);
    } else if (texCoord.x > 1.5) {
        // The folded mesh supplies the silhouette. A fine midrib and quiet side veins
        // read as a leaf close up; derivative filtering removes them naturally at distance.
        vec2 leaf = vec2((texCoord.x - 2.5) * 2.0, texCoord.y);
        float aa = max(fwidth(leaf.x), 0.012);
        float midrib = 1.0 - smoothstep(0.014, 0.014 + aa, abs(leaf.x));
        float veinPhase = leaf.y * 3.0 - abs(leaf.x) * 0.86;
        float sideDistance = abs(fract(veinPhase + 0.5) - 0.5);
        float sideAa = max(fwidth(veinPhase), 0.018);
        float veins = (1.0 - smoothstep(0.018, 0.018 + sideAa, sideDistance))
            * smoothstep(0.05, 0.15, abs(leaf.x)) * (1.0 - smoothstep(0.40, 0.67, abs(leaf.x)));
        float detail = 1.0 - smoothstep(0.06, 0.24, aa);
        color = tint.rgb * (0.56 + diffuse * 0.40);
        color *= 1.0 - detail * (midrib * 0.23 + veins * 0.09);
        color += tint.rgb * edge * 0.07;
    }
    if (tint.a < 0.003) discard;
    fragColor = apply_fog(vec4(color, tint.a), fogDistance.x, fogDistance.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
