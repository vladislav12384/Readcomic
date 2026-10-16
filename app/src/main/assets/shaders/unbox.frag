#version 300 es
#define UNBOX

// The box of new comics: cardboard, tape, the wrapping paper and its torn pieces, the glint,
// and the comics coming out of it. Built only when there is a box.

#include shelf_common

// region Unpacking

// Bumps a normal by a height field [h] (meters) given at this pixel, without tangents.
vec3 bump(vec3 n, float h) {
    vec3 dpdx = dFdx(vWorld);
    vec3 dpdy = dFdy(vWorld);
    vec3 r1 = cross(dpdy, n);
    vec3 r2 = cross(n, dpdx);
    float det = dot(dpdx, r1);
    vec3 grad = sign(det) * (dFdx(h) * r1 + dFdy(h) * r2);
    return normalize(abs(det) * n - grad);
}

// Straight creases meeting at angles, like paper that has been crumpled a little and smoothed
// out again: the edges between the cells of a scattered pattern (distance to the second
// nearest seed minus the nearest), at [scale] cells per meter.
float folds(vec2 p, float scale) {
    vec2 q = p * scale;
    vec2 cell = floor(q);
    float d1 = 8.0;
    float d2 = 8.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 c = cell + vec2(float(i), float(j));
            vec2 seed = c + vec2(hash(c), hash(c + 17.31));
            float d = distance(q, seed);
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) {
                d2 = d;
            }
        }
    }
    return d2 - d1;
}

// Fine creases of thin paper, and a little tooth.
float creases(vec2 p) {
    // Broad soft folds a few centimetres apart, a hint of finer ones, and the tooth of the paper.
    float big = 1.0 - smoothstep(0.0, 0.28, folds(p, 17.0));
    float small = 1.0 - smoothstep(0.0, 0.2, folds(p + 0.37, 46.0));
    return big * big * 0.00045 + small * small * 0.00005 + noise(p * 520.0) * 0.00001;
}

// Where a tear runs through a cell: the mask crosses this level. The same everywhere, so the
// edge of a hole and the edge of the piece torn out of it match fibre for fibre.
float tornEdge(vec2 u) {
    float fibre = noise(u * 2600.0) * 0.55 + noise(u * 760.0) * 0.45;
    return 0.5 + (fibre - 0.5) * 0.5;
}

// The colour of the paper: a dense white wrapping paper, cloudy with its fibres, through which
// what lies under it only shows as a soft shadow where it touches ([contact] 1); torn fibres
// ([rim]) whiter still.
vec3 paperColour(vec2 u, float contact, float rim, bool back) {
    float cloud = fbm(u * 150.0) * 0.6 + fbm(u * 420.0 + 3.0) * 0.4;
    vec3 milk = vec3(0.91, 0.90, 0.87) * (0.95 + 0.08 * cloud);
    vec3 under = uHasCover > 0.5 ? toLinear(textureLod(uCover, vec2(0.5), 10.0).rgb) * 0.75 : PAPER * 0.5;
    bool onCover = !back && abs(u.x) < uWrap.x * 0.5 && abs(u.y) < uWrap.y * 0.5;
    if (onCover && uHasCover > 0.5) under = toLinear(textureLod(uCover, vec2(u.x / uWrap.x + 0.5, 0.5 - u.y / uWrap.y), 2.6).rgb);
    vec3 a = mix(milk, mix(under, milk, 0.6), contact);
    return mix(a, vec3(0.98, 0.97, 0.95), rim);
}

// Thin paper is lit from both sides: light coming through from behind brightens it too.
vec3 paperLight(vec3 a, vec3 n, vec3 v) {
    vec3 color = a * (irradiance(n, 1.0) * 0.9 + irradiance(-n, 1.0) * 0.3 + 0.04);
    color += sparkle(n, v, 70.0, 0.25) + sparkle(n, v, 320.0, 0.5);
    return coat(color, n, v, 0.5, 0.12);
}

