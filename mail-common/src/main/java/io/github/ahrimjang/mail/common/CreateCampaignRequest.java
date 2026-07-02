package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * Request to create and immediately enqueue a bulk-mail campaign.
 *
 * <p>Content comes either from direct {@code subject}/{@code body} or from a
 * {@code templateId}, whose subject/body are snapshotted at create time.
 * Recipients come either from the explicit {@code recipients} list or from a
 * {@code listId} targeting a contact list (one queued message per member).
 *
 * @param subject    mail subject line (ignored when {@code templateId} is set)
 * @param body       mail HTML body (ignored when {@code templateId} is set)
 * @param recipients destination addresses; one queued message is created per entry
 * @param templateId optional template whose content is snapshotted into the campaign
 * @param listId     optional contact list to fan the campaign out to
 */
public record CreateCampaignRequest(
        String subject,
        String body,
        List<String> recipients,
        Long templateId,
        Long listId
) {
}
