#version 300 es

// The wall of shelves: wood, wall, labels, signs, bookmarks and the comics on them.

#include shelf_common

// region Shelves

vec3 woodAlbedo(vec3 p) {
    // Oak: long streaks along the board, a little wavy, with fine pores.
    float across = p.y * 0.6 + p.z;
    float warp = fbm(vec2(p.x * 1.4, across * 11.0));
    float streak = fract(across * 46.0 + warp * 2.4 + p.x * 0.15);
    float rings = smoothstep(0.0, 0.35, streak) * (1.0 - smoothstep(0.55, 1.0, streak));
    float pores = noise(vec2(p.x * 40.0, across * 900.0));
    vec3 light = vec3(0.45, 0.26, 0.12);
    vec3 dark = vec3(0.22, 0.11, 0.05);
    return mix(light, dark, rings * 0.55 + pores * 0.2 + warp * 0.15);
}

// The light strip itself: a frosted line near the front edge of a shelf's underside.
vec3 withStrip(vec3 color, vec3 p) {
    float bay = bayOf(p.y - 0.001);
    float strip = band(p.z, uPlank.y - 0.02, 0.006, 0.0015);
    return mix(color, STRIP * 9.0, strip * stripOn(bay));
}

// Which shelf a point of a board belongs to (0 the top one, the cornice -1).
float shelfIndex(vec3 p) {
    return floor(-p.y / uRowHeight + 0.5);
}

// Light: white lacquered boards whose front edges show the plies of birch plywood.
vec3 lightShelf(vec3 v) {
    vec3 n = bevelNormal(0.0025);
    vec3 p = vWorld;
    float occlusion = 1.0;
    if (vFace == 4) occlusion = 1.0 - 0.45 * exp(-p.z / 0.03);
    if (vFace == 5) occlusion = 0.7;
    vec3 a = vec3(0.70, 0.68, 0.64) * (0.98 + noise(p.xz * 400.0) * 0.03);
    if (vFace == 0) {
        // Plies about 4.5 mm thick, light birch with thin darker glue lines, a faint grain along them.
        float y = (vLocal.y + 0.5) * boxSize().y / 0.0045;
        float glue = 1.0 - smoothstep(0.03, 0.09 + fwidth(y), 0.5 - abs(fract(y) - 0.5));
        float ply = floor(y);
        float grain = fbm(vec2(p.x * 9.0, ply * 3.1)) * 0.12 + noise(vec2(p.x * 160.0, ply * 7.0)) * 0.05;
        vec3 birch = mix(vec3(0.56, 0.40, 0.24), vec3(0.62, 0.46, 0.29), hash(vec2(ply, 3.0)));
        a = mix(birch * (0.92 + grain), vec3(0.30, 0.19, 0.10), glue * 0.55);
    }
    vec3 color = a * irradiance(n, occlusion) + sparkle(n, v, 40.0, vFace == 0 ? 0.05 : 0.12);
    color = coat(color, n, v, vFace == 0 ? 0.15 : 0.35, 0.22);
    if (vFace == 5) color = withStrip(color, p);
    return color;
}

// Comic shop: boards of white powder-coated steel with a price rail along the front, in the
// colours of print: red, yellow and blue, one after another down the wall.
vec3 shopShelf(vec3 v) {
    vec3 n = bevelNormal(0.003);
    vec3 p = vWorld;
    float occlusion = 1.0;
    if (vFace == 4) occlusion = 1.0 - 0.45 * exp(-p.z / 0.03);
    if (vFace == 5) occlusion = 0.7;
    // Powder coat: a fine orange-peel ripple in the shine.
    vec2 peel = vec2(noise(p.xz * 900.0), noise(p.zx * 900.0 + 7.0)) - 0.5;
    vec3 rippled = normalize(n + vec3(peel.x, 0.0, peel.y) * 0.06);
    vec3 a = vec3(0.74, 0.75, 0.76);
    float gloss = 0.4;
    if (vFace == 0) {
        float k = mod(shelfIndex(p), 3.0);
        vec3 rail = k < 0.5 ? vec3(0.80, 0.04, 0.03) : (k < 1.5 ? vec3(0.92, 0.62, 0.01) : vec3(0.02, 0.16, 0.62));
        // The rail is a plastic channel with white lips above and below it.
        float h = (vLocal.y + 0.5) * boxSize().y;
        float lip = 1.0 - smoothstep(0.0025, 0.0035, min(h, boxSize().y - h));
        a = mix(rail, vec3(0.80), lip);
        gloss = 0.55;
        rippled = n;
    }
    vec3 color = a * irradiance(n, occlusion) + sparkle(rippled, v, 70.0, 0.3);
    color = coat(color, rippled, v, gloss, 0.1);
    if (vFace == 5) color = withStrip(color, p);
    return color;
}

