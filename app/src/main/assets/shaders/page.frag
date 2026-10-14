#version 300 es
precision highp float;

in vec2 vUV;
in vec3 vNormal;
in float vHeight;

uniform sampler2D uTex;
uniform float uHasTex;       // 0 while the page bitmap is still loading
uniform sampler2D uShadowTex;
uniform vec2 uViewport;
uniform float uShadowOn;     // receive the blurred shadow of the lifted sheet
uniform float uRadius;
uniform vec3 uLight;         // normalized direction towards the light
uniform vec3 uPaper;         // paper colour used for the back side
uniform float uGloss;        // strength of the glossy highlight on the bend
uniform float uGutter;       // darkening near the spine

out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    vec3 print = mix(uPaper, texture(uTex, vUV).rgb, uHasTex);

    vec3 albedo;
    vec3 nv;  // normal of the side that faces the viewer
    if (n.z >= 0.0) {
        albedo = print;
        nv = n;
    } else {
        // Back of the sheet: blank paper with a faint, mirrored show-through of the print.
        albedo = uPaper * (vec3(1.0) - 0.16 * (vec3(1.0) - print));
        nv = -n;
    }

    // Soft gutter shading along the spine (u = 0).
    albedo *= 1.0 - uGutter * exp(-vUV.x * 28.0);

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
