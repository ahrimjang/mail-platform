package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * One aggregated send-log row: deliveries that reached {@code status} within the
 * same time bucket, collapsed into a count. Keeps the campaign-detail log short
 * regardless of recipient volume (18k sends ≠ 18k rows).
 *
 * @param time   bucket start
 * @param status delivery outcome shared by the bucket
 * @param count  number of messages in the bucket
 * @param detail representative error message for FAILED/BOUNCED buckets, else null
 */
public record SendLogEntry(
        Instant time,
        MessageStatus status,
        long count,
        String detail
) {
}
