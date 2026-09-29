#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>

uniform sampler2D SceneDepth;
uniform sampler2D ReachableTops;
in vec4 nearPlane;
in vec4 farPlane;
flat in vec4 clipStepX;
flat in vec4 clipStepY;
out vec4 fragColor;

// ColorModulator: effective Atmosphere/local RGB and intensity.
// TextureMat: inverse actual terrain view matrix, including view bob/tilt.
// ModelOffset: camera world position minus the integer mask origin, from doubles.
// ModelViewMat[0]: mask size X/Y/Z and player feet Y relative to that origin.
// ModelViewMat[1].x: valid mask. RGBA8 atlas texel(x, y * sizeZ + z) stores
// 32 reachable top-height bits in ascending R/G/B/A bytes, at 1/32-block levels.
// RGB blending is destination-color multiplication; alpha and depth are unchanged.
bool validDepth(float depth) { return depth > 0.0 && depth < 1.0; }

float neighborDepth(ivec2 pixel, ivec2 size) {
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size))) return 1.0;
    return texelFetch(SceneDepth, pixel, 0).r;
}

vec3 neighborPosition(vec2 offset, float depth, vec2 size) {
    vec4 point = mix(nearPlane, farPlane, depth)
        + clipStepX * (offset.x / size.x) + clipStepY * (offset.y / size.y);
    return point.xyz / point.w;
}

bool upwardFace(ivec2 pixel, float centerDepth, vec3 center) {
    ivec2 size = textureSize(SceneDepth, 0);
    float left = neighborDepth(pixel + ivec2(-1,0), size);
    float right = neighborDepth(pixel + ivec2(1,0), size);
    float down = neighborDepth(pixel + ivec2(0,-1), size);
    float up = neighborDepth(pixel + ivec2(0,1), size);
    float ll = neighborDepth(pixel + ivec2(-2,0), size);
    float rr = neighborDepth(pixel + ivec2(2,0), size);
    float dd = neighborDepth(pixel + ivec2(0,-2), size);
    float uu = neighborDepth(pixel + ivec2(0,2), size);
    mat3 world = mat3(TextureMat);
    vec3 leftDelta = world * (center - neighborPosition(vec2(-1,0), left, vec2(size)));
    vec3 rightDelta = world * (neighborPosition(vec2(1,0), right, vec2(size)) - center);
    vec3 downDelta = world * (center - neighborPosition(vec2(0,-1), down, vec2(size)));
    vec3 upDelta = world * (neighborPosition(vec2(0,1), up, vec2(size)) - center);
    // At a thin top corner, the second neighbor can already be behind the block.
    // Preserve direct first-neighbor evidence of the same horizontal plane before
    // comparing extrapolations from an unrelated wall and distant background.
    vec3 horizontal = validDepth(left) && (!validDepth(right) || abs(leftDelta.y) < abs(rightDelta.y)) ? leftDelta : rightDelta;
    vec3 vertical = validDepth(down) && (!validDepth(up) || abs(downDelta.y) < abs(upDelta.y)) ? downDelta : upDelta;
    float heightError = max(.00003, center.z * center.z * .0000004);
    bool flatHorizontal = (validDepth(left) || validDepth(right)) && abs(horizontal.y) <= heightError;
    bool flatVertical = (validDepth(down) || validDepth(up)) && abs(vertical.y) <= heightError;
    if (flatHorizontal && flatVertical && cross(horizontal, vertical).y > .00000001) return true;
    // The last pixel of a top corner may only share that plane diagonally. Use
    // bounded diagonal evidence instead of treating it as its neighboring wall.
    if (flatHorizontal || flatVertical) {
        const ivec2 corners[4] = ivec2[4](ivec2(-1,1), ivec2(1,1), ivec2(-1,-1), ivec2(1,-1));
        for (int i = 0; i < 4; i++) {
            float cornerDepth = neighborDepth(pixel + corners[i], size);
            if (!validDepth(cornerDepth)) continue;
            vec3 diagonal = world * (neighborPosition(vec2(corners[i]), cornerDepth, vec2(size)) - center);
            if (abs(diagonal.y) > heightError) continue;
            if (flatHorizontal && cross(horizontal, diagonal * float(corners[i].y)).y > .00000001) return true;
            if (flatVertical && cross(diagonal * float(corners[i].x), vertical).y > .00000001) return true;
        }
    }
    // Window depth is affine on each terrain plane. Select its own one-sided
    // tangent rather than mixing stair tops with adjacent risers or overhangs.
    bool useLeft = validDepth(left) && validDepth(ll)
        && (!validDepth(right) || !validDepth(rr) || abs(2.0 * left - ll - centerDepth) < abs(2.0 * right - rr - centerDepth));
    bool useDown = validDepth(down) && validDepth(dd)
        && (!validDepth(up) || !validDepth(uu) || abs(2.0 * down - dd - centerDepth) < abs(2.0 * up - uu - centerDepth));
    if (!(useLeft ? validDepth(left) : validDepth(right)) || !(useDown ? validDepth(down) : validDepth(up))) return false;
    vec3 dx = useLeft ? center - neighborPosition(vec2(-1,0), left, vec2(size))
                      : neighborPosition(vec2(1,0), right, vec2(size)) - center;
    vec3 dy = useDown ? center - neighborPosition(vec2(0,-1), down, vec2(size))
                      : neighborPosition(vec2(0,1), up, vec2(size)) - center;
    vec3 normal = cross(world * dx, world * dy);
    float magnitude = length(normal);
    return magnitude > .00000001 && !isnan(magnitude) && !isinf(magnitude) && normal.y > magnitude * .84;
}

