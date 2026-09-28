#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 worldDirection;
out vec4 fragColor;

// ColorModulator = mode, brightness, bounded animation phase, star amount.
// ModelOffset.x is rain coverage; y enables sky cubes. Motion loops at phase 2pi.
float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    return vec3(hash31(p), hash31(p + 19.17), hash31(p + 43.71));
}

float skyNoise3(vec3 p) {
    vec3 cell = floor(p), f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(mix(hash31(cell), hash31(cell + vec3(1,0,0)), f.x),
                   mix(hash31(cell + vec3(0,1,0)), hash31(cell + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash31(cell + vec3(0,0,1)), hash31(cell + vec3(1,0,1)), f.x),
                   mix(hash31(cell + vec3(0,1,1)), hash31(cell + vec3(1,1,1)), f.x), f.y), f.z);
}

float cloudNoise(vec3 p) {
    // Three broad octaves: no expensive ray marching and no narrow wire/rib detail.
    float value = skyNoise3(p) * 0.57;
    value += skyNoise3(p * 2.03 + 17.1) * 0.29;
    value += skyNoise3(p * 4.11 + 31.7) * 0.14;
    return value;
}

float stars(vec3 direction, float phase) {
    vec3 p = direction * 145.0;
    vec3 cell = floor(p), local = fract(p) - 0.5;
    vec3 jitter = (hash33(cell + 7.3) - 0.5) * 0.46;
    float seed = hash31(cell + 67.2);
    float distanceToStar = length(local - jitter);
    float footprint = clamp(length(fwidth(p)), 0.015, 0.10);
    float radius = mix(0.045, 0.082, hash31(cell + 5.8));
    float core = 1.0 - smoothstep(radius, radius + footprint, distanceToStar);
    // Each glow fits wholly in its 3D cell, so no cell/spherical-UV seams appear.
    float halo = (1.0 - smoothstep(radius, 0.21, distanceToStar)) * 0.10;
    float twinkle = 0.78 + 0.22 * sin(phase * 3.0 + seed * 50.0);
    return (core + halo) * smoothstep(0.965, 0.985, seed) * twinkle;
}

vec3 aurora(vec3 d, float phase, out float cloudCover) {
    vec3 drift = vec3(sin(phase), cos(phase), sin(phase * 2.0)) * 0.32;
    float clouds = cloudNoise(d * 3.5 + drift + vec3(8,1,4));
    float fold = cloudNoise(d * 5.2 - drift * 0.6 + vec3(1,7,3));
    float lower = d.y - (0.32 + 0.13 * sin(d.x * 3.7 + sin(phase) * 0.42)
                                      + 0.07 * cos(d.z * 4.1 + cos(phase) * 0.36));
    float upper = d.y - (0.60 + 0.12 * sin(d.z * 3.4 - sin(phase) * 0.35));
    float ribbon = exp(-lower * lower / 0.021) * (0.40 + clouds * 0.70);
    float highRibbon = exp(-upper * upper / 0.035) * (0.25 + fold * 0.65);
    vec3 zenith = mix(vec3(0.016,0.027,0.092), vec3(0.042,0.026,0.115), max(d.y,0.0));
    vec3 first = mix(vec3(0.055,0.55,0.48), vec3(0.18,0.31,0.72), smoothstep(0.28,0.78,fold));
    vec3 second = mix(vec3(0.16,0.22,0.56), vec3(0.43,0.14,0.50), smoothstep(-0.8,0.8,d.x));
    cloudCover = clamp(ribbon * 0.50 + highRibbon * 0.35, 0.0, 0.88);
    return zenith + first * ribbon * 0.88 + second * highRibbon * 0.64
        + vec3(0.055,0.08,0.16) * clouds * clouds;
}

