#version 300 es
precision mediump float;

in float vStrength;
out vec4 fragColor;

void main() {
    fragColor = vec4(vStrength, 0.0, 0.0, 1.0);
}
