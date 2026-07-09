package io.github.ahrimjang.mail.common;

/**
 * The mail a campaign sends: the subject and HTML body snapshotted at create
 * time (still containing raw {@code {{variables}}} — personalization happens
 * per recipient at dispatch). Served separately from {@link CampaignView} so
 * list/detail polling doesn't drag the full HTML around.
 */
public record CampaignContentView(
        String subject,
        String htmlBody
) {
}