vec3 nebula(vec3 d, float phase, out float cloudCover) {
    vec3 drift = vec3(sin(phase), cos(phase), sin(phase * 2.0)) * 0.22;
    float broad = cloudNoise(d * 3.1 + drift + vec3(3,9,2));
    float detail = cloudNoise(d * 6.2 - drift + vec3(7,2,11));
    float plane = dot(d, normalize(vec3(0.32,0.80,-0.29)));
    float belt = exp(-plane * plane * 3.6);
    float mist = smoothstep(0.24,0.72,broad) * belt;
    float luminous = smoothstep(0.37,0.74,broad * 0.66 + detail * 0.34);
    vec3 velvet = mix(vec3(0.017,0.021,0.068), vec3(0.044,0.029,0.12), max(d.y,0.0));
    vec3 pigment = mix(vec3(0.10,0.27,0.60), vec3(0.46,0.16,0.49), smoothstep(0.25,0.77,detail));
    cloudCover = mist * 0.63;
    return velvet + pigment * mist * 1.02 + vec3(0.20,0.24,0.45) * luminous * belt * 0.58;
}

float annularBand(float radius, float inner, float outer, float aa) {
    return smoothstep(inner-aa,inner+aa,radius)
        * (1.0-smoothstep(outer-aa,outer+aa,radius));
}

float safeAzimuth(vec2 point) {
    // GLSL leaves atan(0,0) undefined; an invisible annulus must not spread NaN
    // through later alpha composites at the exact celestial center.
    return dot(point,point) > 0.000000000001 ? atan(point.y,point.x) : 0.0;
}

float ringDustBands(float radius, float angle, float phase, float aa) {
    // Separate translucent populations, with genuine dark gaps between them.
    // Each angular pattern advects in the plane: a uniform spinning disc would
    // appear motionless. Integer phase speeds retain the exact 240-second loop.
    float first = annularBand(radius,.139,.153,aa);
    float second = annularBand(radius,.162,.183,aa);
    float third = annularBand(radius,.191,.211,aa);
    float a = angle-phase*12.0;
    float b = angle-phase*14.0;
    float c = angle-phase*16.0;
    float clumpA = .5+.5*sin(a*3.0+.65*sin(a));
    float clumpB = .5+.5*sin(b*2.0+.85*sin(b+1.4));
    float clumpC = .5+.5*sin(c*4.0+.55*sin(c-1.0));
    // Fine structure is filtered out at small angular sizes instead of shimmering.
    float detailVisibility = 1.0-smoothstep(.001,.0045,aa);
    float grain = mix(1.0,.80+.20*sin(radius*1750.0+sin(b*5.0)),detailVisibility);
    return clamp((first*(.16+.30*clumpA)+second*(.22+.37*clumpB)
        +third*(.12+.32*clumpC))*grain,0.0,.62);
}

