#version 330

#moj_import <celeste:common.glsl>

in vec2 FragCoord;
in vec4 FragColor;
out vec4 fragColor;

const float PI = 3.14159265359;
const float TAU = 6.28318530718;

// Smoothed interval, rather than a minimum of edge distances: even a subpixel
// final sliver fades continuously to zero instead of leaving a last bright dot.
float intervalCoverage(float value, float halfLength, float aa) {
    return smoothstep(-aa, aa, value + halfLength)
        - smoothstep(-aa, aa, value - halfLength);
}

float segmentDistance(vec2 point, vec2 start, vec2 end) {
    vec2 segment = end - start;
    float projection = clamp(dot(point - start, segment) / max(dot(segment, segment), 0.0001), 0.0, 1.0);
    return length(point - start - segment * projection);
}

void main() {
    float outerRadius = uRadius.x;
    float thickness = uRadius.y;
    float remaining = clamp(uRadius.z, 0.0, 1.0);
    float markMode = uShape.x;
    if ((remaining <= 0.0 && abs(markMode) < 0.5) || FragColor.a <= 0.0) discard;
    vec2 local = (FragCoord * 2.0 - 1.0) * uRadius.w;
    float radial = length(local);
    float middleRadius = outerRadius - thickness * 0.5;
    float distanceToRing = abs(radial - middleRadius) - thickness * 0.5;
    float aa = max(fwidth(radial) * 0.5, 0.0001);
    float ringCoverage = remaining > 0.0 ? 1.0 - smoothstep(-aa, aa, distanceToRing) : 0.0;

    if (remaining > 0.0 && remaining < 1.0) {
        // GUI Y increases downward: atan(x,-y) starts at twelve and grows clockwise.
        float angle = atan(local.x, -local.y);
        float relative = atan(sin(angle + PI * remaining), cos(angle + PI * remaining));
        float along = relative * middleRadius;
        float halfLength = PI * remaining * middleRadius;
        float period = TAU * middleRadius;
        // Include adjacent turns so the twelve-o'clock seam is seamless at full circle.
        float arc = intervalCoverage(along, halfLength, aa)
            + intervalCoverage(along - period, halfLength, aa)
            + intervalCoverage(along + period, halfLength, aa);
        ringCoverage *= clamp(arc, 0.0, 1.0);
    }

    // Status marks use analytic line-segment distances in the same pass as
    // the circle. Unlike rotated 1px quads, derivatives remain continuous at
    // every GUI scale, so the check and X do not collapse into diamonds.
    float markCoverage = 0.0;
    if (abs(markMode) >= 0.5) {
        float markDistance;
        if (markMode > 0.0) {
            float shortStroke = segmentDistance(local,
                vec2(-outerRadius * 0.48, outerRadius * 0.02),
                vec2(-outerRadius * 0.12, outerRadius * 0.38));
            float longStroke = segmentDistance(local,
                vec2(-outerRadius * 0.12, outerRadius * 0.38),
                vec2(outerRadius * 0.53, -outerRadius * 0.40));
            markDistance = min(shortStroke, longStroke);
        } else {
            vec2 extent = vec2(outerRadius * 0.38);
            markDistance = min(segmentDistance(local, -extent, extent),
                segmentDistance(local, vec2(extent.x, -extent.y), vec2(-extent.x, extent.y)));
        }
        float markHalfWidth = max(0.43, thickness * 0.43);
        float markAa = max(fwidth(markDistance) * 0.62, 0.0001);
        markCoverage = 1.0 - smoothstep(markHalfWidth - markAa, markHalfWidth + markAa, markDistance);
    }

    float coverage = max(ringCoverage, markCoverage);
    if (coverage <= 0.0) discard;

    float alpha = FragColor.a * coverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(FragColor.rgb, alpha);
}
