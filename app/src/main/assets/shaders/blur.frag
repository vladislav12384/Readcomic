#version 300 es
precision mediump float;

// Separable 9-tap gaussian (5 fetches thanks to linear filtering).
in vec2 vUV;

uniform sampler2D uTex;
uniform vec2 uStep;

out vec4 fragColor;

void main() {
    float c = texture(uTex, vUV).r * 0.2270270270;
    c += texture(uTex, vUV + uStep * 1.3846153846).r * 0.3162162162;
    c += texture(uTex, vUV - uStep * 1.3846153846).r * 0.3162162162;
    c += texture(uTex, vUV + uStep * 3.2307692308).r * 0.0702702703;
    c += texture(uTex, vUV - uStep * 3.2307692308).r * 0.0702702703;
    fragColor = vec4(c, 0.0, 0.0, 1.0);
}
