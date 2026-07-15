package io.github.ahrimjang.mail.common;

/**
 * One row of the analytics link ranking: a tracked URL and how often it was
 * clicked in the selected period ({@code uniqueMessages} counts distinct
 * deliveries, so one recipient hammering a link doesn't inflate the ranking).
 */
public record LinkClicksView(
        String url,
        long clicks,
        long uniqueMessages
) {
}
