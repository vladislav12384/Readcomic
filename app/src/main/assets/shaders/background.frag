#version 300 es
precision highp float;

// The "table": background with a vignette, the soft drop shadow of the comic,
// the visible edges of the stacks of sheets and the shadow of a turning page.

uniform vec2 uViewport;
uniform vec3 uTransform;     // scale, offsetX, offsetY: view = world * scale + offset
uniform vec4 uDropRect[2];   // where the comic lies on the table (one per side of a spread)
uniform float uDropAlpha[2];
uniform int uDropCount;
uniform vec4 uStackRect[2];  // page lying on top of each stack
uniform vec2 uStackShift[2]; // offset of the bottom sheet of each stack
uniform float uStackLines[2];
uniform int uStackCount;
uniform vec3 uBgColor;
uniform vec3 uPaper;
uniform sampler2D uShadowTex;
uniform float uShadowOn;

out vec4 fragColor;

float sdBox(vec2 p, vec2 halfSize) {
    vec2 d = abs(p) - halfSize;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

// Colours [color] with the edge of a stack if [w] falls on it.
void stackEdge(vec2 w, vec4 rect, vec2 shift, float lines, inout vec3 color) {
    vec2 lo = rect.xy;
    vec2 hi = rect.xy + rect.zw;
    // Find the top-most sheet (smallest t) of the stack that covers this point.
    float tMin = 0.0;
    float tMax = 1.0;
    for (int i = 0; i < 2; i++) {
        float s = shift[i];
        if (abs(s) > 1e-4) {
            float a = (w[i] - hi[i]) / s;
            float b = (w[i] - lo[i]) / s;
            tMin = max(tMin, min(a, b));
            tMax = min(tMax, max(a, b));
        } else if (w[i] < lo[i] || w[i] > hi[i]) {
            tMax = -1.0;
        }
    }
    if (tMin <= tMax && tMin > 0.0) {
        float sheet = fract(tMin * lines);
        float line = smoothstep(0.0, 0.3, sheet) * (1.0 - smoothstep(0.7, 1.0, sheet));
        color = uPaper * mix(0.70, 0.86, line) * (1.0 - 0.22 * tMin);
    }
}

void main() {
    vec2 frag = vec2(gl_FragCoord.x, uViewport.y - gl_FragCoord.y);
    vec2 w = (frag - uTransform.yz) / uTransform.x;

    vec2 q = frag / uViewport - 0.5;
    vec3 color = uBgColor * (1.0 - 0.55 * dot(q, q));

    // Drop shadow under the comic, a little larger than the pages to include the stacks.
    float drop = 0.0;
    for (int i = 0; i < 2; i++) {
        if (i >= uDropCount) break;
        vec4 r = uDropRect[i];
        float blur = 0.03 * min(r.z, r.w * 0.7);
        vec2 center = r.xy + r.zw * 0.5 + vec2(0.0, blur * 0.6);
        vec2 halfSize = r.zw * 0.5 + vec2(blur * 0.2);
        float d = sdBox(w - center, halfSize);
        drop = max(drop, uDropAlpha[i] * (1.0 - smoothstep(-blur, blur * 1.8, d)));
    }
    color *= 1.0 - 0.6 * drop;

    for (int i = 0; i < 2; i++) {
        if (i < uStackCount) stackEdge(w, uStackRect[i], uStackShift[i], uStackLines[i], color);
    }

    if (uShadowOn > 0.5) {
        color *= 1.0 - texture(uShadowTex, gl_FragCoord.xy / uViewport).r;
    }
    fragColor = vec4(color, 1.0);
}
