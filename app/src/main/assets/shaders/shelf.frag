#version 300 es
precision highp float;
precision highp sampler2DShadow;

// The shelves, lit like a display cabinet: a strip of warm LEDs under every shelf washes the
// comics and the wall below it, a soft room light casts the shadows (shadow map), and glossy
// covers, varnished oak and brass mirror the bright room in front of the shelves with its big
// window. Lighting is computed in linear colour and tone mapped at the end.

in vec3 vWorld;
in vec3 vNormal;
in vec3 vLocal;
in vec2 vUV;
in vec4 vLight;
flat in int vFace;   // boxes: 0 front, 1 back, 2 left, 3 right, 4 top, 5 bottom; comics: their part (ComicShape)

uniform int uMaterial;        // 0 comic, 1 wood, 2 wall, 3 label, 4 ribbon, 5 name plate
uniform mat4 uModel;
uniform mat3 uNormalMatrix;
uniform sampler2D uCover;
uniform sampler2D uBack;
uniform sampler2D uLabel;     // the label's paper, or the engraving of the name plate
uniform float uHasCover;
uniform float uHasBack;
uniform float uHasLabel;
uniform sampler2D uSpine;     // what is printed on the spine
uniform float uHasSpine;
uniform vec4 uBook;           // binding (0 stapled, 1 paperback, 2 hardcover), pages, board thickness, spine rounding
uniform float uDim;           // darkening of everything but the comic in hand
uniform float uHeld;          // 1 for the comic in hand: lit like in a photo studio
uniform float uLightsTime;    // seconds since the shelf lights were switched on
uniform vec3 uEye;
uniform vec3 uLightDir;       // towards the room light
uniform sampler2DShadow uShadow;
uniform float uShadowTexel;
uniform float uRowHeight;
uniform vec4 uPlank;          // shelf thickness, shelf depth, z of the comics' bottom edge, cornice height
uniform vec3 uWall;           // paint of the back wall, linear

out vec4 fragColor;

const float PI = 3.14159265;
const vec3 PAPER = vec3(0.80, 0.76, 0.66);
const vec3 STRIP = vec3(1.0, 0.82, 0.62);
const vec3 BRASS = vec3(0.90, 0.66, 0.30);
const vec3 FOIL = vec3(0.851, 0.698, 0.416);   // the gold of titles stamped on hardcover spines (sRGB)
const vec3 ENDPAPER = vec3(0.70, 0.64, 0.52);
const float EXPOSURE = 1.0;

const vec2 POISSON[8] = vec2[8](
    vec2(-0.613, 0.617), vec2(0.170, -0.040), vec2(-0.299, -0.792), vec2(0.645, 0.493),
    vec2(-0.651, -0.172), vec2(0.421, -0.581), vec2(-0.094, 0.416), vec2(0.958, -0.120)
);

vec3 toLinear(vec3 c) {
    return c * (c * (c * 0.305306011 + 0.682171111) + 0.012522878);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 3; i++) {
        sum += amplitude * noise(p);
        p *= 2.03;
        amplitude *= 0.5;
    }
    return sum;
}

