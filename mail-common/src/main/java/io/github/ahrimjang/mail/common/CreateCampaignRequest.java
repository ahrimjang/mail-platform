package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * Request to create and immediately enqueue a bulk-mail campaign.
 *
 * @param subject    mail subject line
 * @param body       mail body (plain text for the POC)
 * @param recipients destination addresses; one queued message is created per entry
 */
public record CreateCampaignRequest(
        String subject,
        String body,
        List<String> recipients
) {
}
