#version 330
#moj_import <minecraft:globals.glsl>

#ifndef CHAMS_MATERIAL
#define CHAMS_MATERIAL 0
#endif

uniform sampler2D InSampler;
out vec4 fragColor;

float occupied(ivec2 p, ivec2 size) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) return 0.0;
    return step(0.001, texelFetch(InSampler, p, 0).a);
}

float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(.1031, .1030, .0973));
    q += dot(q, q.yxz + 19.19);
    return fract((q.x + q.y) * q.z);
}

float noise(vec2 p) {
    vec2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1, 0)), f.x),
               mix(hash(cell + vec2(0, 1)), hash(cell + vec2(1, 1)), f.x), f.y);
}

vec3 surface(vec2 p, vec3 tint) {
    vec3 shade = tint * .28;
    vec3 light = mix(tint, vec3(1.0), .32);
#if CHAMS_MATERIAL == 1
    // Broad, intersecting water currents with soft crests.
    float a = sin(p.x * 11.0 + sin(p.y * 6.0) * 1.8);
    float b = sin(p.y * 13.0 + cos(p.x * 7.0) * 1.4);
    float crest = pow(clamp(1.0 - abs(a + b) * .55, 0.0, 1.0), 5.0);
    float wash = .5 + .5 * sin(p.x * 3.0 + p.y * 5.0);
    return mix(shade, tint * .78, wash * .6 + .3) + light * crest * .25;
#elif CHAMS_MATERIAL == 2
    // Three smooth octaves keep the cloud material quiet at a distance.
    float cloud = noise(p * 5.0) * .58 + noise(p * 10.0 + 7.0) * .28
        + noise(p * 20.0 + 17.0) * .14;
    return mix(shade, light, smoothstep(.15, .92, cloud) * .75);
#else
    // Long, soft reflections inspired by rippled glass.
    vec2 wave = vec2(cos(p.x * 6.0 + p.y * 3.0), sin(p.y * 8.0 - p.x * 2.0));
    wave += vec2(sin(p.y * 12.0 + p.x * 4.0), cos(p.x * 9.0 - p.y * 5.0)) * .24;
    vec3 normal = normalize(vec3(wave * .45, 1.0));
    float diffuse = dot(normal, normalize(vec3(-.35, .4, 1.0))) * .5 + .5;
    float reflection = pow(max(dot(normal, normalize(vec3(.45, -.25, 1.0))), 0.0), 18.0);
    return mix(shade, tint * .82, diffuse) + light * reflection * .2;
#endif
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy), size = textureSize(InSampler, 0);
    vec4 fill = texelFetch(InSampler, p, 0);
    if (fill.a < .001) { fragColor = vec4(0.0); return; }
    int r = max(1, int(round(float(size.y) / 1080.0)));
    float neighbors = occupied(p + ivec2(r, 0), size) + occupied(p - ivec2(r, 0), size)
        + occupied(p + ivec2(0, r), size) + occupied(p - ivec2(0, r), size);
    float rim = (1.0 - neighbors * .25) * .32;
    vec2 field = gl_FragCoord.xy / float(size.y) - vec2(0.0, GameTime * 1200.0 * .16);
    vec3 body = surface(field, fill.rgb);
    body = mix(body, mix(fill.rgb, vec3(1.0), .38), rim);
    fragColor = vec4(clamp(body, 0.0, 1.0), fill.a);
}
