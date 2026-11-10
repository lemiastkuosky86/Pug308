package com.peugeot308.oem;
/** Animação meteorológica determinística, sem alocação por quadro. */
final class WeatherMotion {
    private WeatherMotion() {}
    static float progress(long nowMs, long periodMs, int index, int count) {
        if (periodMs <= 0L || count <= 0) return 0f;
        long tick = Math.floorMod(nowMs, periodMs);
        return ((tick / (float) periodMs) + (index / (float) count)) % 1f;
    }
}
