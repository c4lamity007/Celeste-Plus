#version 330
#moj_import <minecraft:projection.glsl>

out vec4 nearPlane;
out vec4 farPlane;

void main() {
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec2 clip = corner * 2.0 - 1.0;
    mat4 inverseProjection = inverse(ProjMat);
    nearPlane = inverseProjection * vec4(clip, -1.0, 1.0);
    farPlane = inverseProjection * vec4(clip, 1.0, 1.0);
    gl_Position = vec4(clip, 0.0, 1.0);
}
