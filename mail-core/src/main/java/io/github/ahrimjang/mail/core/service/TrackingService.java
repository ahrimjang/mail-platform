package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.stereotype.Service;

/**
 * Records recipient engagement (opens/clicks) resolved from a per-message
 * tracking token into {@code EmailEvent} rows. Unknown tokens are ignored.
 */
@Service
public class TrackingService {

    private final MailMessageRepository messages;
    private final EmailEventRepository events;

    public TrackingService(MailMessageRepository messages, EmailEventRepository events) {
        this.messages = messages;
        this.events = events;
    }

    public void recordOpen(String trackingToken) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.save(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.OPEN, null)));
    }

    public void recordClick(String trackingToken, String url) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.save(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.CLICK, url)));
    }
}
