#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <celeste:common.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 TexCoord;
out vec4 FragColor;
out vec2 TextPosition;

void main() {
    TexCoord = UV0;
    FragColor = Color;
    // uMix opts only drawGradient into the whole-line shader effect. Ordinary
    // text keeps its existing vertex color and never needs the inverse basis.
    TextPosition = vec2(0.0);
    if (uMix > 0.5) {
        vec2 relative = Position.xy - uShape.xy;
        TextPosition = vec2(dot(relative, uRadius.xy), dot(relative, uRadius.zw));
    }
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
