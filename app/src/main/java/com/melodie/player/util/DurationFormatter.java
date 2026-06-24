package com.melodie.player.util;

import java.util.Locale;

public final class DurationFormatter {
    private DurationFormatter() {}

    public static String format(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}