vec3 nebulaCelestial(vec3 d, float phase, vec3 background, vec3 cloudSky, float cover) {
    // A real sphere and tilted annulus in world-direction space: neither camera
    // position nor screen coordinates enter the geometry. Angular radii ~6/12deg.
    vec3 center = normalize(vec3(-0.30,0.46,-0.84));
    vec3 ringNormal = normalize(vec3(0.42,0.83,-0.36));
    vec3 light = normalize(vec3(-0.25,0.30,0.95));
    float radius = 0.104528;
    float facing = dot(d,center);
    float impact = length(cross(d,center));
    vec3 rayDx = dFdx(d), rayDy = dFdy(d);
    float rayAA = clamp(length(abs(rayDx)+abs(rayDy)) * 0.65,0.00008,0.006);
    // The previous planet material evaluated three noise octaves over the whole
    // screen. All celestial work now stays inside this generous angular bound.
    if (facing <= 0.0 || impact > .285) return background;
    float sphereMask = (1.0-smoothstep(radius-rayAA,radius+rayAA,impact))
        * step(0.0,facing);
    float sphereT = facing-sqrt(max(radius*radius-impact*impact,0.0));
    vec3 normal = normalize(d*sphereT-center);

    float denominator = dot(d,ringNormal);
    float safeDenominator = (denominator < 0.0 ? -1.0 : 1.0) * max(abs(denominator),0.02);
    float ringT = dot(center,ringNormal)/safeDenominator;
    vec3 ringPoint = d*ringT-center;
    float ringRadius = length(ringPoint);
    // Differentiate the plane intersection analytically from derivatives read
    // BEFORE the angular branch; no undefined fwidth inside divergent control flow.
    vec3 ringDx = ringT*(rayDx-d*dot(rayDx,ringNormal)/safeDenominator);
    vec3 ringDy = ringT*(rayDy-d*dot(rayDy,ringNormal)/safeDenominator);
    float ringAA = clamp(length(abs(ringDx)+abs(ringDy)) * 0.65,0.0001,0.006);
    vec3 ringU = normalize(cross(ringNormal,center));
    vec3 ringV = cross(ringNormal,ringU);
    float ringAngle = safeAzimuth(vec2(dot(ringPoint,ringU),dot(ringPoint,ringV)));
    float ringAlpha = ringDustBands(ringRadius,ringAngle,phase,ringAA)*step(0.02,denominator);

    // Broad gas bands and a soft terminator supply volume without contour lines.
    vec3 sphereColor = cloudSky;
    if (sphereMask > 0.0) {
        vec3 drift = vec3(sin(phase),cos(phase),sin(phase*2.0)) * 0.10;
        float gas = cloudNoise(normal*3.4+drift+vec3(4,12,7));
        float latitude = dot(normal,ringNormal)+(gas-0.5)*0.18;
        float band = exp(-pow((latitude+0.10)/0.28,2.0));
        vec3 surface = mix(vec3(0.35,0.39,0.62),vec3(0.70,0.59,0.76),gas);
        surface = mix(surface,vec3(0.34,0.24,0.48),band*0.48);
        float diffuse = pow(max(dot(normal,light),0.0),0.82);
        sphereColor = surface*(0.12+diffuse*0.96);
        float glancing = pow(1.0-clamp(dot(normal,-d),0.0,1.0),2.0);
        sphereColor += vec3(0.11,0.16,0.28)*glancing*(0.20+diffuse*0.42);
    }

    float radial = clamp((ringRadius-0.134)/0.080,0.0,1.0);
    vec3 ringColor = mix(vec3(0.46,0.40,0.65),vec3(0.77,0.63,0.76),radial);
    ringColor *= 0.78+0.22*sin(radial*3.14159265);
    // The planet also casts a broad, softly filtered shadow into the ring plane.
    float alongLight = dot(ringPoint,light);
    float shadowDistance = length(ringPoint-light*alongLight);
    float ringShadow = mix(1.0,smoothstep(radius*0.84,radius*1.12,shadowDistance),
        1.0-smoothstep(-0.018,0.018,alongLight));
    ringColor *= 0.25+0.75*ringShadow;

    // Cloud radiance lies in front of the celestial body; opaque surfaces still
    // occlude background stars, rather than becoming a transparent flat decal.
    float veil = clamp(cover*1.35,0.0,0.76);
    sphereColor = mix(sphereColor,cloudSky,veil);
    ringColor = mix(ringColor,cloudSky,veil);
    ringAlpha *= 1.0-veil*.32;
    float nearRing = step(ringT,sphereT);
    float halo = exp(-pow(max(impact-radius,0.0)/0.038,2.0))
        * (1.0-sphereMask)*step(0.0,facing)*(1.0-veil);
    vec3 sky = background+vec3(0.055,0.065,0.12)*halo;
    sky = mix(sky,ringColor,ringAlpha*(1.0-nearRing));
    sky = mix(sky,sphereColor,sphereMask);
    return mix(sky,ringColor,ringAlpha*nearRing);
}

vec3 blackHoleBackground(vec3 d, float phase, out float cloudCover) {
    // One broad noise octave is sufficient behind the focal accretion effect.
    float mist = skyNoise3(d*3.0+vec3(sin(phase),cos(phase),0.0)*.16+vec3(7,4,11));
    float belt = exp(-pow(dot(d,normalize(vec3(.2,.9,.3)))*2.8,2.0));
    cloudCover = mist*belt*.32;
    return mix(vec3(.009,.012,.034),vec3(.032,.026,.067),max(d.y,0.0))
        +vec3(.10,.066,.095)*mist*mist*belt;
}