// Glassine wrapping: thin, milky, finely creased paper through which the cover shows softly;
// torn away where the mask says, with a thin white edge of fibres where it tore.
vec3 paper(vec3 v, out float alpha) {
    alpha = uAlpha;
    vec2 u = vUV;
    bool back = vFace == 1;
    bool front = !back && abs(u.x) <= uWrap.x * 0.5 && abs(u.y) <= uWrap.y * 0.5;
    // Once the front has come off in its pieces, only the paper round the edges is left here.
    if (uReleased > 0.5 && front) discard;
    float rim = 0.0;
    if (!back && uWrap.w > 0.5) {
        float m = texture(uMask, maskCoord(u)).r;
        float edge = tornEdge(u);
        if (m > edge) discard;
        rim = smoothstep(edge - 0.09, edge, m) * (0.55 + 0.45 * noise(u * 1800.0));
    }
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    // Creases are fine: seen edge on, or from inside, they should not turn into hills.
    n = bump(n, creases(u) * (gl_FrontFacing ? 1.0 : 0.4) * smoothstep(0.1, 0.5, abs(dot(n, v))));
    // The further the paper stands off the cover, the less of it shows through.
    float off = back ? 1.0 : smoothstep(0.0012, 0.009, max(vLocal.z - uPaperLift, 0.0)) * 0.6;
    return paperLight(paperColour(u, 1.0 - off, rim, back), n, v);
}

// A piece of the paper off the comic: a strip being torn, a strip in the air, or a piece of the
// rest coming off. Drawn only where its own cells were (see WrapTear.pieceMesh for the code).
vec3 strip(vec3 v, out float alpha) {
    float kind = floor(vPart / 256.0 + 0.0001);
    float rest = vPart - kind * 256.0;
    float id = floor(rest + 0.0005);
    float f = clamp((rest - id) / 0.98, 0.0, 1.0);
    vec2 u = vUV;
    vec2 mc = maskCoord(u);
    ivec2 cells = textureSize(uOwner, 0);
    ivec2 cell = clamp(ivec2(floor(mc * vec2(cells))), ivec2(0), cells - 1);
    float owner = floor(texelFetch(uOwner, cell, 0).r * 255.0 + 0.5);
    if (abs(owner - id) > 0.5) discard;
    float m = texture(uMask, mc).r;
    float edge = tornEdge(u);
    // A strip is what was torn out; a piece of the rest is what was left.
    bool leftover = kind > 1.5;
    if (leftover ? m > edge : m <= edge) discard;
    float rim = (leftover ? smoothstep(edge - 0.09, edge, m) : smoothstep(edge + 0.09, edge, m)) * (0.55 + 0.45 * noise(u * 1800.0));
    float contact = kind < 0.5 ? f : (leftover ? 1.0 - smoothstep(0.0, 0.12, f) : 0.0);
    float age = kind < 0.5 ? 0.0 : f;
    // Off the cover the thin paper lets a little of what is behind it through.
    alpha = (1.0 - smoothstep(0.72, 1.0, age)) * mix(0.96, 1.0, contact);
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    n = bump(n, creases(u) * smoothstep(0.1, 0.5, abs(dot(n, v))));
    return paperLight(paperColour(u, contact, rim, false), n, v);
}

// Corrugated cardboard: kraft liners, the wavy flutes between them showing on the cut edges,
// a print on the outside of the front, and lighter, rougher board inside.
vec3 cardboard(vec3 v) {
    vec3 size = boxSize();
    vec3 p = vLocal * size;
    int thin = size.x < size.y ? (size.x < size.z ? 0 : 2) : (size.y < size.z ? 1 : 2);
    vec3 fn = faceNormal(vFace);
    vec3 n = bevelNormal(0.0012);
    bool inside = vFace == uInnerFace;
    vec3 kraft = inside ? vec3(0.50, 0.37, 0.22) : vec3(0.43, 0.27, 0.13);
    // Paper fibres, and the flutes faintly showing through the liner as soft ridges.
    vec2 q = abs(fn.x) > 0.5 ? p.zy : (abs(fn.y) > 0.5 ? p.xz : p.xy);
    float fibres = noise(q * vec2(900.0, 260.0)) * 0.06 + fbm(q * 40.0) * 0.08;
    float flutes = sin(q.x * 2.0 * PI / 0.0042) * 0.025;
    vec3 a = kraft * (0.92 + fibres + flutes);
    if (abs(fn[thin]) < 0.5) {
        // A cut edge: two liners and the wave of the flute between them.
        float t = vLocal[thin];
        int faceAxis = abs(fn.x) > 0.5 ? 0 : (abs(fn.y) > 0.5 ? 1 : 2);
        float along = p[3 - faceAxis - thin];
        float wave = sin(along * 2.0 * PI / 0.0042) * 0.3;
        float liner = step(0.38, abs(t));
        float flute = 1.0 - smoothstep(0.05, 0.12, abs(t - wave));
        float gap = (1.0 - liner) * (1.0 - flute);
        a = mix(vec3(0.46, 0.33, 0.19), vec3(0.08, 0.05, 0.03), gap * 0.85);
    }
    // Rubbed a little darker towards the edges of every face.
    vec3 dist = (0.5 - abs(vLocal)) * size;
    float wear = min(min(fn.x == 0.0 ? dist.x : 1.0, fn.y == 0.0 ? dist.y : 1.0), fn.z == 0.0 ? dist.z : 1.0);
    a *= 0.86 + 0.14 * smoothstep(0.0, 0.012, wear);
    if (vFace == uPrintFace && uHasLabel > 0.5) {
        vec4 print = texture(uLabel, vUV);
        // Ink soaks into the board unevenly.
        float soak = 0.82 + 0.18 * noise(vUV * vec2(500.0, 180.0));
        a = mix(a, toLinear(print.rgb), print.a * soak);
    }
    float occlusion = inside ? 0.72 : 1.0;
    return a * irradiance(n, occlusion) + sparkle(n, v, 20.0, 0.03);
}