// region Display case and glass

const vec3 NAVY = vec3(0.005, 0.007, 0.015);
const vec3 COLD = vec3(0.42, 0.70, 1.25);

// The dark blue lacquered boards of the display case ([panel]: the uprights between the cells).
// Their front edges catch the cold light in a fine bright line.
vec3 caseBoard(vec3 v, bool panel) {
    vec3 n = bevelNormal(panel ? 0.002 : 0.003);
    vec3 p = vWorld;
    vec3 size = boxSize();
    float occlusion = 1.0;
    if (vFace == 4) occlusion = 1.0 - 0.5 * exp(-p.z / 0.03);
    if (vFace == 5) occlusion = 0.6;
    if (panel && vFace != 0) occlusion = 0.85 - 0.35 * exp(-p.z / 0.03);
    vec3 a = NAVY * (0.94 + noise(vec2(p.x * 300.0 + p.z * 200.0, p.y * 40.0)) * 0.08);
    vec3 color = a * irradiance(n, occlusion) + sparkle(n, v, 90.0, 0.3);
    // Glossy on top, where the comics are mirrored; satin on the edges, so they stay deep blue.
    color = coat(color, n, v, vFace == 4 ? 0.85 : 0.22, vFace == 4 ? 0.05 : 0.2);
    float lit = stripOn(max(bayOf(p.y), 0.0));
    if (vFace == 0) {
        float d = panel ? abs(vLocal.x) * size.x : (0.5 - vLocal.y) * size.y;
        float line = 1.0 - smoothstep(0.0005, 0.0012 + fwidth(d), d);
        color += COLD * line * (panel ? 0.5 : 1.4) * (0.3 + 0.7 * lit);
        if (!panel) {
            // Along the bottom of the edge the light strip itself shows, bright, and lights the edge a little.
            float b = (vLocal.y + 0.5) * size.y;
            float led = 1.0 - smoothstep(0.0008, 0.0022 + fwidth(b), b);
            float below = stripOn(max(bayOf(p.y - 0.02), 0.0));
            color += COLD * (led * 2.6 + exp(-b / 0.008) * 0.12) * below;
        }
    }
    if (vFace == 5) color = withStrip(color, p);
    return color;
}

// Thick glass: dark and clear from above, mirroring the room (and the comics, see
// mirrorImage); light caught in it glows cyan along its cut edges.
vec3 glassBoard(vec3 v) {
    vec3 n = bevelNormal(0.006);
    vec3 p = vWorld;
    vec3 size = boxSize();
    vec3 tint = vec3(0.06, 0.16, 0.20);
    vec3 color;
    if (vFace == 0) {
        float h = (vLocal.y + 0.5) * size.y;
        float rim = exp(-min(h, size.y - h) / 0.0022);
        color = tint * (irradiance(n, 1.0) * 0.45 + 0.01) + vec3(0.32, 0.62, 0.95) * rim * 1.1;
        // Light runs through the glass from the strip under it: the edge glows more towards its bottom.
        color += COLD * smoothstep(0.2, 1.0, 1.0 - vLocal.y - 0.5) * 0.10 * stripOn(max(bayOf(p.y - 0.02), 0.0));
        color = coat(color, n, v, 0.6, 0.02) + sparkle(n, v, 160.0, 0.6);
    } else {
        float occlusion = vFace == 4 ? 1.0 - 0.45 * exp(-p.z / 0.03) : 0.7;
        color = tint * 0.1 * irradiance(n, occlusion);
        if (vFace == 4) color += vec3(0.30, 0.55, 0.85) * exp(-(uPlank.y - p.z) / 0.003) * 0.6;
        color = coat(color, n, v, 0.55, 0.015) + sparkle(n, v, 200.0, 0.8);
        if (vFace == 5) color = withStrip(color, p);
    }
    return color;
}

