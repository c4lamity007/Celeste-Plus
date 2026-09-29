#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>

uniform sampler2D SceneDepth;
in vec2 texCoord;
in vec4 nearPlane;
in vec4 farPlane;
flat in vec4 clipStepX;
flat in vec4 clipStepY;
out vec4 fragColor;

// ModelViewMat[0]: wrapped animation radians, range, 1 / pattern size, mode.
// ModelOffset.xz: positive modulo(camera world XZ / pattern size, 1024), from doubles.
// TextureMat: inverse actual terrain view matrix, including view bob/tilt.
// ColorModulator: effective Atmosphere/local RGB and intensity. No scene-color copy.
bool validDepth(float depth) { return depth > 0.0 && depth < .999999; }

vec3 viewAt(vec2 offset, float depth, vec2 size) {
    vec4 point = mix(nearPlane, farPlane, depth)
        + clipStepX * (offset.x / size.x) + clipStepY * (offset.y / size.y);
    return point.xyz / point.w;
}

float hash21(vec2 cell) {
    // Every octave repeats in a divisor of 1024 pattern units, including the warp.
    // Wrapping the scaled camera origin therefore never slides or jumps the field.
    cell = mod(cell, 64.0);
    vec3 p = fract(vec3(cell.xyx) * .1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float groundNoise(vec2 point) {
    vec2 cell = floor(point), f = fract(point);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(cell), hash21(cell + vec2(1,0)), f.x),
               mix(hash21(cell + vec2(0,1)), hash21(cell + vec2(1,1)), f.x), f.y);
}

vec2 groundMaterial(vec2 p, float phase, float mode) {
    vec2 drift = vec2(sin(phase), cos(phase)) * 1.8;
    vec2 warp = vec2(groundNoise(p / 16.0 + drift * .11),
                     groundNoise(p / 16.0 + vec2(17.0, 29.0) - drift * .09)) - .5;
    float field = groundNoise((p + warp * 5.0) / 8.0 + drift * .06);
    float detail = groundNoise((p - warp * 2.0) / 4.0 - drift * .08);
    float pool = smoothstep(.24, .82, field);
    float contour = abs(sin((field * 2.6 + detail * .16 + sin(phase) * .12) * 3.14159265));
    float aa = min(.16, max(.018, fwidth(contour)));
    float ribbon = 1.0 - smoothstep(.08, .21 + aa, contour);
    if (mode > .5) {
        // Connected soft veins, with a wider dim halo instead of a hard grid.
        float halo = 1.0 - smoothstep(.12, .52, contour);
        return vec2(.035 + halo * .22 + ribbon * .74, .50 + ribbon * .45);
    }
    return vec2(.055 + pool * .35 + ribbon * .52, .48 + pool * .25 + ribbon * .27);
}

void main() {
    float intensity = clamp(ColorModulator.a, 0.0, 1.0);
    if (intensity <= 0.0) discard;
    ivec2 size = textureSize(SceneDepth, 0), pixel = ivec2(gl_FragCoord.xy);
    // A two-pixel margin prevents clamped depth from inventing a border normal.
    if (any(lessThan(pixel, ivec2(2))) || any(greaterThanEqual(pixel, size - 2))) discard;
    float centerDepth = texelFetch(SceneDepth, pixel, 0).r;
    if (!validDepth(centerDepth)) discard;
    vec3 viewPosition = viewAt(vec2(0), centerDepth, vec2(size));
    float distance = length(viewPosition), range = clamp(ModelViewMat[0].y, 4.0, 96.0);
    if (any(isnan(viewPosition)) || any(isinf(viewPosition)) || distance >= range || distance < .03) discard;

    // Choose each tangent from the neighbor pair belonging to the center plane.
    // Window depth is affine across a plane, so extrapolation rejects wall/step
    // discontinuities without averaging the two surfaces into a glowing edge.
    float left = texelFetch(SceneDepth, pixel + ivec2(-1,0), 0).r;
    float right = texelFetch(SceneDepth, pixel + ivec2(1,0), 0).r;
    float down = texelFetch(SceneDepth, pixel + ivec2(0,-1), 0).r;
    float up = texelFetch(SceneDepth, pixel + ivec2(0,1), 0).r;
    float ll = texelFetch(SceneDepth, pixel + ivec2(-2,0), 0).r;
    float rr = texelFetch(SceneDepth, pixel + ivec2(2,0), 0).r;
    float dd = texelFetch(SceneDepth, pixel + ivec2(0,-2), 0).r;
    float uu = texelFetch(SceneDepth, pixel + ivec2(0,2), 0).r;
    bool useLeft = validDepth(left) && validDepth(ll)
        && (!validDepth(right) || !validDepth(rr) || abs(2.0 * left - ll - centerDepth) < abs(2.0 * right - rr - centerDepth));
    bool useDown = validDepth(down) && validDepth(dd)
        && (!validDepth(up) || !validDepth(uu) || abs(2.0 * down - dd - centerDepth) < abs(2.0 * up - uu - centerDepth));
    if (!(useLeft ? validDepth(left) : validDepth(right)) || !(useDown ? validDepth(down) : validDepth(up))) discard;
    vec3 dx = useLeft ? viewPosition - viewAt(vec2(-1,0), left, vec2(size))
                      : viewAt(vec2(1,0), right, vec2(size)) - viewPosition;
    vec3 dy = useDown ? viewPosition - viewAt(vec2(0,-1), down, vec2(size))
                      : viewAt(vec2(0,1), up, vec2(size)) - viewPosition;
    vec3 worldDx = mat3(TextureMat) * dx, worldDy = mat3(TextureMat) * dy;
    vec3 normal = cross(worldDx, worldDy);
    float normalLength = length(normal);
    if (normalLength < .00000001 || isnan(normalLength) || isinf(normalLength)) discard;
    // Real upward faces only: stair treads and slab tops pass; walls/undersides do not.
    float facing = smoothstep(.84, .98, normal.y / normalLength);
    if (facing <= 0.0) discard;
    vec3 relativeWorld = (TextureMat * vec4(viewPosition, 1.0)).xyz;
    // Terrain computes fog before its view transform. Keep the same world-relative
    // distances so camera pitch/roll/bob cannot move the cylindrical fog boundary.
    float visibility = 1.0 - total_fog_value(length(relativeWorld), max(length(relativeWorld.xz), abs(relativeWorld.y)),
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float fade = 1.0 - smoothstep(range * .72, range, distance);
    if (visibility * fade <= .00001) discard;

    vec2 point = mod(ModelOffset.xz + relativeWorld.xz * ModelViewMat[0].z, 1024.0);
    vec2 material = groundMaterial(point, ModelViewMat[0].x, ModelViewMat[0].w);
    float alpha = min(.72, intensity * material.x) * facing * visibility * fade;
    // Normal alpha blending keeps the original terrain grain visible everywhere.
    fragColor = vec4(clamp(ColorModulator.rgb * material.y, 0.0, 1.0), alpha);
}
