#version 330
#moj_import <minecraft:fog.glsl>

in vec2 texCoord;
in vec4 tint;
in vec2 fogDistance;
out vec4 fragColor;

void main() {
    float mode = floor(texCoord.x * .5);
    vec2 p = vec2(texCoord.x - mode * 2.0, texCoord.y) * 2.0 - 1.0;
    float core;
    float halo;
    if (mode > 2.5) {
        float across = abs(p.x);
        // Tapered tips become very narrow: keep derivative AA from lighting their whole triangle.
        float aa = clamp(fwidth(across), .035, .18);
        core = 1.0 - smoothstep(.09, .09 + aa, across);
        halo = exp(-across * across * 5.2) * (1.0 - smoothstep(.72, 1.0, across));
    } else {
        float r2 = dot(p,p);
        halo = exp(-r2 * 4.5) * (1.0 - smoothstep(.68, 1.0, r2));
        float distance;
        if (mode > 1.5) distance = abs(p.x) * .7 + abs(p.y) * 1.75;
        else if (mode > .5) distance = sqrt(abs(p.x)) + sqrt(abs(p.y));
        else distance = length(p) * 1.55;
        float threshold = mode > .5 && mode < 1.5 ? .87 : .40;
        float aa = max(fwidth(distance), .035);
        core = 1.0 - smoothstep(threshold - aa, threshold + aa, distance);
    }
    float fog = total_fog_value(fogDistance.x, fogDistance.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float alpha = tint.a * max(core, halo * .55) * (1.0 - fog);
    if (alpha < .002) discard;
    vec3 color = mix(tint.rgb, vec3(1.0), core * .30) * (.80 + core * .40);
    fragColor = vec4(color, alpha);
}