vec3 blackHoleCelestial(vec3 d, float phase, vec3 background) {
    vec3 center = normalize(vec3(.40,.44,-.80));
    vec3 discNormal = normalize(vec3(.20,.96,.16));
    float facing = dot(d,center);
    float impact = length(cross(d,center));
    vec3 rayDx = dFdx(d), rayDy = dFdy(d);
    float aa = clamp(length(abs(rayDx)+abs(rayDy))*.70,.00008,.006);
    if (facing <= 0.0 || impact > .32) return background;
    float radius = .077;
    float core = 1.0-smoothstep(radius-aa,radius+aa,impact);
    float coreT = facing-sqrt(max(radius*radius-impact*impact,0.0));

    vec3 discU = normalize(cross(discNormal,center));
    vec3 discV = cross(discNormal,discU);
    float denominator = dot(d,discNormal);
    float safeDenominator = (denominator < 0.0 ? -1.0 : 1.0)*max(abs(denominator),.015);
    float discT = dot(center,discNormal)/safeDenominator;
    vec3 discPoint = d*discT-center;
    float r = length(discPoint);
    vec3 discDx = discT*(rayDx-d*dot(rayDx,discNormal)/safeDenominator);
    vec3 discDy = discT*(rayDy-d*dot(rayDy,discNormal)/safeDenominator);
    float radialAA = clamp(length(abs(discDx)+abs(discDy))*.70,.0001,.007);
    float disc = annularBand(r,.105,.255,radialAA)*step(.015,denominator);
    float angle = safeAzimuth(vec2(dot(discPoint,discU),dot(discPoint,discV)));
    float orbit = angle-phase*16.0;
    float arc = .5+.5*sin(orbit*3.0+.9*sin(orbit)+r*32.0);
    float structure = .63+.37*arc;
    float filament = .5+.5*sin(r*660.0+sin(orbit*4.0)*1.2);
    structure *= mix(1.0,.76+.24*filament,1.0-smoothstep(.001,.005,radialAA));
    float heat = clamp((.255-r)/.15,0.0,1.0);
    vec3 discColor = mix(vec3(.50,.12,.038),vec3(1.45,.95,.43),heat);
    // An asymmetric bright approaching side supplies volume rather than a flat orange ring.
    float approaching = .72+.28*cos(angle-.50);
    discColor *= (.62+structure*.73)*approaching;
    float discAlpha = disc*(.36+.46*structure);

    // Analytic lensing-inspired far-disk arcs: no ray marching, recursive samples,
    // or physics claim. The wide upper curl and faint lower curl frame a black core.
    vec3 viewU = normalize(cross(center,vec3(0,1,0)));
    vec3 viewV = cross(viewU,center);
    vec2 q = vec2(dot(d,viewU),dot(d,viewV))/max(facing,.01);
    float arcRadius = length(vec2(q.x,q.y*.90));
    float polar = safeAzimuth(q);
    float arcWidth = .007+aa;
    float lens = exp(-pow((arcRadius-.099)/arcWidth,2.0));
    lens *= mix(.26,1.0,smoothstep(-.018,.022,q.y));
    float arcDust = .70+.30*sin(polar*3.0-phase*48.0+.55*sin(polar));
    vec3 lensColor = mix(vec3(.70,.19,.045),vec3(1.40,.89,.38),arcDust);
    float photon = exp(-pow((impact-(radius+.0035))/(.0018+aa),2.0));
    float bloom = exp(-pow(max(impact-radius,0.0)/.045,2.0))*(1.0-core);
    vec3 sky = background+vec3(.16,.068,.023)*bloom;
    // Far gas and the curved far image sit behind the opaque shadow; the near
    // projected plane passes in front, just as in the existing planet renderer.
    float nearDisc = step(discT,coreT);
    sky = mix(sky,discColor,discAlpha*(1.0-nearDisc));
    sky += lensColor*lens*.88;
    sky = mix(sky,vec3(.0006,.0008,.002),core);
    sky += vec3(.74,.42,.14)*photon*(1.0-core*.90);
    return mix(sky,discColor,discAlpha*nearDisc);
}

vec3 sunset(vec3 d, float phase, out float cloudCover) {
    vec3 drift = vec3(sin(phase), cos(phase), sin(phase * 2.0)) * 0.17;
    float clouds = cloudNoise(d * vec3(4.5,7.0,4.5) + drift + vec3(13,3,5));
    float veil = smoothstep(0.34,0.74,clouds) * smoothstep(0.03,0.30,d.y);
    float altitude = smoothstep(0.02,0.86,max(d.y,0.0));
    vec3 sky = mix(vec3(0.90,0.38,0.23), vec3(0.095,0.075,0.24), altitude);
    vec3 sunDirection = normalize(vec3(0.55,0.23,-0.80));
    float facing = dot(d, sunDirection);
    float radiance = exp(-max(0.0,1.0-facing) * 12.0);
    float disc = smoothstep(0.9980,0.9994,facing);
    vec3 cloudColor = mix(vec3(0.72,0.28,0.27), vec3(0.24,0.14,0.31), altitude);
    sky = mix(sky, cloudColor, veil * 0.58);
    sky += vec3(0.50,0.25,0.10) * radiance * (1.0-veil*0.35);
    sky += vec3(0.65,0.54,0.32) * disc * (1.0-veil*0.66);
    cloudCover = veil;
    return sky;
}

