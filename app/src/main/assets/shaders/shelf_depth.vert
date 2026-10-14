#version 300 es
precision highp float;

// Depth from the light's point of view, for the shadow map.

layout(location = 0) in vec3 aPos;

uniform mat4 uModel;
uniform mat4 uLightViewProj;

void main() {
    gl_Position = uLightViewProj * uModel * vec4(aPos, 1.0);
}
