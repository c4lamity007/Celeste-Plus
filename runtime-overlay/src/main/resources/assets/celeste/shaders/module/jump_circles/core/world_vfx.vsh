#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
#if ECHO_MESH
in vec3 Normal;
out vec3 viewNormal;
out vec3 viewPosition;
#endif
out vec2 texCoord;
out vec4 tint;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord = UV0;
    tint = Color * ColorModulator;
#if ECHO_MESH
    viewNormal = mat3(ModelViewMat) * Normal;
    viewPosition = view.xyz;
#endif
}