vec3 abyss(vec3 d, float phase, out float cloudCover) {
    // Broad, deep-water-inspired strata and luminous currents: continuous volume,
    // not wire rings or a screen-space overlay. Coordinates stay celestial/world-fixed.
    vec3 drift = vec3(sin(phase*2.0),-sin(phase*3.0),cos(phase*2.0))*.24;
    float currents = cloudNoise(d*3.3 + drift + vec3(5,11,3));
    float veil = cloudNoise(d*5.0 - drift*.6 + vec3(12,3,6));
    float depth = smoothstep(0.0,.95,max(d.y,0.0));
    vec3 sky = mix(vec3(.045,.16,.22),vec3(.007,.017,.06),depth);
    float broad = exp(-pow((d.y-.34-(currents-.5)*.35)/.28,2.0));
    float light = pow(max(dot(d,normalize(vec3(-.38,.55,-.74))),0.0),6.0);
    sky += mix(vec3(.016,.10,.17),vec3(.025,.31,.33),veil)*broad*(.35+currents*.65);
    sky += vec3(.035,.13,.18)*light*(.40+veil*.65);
    cloudCover = clamp(broad*.58+light*.20,0.0,.82);
    return sky;
}

// Sparse world-fixed cubes; detailed fill and 28 lines stay inside an angular
// bound. No ray march, textures, terrain pass or additional draw.
const vec4 SKY_CUBES[32] = vec4[32](
    vec4(0.9654823, 0.2604688, 0.0000000, 0.0140000),
    vec4(-0.7075709, 0.2814063, 0.6481929, 0.0209333),
    vec4(0.0833341, 0.3023438, -0.9495492, 0.0278667),
    vec4(0.5757675, 0.3232813, 0.7509867, 0.0182667),
    vec4(-0.9245371, 0.3442188, -0.1635376, 0.0252000),
    vec4(0.7854908, 0.3651563, -0.4996650, 0.0156000),
    vec4(-0.2394745, 0.3860938, 0.8908331, 0.0225333),
    vec4(-0.4209990, 0.4070313, -0.8106080, 0.0294667),
    vec4(0.8489525, 0.4279688, 0.3100361, 0.0198667),
    vec4(-0.8259756, 0.4489063, 0.3409507, 0.0268000),
    vec4(0.3741499, 0.4698438, -0.7995366, 0.0172000),
    vec4(0.2607609, 0.4907813, 0.8313468, 0.0241333),
    vec4(-0.7433488, 0.5117188, -0.4307858, 0.0145333),
    vec4(0.8265917, 0.5326563, -0.1817239, 0.0214667),
    vec4(-0.4789602, 0.5535937, 0.6812716, 0.0284000),
    vec4(-0.1051838, 0.5745312, -0.8116959, 0.0188000),
    vec4(0.6143025, 0.5954687, 0.5177348, 0.0257333),
    vec4(-0.7867559, 0.6164063, 0.0325348, 0.0161333),
    vec4(0.5462095, 0.6373438, -0.5435514, 0.0230667),
    vec4(-0.0347716, 0.6582813, 0.7519686, 0.0300000),
    vec4(-0.4702394, 0.6792187, -0.5635040, 0.0204000),
    vec4(0.7076133, 0.7001563, 0.0952084, 0.0273333),
    vec4(-0.5687214, 0.7210938, 0.3957016, 0.0177333),
    vec4(0.1471327, 0.7420313, -0.6540196, 0.0246667),
    vec4(0.3213953, 0.7629688, 0.5608777, 0.0150667),
    vec4(-0.5915071, 0.7839063, -0.1887069, 0.0220000),
    vec4(0.5387620, 0.8048438, -0.2489217, 0.0289333),
    vec4(-0.2177348, 0.8257813, 0.5202662, 0.0193333),
    vec4(-0.1800704, 0.8467188, -0.5006416, 0.0262667),
    vec4(0.4400848, 0.8676563, 0.2312963, 0.0166667),
    vec4(-0.4435444, 0.8885938, 0.1169169, 0.0236000),
    vec4(0.2247914, 0.9095313, -0.3496023, 0.0140000));

