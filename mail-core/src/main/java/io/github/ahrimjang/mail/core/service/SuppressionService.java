package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

/**
 * Manages the global suppression list: resolving unsubscribe tokens to addresses
 * and answering whether a given address is suppressed.
 */
@Service
public class SuppressionService {

    private final MailMessageRepository messages;
    private final SuppressionRepository suppressions;

    public SuppressionService(MailMessageRepository messages,
                              SuppressionRepository suppressions) {
        this.messages = messages;
        this.suppressions = suppressions;
    }

    /** Suppress the address behind an unsubscribe token, if the token resolves. */
    public void suppressByUnsubToken(String token) {
        messages.findByUnsubToken(token)
                .ifPresent(m -> suppressions.save(Suppression.of(m.getRecipient(), "unsubscribe")));
    }

    public boolean isSuppressed(String email) {
        return suppressions.existsByEmail(email);
    }
}
