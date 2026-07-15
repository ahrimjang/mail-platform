package io.github.ahrimjang.mail.common;

/**
 * One cell of the analytics open heatmap: distinct opened messages in a
 * (weekday, hour) bucket of the console's local calendar.
 *
 * @param dayOfWeek ISO weekday, 1 = Monday .. 7 = Sunday
 * @param hour      local hour of day, 0..23
 */
public record OpenHeatmapCell(
        int dayOfWeek,
        int hour,
        long opens
) {
}
