package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Records recipient engagement (opens/clicks) resolved from a per-message
 * tracking token. Events are published to the async event stream (Kafka) and
 * projected into the read model by a separate consumer — the tracking endpoints
 * stay fast and never touch the events table directly. Unknown tokens are ignored.
 *
 * <p>A campaign with a period end ({@code endsAt}) stops collecting after that
 * instant: the click redirect and open pixel still work for the recipient, but
 * no event is recorded, so the campaign's reported rates cover a bounded window.
 */
@Service
public class TrackingService {

    private final MailMessageRepository messages;
    private final CampaignRepository campaigns;
    private final EmailEventPublisher events;
    private final TrackingLinkSigner signer;

    public TrackingService(MailMessageRepository messages, CampaignRepository campaigns,
                           EmailEventPublisher events, TrackingLinkSigner signer) {
        this.messages = messages;
        this.campaigns = campaigns;
        this.events = events;
        this.signer = signer;
    }

    public void recordOpen(String trackingToken) {
        messages.findByTrackingToken(trackingToken)
                .filter(this::withinPeriod)
                .ifPresent(m -> events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.OPEN, null)));
    }

    /**
     * 서명이 유효한 클릭만 처리한다. 유효하면 참여를 기록하고 true 를 반환해 컨트롤러가
     * 목적지로 리다이렉트하게 하고, 서명이 없거나 틀리면 false — 오픈 리다이렉트 차단.
     * (수집 기간이 지난 캠페인은 리다이렉트는 하되 이벤트는 남기지 않는다.)
     */
    public boolean recordClick(String trackingToken, String url, String signature) {
        if (!signer.verify(trackingToken, url, signature)) {
            return false;
        }
        messages.findByTrackingToken(trackingToken)
                .filter(this::withinPeriod)
                .ifPresent(m -> events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.CLICK, url)));
        return true;
    }

    /** True while the message's campaign is still collecting (no endsAt, or before it). */
    private boolean withinPeriod(MailMessage message) {
        Instant endsAt = campaigns.findById(message.getCampaignId())
                .map(Campaign::getEndsAt)
                .orElse(null);
        return endsAt == null || Instant.now().isBefore(endsAt);
    }
}
