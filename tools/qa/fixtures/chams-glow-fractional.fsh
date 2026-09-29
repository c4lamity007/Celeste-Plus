#version 330

uniform sampler2D InSampler;
uniform sampler2D GlowSampler;
out vec4 fragColor;

vec4 sampleGlow(vec2 uv) {
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
    return texture(GlowSampler, uv);
}

vec4 sampleEdge(vec2 uv) {
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
    vec4 value = texture(InSampler, uv);
    return vec4(value.rgb * value.a, value.a);
}

void main() {
    ivec2 size = textureSize(InSampler, 0), p = ivec2(gl_FragCoord.xy);
    // Preserve the rendered body, clothing, internal joints and overlapping limbs.
    // Only genuinely empty pixels outside the captured model receive this effect.
    if (texelFetch(InSampler, p, 0).a > .001) { fragColor = vec4(0.0); return; }
    vec2 uv = gl_FragCoord.xy / vec2(size);
    float radius = clamp(float(size.y) / 1080.0, .75, 4.0);
    // A continuous Gaussian halo spanning 24 screen pixels at 1080p.
    vec2 stepSize = vec2(0.0, 2.0 * radius / float(size.y));
    const float weights[13] = float[13](0.0999083581, 0.0968345011, 0.0881688166, 0.0754147853, 0.0605974824, 0.0457413795, 0.0324354950, 0.0216066977, 0.0135211259, 0.0079486599, 0.0043896669, 0.0022773292, 0.0011098816);
    vec4 blurred = sampleGlow(uv) * weights[0];
    for (int i = 1; i <= 12; i++) {
        vec2 offset = stepSize * float(i);
        blurred += (sampleGlow(uv + offset) + sampleGlow(uv - offset)) * weights[i];
    }
    vec2 edgeStep = vec2(1.35 * radius) / vec2(size);
    vec4 edge = vec4(0.0);
    const vec2 directions[8] = vec2[8](vec2(1,0), vec2(-1,0), vec2(0,1), vec2(0,-1),
        vec2(.707107,.707107), vec2(-.707107,.707107), vec2(.707107,-.707107), vec2(-.707107,-.707107));
    for (int i = 0; i < 8; i++) {
        vec4 tap = sampleEdge(uv + edgeStep * directions[i]);
        if (tap.a > edge.a) edge = tap;
    }
    float haloAlpha = clamp(blurred.a * 2.6, 0.0, .84);
    float edgeAlpha = clamp(edge.a * 1.1, 0.0, .88);
    float alpha = min(.96, 1.0 - (1.0 - edgeAlpha) * (1.0 - haloAlpha));
    if (alpha < .0001) { fragColor = vec4(0.0); return; }
    vec4 tint = edge.a > .001 ? edge : blurred;
    vec3 color = tint.rgb / max(tint.a, .0001);
    float core = edge.a / max(edge.a + blurred.a, .0001);
    fragColor = vec4(mix(clamp(color, 0.0, 1.0), vec3(1.0), core * .34), alpha);
}