// Brown packing tape: glossy, a little see-through, stretched; cut open along the seam.
vec3 tape(vec3 v) {
    vec3 size = boxSize();
    vec3 p = vLocal * size;
    vec3 n = normalize(uNormalMatrix * faceNormal(vFace));
    float along = vLocal.z + 0.5;
    float streaks = noise(vec2(p.x * 1400.0, p.z * 30.0)) * 0.08;
    vec3 a = vec3(0.30, 0.15, 0.045) * (0.92 + streaks);
    vec3 color = a * irradiance(n, 1.0);
    if (uInnerFace >= 0 && along >= uTapeCut.x && along <= uTapeCut.y && uTapeCut.y > uTapeCut.x) {
        // The slit of the cutter along the seam, the edges lifting a hair.
        float seam = uInnerFace == 3 ? (0.5 - vLocal.x) * size.x : (vLocal.x + 0.5) * size.x;
        float slit = 1.0 - smoothstep(0.0003, 0.0009, seam);
        color = mix(color, vec3(0.02, 0.012, 0.006), slit * 0.9);
        color += sparkle(normalize(n + uNormalMatrix * vec3(uInnerFace == 3 ? 0.6 : -0.6, 0.0, 0.0)), v, 90.0, 0.8) * (1.0 - smoothstep(0.0008, 0.0022, seam));
    }
    color += sparkle(n, v, 260.0, 1.8) + sparkle(n, v, 40.0, 0.12);
    return coat(color, n, v, 1.4, 0.01);
}

// A four-pointed star of light on a gleaming cover.
vec3 star() {
    vec2 q = (vUV - 0.5) * 2.0;
    float r = length(q);
    float cross1 = exp(-abs(q.y) * 34.0) * exp(-abs(q.x) * 2.4) + exp(-abs(q.x) * 34.0) * exp(-abs(q.y) * 2.4);
    vec2 d = vec2(q.x + q.y, q.x - q.y) * 0.7071;
    float cross2 = exp(-abs(d.y) * 40.0) * exp(-abs(d.x) * 5.0) + exp(-abs(d.x) * 40.0) * exp(-abs(d.y) * 5.0);
    float glow = exp(-r * 7.0);
    float pulse = sin(PI * clamp(uShine * 1.5, 0.0, 1.0));
    return vec3(1.0, 0.95, 0.84) * (cross1 + 0.4 * cross2 + glow) * pulse * 1.4;
}

// endregion

void main() {
    vec3 v = normalize(uEye - vWorld);
    vec3 color;
    float alpha = 1.0;
    if (uMaterial == 0) {
        color = comic(v);
    } else if (uMaterial == 6) {
        color = cardboard(v);
    } else if (uMaterial == 7) {
        color = tape(v);
    } else if (uMaterial == 8) {
        color = paper(v, alpha);
    } else if (uMaterial == 9) {
        color = strip(v, alpha);
    } else {
        // Light itself, added on top of what is there.
        fragColor = vec4(star(), 1.0);
        return;
    }

    // The comic in hand stands out: the rest of the room dims and loses some colour.
    color = mix(color, vec3(luminance(color)), uDim * 0.6) * (1.0 - uDim);

    // Filmic tone curve (ACES fit), then back to sRGB.
    color *= EXPOSURE;
    color = clamp((color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14), 0.0, 1.0);
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), alpha);
}