float skyCubeEdge(vec3 ray, vec3 a, vec3 edge) {
    float along = dot(ray, edge);
    float t = clamp((along * dot(ray, a) - dot(edge, a))
        / max(dot(edge, edge) - along * along, 0.0000001), 0.0, 1.0);
    vec3 point = a + edge * t;
    return length(point - ray * max(dot(ray, point), 0.0));
}

// RGB is premultiplied translucent face color plus luminous lines/glow;
// alpha only covers the faces, so the sky remains visible through the cubes.
vec4 skyCubeMaterial(vec3 ray, vec3 origin, float s, float aa) {
    float distanceToEdge = 1.0, distanceToInner = 1.0, distanceToDiagonal = 1.0;
    for (int edge = 0; edge < 4; ++edge) {
        float a = (edge & 1) == 0 ? -s : s;
        float b = (edge & 2) == 0 ? -s : s;
        distanceToEdge = min(distanceToEdge, skyCubeEdge(ray, origin + vec3(-s,a,b), vec3(2.0*s,0,0)));
        distanceToEdge = min(distanceToEdge, skyCubeEdge(ray, origin + vec3(a,-s,b), vec3(0,2.0*s,0)));
        distanceToEdge = min(distanceToEdge, skyCubeEdge(ray, origin + vec3(a,b,-s), vec3(0,0,2.0*s)));
        float inner = s*0.42, ia = a*0.42, ib = b*0.42;
        distanceToInner = min(distanceToInner, skyCubeEdge(ray, origin+vec3(-inner,ia,ib),vec3(2.0*inner,0,0)));
        distanceToInner = min(distanceToInner, skyCubeEdge(ray, origin+vec3(ia,-inner,ib),vec3(0,2.0*inner,0)));
        distanceToInner = min(distanceToInner, skyCubeEdge(ray, origin+vec3(ia,ib,-inner),vec3(0,0,2.0*inner)));
        // Four opposite-corner diagonals cross the center and contain the eight
        // outer-to-inner corner links; do not draw those same links a second time.
        distanceToDiagonal = min(distanceToDiagonal,
            skyCubeEdge(ray, origin + vec3(-s,a,b), vec3(2.0*s,-2.0*a,-2.0*b)));
    }
    // Analytic box intersection: translucent faces rather than a filled screen quad.
    vec3 inverseRay = mix(vec3(-1.0), vec3(1.0), greaterThanEqual(ray,vec3(0.0)))
        / max(abs(ray),vec3(0.00001));
    vec3 first = (origin-vec3(s))*inverseRay, last = (origin+vec3(s))*inverseRay;
    vec3 nearPlanes = min(first,last), farPlanes = max(first,last);
    float entry = max(max(nearPlanes.x,nearPlanes.y),nearPlanes.z);
    float exit = min(min(farPlanes.x,farPlanes.y),farPlanes.z);
    float inside = smoothstep(0.0,aa*2.0,exit-max(entry,0.0));
    float faceAlpha = inside * (nearPlanes.y >= entry ? 0.20 : 0.14);
    float core = 1.0-smoothstep(0.00060,0.00060+aa,distanceToEdge);
    float glow = 1.0-smoothstep(0.0,0.014+aa,distanceToEdge);
    float diagonal = 1.0-smoothstep(0.00040,0.00040+aa*0.75,distanceToDiagonal);
    float innerGlow = 1.0-smoothstep(0.0,0.0075+aa,distanceToDiagonal);
    float innerCore = 1.0-smoothstep(0.0004,0.0004+aa*0.70,distanceToInner);
    float nestedGlow = 1.0-smoothstep(0.0,0.006+aa,distanceToInner);
    vec3 light = vec3(0.04,0.27,0.55)*faceAlpha
        + vec3(0.34,0.85,1.35)*core*1.45
        + vec3(0.025,0.32,0.92)*glow*glow*0.65
        + vec3(0.25,0.72,1.18)*innerCore*0.80
        + vec3(0.02,0.24,0.62)*nestedGlow*nestedGlow*0.22
        + vec3(0.20,0.66,1.10)*diagonal*0.45
        + vec3(0.02,0.23,0.62)*innerGlow*innerGlow*0.25;
    return vec4(light,faceAlpha);
}

