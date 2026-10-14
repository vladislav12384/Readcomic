#version 300 es
precision highp float;
precision highp sampler2DShadow;

// Materials of the shelf scene: comics (covers, spine, page edges), wooden planks, the wall,
// paper labels and bookmark ribbons. Lit by a warm key light with a shadow map, a soft
// ambient, and small lamps whose pools of light sit above every comic on the shelves.

in vec3 vWorld;
in vec3 vNormal;
in vec3 vLocal;
in vec2 vUV;
in vec4 vLight;
flat in int vFace;   // 0 front, 1 back, 2 left (spine), 3 right, 4 top, 5 bottom

uniform int uMaterial;        // 0 comic, 1 wood, 2 wall, 3 label, 4 ribbon
uniform mat3 uNormalMatrix;
uniform sampler2D uCover;
uniform sampler2D uBack;
uniform sampler2D uLabel;
uniform float uHasCover;
uniform float uHasBack;
uniform float uHasLabel;
uniform float uSheets;        // sheets of paper in the comic
uniform float uThickness;     // of the comic, meters
uniform float uDim;           // darkening of everything but the comic in hand
uniform vec3 uEye;
uniform vec3 uLightDir;       // towards the key light
uniform sampler2DShadow uShadow;
uniform float uShadowTexel;
uniform float uColumns;
uniform float uCount;
uniform float uSlotWidth;
uniform float uRowHeight;

out vec4 fragColor;

const vec3 PAPER = vec3(0.93, 0.90, 0.83);
const float PI = 3.14159265;

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
    for (int i = 0; i < 4; i++) {
        sum += amplitude * noise(p);
        p *= 2.03;
        amplitude *= 0.5;
    }
    return sum;
}

float shadowAt(vec3 n) {
    vec3 p = vLight.xyz / vLight.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float bias = 0.0006 + 0.002 * (1.0 - max(dot(n, uLightDir), 0.0));
    float sum = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            sum += texture(uShadow, vec3(p.xy + vec2(float(x), float(y)) * uShadowTexel * 1.5, p.z - bias));
        }
    }
    return sum / 9.0;
}

// Warm pools of light from small lamps above every comic on the shelves.
float pools(vec3 w) {
    float c = floor(w.x / uSlotWidth + (uColumns - 1.0) * 0.5 + 0.5);
    float r = floor(-w.y / uRowHeight) + 1.0;
    if (c < 0.0 || c >= uColumns || r < 0.0 || r * uColumns + c >= uCount) return 0.0;
    // A lamp above the comic: a soft cone on the wall, brightest just above the comic's top.
    vec2 center = vec2((c - (uColumns - 1.0) * 0.5) * uSlotWidth, -r * uRowHeight + 0.30);
    vec2 d = (w.xy - center) / vec2(0.15 + 0.10 * clamp(center.y - w.y, 0.0, 0.4), 0.28);
    return exp(-dot(d, d)) * mix(1.0, 0.6, clamp(w.z / 0.15, 0.0, 1.0));
}

vec3 shade(vec3 albedo, vec3 n, float gloss, float shininess) {
    vec3 v = normalize(uEye - vWorld);
    float ndl = max(dot(n, uLightDir), 0.0);
    float shadow = shadowAt(n);
    vec3 ambient = mix(vec3(0.13, 0.12, 0.11), vec3(0.36, 0.35, 0.34), n.y * 0.5 + 0.5);
    vec3 key = vec3(1.0, 0.93, 0.84) * ndl * shadow * 0.80;
    vec3 pool = vec3(1.0, 0.74, 0.46) * pools(vWorld) * 1.15;
    vec3 color = albedo * (ambient + key + pool);
    vec3 h = normalize(uLightDir + v);
    color += vec3(1.0, 0.95, 0.88) * pow(max(dot(n, h), 0.0), shininess) * gloss * shadow;
    return color;
}

// The glossy coating of a cover mirrors a long soft lamp hanging in front of the shelves.
vec3 coat(vec3 n, vec3 color) {
    vec3 v = normalize(uEye - vWorld);
    vec3 r = reflect(-v, n);
    float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    float band = exp(-pow((r.y - 0.40) / 0.06, 2.0)) * smoothstep(-0.1, 0.4, r.z);
    float c = clamp((band * 0.14 + 0.015) * (1.0 + 3.0 * fresnel), 0.0, 0.55);
    return vec3(1.0) - (vec3(1.0) - color) * (1.0 - c);
}

