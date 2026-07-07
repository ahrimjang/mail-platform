package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.stereotype.Service;

/**
 * Records recipient engagement (opens/clicks) resolved from a per-message
 * tracking token. Events are published to the async event stream (Kafka) and
 * projected into the read model by a separate consumer — the tracking endpoints
 * stay fast and never touch the events table directly. Unknown tokens are ignored.
 */
@Service
public class TrackingService {

    private final MailMessageRepository messages;
    private final EmailEventPublisher events;

    public TrackingService(MailMessageRepository messages, EmailEventPublisher events) {
        this.messages = messages;
        this.events = events;
    }

    public void recordOpen(String trackingToken) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.OPEN, null)));
    }

    public void recordClick(String trackingToken, String url) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.CLICK, url)));
    }
}
