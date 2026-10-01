package com.instagram.common;

public final class Limits {
    private Limits() {}

    public static int clamp(Integer requested, int defaultSize, int max) {
        if (requested == null || requested <= 0) return defaultSize;
        return Math.min(requested, max);
    }
}
