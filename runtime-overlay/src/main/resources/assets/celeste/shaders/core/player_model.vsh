#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
out vec2 texCoord;
out vec4 tint;
out float sphericalDistance;
out float cylindricalDistance;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord = UV0;
    vec3 n = mat3(TextureMat) * Normal;
    n *= inversesqrt(max(dot(n, n), 0.00000001));
    float diffuse = 0.65 + 0.35 * max(dot(n, normalize(vec3(0.2, 0.85, 0.4))), 0.0);
    tint = vec4(Color.rgb * ColorModulator.rgb * diffuse, Color.a * ColorModulator.a);
    sphericalDistance = fog_spherical_distance(view.xyz);
    cylindricalDistance = fog_cylindrical_distance(view.xyz);
}
