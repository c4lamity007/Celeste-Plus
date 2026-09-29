#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 skinUv;
flat out vec4 fillColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    skinUv = UV0;
    fillColor = Color * ColorModulator;
}
