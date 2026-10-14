#version 300 es
precision highp float;

#include curl_common

// Projects the lifted part of the sheet onto the table along the light direction.
// The result is blurred afterwards and used as a soft shadow.

layout(location = 0) in vec2 aUV;

uniform mat4 uMVP;
uniform vec3 uLight;
uniform float uStrength;

out float vStrength;

void main() {
    vec3 pos;
    vec3 normal;
    curlDeform(aUV, pos, normal);
    vec2 projected = pos.xy - uLight.xy / uLight.z * pos.z;

    float height = pos.z / max(2.0 * uRadius, 1.0);
    // Flat parts cast nothing; higher parts cast lighter shadows.
    vStrength = uStrength * smoothstep(0.0, 0.12, height) * mix(1.0, 0.55, clamp(height, 0.0, 1.0));
    gl_Position = uMVP * vec4(projected, 0.0, 1.0);
}
