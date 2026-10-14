#version 300 es
precision highp float;

#include curl_common

layout(location = 0) in vec2 aUV;

uniform mat4 uMVP;
uniform float uDepth;     // depth offset of the whole sheet (pages underneath use a negative value)

out vec2 vUV;
out vec3 vNormal;
out float vHeight;

void main() {
    vec3 pos;
    vec3 normal;
    curlDeform(aUV, pos, normal);
    vUV = aUV;
    vNormal = normal;
    vHeight = pos.z;
    gl_Position = uMVP * vec4(pos.xy, pos.z + uDepth, 1.0);
}