vec3 wood(vec3 p) {
    float warp = fbm(vec2(p.x * 1.3, (p.y + p.z) * 7.0)) * 1.6;
    float rings = fract((p.y * 48.0 + p.z * 31.0) + warp * 2.6);
    float fibers = noise(vec2(p.x * 36.0, (p.y + p.z) * 700.0));
    vec3 light = vec3(0.66, 0.44, 0.26);
    vec3 dark = vec3(0.40, 0.23, 0.12);
    return mix(light, dark, smoothstep(0.15, 0.85, rings) * 0.55 + fibers * 0.22);
}

vec3 coverColor(float lod) {
    return textureLod(uCover, vec2(0.5), lod).rgb;
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 color;

    if (uMaterial == 0) {
        if (vFace == 0) {
            vec3 a = uHasCover > 0.5 ? texture(uCover, vUV).rgb : PAPER * 0.75;
            // Ink sits on top of coated paper: slightly deeper colours.
            a = mix(a, a * a * (3.0 - 2.0 * a), 0.15);
            color = coat(n, shade(a, n, 0.30, 70.0));
        } else if (vFace == 1) {
            vec3 a = uHasBack > 0.5 ? texture(uBack, vUV).rgb : (uHasCover > 0.5 ? coverColor(10.0) * 0.8 : PAPER * 0.7);
            color = coat(n, shade(a, n, 0.30, 70.0));
        } else if (vFace == 2) {
            // The spine: the cover wraps around it, so it is rounded and has the cover's edge colour.
            float t = vUV.x;
            vec3 rounded = vec3(-cos((t - 0.5) * PI), 0.0, sin((t - 0.5) * PI));
            vec3 nr = normalize(uNormalMatrix * rounded);
            vec3 a = uHasCover > 0.5 ? textureLod(uCover, vec2(0.02, vUV.y), 3.0).rgb : PAPER * 0.7;
            if (uSheets <= 40.0) {
                // Thin comics are held together by two staples.
                float sy = min(abs(vUV.y - 0.27), abs(vUV.y - 0.73));
                float staple = (1.0 - smoothstep(0.004, 0.008, sy)) * (1.0 - smoothstep(0.28, 0.36, abs(t - 0.5)));
                a = mix(a, vec3(0.70, 0.71, 0.74), staple);
            }
            color = coat(nr, shade(a, nr, 0.25, 50.0));
        } else {
            // Page edges: a block of sheets between the two covers.
            float t = vFace == 3 ? vUV.x : vUV.y;
            float sheet = t * uSheets;
            float width = fwidth(sheet);
            float d = abs(fract(sheet) - 0.5);
            float line = smoothstep(0.5 - max(width, 0.12), 0.5, d) * (1.0 - smoothstep(0.4, 1.2, width));
            vec3 a = PAPER * (1.0 - 0.2 * line) * (0.97 + 0.03 * noise(vec2(sheet, vLocal.y * 300.0)));
            float cover = 0.00028 / max(uThickness, 0.0005);
            if (uHasCover > 0.5) {
                if (t > 1.0 - cover) a = textureLod(uCover, vec2(0.97, 0.5), 5.0).rgb;
                if (t < cover) a = coverColor(10.0) * 0.8;
            }
            color = shade(a, n, 0.04, 12.0);
        }
    } else if (uMaterial == 1) {
        color = shade(wood(vWorld), n, 0.16, 28.0);
    } else if (uMaterial == 2) {
        float grain = fbm(vWorld.xy * 16.0) * 0.07 + noise(vWorld.xy * 260.0) * 0.03;
        vec3 a = vec3(0.10, 0.16, 0.16) * (0.93 + grain);
        color = shade(a, vec3(0.0, 0.0, 1.0), 0.0, 1.0);
    } else if (uMaterial == 3) {
        vec3 a = uHasLabel > 0.5 ? texture(uLabel, vUV).rgb : PAPER;
        color = shade(a, n, 0.04, 10.0);
    } else {
        color = shade(vec3(0.52, 0.05, 0.07), n, 0.35, 16.0);
    }

    color *= 1.0 - uDim;
    // Soft shoulder so the lamps never clip.
    color = color / (1.0 + 0.18 * color);
    fragColor = vec4(color, 1.0);
}
