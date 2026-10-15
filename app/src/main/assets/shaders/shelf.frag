#version 300 es

// The wall of shelves: oak shelves, the wall, names engraved and lettered, bookmarks and the
// comics standing on the shelves.

#include shelf_common

// region Shelves

// The rail along the front of a shelf: as deep as ShelfController.RAIL_DEPTH.
const float RAIL_DEPTH = 0.012;

vec3 woodAlbedo(vec3 p) {
    // Pale oak, the grain running along the shelf: long streaks, gently wavy, with fine lines
    // and pores between them.
    float across = p.y * 0.5 + p.z;
    float warp = fbm(vec2(p.x * 1.2, across * 9.0));
    float streak = fract(across * 70.0 + warp * 2.2 + p.x * 0.06);
    float rings = smoothstep(0.0, 0.25, streak) * (1.0 - smoothstep(0.45, 1.0, streak));
    float lines = smoothstep(0.55, 0.9, noise(vec2(p.x * 5.0, across * 2600.0 + warp * 40.0)));
    float pores = noise(vec2(p.x * 90.0, across * 1800.0));
    float fleck = smoothstep(0.7, 0.92, noise(vec2(p.x * 9.0, across * 160.0)));
    vec3 light = vec3(0.60, 0.43, 0.26);
    vec3 dark = vec3(0.37, 0.24, 0.13);
    return mix(light, dark, rings * 0.5 + lines * 0.22 + pores * 0.12 + warp * 0.12 + fleck * 0.12);
}

// Oiled oak: soft, satin, only a faint sheen of the room on it.
vec3 woodShade(vec3 p, vec3 n, vec3 v, float occlusion) {
    vec3 color = woodAlbedo(p) * irradiance(n, occlusion) + sparkle(n, v, 24.0, 0.06);
    return coat(color, n, v, 0.22, 0.3);
}

vec3 wood(vec3 v) {
    vec3 n = bevelNormal(0.0035);
    vec3 p = vWorld;
    float inner = uPlank.y - RAIL_DEPTH;
    float floorY = -bayOf(p.y + 0.001) * uRowHeight;
    float occlusion = 1.0;
    if (vFace == 4 && p.z < inner - 0.0005) {
        // The top of a board: in shade where it meets the wall and at the foot of the rail.
        occlusion = (1.0 - 0.5 * exp(-p.z / 0.018)) * (1.0 - 0.45 * exp(-(inner - p.z) / 0.007));
    }
    // The inside of the rail, facing the comics: darker down by the board.
    if (vFace == 1 && p.z > inner - 0.0005) occlusion = 1.0 - 0.45 * exp(-(p.y - floorY) / 0.006);
    if (vFace == 5) occlusion = 0.9;                               // underside
    vec3 color = woodShade(p, n, v, occlusion);
    // Underneath, the light thrown back by the wall and the comics below.
    if (vFace == 5) color += woodAlbedo(p) * STRIP * 0.12 * stripOn(bayOf(p.y - 0.001));
    if (vFace == 5) {
        // The light: a slim aluminium channel let into the underside just behind the rail, its
        // opal cover glowing evenly, the wood around it lit by it.
        float bay = bayOf(p.y - 0.001);
        float z = inner - 0.008;
        float on = stripOn(bay);
        float channel = band(p.z, z, 0.0055, 0.0004);
        float opal = band(p.z, z, 0.004, 0.0012);
        vec3 metal = vec3(0.30, 0.30, 0.31) * irradiance(n, 0.8);
        color = mix(color, metal, channel);
        color = mix(color, STRIP * 1.15, opal * on);
        color += STRIP * exp(-abs(p.z - z) / 0.01) * 0.12 * on * (1.0 - channel);
    }
    return color;
}

vec3 wall(vec3 v) {
    vec3 p = vWorld;
    float bay = bayOf(p.y);
    float floorY = bay < 0.0 ? uRowHeight - uPlank.x + uPlank.w : -bay * uRowHeight;
    float ceilingY = (1.0 - bay) * uRowHeight - uPlank.x;
    // Darker in the corners where the shelves meet the wall.
    float occlusion = (1.0 - 0.5 * exp(-(p.y - floorY) / 0.03));
    if (bay >= 0.0) occlusion *= 1.0 - 0.3 * exp(-(ceilingY - p.y) / 0.02);
    // Smooth limewash: soft clouds of slightly lighter and darker paint, a faint grain.
    float clouds = fbm(p.xy * 3.2) - 0.5;
    float strokes = fbm(vec2(p.x * 7.0 + p.y * 3.0, p.y * 9.0 - p.x * 2.0)) - 0.5;
    float grain = noise(p.xy * 420.0) - 0.5;
    vec3 a = uWall * (1.0 + clouds * 0.12 + strokes * 0.06 + grain * 0.035);
    return a * irradiance(vec3(0.0, 0.0, 1.0), occlusion);
}

vec3 label(vec3 v) {
    // The name engraved in the front of the rail: the oak around it as everywhere on the rail,
    // the letters cut in and darkened, their upper edges in the shade of the cut.
    vec3 n = normalize(uNormalMatrix * vec3(0.0, 0.0, 1.0));
    // The grain exactly as on the rail, which this lies a hair in front of.
    vec3 color = woodShade(vec3(vWorld.xy, uPlank.y), n, v, 1.0);
    if (uHasLabel < 0.5) return color;
    float ink = texture(uLabel, vUV).r;
    float above = texture(uLabel, vUV - vec2(0.0, 0.08)).r;
    // Burnt in: dark brown in the cut, darker still under its upper edge.
    vec3 burnt = vec3(0.22, 0.15, 0.10) * (1.0 - 0.3 * (1.0 - above));
    return mix(color, color * burnt, ink);
}

// Letters on the wall (the name of a series, "Collection"): matte, standing a few millimetres
// off it, so they throw a soft shadow down and away from the light; light ones on a dark wall,
// dark ones on a light wall.
vec3 lettering(vec3 v) {
    vec3 color = wall(v);
    if (uHasLabel < 0.5) return color;
    vec3 size = boxSize();
    float ink = texture(uLabel, vUV).r;
    vec2 away = vec2(-0.0016 / size.x, -0.0026 / size.y);
    float shadow = textureLod(uLabel, vUV + away, 2.5).r;
    color *= 1.0 - 0.5 * shadow * (1.0 - ink);
    vec3 paint = luminance(uWall) < 0.25 ? vec3(0.78, 0.75, 0.70) : vec3(0.045, 0.04, 0.036);
    vec3 n = normalize(uNormalMatrix * vec3(0.0, 0.0, 1.0));
    return mix(color, paint * irradiance(n, 1.0) + sparkle(n, v, 16.0, 0.03), ink);
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
        color = lettering(v);
    }

    // The comic in hand stands out: the rest of the room dims and loses some colour.
    color = mix(color, vec3(luminance(color)), uDim * 0.6) * (1.0 - uDim);

    // Filmic tone curve (ACES fit), then back to sRGB.
    color *= EXPOSURE;
    color = clamp((color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14), 0.0, 1.0);
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), alpha);
}
