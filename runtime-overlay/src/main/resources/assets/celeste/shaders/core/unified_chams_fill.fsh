#version 330

flat in vec4 fillColor;
out vec4 fragColor;

void main() {
    // No skin UV, lighting, normals, per-face rim or blending in this pass.
    fragColor = fillColor;
}
