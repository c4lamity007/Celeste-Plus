#version 330
uniform sampler2D InSampler;
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size))) discard;
    vec4 surface = texelFetch(InSampler, pixel, 0);
    if (surface.a < 0.003) discard;
    fragColor = surface;
}
