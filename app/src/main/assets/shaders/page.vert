#version 300 es
precision highp float;

#include curl_common

layout(location = 0) in vec2 aUV;

uniform mat4 uMVP;
uniform float uDepth;     // depth offset of the whole sheet (pages underneath use a negative value)

out vec2 vUV;
out vec3 vNormal;
out float vHeight;
out vec3 vPos;

void main() {
    vec3 pos;
    vec3 normal;
    sheetDeform(aUV, pos, normal);
    vUV = aUV;
    vNormal = normal;
    vHeight = pos.z;
    vPos = pos;
    gl_Position = uMVP * vec4(pos.xy, pos.z + uDepth, 1.0);
}
