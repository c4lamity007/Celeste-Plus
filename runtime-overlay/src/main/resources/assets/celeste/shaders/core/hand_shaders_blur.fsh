#version 330
uniform sampler2D InSampler;
layout(std140) uniform BlurData { vec4 BlurDirection; };
in vec2 texCoord;
out vec4 fragColor;

float sampleMask(vec2 uv) {
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return 0.0;
    float alpha = texture(InSampler, uv).a;
    return BlurDirection.z > 0.5 ? smoothstep(0.005, 0.03, alpha) : alpha;
}
void main() {
    if (BlurDirection.w > 0.0) {
        // Dense Gaussian taps on the quarter-resolution sampling grid. The H
        // input is tightly prefiltered before reduction; V reads that reduction.
        // Paired linear taps avoid the old widely spaced contour repetitions.
        float sigma = BlurDirection.w;
        float mask = sampleMask(texCoord);
        float total = 1.0;
        for (int i = 1; i <= 27; i += 2) {
            float a = float(i);
            float b = a + 1.0;
            float wa = exp(-0.5 * a * a / (sigma * sigma));
            float wb = exp(-0.5 * b * b / (sigma * sigma));
            float weight = wa + wb;
            vec2 offset = BlurDirection.xy * ((a * wa + b * wb) / max(weight, 1e-20));
            mask += (sampleMask(texCoord + offset) + sampleMask(texCoord - offset)) * weight;
            total += 2.0 * weight;
        }
        mask /= total;
        fragColor = vec4(vec3(mask), mask);
        return;
    }
    // Separable Gaussian on union coverage, not per-face normals or texture colors.
    float mask = sampleMask(texCoord) * 0.200565;
    const float weights[5] = float[5](0.176998, 0.121649, 0.065114, 0.027144, 0.008812);
    for (int i = 1; i <= 5; ++i) {
        vec2 offset = BlurDirection.xy * float(i);
        mask += (sampleMask(texCoord + offset) + sampleMask(texCoord - offset)) * weights[i - 1];
    }
    fragColor = vec4(vec3(mask), mask);
}
