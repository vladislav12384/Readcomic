#version 300 es
precision highp float;

#include curl_common

// Projects the lifted part of the sheet onto the table along the light direction.
// The result is blurred afterwards and used as a soft shadow.

layout(location = 0) in vec2 aUV;

uniform mat4 uMVP;
uniform vec3 uLight;
uniform float uStrength;
uniform float uShadowScale;   // height at which the shadow gets noticeably lighter

out float vStrength;

void main() {
    vec3 pos;
    vec3 normal;
    sheetDeform(aUV, pos, normal);
    vec2 projected = pos.xy - uLight.xy / uLight.z * pos.z;

    float height = pos.z / uShadowScale;
    // Flat parts cast nothing (so a leaf that lands softly loses its shadow); higher parts cast lighter shadows.
    vStrength = uStrength * smoothstep(0.0, 0.06, height) * mix(1.0, 0.55, clamp(height, 0.0, 1.0));
    gl_Position = uMVP * vec4(projected, 0.0, 1.0);
}