// 1 inside |x - center| < halfWidth, fading out over [soft].
float band(float x, float center, float halfWidth, float soft) {
    return 1.0 - smoothstep(halfWidth - soft, halfWidth + soft, abs(x - center));
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// region Light

float shadowAt(vec3 n) {
    if (uHeld > 0.5) return 1.0;
    vec3 p = vLight.xyz / vLight.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float bias = 0.0005 + 0.0015 * (1.0 - max(dot(n, uLightDir), 0.0));
    float sum = 0.0;
    for (int i = 0; i < 8; i++) {
        sum += texture(uShadow, vec3(p.xy + POISSON[i] * uShadowTexel * 2.5, p.z - bias));
    }
    return sum / 8.0;
}

// Bays are the spaces between two shelves; bay r stands on shelf r (its top at y = -r * row height).
float bayOf(float y) {
    return floor((uRowHeight - uPlank.x - y) / uRowHeight);
}

// The strips switch on one after another, from the top down.
float stripOn(float bay) {
    if (bay < 0.0) return 0.0;
    float t = clamp((uLightsTime - 0.2 - bay * 0.16) / 0.5, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Light from the strip under the shelf above: a line along x near the front edge, shining
// down and a little towards the wall. Falls off slower than a lamp, like any long light.
vec3 stripLight(vec3 p, vec3 n) {
    float bay = bayOf(p.y);
    float ys = (1.0 - bay) * uRowHeight - uPlank.x - 0.004;
    vec2 d = vec2(ys - p.y, uPlank.y - 0.02 - p.z);
    float dist = max(length(d), 1e-4);
    vec3 l = vec3(0.0, d / dist);
    float emit = max(dot(-l.yz, vec2(-0.912, -0.410)), 0.0);
    float e = max(dot(n, l), 0.0) * emit / (dist + 0.10);
    return STRIP * (e * 0.38 * stripOn(bay));
}

vec3 ambient(vec3 n) {
    return mix(vec3(0.09, 0.075, 0.06), vec3(0.32, 0.30, 0.27), n.y * 0.5 + 0.5);
}

// Diffuse light reaching a surface (without its colour).
vec3 irradiance(vec3 n, float occlusion) {
    if (uHeld > 0.5) {
        // Studio: a big soft key light up left, a fill on the right, a rim from behind.
        float key = max(dot(n, normalize(vec3(-0.45, 0.50, 0.75))), 0.0);
        float fill = max(dot(n, normalize(vec3(0.75, 0.10, 0.55))), 0.0);
        float rim = max(dot(n, normalize(vec3(0.30, 0.55, -0.75))), 0.0);
        return ambient(n) * 0.8 + vec3(1.0, 0.95, 0.88) * key * 1.0 + vec3(0.85, 0.90, 1.0) * fill * 0.3
            + vec3(1.0, 0.85, 0.65) * rim * 0.5;
    }
    vec3 key = vec3(1.0, 0.94, 0.86) * max(dot(n, uLightDir), 0.0) * shadowAt(n) * 0.6;
    // The room light comes up together with the first shelf lights.
    float room = 0.45 + 0.55 * smoothstep(0.0, 0.9, uLightsTime);
    return (ambient(n) + key) * occlusion * room + stripLight(vWorld, n);
}

// The room in front of the shelves as mirrored by shiny things: a tall window on the left with
// its crossbars, a narrow studio softbox on the right, a glowing ceiling, a warm room and a dark
// floor. [blur] softens every edge, for less polished surfaces.
vec3 environment(vec3 r, float blur) {
    vec3 room = mix(vec3(0.03, 0.025, 0.02), vec3(0.30, 0.27, 0.23), smoothstep(-0.45, 0.55, r.y));
    if (r.z < 0.05) return room * 0.8;
    vec2 q = r.xy / r.z;
    float s = 0.012 + blur;
    float window = band(q.x, -0.66, 0.30, s) * band(q.y, 0.50, 0.40, s);
    float bars = max(band(q.x, -0.66, 0.012, s), band(q.y, 0.62, 0.012, s));
    vec3 sky = mix(vec3(1.3, 1.4, 1.6), vec3(2.2, 2.3, 2.5), smoothstep(0.1, 0.9, q.y));
    vec3 color = mix(room, sky, window * (1.0 - bars * 0.9));
    float softbox = band(q.x, 0.10, 0.028, s + 0.008) * band(q.y, 0.40, 0.60, s);
    color += vec3(2.6, 2.45, 2.2) * softbox;
    float ceiling = smoothstep(0.9, 1.8, q.y);
    return color + vec3(0.32, 0.29, 0.25) * ceiling;
}

float fresnel(vec3 n, vec3 v, float f0) {
    return f0 + (1.0 - f0) * pow(1.0 - max(dot(n, v), 0.0), 5.0);
}

// A clear glossy coat over [base]: reflects the room, more so at grazing angles.
vec3 coat(vec3 base, vec3 n, vec3 v, float strength, float blur) {
    float f = fresnel(n, v, 0.04) * strength;
    return base * (1.0 - f) + environment(reflect(-v, n), blur) * f;
}

// Sharp highlight of the room light on a shiny surface.
vec3 sparkle(vec3 n, vec3 v, float shininess, float amount) {
    if (uHeld > 0.5) {
        vec3 h = normalize(normalize(vec3(-0.45, 0.50, 0.75)) + v);
        return vec3(1.0, 0.96, 0.9) * pow(max(dot(n, h), 0.0), shininess) * amount * 1.5;
    }
    vec3 h = normalize(uLightDir + v);
    return vec3(1.0, 0.95, 0.88) * pow(max(dot(n, h), 0.0), shininess) * amount * shadowAt(n);
}

// endregion

// region Shapes

vec3 boxSize() {
    return vec3(length(uModel[0].xyz), length(uModel[1].xyz), length(uModel[2].xyz));
}

vec3 faceNormal(int face) {
    if (face == 0) return vec3(0.0, 0.0, 1.0);
    if (face == 1) return vec3(0.0, 0.0, -1.0);
    if (face == 2) return vec3(-1.0, 0.0, 0.0);
    if (face == 3) return vec3(1.0, 0.0, 0.0);
    if (face == 4) return vec3(0.0, 1.0, 0.0);
    return vec3(0.0, -1.0, 0.0);
}

// Rounded edges on a sharp box: within [radius] of an edge the normal turns towards the
// neighbouring face, so the edges catch the light.
vec3 bevelNormal(float radius) {
    vec3 n = faceNormal(vFace);
    vec3 dist = (0.5 - abs(vLocal)) * boxSize();
    vec3 t = clamp(1.0 - dist / radius, 0.0, 1.0) * (1.0 - abs(n));
    vec3 local = n + sign(vLocal) * tan(t * 0.785398);
    return normalize(uNormalMatrix * normalize(local));
}

// endregion

// region Materials

// A normal given in the comic's own meters, to the world.
vec3 comicNormal(vec3 n) {
    return normalize(uNormalMatrix * normalize(n * boxSize()));
}

vec3 coverEdgeColor() {
    return uHasCover > 0.5 ? toLinear(textureLod(uCover, vec2(0.97, 0.5), 6.0).rgb) : PAPER * 0.6;
}

// The outside of a cover ([side] 1 front, -1 back): the cut edges are slightly rounded and a
// paperback has a crease along the spine where it bends open.
vec3 coverNormal(float side, vec3 p, vec3 size) {
    float radius = uBook.x > 1.5 ? 0.0012 : 0.0006;
    vec2 t = clamp(1.0 - vec2(size.x * 0.5 - p.x, size.y * 0.5 - abs(p.y)) / radius, 0.0, 1.0);
    vec3 n = vec3(tan(t.x * 0.785), sign(p.y) * tan(t.y * 0.785), side);
    if (uBook.x > 0.5 && uBook.x < 1.5) {
        float x = p.x - (-size.x * 0.5 + 0.0065);
        n.x += sin(clamp(x / 0.0008, -1.0, 1.0) * PI) * 0.18 * (1.0 - smoothstep(0.0006, 0.0009, abs(x)));
    }
    return comicNormal(n);
}

vec3 comic(vec3 v) {
    vec3 size = boxSize();
    vec3 p = vLocal * size;
    float binding = uBook.x;
    float pages = max(uBook.y, 2.0);
    // Standing on the shelf: darker where the comic meets the wood.
    float occlusion = uHeld > 0.5 ? 1.0 : 1.0 - 0.45 * exp(-(p.y + size.y * 0.5) / 0.012);
    int part = vFace;

    if (part == 0 || part == 1) {
        vec3 n = coverNormal(part == 0 ? 1.0 : -1.0, p, size);
        vec3 a;
        if (part == 0) {
            a = uHasCover > 0.5 ? toLinear(texture(uCover, vUV).rgb) : PAPER * 0.6;
            // Ink on coated paper: a touch richer.
            a = max(mix(vec3(luminance(a)), a, 1.12), 0.0);
        } else if (uHasBack > 0.5) {
            a = toLinear(texture(uBack, vUV).rgb);
        } else {
            a = uHasCover > 0.5 ? toLinear(textureLod(uCover, vec2(0.5), 10.0).rgb) * 0.8 : PAPER * 0.5;
        }
        vec3 color = a * irradiance(n, occlusion) + sparkle(n, v, 300.0, 1.2);
        return coat(color, n, v, binding > 1.5 ? 0.8 : 1.0, binding > 1.5 ? 0.02 : 0.0);
    }

    vec3 n = normalize(vNormal);
    if (part == 2) {
        vec3 print = uHasSpine > 0.5 ? texture(uSpine, vUV).rgb : vec3(-1.0);
        vec3 a = print.r >= 0.0 ? toLinear(print) : coverEdgeColor();
        if (binding < 0.5 && uHasCover > 0.5) {
            // A stapled issue is one folded sheet: the covers' own pictures run round the fold.
            float edge = size.z * 0.5 / size.x;
            if (vUV.x >= 0.5) {
                a = toLinear(texture(uCover, vec2(mix(0.0, edge, (vUV.x - 0.5) * 2.0), vUV.y)).rgb);
            } else if (uHasBack > 0.5) {
                a = toLinear(texture(uBack, vec2(mix(1.0 - edge, 1.0, vUV.x * 2.0), vUV.y)).rgb);
            } else {
                a = toLinear(textureLod(uCover, vec2(0.5), 10.0).rgb) * 0.8;
            }
        }
        vec3 color = a * irradiance(n, occlusion);
        float metal = 0.0;
        if (binding < 0.5) {
            // Two staples through the fold.
            float across = abs(vUV.x - 0.5) * PI * size.z * 0.5;
            float along = min(abs(vUV.y - 0.27), abs(vUV.y - 0.73)) * size.y;
            metal = (1.0 - smoothstep(0.0003, 0.0005, across)) * (1.0 - smoothstep(0.0055, 0.0065, along));
        } else if (binding > 1.5 && print.r >= 0.0) {
            // Gold foil stamped into the cloth.
            metal = 1.0 - smoothstep(0.03, 0.09, distance(print, FOIL));
        }
        color += sparkle(n, v, 200.0, 0.8);
        color = coat(color, n, v, binding > 1.5 ? 0.6 : 1.0, binding > 1.5 ? 0.05 : 0.0);
        if (metal > 0.0) {
            vec3 tint = binding < 0.5 ? vec3(0.62, 0.63, 0.66) : BRASS;
            vec3 r = reflect(-v, n);
            vec3 shine = tint * (environment(r, 0.05) * 0.9 + irradiance(n, occlusion) * 0.3) + sparkle(n, v, 90.0, 2.5) * tint;
            color = mix(color, shine, metal);
        }
        return color;
    }

    if (part == 6) {
        // The boards' edges, wrapped in the cover.
        return coverEdgeColor() * 0.85 * irradiance(n, occlusion) + sparkle(n, v, 40.0, 0.2);
    }
    if (part == 7) {
        return ENDPAPER * irradiance(n, occlusion);
    }

    // The pages: lines of the sheets across the block, the cover's cut edge on the outside.
    float layer;
    float leaves;
    float coverBand;
    if (binding < 0.5) {
        // Folded sheets nest inside each other around the fold of a stapled comic.
        float r = size.z * 0.5;
        float cx = -size.x * 0.5 + r;
        float d = p.x < cx ? length(vec2(p.x - cx, p.z)) : abs(p.z);
        layer = d / r;
        leaves = max(pages / 4.0, 1.0);
        coverBand = step(1.0 - 0.00025 / r, layer);
    } else if (binding < 1.5) {
        layer = (p.z + size.z * 0.5) / size.z;
        leaves = pages / 2.0;
        float c = 0.0003 / size.z;
        coverBand = max(step(layer, c), step(1.0 - c, layer));
        coverBand = max(coverBand, step(p.x, -size.x * 0.5 + 0.0003));
    } else {
        float blockHalf = size.z * 0.5 - uBook.z;
        layer = (p.z + blockHalf) / (2.0 * blockHalf);
        leaves = pages / 2.0;
        coverBand = 0.0;
    }
    float sheet = layer * leaves;
    float width = fwidth(sheet);
    float d = abs(fract(sheet) - 0.5);
    float line = smoothstep(0.5 - max(width, 0.12), 0.5, d) * (1.0 - smoothstep(0.4, 1.2, width));
    // Slightly uneven sheets, a little yellower towards the outside.
    float uneven = noise(vec2(floor(sheet) * 7.0, p.y * 40.0 + p.x * 40.0));
    // Too many sheets to tell apart from afar: they still show as fine striations.
    float striation = noise(vec2(layer * 140.0, (p.x + p.y) * 2.0)) * (1.0 - line);
    vec3 a = PAPER * (1.0 - 0.28 * line) * (0.95 + 0.06 * uneven) * (0.96 + 0.08 * striation);
    a *= mix(vec3(1.0), vec3(1.0, 0.95, 0.86), smoothstep(0.6, 1.0, abs(layer * 2.0 - 1.0)) * 0.5);
    if (coverBand > 0.5) a = coverEdgeColor();
    // Pages sink a little into the shade between the covers.
    float inner = 1.0 - 0.25 * (1.0 - smoothstep(0.0, 0.15, min(layer, 1.0 - layer))) * step(0.5, binding);
    return a * irradiance(n, occlusion * inner);
}

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

vec3 wood(vec3 v) {
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

vec3 wall(vec3 v) {
    vec3 p = vWorld;
    float bay = bayOf(p.y);
    float floorY = bay < 0.0 ? uRowHeight - uPlank.x + uPlank.w : -bay * uRowHeight;
    float ceilingY = (1.0 - bay) * uRowHeight - uPlank.x;
    // Darker in the corners where the shelves meet the wall.
    float occlusion = (1.0 - 0.5 * exp(-(p.y - floorY) / 0.035));
    if (bay >= 0.0) occlusion *= 1.0 - 0.35 * exp(-(ceilingY - p.y) / 0.025);
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
    if (uMaterial == 0) {
        color = comic(v);
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
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), 1.0);
}