vec4 skyCubes(vec3 d, float phase, float aa) {
    vec3 light = vec3(0.0);
    float opacity = 0.0;
    for (int i = 0; i < 32; ++i) {
        vec4 cube = SKY_CUBES[i];
        // Bound includes all rotated corners, the glow, and the small drift.
        if (dot(d, cube.xyz) < 0.995) continue;
        float seed = float(i) * 2.39996323;
        float wave = 0.5 + 0.5 * sin(phase * 18.0 + seed);
        float fade = smoothstep(0.10, 0.72, wave);
        if (fade <= 0.0001) continue;
        vec3 center = normalize(cube.xyz + vec3(0.004 * cos(phase * 2.0 + seed),
            0.006 * sin(phase * 3.0 + seed), 0.0));
        float yaw = phase * 3.0 + seed, tilt = 0.44 + 0.20 * sin(phase * 2.0 + seed);
        float cy = cos(yaw), sy = sin(yaw), cx = cos(tilt), sx = sin(tilt);
        mat3 turn = mat3(cy, 0.0, -sy, sy*sx, cx, cy*sx, sy*cx, -sx, cy*cx);
        vec4 material = skyCubeMaterial(transpose(turn)*d,transpose(turn)*center,cube.w,aa)*fade;
        light = light*(1.0-material.a)+material.rgb;
        opacity += material.a*(1.0-opacity);
    }
    return vec4(light,opacity);
}

void main() {
    vec3 d = normalize(worldDirection);
    float phase = ColorModulator.z;
    float cover;
    vec3 sky;
    if (ColorModulator.x < 0.5) sky = aurora(d, phase, cover);
    else if (ColorModulator.x < 1.5) sky = nebula(d, phase, cover);
    else if (ColorModulator.x < 2.5) sky = sunset(d, phase, cover);
    else if (ColorModulator.x < 3.5) sky = abyss(d, phase, cover);
    else sky = blackHoleBackground(d, phase, cover);
    vec3 cloudSky = sky;
    float starAmount = clamp(ColorModulator.w,0.0,1.0) * (1.0-cover*0.72);
    if (ColorModulator.x > 1.5 && ColorModulator.x < 2.5) starAmount *= 0.15 * smoothstep(0.35,0.85,d.y);
    if (ColorModulator.x >= 2.5) starAmount *= .42;
    sky += vec3(0.72,0.82,1.0) * stars(d,phase) * starAmount;
    if (ColorModulator.x >= 0.5 && ColorModulator.x < 1.5)
        sky = nebulaCelestial(d,phase,sky,cloudSky,cover);
    if (ColorModulator.x >= 3.5) sky = blackHoleCelestial(d,phase,sky);
    // Evaluate derivatives before any cube's divergent angular cull.
    float cubeAA = clamp(length(abs(dFdx(d)) + abs(dFdy(d))) * 0.55, 0.00018, 0.004);
    if (ModelOffset.y > 0.5) {
        vec4 cubes = skyCubes(d,phase,cubeAA);
        sky = sky*(1.0-cubes.a)+cubes.rgb;
    }
    sky = max(sky,vec3(0.0)) * clamp(ColorModulator.y,0.35,1.4);
    sky = sky / (vec3(1.0) + sky * 0.22);
    float rain = clamp(ModelOffset.x,0.0,1.0);
    sky = mix(sky, mix(FogColor.rgb,vec3(dot(sky,vec3(0.2126,0.7152,0.0722))),0.28),rain*0.76);
    // Exact native fog at/below the horizon, including Custom Fog's updated UBO.
    float dome = smoothstep(0.0,0.24,d.y);
    fragColor = vec4(clamp(mix(FogColor.rgb,sky,dome),0.0,1.0),1.0);
}
