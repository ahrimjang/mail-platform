package io.github.ahrimjang.mail.common;

/**
 * One contact's engagement summary: how many mails were delivered to them and
 * how many distinct ones they opened/clicked. Rates are derived by the caller
 * (opened/sent, clicked/sent).
 */
public record ContactEngagementView(
        Long contactId,
        String email,
        String firstName,
        String lastName,
        long sent,
        long opened,
        long clicked
) {
}
