#version 300 es
precision highp float;

// Shelf scene: meters, Y up, the wall is the plane z = 0, the camera looks along -z.

layout(location = 0) in vec3 aPos;      // unit box / quad, -0.5..0.5
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUV;
layout(location = 3) in float aFace;    // 0 front, 1 back, 2 left, 3 right, 4 top, 5 bottom

uniform mat4 uModel;
uniform mat3 uNormalMatrix;
uniform mat4 uViewProj;
uniform mat4 uLightViewProj;

out vec3 vWorld;
out vec3 vNormal;
out vec3 vLocal;
out vec2 vUV;
out vec4 vLight;
flat out int vFace;

void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vNormal = uNormalMatrix * aNormal;
    vLocal = aPos;
    vUV = aUV;
    vFace = int(aFace + 0.5);
    vLight = uLightViewProj * world;
    gl_Position = uViewProj * world;
}
