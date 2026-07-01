package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.BounceNotification;
import io.github.ahrimjang.mail.common.BounceType;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

/**
 * Applies asynchronous bounce/complaint notifications arriving from a provider webhook.
 *
 * <p>Permanent problems (hard bounce, complaint) suppress the address; when a
 * message id is correlated we also mark that message BOUNCED and record a BOUNCE
 * event. Soft bounces are transient and left untouched so delivery may be retried.
 * The handler is idempotent: a message already BOUNCED is skipped, and suppression
 * saves are deduplicated by the adapter.
 */
@Service
public class BounceService {

    private final SuppressionRepository suppressions;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;

    public BounceService(SuppressionRepository suppressions,
                         MailMessageRepository messages,
                         EmailEventRepository events) {
        this.suppressions = suppressions;
        this.messages = messages;
        this.events = events;
    }

    public void handle(BounceNotification n) {
        // 1) correlation (optional) — mark the specific message BOUNCED + record event, idempotently
        if (n.messageId() != null) {
            messages.findById(n.messageId()).ifPresent(m -> {
                if (m.getStatus() != MessageStatus.BOUNCED) {
                    m.markBounced(n.reason());
                    messages.save(m);
                    events.save(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.BOUNCE, null));
                }
            });
        }
        // 2) email-based suppression for permanent problems (always; save is idempotent via existsByEmail)
        if (n.type() == BounceType.HARD_BOUNCE || n.type() == BounceType.COMPLAINT) {
            suppressions.save(Suppression.of(n.email(), n.type().name().toLowerCase()));
        }
        // SOFT_BOUNCE: transient — do nothing (retryable)
    }
}
