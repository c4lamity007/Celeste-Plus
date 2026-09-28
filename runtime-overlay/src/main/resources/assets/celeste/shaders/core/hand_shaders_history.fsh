#version 330
uniform sampler2D InSampler;
uniform sampler2D HistorySampler;
layout(std140) uniform HistoryData { vec4 HistoryEffect; };
in vec2 texCoord;
out vec4 fragColor;

void main() {
    float current = texture(InSampler, texCoord).a;
    // Linear decoding commutes with texture interpolation. Sixteen-bit coverage
    // avoids the persistent low-opacity tails of an 8-bit recursive history.
    float fog = current;
    if (HistoryEffect.x > 0.0) {
        // Positive displacement rises in framebuffer coordinates. Sampling is
        // explicitly bounded so clamp-to-edge cannot feed a permanent border.
        vec2 previousUv = texCoord - vec2(0.0, HistoryEffect.y);
        if (HistoryEffect.w > 0.0) {
            vec2 size = vec2(textureSize(InSampler, 0));
            vec2 stepUv = 1.0 / size;
            // The negative silhouette gradient points away from the item.
            vec2 gradient = vec2(texture(InSampler, texCoord + vec2(stepUv.x,0.0)).a
                - texture(InSampler, texCoord - vec2(stepUv.x,0.0)).a,
                texture(InSampler, texCoord + vec2(0.0,stepUv.y)).a
                - texture(InSampler, texCoord - vec2(0.0,stepUv.y)).a);
            vec2 outward = -gradient / max(length(gradient), 0.002);
            previousUv -= vec2(HistoryEffect.z, 0.0)
                + outward * HistoryEffect.w * vec2(size.y / size.x, 1.0);
        }
        float previous = 0.0;
        if (all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0)))) {
            previous = dot(texture(HistorySampler, previousUv).rg, vec2(256.0 / 257.0, 1.0 / 257.0));
        }
        fog = mix(current, previous, clamp(HistoryEffect.x, 0.0, 1.0));
    }
    if (fog < 1.0 / 4096.0) fog = 0.0;
    float encoded = floor(clamp(fog, 0.0, 1.0) * 65535.0 + 0.5);
    fragColor = vec4(floor(encoded / 256.0) / 255.0, mod(encoded, 256.0) / 255.0, 0.0, fog);
}
