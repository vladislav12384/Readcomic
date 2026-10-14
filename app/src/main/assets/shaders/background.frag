#version 300 es
precision highp float;

// The "table": background with a vignette, the soft drop shadow of the comic,
// the visible edge of the remaining stack of sheets and the shadow of a turning page.

uniform vec2 uViewport;
uniform vec3 uTransform;     // scale, offsetX, offsetY: view = world * scale + offset
uniform vec4 uPageRect;      // world rect of the top page
uniform vec2 uStack;         // world offset of the bottom sheet of the stack
uniform float uSheets;       // how many sheet lines to draw on the stack edge
uniform float uHasPage;
uniform vec3 uBgColor;
uniform vec3 uPaper;
uniform sampler2D uShadowTex;
uniform float uShadowOn;

out vec4 fragColor;

float sdBox(vec2 p, vec2 halfSize) {
    vec2 d = abs(p) - halfSize;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

void main() {
    vec2 frag = vec2(gl_FragCoord.x, uViewport.y - gl_FragCoord.y);
    vec2 w = (frag - uTransform.yz) / uTransform.x;

    vec2 q = frag / uViewport - 0.5;
    vec3 color = uBgColor * (1.0 - 0.55 * dot(q, q));

    if (uHasPage > 0.5) {
        vec2 lo = uPageRect.xy;
        vec2 hi = uPageRect.xy + uPageRect.zw;

        // Drop shadow under the comic.
        float blur = 0.03 * uPageRect.z;
        vec2 center = (lo + hi + uStack) * 0.5 + vec2(0.0, blur * 0.4);
        vec2 halfSize = (hi - lo + abs(uStack)) * 0.5;
        float d = sdBox(w - center, halfSize);
        color *= 1.0 - 0.6 * (1.0 - smoothstep(-blur, blur * 1.8, d));

        // Edge of the stack: find the top-most sheet (smallest t) that covers this point.
        float tMin = 0.0;
        float tMax = 1.0;
        for (int i = 0; i < 2; i++) {
            float s = uStack[i];
            if (s > 1e-4) {
                tMin = max(tMin, (w[i] - hi[i]) / s);
                tMax = min(tMax, (w[i] - lo[i]) / s);
            } else if (w[i] < lo[i] || w[i] > hi[i]) {
                tMax = -1.0;
            }
        }
        if (tMin <= tMax && tMin > 0.0) {
            float sheet = fract(tMin * uSheets);
            float line = smoothstep(0.0, 0.3, sheet) * (1.0 - smoothstep(0.7, 1.0, sheet));
            color = uPaper * mix(0.70, 0.86, line) * (1.0 - 0.22 * tMin);
        }
    }

    if (uShadowOn > 0.5) {
        color *= 1.0 - texture(uShadowTex, gl_FragCoord.xy / uViewport).r;
    }
    fragColor = vec4(color, 1.0);
}