bool reachableTop(vec3 localPoint) {
    if (ModelViewMat[1].x < .5) return false;
    ivec3 size = ivec3(ModelViewMat[0].xyz);
    // A top exactly at an integer height belongs to the supporting cell below it;
    // fractional slab/stair heights use the same coordinate rule as the CPU mask.
    ivec3 cell = ivec3(floor(localPoint - vec3(0.0, .015, 0.0)));
    if (any(lessThanEqual(size, ivec3(0))) || any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, size))) return false;
    ivec2 texel = ivec2(cell.x, cell.y * size.z + cell.z);
    if (any(greaterThanEqual(texel, textureSize(ReachableTops, 0)))) return false;
    // Keep a stair's lower and upper surfaces separate even within the same cell;
    // standing below a low ceiling must not mark its inaccessible upper half.
    int level = clamp(int(floor((localPoint.y - float(cell.y)) * 32.0 + .5)) - 1, 0, 31);
    vec4 heights = texelFetch(ReachableTops, texel, 0);
    uint bits = uint(floor(clamp(heights[level >> 3], 0.0, 1.0) * 255.0 + .5));
    return (bits & (1u << uint(level & 7))) != 0u;
}

void main() {
    float intensity = clamp(ColorModulator.a, 0.0, 1.0);
    if (intensity <= 0.0) discard;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    // The copied depth contains terrain only. Cleared sky is exactly one.
    if (depth <= 0.0 || depth >= 1.0) discard;
    vec4 point = mix(nearPlane, farPlane, depth);
    if (abs(point.w) < .00000001) discard;
    vec3 viewPosition = point.xyz / point.w;
    vec3 relativeWorld = (TextureMat * vec4(viewPosition, 1.0)).xyz;
    if (any(isnan(relativeWorld)) || any(isinf(relativeWorld))) discard;

    // Match terrain fog before the view transform; no independent range cutoff.
    float visibility = 1.0 - total_fog_value(length(relativeWorld),
        max(length(relativeWorld.xz), abs(relativeWorld.y)),
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float amount = intensity * clamp(visibility, 0.0, 1.0);
    if (amount <= 0.0) discard;

    vec3 localPoint = relativeWorld + ModelOffset;
    if (localPoint.y > ModelViewMat[0].w + .125 && upwardFace(pixel, depth, viewPosition)
        && !reachableTop(localPoint)) discard;

    vec3 tint = clamp(ColorModulator.rgb, 0.0, 1.0);
    // Keep the strongest channel's existing lighting, tint the others, and retain
    // every surface's texture/shadow contrast. White is identity; black only dims.
    float peak = max(tint.r, max(tint.g, tint.b));
    vec3 ambient = tint / max(peak, .000001);
    fragColor = vec4(mix(vec3(1.0), ambient, amount), 1.0);
}
