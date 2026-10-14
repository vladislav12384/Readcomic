#version 300 es
precision highp float;

in vec2 vUV;       // sheet coordinates, u = 0 at the spine
in vec3 vNormal;
in float vHeight;

// What is printed on each side of the sheet (see Face in Scene.kt).
uniform sampler2D uFrontTex;
uniform float uFrontHasTex;  // 0 while the bitmap is loading or for a blank page
uniform vec4 uFrontRect;     // where the page lies on the sheet
uniform vec4 uFrontSub;      // which part of the texture is the page
uniform float uFrontFlip;    // sheet u runs from the spine: flip it for pages whose spine is on the right
uniform sampler2D uBackTex;
uniform float uBackPrinted;  // 0 = blank paper on the back
uniform float uBackHasTex;
uniform vec4 uBackRect;
uniform vec4 uBackSub;
uniform float uBackFlip;

uniform sampler2D uShadowTex;
uniform vec2 uViewport;
uniform float uShadowOn;     // receive the blurred shadow of the lifted sheet
uniform float uRadius;
uniform vec3 uLight;         // normalized direction towards the light
uniform vec3 uPaper;         // paper colour
uniform float uGloss;        // strength of the glossy highlight on the bend
uniform float uGutter;       // darkening near the spine

out vec4 fragColor;

// Samples outside of control flow so mipmapping keeps working at the letterbox edges.
vec3 printed(sampler2D tex, float hasTex, vec4 rect, vec4 sub, float flip) {
    vec2 p = vec2(flip > 0.5 ? 1.0 - vUV.x : vUV.x, vUV.y);
    p = (p - rect.xy) / rect.zw;
    vec3 color = texture(tex, sub.xy + clamp(p, 0.0, 1.0) * sub.zw).rgb;
    vec2 inside = step(vec2(0.0), p) * step(p, vec2(1.0));
    return mix(uPaper, color, inside.x * inside.y * hasTex);
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 front = printed(uFrontTex, uFrontHasTex, uFrontRect, uFrontSub, uFrontFlip);
    vec3 back = printed(uBackTex, uBackHasTex, uBackRect, uBackSub, uBackFlip);

    vec3 albedo;
    vec3 nv;  // normal of the side that faces the viewer
    if (n.z >= 0.0) {
        albedo = front;
        nv = n;
    } else {
        // The other side of the sheet: the next page, or blank paper. Either way the print of
        // the front shows through a little, mirrored, like real paper.
        vec3 base = uBackPrinted > 0.5 ? back : uPaper;
        float showThrough = uBackPrinted > 0.5 ? 0.05 : 0.16;
        albedo = base * (vec3(1.0) - showThrough * (vec3(1.0) - front));
        nv = -n;
    }

    // Soft gutter shading along the spine (u = 0).
    albedo *= 1.0 - uGutter * exp(-vUV.x * 26.0);

    // Diffuse light normalised so that a flat sheet keeps the exact colours of the page.
    const float ambient = 0.38;
    float ndl = max(dot(nv, uLight), 0.0);
    float lit = (ambient + (1.0 - ambient) * ndl) / (ambient + (1.0 - ambient) * uLight.z);

    // Glossy paper sheen that only shows up on the bend.
    vec3 h = normalize(uLight + vec3(0.0, 0.0, 1.0));
    float spec = max(pow(max(dot(nv, h), 0.0), 28.0) - pow(h.z, 28.0), 0.0) * uGloss;

    vec3 color = albedo * lit + vec3(spec);

    if (uShadowOn > 0.5) {
        float receive = 1.0 - smoothstep(0.0, max(uRadius * 0.08, 0.75), vHeight);
        float shadow = texture(uShadowTex, gl_FragCoord.xy / uViewport).r;
        color *= 1.0 - shadow * receive;
    }

    fragColor = vec4(color, 1.0);
}
