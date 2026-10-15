#version 300 es
precision highp float;

// The surface of the reading table, drawn once into a texture when the table or the screen
// changes (see CurlRenderer): wood, leather, cloth or stone, all made of noise.

uniform vec2 uViewport;
uniform int uKind;   // see ReadingTable: 0 graphite, 1 black, 2 walnut, 3 linen, 4 oak, 5 leather, 6 felt, 7 marble

out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec2 hash2(vec2 p) {
    return vec2(hash(p), hash(p + 19.19));
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
    for (int i = 0; i < 5; i++) {
        sum += amplitude * noise(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        amplitude *= 0.5;
    }
    return sum;
}

// Distance to the nearest of randomly placed points: the pebbles of leather.
float cells(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float nearest = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 d = o + hash2(i + o) * 0.85 - f;
            nearest = min(nearest, dot(d, d));
        }
    }
    return sqrt(nearest);
}

// A table top of wood, its grain along [g].x; [planks] boards across it (0: one solid top).
vec3 wood(vec2 g, vec3 dark, vec3 light, float planks) {
    float board = 0.0;
    float seam = 1.0;
    if (planks > 0.0) {
        board = floor(g.y * planks);
        float across = fract(g.y * planks);
        float gap = 0.006 * planks;
        seam = smoothstep(0.0, gap, across) * smoothstep(0.0, gap, 1.0 - across);
        // Every board its own piece of wood, and a joint somewhere along it.
        g.x += hash(vec2(board, 3.1)) * 9.0;
        float joint = abs(fract(g.x * 0.42 + hash(vec2(board, 7.7))) - 0.5);
        seam *= mix(0.5, 1.0, smoothstep(0.0015, 0.004, joint));
    }
    // Long figure, rings warped by it, and fine pores along the grain.
    float figure = fbm(vec2(g.x * 0.7, g.y * 7.0) + board * 7.3);
    float rings = g.y * 34.0 + figure * 11.0 + fbm(vec2(g.x * 0.25, g.y * 2.0) + board) * 6.0;
    float line = pow(abs(sin(rings)), 0.6);
    float pores = noise(vec2(g.x * 24.0, g.y * 420.0));
    vec3 color = mix(dark, light, clamp(smoothstep(0.25, 0.85, figure) * 0.75 + line * 0.35, 0.0, 1.0));
    color *= 0.9 + 0.12 * pores;
    color *= 0.93 + 0.14 * hash(vec2(board, 1.7));
    return color * mix(0.45, 1.0, seam);
}

