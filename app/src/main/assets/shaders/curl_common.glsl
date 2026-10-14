// Shared page deformation: the sheet is wrapped around a cylinder lying on the page.
//
// All coordinates are view pixels: x to the right, y down, z towards the viewer.
// uAxisPoint/uAxisNormal describe the fold line; the normal points to the side of
// the sheet that is lifted. Points with s <= 0 stay flat, points with
// 0 < s < PI*R lie on the cylinder, the rest is flipped over and lies flat on top.

const float PI = 3.14159265;

uniform vec4 uPageRect;   // left, top, width, height
uniform vec2 uAxisPoint;
uniform vec2 uAxisNormal;
uniform float uRadius;    // 0 disables the curl

void curlDeform(vec2 uv, out vec3 pos, out vec3 normal) {
    vec2 p = uPageRect.xy + uv * uPageRect.zw;
    pos = vec3(p, 0.0);
    normal = vec3(0.0, 0.0, 1.0);
    if (uRadius <= 0.0) return;

    float s = dot(p - uAxisPoint, uAxisNormal);
    if (s <= 0.0) return;

    vec2 base = p - s * uAxisNormal;
    float theta = s / uRadius;
    if (theta < PI) {
        pos = vec3(base + uAxisNormal * (uRadius * sin(theta)), uRadius * (1.0 - cos(theta)));
        normal = vec3(-uAxisNormal * sin(theta), cos(theta));
    } else {
        pos = vec3(base - uAxisNormal * (s - PI * uRadius), 2.0 * uRadius);
        normal = vec3(0.0, 0.0, -1.0);
    }
}
