#version 330
#moj_import <minecraft:fog.glsl>

in vec2 texCoord;
in vec4 tint;
in vec2 fogDistance;
out vec4 fragColor;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float radius = dot(p,p);
    float falloff = exp(-radius * 5.5) * (1.0 - smoothstep(0.55,1.0,radius));
    float fog = total_fog_value(fogDistance.x, fogDistance.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    vec3 color = tint.rgb;
    if (texCoord.x > 1.5) {
        // World-space beam quads carry U=2..3; radial motes retain the original branch.
        float across = abs((texCoord.x-2.0)*2.0-1.0);
        float aa = max(fwidth(across),0.075);
        float core = 1.0-smoothstep(0.055,0.055+aa,across);
        float halo = exp(-across*across*6.0)*(1.0-smoothstep(0.65,1.0,across));
        falloff = max(core,halo*0.72);
        color *= 0.70+core*1.65;
    }
    float alpha = tint.a * falloff * (1.0 - fog);
    if (alpha < 0.002) discard;
    fragColor = vec4(color, alpha);
}
