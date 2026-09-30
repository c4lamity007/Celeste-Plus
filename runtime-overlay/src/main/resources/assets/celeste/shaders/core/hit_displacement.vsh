#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

out vec2 texCoord;
flat out vec4 projectedLenses[4];

void main() {
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    texCoord = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
    // The three fullscreen vertices share these uniforms. Project each pulse
    // here instead of repeating identical matrix multiplies for every pixel.
    // Flat outputs preserve the exact current scene projection and hit center.
    for (int i = 0; i < 4; ++i) {
        projectedLenses[i] = vec4(0.0);
        vec4 lens = ModelViewMat[i];
        float strength = ColorModulator[i];
        if (lens.w <= 0.00001 || strength <= 0.0) continue;
        vec4 clip = ProjMat * vec4(lens.xyz, 1.0);
        if (clip.w <= 0.00001 || abs(clip.z) > clip.w * 1.01) continue;
        projectedLenses[i] = vec4(clip.xy / clip.w * 0.5 + 0.5, lens.w, strength);
    }
}
