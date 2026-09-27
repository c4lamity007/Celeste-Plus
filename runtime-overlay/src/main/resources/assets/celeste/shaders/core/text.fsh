#version 330

#moj_import <celeste:common.glsl>

uniform sampler2D Sampler0;

in vec2 TexCoord;
in vec4 FragColor;
in vec2 TextPosition;

out vec4 fragColor;

float median(vec3 value) {
    return max(min(value.r, value.g), min(max(value.r, value.g), value.b));
}

void main() {
    float distance = median(texture(Sampler0, TexCoord).rgb) - 0.5 + uThickness;
    vec2 derivatives = vec2(dFdx(TexCoord.x), dFdy(TexCoord.y)) * textureSize(Sampler0, 0);
    float pixels = uRange * inversesqrt(dot(derivatives, derivatives));
    float alpha = smoothstep(-uSmoothness, uSmoothness, distance * pixels);
    vec4 textColor = FragColor;
    if (uMix > 0.5) {
        textColor = mix(uTopLeftColor, uBottomLeftColor, clamp(TextPosition.x, 0.0, 1.0));
        float position = TextPosition.x + (TextPosition.y - 0.5) * uTopRightColor.w;
        float band = clamp(1.0 - abs(position - uTopRightColor.x)
            / max(uTopRightColor.z, 0.0001), 0.0, 1.0);
        band = band * band * (3.0 - 2.0 * band) * uTopRightColor.y;
        textColor.rgb = mix(textColor.rgb, uBottomRightColor.rgb, band);
    }
    vec4 color = vec4(textColor.rgb, textColor.a * alpha);

    if (uOutline > 0.5) {
        color = mix(uOutlineColor, textColor, alpha);
        color.a *= smoothstep(-uSmoothness, uSmoothness, (distance + uOutlineThickness) * pixels);
    }

    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