// An upright of the glass frame: the same thick glass, its cut edges glowing down both sides.
vec3 glassPost(vec3 v) {
    vec3 n = bevelNormal(0.008);
    vec3 size = boxSize();
    vec3 tint = vec3(0.06, 0.16, 0.20);
    vec3 color;
    if (vFace == 0) {
        float x = (vLocal.x + 0.5) * size.x;
        float rim = exp(-min(x, size.x - x) / 0.0025);
        color = tint * (irradiance(n, 1.0) * 0.45 + 0.01) + vec3(0.32, 0.62, 0.95) * rim * 1.1;
        color = coat(color, n, v, 0.6, 0.02) + sparkle(n, v, 160.0, 0.6);
    } else {
        color = coat(tint * 0.1 * irradiance(n, 0.8), n, v, 0.55, 0.015) + sparkle(n, v, 200.0, 0.8);
    }
    return color;
}

// The back of the display case: dark blue, a seam behind every upright, each cell lit from its top.
vec3 caseWall(vec3 p, float occlusion, float bay, float ceilingY) {
    // Across a cell, 0 to 1; the comic stands in its middle.
    float f = fract((p.x - uCells.y) / uCells.x);
    float dx = (f - 0.5) * uCells.x;
    float below = max(ceilingY - p.y, 0.0);
    // Deep blue, darker towards the bottom of every cell, with a fine brushed grain.
    float depth = bay >= 0.0 ? clamp(below / (uRowHeight - uPlank.x), 0.0, 1.0) : 0.5;
    vec3 a = vec3(0.0032, 0.0058, 0.0160) * (1.1 - 0.45 * depth) * (0.94 + 0.06 * fbm(p.xy * vec2(60.0, 3.0)));
    vec3 color = a * irradiance(vec3(0.0, 0.0, 1.0), occlusion);
    if (bay >= 0.0) {
        // The light of the cell: a bright wash just under the strip, and a cone over the comic
        // that widens and fades as it goes down the wall.
        float wash = exp(-below / 0.02) * (0.4 + 0.6 * exp(-dx * dx / 0.003));
        float cone = exp(-below / 0.13) * exp(-dx * dx / (0.0012 + below * 0.014));
        color += COLD * (wash * 0.42 + cone * 0.28) * stripOn(bay);
    }
    return color;
}

// Behind the glass shelves: deep blue, softly uneven, with faint upright lines and a glow under every shelf.
vec3 glassWall(vec3 p, float occlusion, float bay, float ceilingY) {
    vec3 a = mix(vec3(0.0035, 0.0055, 0.012), vec3(0.008, 0.013, 0.027), smoothstep(0.35, 0.65, fbm(p.xy * 2.5)));
    vec3 color = a * irradiance(vec3(0.0, 0.0, 1.0), occlusion);
    color += vec3(0.25, 0.42, 0.75) * smoothstep(0.66, 0.82, noise(p.xy * 6.0 + 3.0)) * 0.025;
    if (bay >= 0.0) color += COLD * (exp(-(ceilingY - p.y) / 0.04) * 0.7 + exp(-(ceilingY - p.y) / 0.16) * 0.3) * stripOn(bay) * 0.18;
    return color;
}

// The bloom of a shelf's light strip seen from in front: a soft bright band along the shelf's
// lower edge, over the middle of every comic strongest in the display case. Added to the image.
vec3 ledGlow() {
    float y = vLocal.y * boxSize().y;
    float lit = stripOn(max(bayOf(uModel[3].y - 0.004), 0.0));
    // Gone by the edges of the quad, so that no edge of it shows.
    float glow = (exp(-abs(y) / 0.004) * 0.5 + exp(-abs(y) / 0.014) * 0.16) * (1.0 - smoothstep(0.25, 0.5, abs(vLocal.y)));
    float f = fract((vWorld.x - uCells.y) / uCells.x);
    float pool = uStyle == 3 ? 0.45 + 0.55 * (0.5 - 0.5 * cos(f * 2.0 * PI)) : 1.0;
    return COLD * glow * pool * lit;
}

