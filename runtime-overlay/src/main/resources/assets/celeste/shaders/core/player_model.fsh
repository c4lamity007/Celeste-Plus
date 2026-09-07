#version 330
#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;
layout(std140) uniform ModelMaterial { vec4 Params; };
in vec2 texCoord;
in vec4 tint;
in float sphericalDistance;
in float cylindricalDistance;
out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    if (Params.z > 0.5) uv.x = 1.0 - abs(mod(uv.x, 2.0) - 1.0);
    if (Params.w > 0.5) uv.y = 1.0 - abs(mod(uv.y, 2.0) - 1.0);
    vec4 color = texture(Sampler0, uv) * tint;
    if (Params.y > 0.5 && Params.y < 1.5 && color.a < Params.x) discard;
    if (Params.y < 1.5) color.a = 1.0;
    if (color.a < 0.003) discard;
    fragColor = apply_fog(color, sphericalDistance, cylindricalDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
