#version 330

uniform sampler2D InSampler;
out vec4 fragColor;

vec4 sampleMask(vec2 uv) {
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
    vec4 value = texture(InSampler, uv);
    return vec4(value.rgb * value.a, value.a);
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    vec2 uv = gl_FragCoord.xy / vec2((size + 1) / 2);
    float radius = clamp(float(size.y) / 1080.0, .75, 4.0);
    // A continuous Gaussian halo spanning 24 screen pixels at 1080p.
    vec2 stepSize = vec2(2.0 * radius / float(size.x), 0.0);
    const float weights[13] = float[13](0.0999083581, 0.0968345011, 0.0881688166, 0.0754147853, 0.0605974824, 0.0457413795, 0.0324354950, 0.0216066977, 0.0135211259, 0.0079486599, 0.0043896669, 0.0022773292, 0.0011098816);
    vec4 blurred = sampleMask(uv) * weights[0];
    for (int i = 1; i <= 12; i++) {
        vec2 offset = stepSize * float(i);
        blurred += (sampleMask(uv + offset) + sampleMask(uv - offset)) * weights[i];
    }
    fragColor = blurred;
}