void main() {
    vec2 frag = vec2(gl_FragCoord.x, uViewport.y - gl_FragCoord.y);
    float unit = min(uViewport.x, uViewport.y);
    vec2 p = frag / unit;
    // Wood grain and cloth run along the long side of the screen.
    vec2 g = uViewport.x >= uViewport.y ? p : vec2(p.y, p.x);
    vec3 color;

    if (uKind == 1) {
        color = vec3(0.0);
    } else if (uKind == 2) {
        // Walnut: dark chocolate with lighter streaks.
        color = wood(g * vec2(0.6, 1.0), vec3(0.13, 0.075, 0.045), vec3(0.34, 0.20, 0.11), 0.0);
    } else if (uKind == 3) {
        // Linen: a loose weave of uneven threads.
        vec2 thread = frag * 1.5708;
        float warp = sin(thread.x + noise(frag * vec2(0.02, 0.4)) * 2.0) * 0.5 + 0.5;
        float weft = sin(thread.y + noise(frag * vec2(0.4, 0.02)) * 2.0) * 0.5 + 0.5;
        float over = step(0.5, fract((floor(frag.x * 0.25) + floor(frag.y * 0.25)) * 0.5));
        float weave = mix(warp, weft, over);
        float slub = noise(vec2(frag.x * 0.015, frag.y * 0.6)) * 0.6 + noise(vec2(frag.x * 0.6, frag.y * 0.015)) * 0.4;
        color = vec3(0.80, 0.76, 0.68) * (0.9 + 0.1 * weave) * (0.93 + 0.1 * slub) * (0.97 + 0.06 * fbm(p * 3.0));
    } else if (uKind == 4) {
        // Oak boards, honey coloured.
        color = wood(g * vec2(0.5, 1.0), vec3(0.42, 0.27, 0.14), vec3(0.68, 0.48, 0.28), 3.2);
    } else if (uKind == 5) {
        // A leather desk pad: pebbled oxblood, a rolled edge and stitching round it.
        float pebble = cells(frag / 7.0);
        float mottle = fbm(p * 3.0);
        color = vec3(0.30, 0.075, 0.06) * (0.82 + 0.24 * (1.0 - smoothstep(0.2, 0.8, pebble))) * (0.86 + 0.28 * mottle);
        float edge = min(min(frag.x, uViewport.x - frag.x), min(frag.y, uViewport.y - frag.y));
        float inset = unit * 0.035;
        // The edge rolls under, catching the light, darker towards the very rim.
        float roll = smoothstep(inset * 0.55, 0.0, edge);
        color *= 1.0 - 0.45 * roll * roll;
        color += vec3(0.05, 0.02, 0.015) * smoothstep(inset * 0.62, inset * 0.45, edge) * smoothstep(0.0, inset * 0.25, edge);
        // Stitches: short slanted dashes of waxed thread, each with a little dip beside it.
        float along = (abs(frag.x - uViewport.x * 0.5) / uViewport.x < abs(frag.y - uViewport.y * 0.5) / uViewport.y) ? frag.x : frag.y;
        float onLine = 1.0 - smoothstep(1.0, 2.2, abs(edge - inset * 0.72));
        float dash = smoothstep(0.08, 0.2, fract(along / 13.0)) * smoothstep(0.78, 0.66, fract(along / 13.0));
        float dip = (1.0 - smoothstep(2.0, 4.0, abs(edge - inset * 0.72))) * (1.0 - dash);
        color *= 1.0 - 0.25 * dip;
        color = mix(color, vec3(0.78, 0.66, 0.46) * (0.85 + 0.15 * noise(frag * 0.7)), onLine * dash);
    } else if (uKind == 6) {
        // Green baize: soft, with tiny fibres and a gentle mottling.
        float fibres = hash(floor(frag * 0.9)) * 0.5 + noise(frag * 0.35) * 0.5;
        color = vec3(0.075, 0.25, 0.15) * (0.86 + 0.18 * fibres) * (0.9 + 0.2 * fbm(p * 2.5));
    } else if (uKind == 7) {
        // Black marble: veins along the crests of warped noise, sweeping across the slab,
        // a few bright ones with a soft halo, a web of faint ones, and cloudy depths.
        vec2 q = vec2(p.x * 0.9 + p.y * 0.55, p.y * 0.8 - p.x * 0.3);
        vec2 warp = vec2(fbm(q * 1.1 + 1.7), fbm(q * 1.1 + 9.2));
        float crest = 1.0 - abs(fbm(q * 0.9 + warp * 0.9) * 2.0 - 1.0);
        float fine = 1.0 - abs(fbm(q * 2.6 + warp * 1.4 + 5.0) * 2.0 - 1.0);
        // Veins gather in some parts of the slab and fade out in others.
        float gather = smoothstep(0.38, 0.62, fbm(p * 0.7 + 3.0));
        float vein = (pow(crest, 50.0) + pow(crest, 12.0) * 0.07) * (0.35 + 0.65 * gather)
            + pow(fine, 90.0) * 0.3 * gather;
        vec3 stone = vec3(0.04, 0.04, 0.045) * (0.8 + 0.5 * fbm(p * 3.0 + 11.0));
        color = mix(stone, vec3(0.80, 0.79, 0.76), clamp(vein, 0.0, 1.0));
    } else {
        // Graphite: a dark matte surface, faintly mottled and grained.
        color = vec3(0.12, 0.115, 0.11) * (0.93 + 0.1 * fbm(p * 4.0)) + (hash(frag) - 0.5) * 0.012;
    }
    fragColor = vec4(max(color, 0.0), 1.0);
}