// A comic mirrored in the glossy shelf it stands on: kept only where the eye sees that shelf's
// top, shaded cheaply and blurred a little, fading away from the shelf.
vec4 mirrorImage() {
    vec3 d = vWorld - uEye;
    float t = (uMirrorY - uEye.y) / d.y;
    if (vWorld.y >= uMirrorY || t <= 0.0 || t >= 1.0) discard;
    vec3 q = uEye + d * t;
    if (q.z < 0.0 || q.z > uPlank.y) discard;
    vec3 a = vFace == 0 && uHasCover > 0.5 ? toLinear(textureLod(uCover, vUV, 0.8).rgb) : coverEdgeColor() * 0.7;
    // Lit about as the comic itself, a little cooler for the glaze it is seen through.
    vec3 color = a * (ambient(vec3(0.0, 1.0, 0.0)) * 0.5 + vec3(0.62)) * vec3(0.86, 0.93, 1.05);
    // More of a mirror the flatter it is seen (Fresnel), fading with the distance from the shelf.
    float grazing = 1.0 - abs(normalize(uEye - q).y);
    float strength = (uStyle == 4 ? 0.62 : 0.46) * mix(0.65, 1.1, grazing);
    return vec4(color, strength * exp(-(uMirrorY - vWorld.y) / 0.12));
}

// endregion

vec3 wood(vec3 v) {
    if (uStyle == 1) return lightShelf(v);
    if (uStyle == 2) return shopShelf(v);
    if (uStyle == 3) return caseBoard(v, false);
    if (uStyle == 4) return glassBoard(v);
    vec3 n = bevelNormal(0.004);
    vec3 p = vWorld;
    float occlusion = 1.0;
    if (vFace == 4) occlusion = 1.0 - 0.55 * exp(-p.z / 0.03);    // top, where it meets the wall
    if (vFace == 5) occlusion = 0.6;                               // underside
    vec3 color = woodAlbedo(p) * irradiance(n, occlusion) + sparkle(n, v, 60.0, 0.25);
    color = coat(color, n, v, 0.55, 0.12);
    if (vFace == 5) {
        // The light strip itself: a frosted line near the front edge of the underside.
        float bay = bayOf(p.y - 0.001);
        float strip = band(p.z, uPlank.y - 0.02, 0.006, 0.0015);
        color = mix(color, STRIP * 9.0, strip * stripOn(bay));
    }
    return color;
}

// Light: smooth painted plaster, with the faint unevenness of a trowel.
vec3 plaster(vec3 p, float occlusion) {
    float trowel = fbm(p.xy * vec2(6.0, 5.0)) * 0.05 + fbm(p.xy * 60.0) * 0.015;
    // The paint in a light, chalky shade of itself.
    vec3 a = mix(uWall, vec3(0.62, 0.59, 0.54), 0.5) * (0.97 + trowel);
    return a * irradiance(vec3(0.0, 0.0, 1.0), occlusion);
}

// Comic shop: painted pegboard, holes an inch apart, dark inside with a lit lower rim.
vec3 pegboard(vec3 p, float occlusion) {
    vec2 cell = p.xy / 0.0254;
    vec2 d = (fract(cell) - 0.5) * 0.0254;
    float r = length(d);
    float aa = fwidth(r) + 0.0002;
    float hole = 1.0 - smoothstep(0.0028 - aa, 0.0028 + aa, r);
    float rim = (1.0 - smoothstep(0.0028, 0.0036 + aa, r)) * (1.0 - hole) * smoothstep(0.0, 0.002, -d.y);
    float speck = noise(p.xy * 500.0) * 0.04 + fbm(p.xy * 8.0) * 0.04;
    vec3 a = uWall * (0.96 + speck) * (1.0 + 0.25 * rim);
    vec3 color = a * irradiance(vec3(0.0, 0.0, 1.0), occlusion);
    return mix(color, vec3(0.006, 0.005, 0.004), hole);
}

vec3 wall(vec3 v) {
    vec3 p = vWorld;
    float bay = bayOf(p.y);
    float floorY = bay < 0.0 ? uRowHeight - uPlank.x + uPlank.w : -bay * uRowHeight;
    float ceilingY = (1.0 - bay) * uRowHeight - uPlank.x;
    // Darker in the corners where the shelves meet the wall.
    float occlusion = (1.0 - 0.5 * exp(-(p.y - floorY) / 0.035));
    if (bay >= 0.0) occlusion *= 1.0 - 0.35 * exp(-(ceilingY - p.y) / 0.025);
    if (uStyle == 1) return plaster(p, occlusion);
    if (uStyle == 2) return pegboard(p, occlusion);
    if (uStyle == 3) return caseWall(p, occlusion, bay, ceilingY);
    if (uStyle == 4) return glassWall(p, occlusion, bay, ceilingY);
    // Painted boards with narrow grooves between them, like the back of a built-in bookcase.
    float board = vWorld.x / 0.085 + 0.5;
    float groove = (0.5 - abs(fract(board) - 0.5)) * 0.085;
    float edge = fwidth(board) * 0.085;
    float dark = 1.0 - smoothstep(0.0008, 0.0016 + edge, groove);
    float lit = (1.0 - smoothstep(0.0016, 0.0032 + edge, groove)) * (1.0 - dark) * (1.0 - step(0.5, fract(board)));
    float grain = fbm(p.xy * vec2(30.0, 2.0)) * 0.06 + noise(p.xy * 300.0) * 0.03;
    vec3 a = uWall * (0.94 + grain) * (1.0 - 0.45 * dark) * (1.0 + 0.25 * lit);
    vec3 n = vec3(0.0, 0.0, 1.0);
    return a * irradiance(n, occlusion);
}

