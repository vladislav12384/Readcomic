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
        color = namePlate(v);
    }

    // The comic in hand stands out: the rest of the room dims and loses some colour.
    color = mix(color, vec3(luminance(color)), uDim * 0.6) * (1.0 - uDim);

    // Filmic tone curve (ACES fit), then back to sRGB.
    color *= EXPOSURE;
    color = clamp((color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14), 0.0, 1.0);
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), alpha);
}
