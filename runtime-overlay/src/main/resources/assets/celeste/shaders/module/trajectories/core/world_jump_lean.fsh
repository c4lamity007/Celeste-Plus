#version 330
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float r = length(p);
    float aa = max(fwidth(r), 0.002);
    float band = smoothstep(0.71 - aa, 0.74 + aa, r) * (1.0 - smoothstep(0.83 - aa, 0.86 + aa, r));
    float halo = exp(-pow((r - 0.785) / 0.09, 2.0)) * 0.13;
    float alpha = tint.a * min(1.0, band * 0.87 + halo) * (1.0 - smoothstep(0.94, 1.0, r));
    if (alpha < 0.003) discard;
    fragColor = vec4(tint.rgb, alpha);
}