vec3 brass(vec3 n, vec3 v) {
    vec3 r = reflect(-v, n);
    vec3 f = BRASS + (1.0 - BRASS) * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    return f * (environment(r, 0.06) * 0.8 + irradiance(n, 1.0) * 0.25) + sparkle(n, v, 120.0, 2.0) * BRASS;
}

vec3 label(vec3 v) {
    // A paper label in a brass holder.
    vec3 size = boxSize();
    vec2 edge = vec2(0.004, 0.003) / size.xy;
    vec2 inner = (vUV - edge) / (1.0 - 2.0 * edge);
    vec2 border = min(vUV, 1.0 - vUV) * size.xy;
    if (inner.x > 0.0 && inner.x < 1.0 && inner.y > 0.0 && inner.y < 1.0 && uStyle >= 3) {
        // A plate of dark glass with the words lit through it.
        vec3 t = uHasLabel > 0.5 ? texture(uLabel, inner).rgb : vec3(1.0);
        float ink = 1.0 - smoothstep(0.25, 0.6, luminance(t));
        vec3 n = normalize(uNormalMatrix * vec3(0.0, 0.0, 1.0));
        vec3 plate = coat(vec3(0.006, 0.009, 0.016) * irradiance(n, 1.0), n, v, 0.6, 0.03);
        return mix(plate, vec3(0.62, 0.76, 0.95), ink);
    }
    if (inner.x > 0.0 && inner.x < 1.0 && inner.y > 0.0 && inner.y < 1.0) {
        vec3 a = uHasLabel > 0.5 ? toLinear(texture(uLabel, inner).rgb) : PAPER;
        // The holder's lip throws a little shadow on the paper.
        float lip = min(inner.y, min(inner.x * size.x / size.y, 1.0 - inner.y));
        a *= 0.75 + 0.25 * smoothstep(0.0, 0.12, lip);
        return a * irradiance(vec3(0.0, 0.0, 1.0), 1.0);
    }
    // The brass frame, rounded towards its edges.
    float m = min(border.x, border.y);
    vec2 side = vUV.x < edge.x || vUV.x > 1.0 - edge.x ? vec2(sign(vUV.x - 0.5), 0.0) : vec2(0.0, sign(vUV.y - 0.5));
    float tilt = (0.5 - clamp(m / 0.004, 0.0, 1.0)) * 1.4;
    vec3 n = normalize(uNormalMatrix * normalize(vec3(side * tilt, 1.0)));
    // In the comic shop a price tag holder of glossy black plastic.
    if (uStyle == 2) return coat(vec3(0.012) * irradiance(n, 1.0), n, v, 0.6, 0.04) + sparkle(n, v, 90.0, 0.4);
    // A thin rim of cold steel round the glass plate.
    if (uStyle >= 3) return coat(vec3(0.05, 0.06, 0.08) * irradiance(n, 1.0), n, v, 0.8, 0.02) + sparkle(n, v, 120.0, 0.6);
    return brass(n, v);
}

