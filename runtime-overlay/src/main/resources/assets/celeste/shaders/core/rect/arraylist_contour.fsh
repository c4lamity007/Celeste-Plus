#version 330
#moj_import <celeste:common.glsl>
in vec2 Local;
in vec4 FragColor;
out vec4 fragColor;

float segment(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 0.00000001), 0.0, 1.0);
    return length(p - a - ab * t);
}

// Exact distance to a quarter circle; signs select its quadrant.
float arc(vec2 p, vec2 center, float radius, vec2 signs) {
    vec2 q = (p - center) * signs;
    if (q.x >= 0.0 && q.y >= 0.0) return abs(length(q) - radius);
    return min(length(q - vec2(radius, 0.0)), length(q - vec2(0.0, radius)));
}

void main() {
    float previous = uRadius.x, width = uRadius.y, next = uRadius.z, height = uRadius.w;
    float rt = uShape.x, rb = uShape.y;
    vec2 p = Local;
    float edge = width;
    if (rt > 0.0 && p.y < rt) {
        float curved = sqrt(max(0.0, rt * rt - (p.y - rt) * (p.y - rt)));
        edge = previous < 0.0 ? width - rt + curved : width + rt - curved;
    }
    if (rb > 0.0 && p.y > height - rb)
        edge = width - rb + sqrt(max(0.0, rb * rb - pow(p.y - height + rb, 2.0)));
    bool inside = p.x >= 0.0 && p.x <= edge
        && (previous >= 0.0 || p.y >= 0.0) && (next >= 0.0 || p.y <= height);
    float distance = min(segment(p, vec2(0.0, previous < 0.0 ? 0.0 : -height),
        vec2(0.0, next < 0.0 ? height : height * 2.0)),
        segment(p, vec2(width, rt), vec2(width, height - rb)));
    if (previous < 0.0) {
        distance = min(distance, segment(p, vec2(0.0), vec2(width - rt, 0.0)));
        distance = min(distance, arc(p, vec2(width - rt, rt), rt, vec2(1.0, -1.0)));
    } else if (previous > width) {
        distance = min(distance, segment(p, vec2(width + rt, 0.0), vec2(previous - rt, 0.0)));
        distance = min(distance, arc(p, vec2(width + rt, rt), rt, vec2(-1.0, -1.0)));
        distance = min(distance, arc(p, vec2(previous - rt, -rt), rt, vec2(1.0, 1.0)));
    }
    if (next < 0.0) {
        distance = min(distance, segment(p, vec2(0.0, height), vec2(width - rb, height)));
        distance = min(distance, arc(p, vec2(width - rb, height - rb), rb, vec2(1.0, 1.0)));
    } else if (next < width) {
        distance = min(distance, segment(p, vec2(next + rb, height), vec2(width - rb, height)));
        distance = min(distance, arc(p, vec2(width - rb, height - rb), rb, vec2(1.0, 1.0)));
        distance = min(distance, arc(p, vec2(next + rb, height + rb), rb, vec2(-1.0, -1.0)));
    }
    float signedDistance = inside ? -distance : distance;
    // Coordinate derivatives give one pixel in local space without the
    // 2x2 derivative-quad asymmetry of differentiating the piecewise SDF.
    float aa = max(max(length(dFdx(p)), length(dFdy(p))), 0.0001);
    float alpha = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, signedDistance);
    if (alpha <= 0.0) discard;
    fragColor = vec4(FragColor.rgb, FragColor.a * alpha);
}
