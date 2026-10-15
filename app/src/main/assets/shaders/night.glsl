// Reading at night: the light of a warm desk lamp instead of daylight.
// night = (strength 0..1, warmth 0..1, brightness 0..1).
vec3 nightTone(vec3 color, vec3 night) {
    if (night.x <= 0.0) return color;
    // Less blue, a little less green.
    vec3 warm = mix(vec3(1.0), vec3(1.0, 0.80, 0.56), night.y);
    color *= mix(vec3(1.0), warm, night.x);
    // Dimmer; white paper loses its glare more than the print darkens.
    float l = dot(color, vec3(0.299, 0.587, 0.114));
    float dim = mix(1.0, mix(0.35, 1.0, night.z), night.x);
    return color * dim * (1.0 - 0.07 * night.x * smoothstep(0.6, 1.0, l));
}