vec3 namePlate(vec3 v) {
    // Brushed brass with engraved letters filled with black enamel.
    vec3 size = boxSize();
    vec2 border = min(vUV, 1.0 - vUV) * size.xy;
    float m = min(border.x, border.y);
    vec2 side = border.x < border.y ? vec2(sign(vUV.x - 0.5), 0.0) : vec2(0.0, sign(0.5 - vUV.y));
    float tilt = (1.0 - clamp(m / 0.003, 0.0, 1.0)) * 1.2;
    vec3 n = normalize(uNormalMatrix * normalize(vec3(side * tilt, 1.0)));
    float brushed = noise(vec2(vUV.x * 3.0, vUV.y * 400.0)) * 0.12;
    vec3 color = brass(n, v) * (0.94 + brushed);
    float ink = uHasLabel > 0.5 ? texture(uLabel, vUV).r : 0.0;
    vec3 enamel = vec3(0.012, 0.010, 0.009) + environment(reflect(-v, n), 0.02) * fresnel(n, v, 0.04);
    if (uStyle >= 3) {
        // Dark glass with the letters lit from within, and a cold glint along its rim.
        vec3 plate = coat(vec3(0.006, 0.009, 0.016) * irradiance(n, 1.0), n, v, 0.7, 0.03);
        vec3 rim = COLD * (1.0 - smoothstep(0.0, 0.0012, m)) * 0.6;
        return mix(plate + rim, vec3(0.80, 0.90, 1.05) * 1.3, ink);
    }
    if (uStyle == 2) {
        // A shop sign: yellow enamel, the letters in black, all under a glossy coat.
        vec3 painted = mix(vec3(0.92, 0.64, 0.02), vec3(0.010), ink) * irradiance(n, 1.0);
        return coat(painted, n, v, 0.5, 0.03) + sparkle(n, v, 90.0, 0.5);
    }
    return mix(color, enamel, ink);
}

// A satin bookmark: woven along its length, so its sheen runs across it in a band that slides
// along the ribbon as it bends (anisotropic, Kajiya-Kay), deep red where the light is low.
vec3 ribbon(vec3 v) {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    // Along the ribbon: where u (across) stays the same.
    vec3 dpx = dFdx(vWorld);
    vec3 dpy = dFdy(vWorld);
    vec3 t = dpx * dFdy(vUV.x) - dpy * dFdx(vUV.x);
    t = length(t) > 1e-9 ? normalize(t) : vec3(0.0, 1.0, 0.0);
    // Fine threads along it, and slightly darker selvedges.
    float threads = 0.92 + 0.08 * sin(vUV.x * 150.0);
    float edge = 1.0 - 0.25 * smoothstep(0.38, 0.5, abs(vUV.x - 0.5));
    vec3 base = vec3(0.36, 0.012, 0.03) * threads * edge;
    vec3 color = base * irradiance(n, 1.0);
    vec3 light = uHeld > 0.5 ? normalize(vec3(-0.45, 0.50, 0.75)) : uLightDir;
    vec3 h = normalize(light + v);
    float th = dot(t, h);
    float sheen = pow(sqrt(max(1.0 - th * th, 0.0)), 70.0) * max(dot(n, light), 0.0);
    float soft = pow(sqrt(max(1.0 - th * th, 0.0)), 8.0) * 0.12;
    float lit = uHeld > 0.5 ? 1.0 : shadowAt(n);
    return color + (vec3(1.0, 0.55, 0.5) * sheen * 0.9 + vec3(0.6, 0.12, 0.12) * soft) * lit;
}

// endregion

void main() {
    vec3 v = normalize(uEye - vWorld);
    vec3 color;
    float alpha = 1.0;
    if (uMirror > 0.5) {
        vec4 mirrored = mirrorImage();
        color = mirrored.rgb;
        alpha = mirrored.a;
    } else if (uMaterial == 0) {
        color = comic(v);
    } else if (uMaterial == 11) {
        color = uStyle == 3 ? caseBoard(v, true) : wood(v);
    } else if (uMaterial == 12) {
        color = uStyle == 4 ? glassPost(v) : wood(v);
    } else if (uMaterial == 13) {
        // Added straight to the picture on screen, as bloom is: no tone curve.
        fragColor = vec4(ledGlow() * (1.0 - uDim), 1.0);
        return;
    } else if (uMaterial == 1) {
        color = wood(v);
    } else if (uMaterial == 2) {
        color = wall(v);
    } else if (uMaterial == 3) {
        color = label(v);
    } else if (uMaterial == 4) {
        color = ribbon(v);
    } else {
        color = namePlate(v);
    }

    // The comic in hand stands out: the rest of the room dims and loses some colour.
    color = mix(color, vec3(luminance(color)), uDim * 0.6) * (1.0 - uDim);

    // Filmic tone curve (ACES fit), then back to sRGB.
    color *= EXPOSURE;
    color = clamp((color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14), 0.0, 1.0);
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), alpha);
}
