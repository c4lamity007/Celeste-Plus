#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 texCoord;
out vec4 tint;
out vec2 fogDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    tint = Color * ColorModulator;
    fogDistance = vec2(length(Position), max(length(Position.xz), abs(Position.y)));
}
