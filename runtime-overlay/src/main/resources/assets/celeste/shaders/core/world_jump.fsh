#version 330
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    // Original analytic annulus: no imported sprite or screen-space moving noise.
    vec2 p = texCoord * 2.0 - 1.0;
    float r = length(p);
    float aa = max(fwidth(r), 0.002);
    float inside = smoothstep(0.32, 0.50, r);
    float outside = 1.0 - smoothstep(0.81, 0.97, r);
    float body = exp(-pow((r - 0.67) / 0.16, 2.0));
    float rim = exp(-pow((r - 0.78) / max(0.022, aa), 2.0));
    float alpha = tint.a * inside * outside * (body * 0.74 + rim * 0.18);
    if (alpha < 0.003) discard;
    fragColor = vec4(mix(tint.rgb, vec3(1.0), rim * 0.22), alpha);
}
