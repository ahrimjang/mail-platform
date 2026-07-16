package io.github.ahrimjang.mail.common;

import java.time.Instant;
import java.util.List;

/**
 * One page of the recipients table, pre-enriched so the console renders a row
 * without any per-contact follow-up calls (the old shape was 3 requests per
 * contact — unusable past a few hundred rows).
 */
public record ContactPageView(List<ContactRowView> rows, long total) {

    /** One table row: identity + subscription state + list memberships/opt-outs. */
    public record ContactRowView(
            Long id,
            String email,
            String firstName,
            String lastName,
            Instant createdAt,
            String consentSource,
            Instant consentedAt,
            boolean suppressed,
            List<Long> listIds,
            List<Long> optOutListIds
    ) {
    }
}
