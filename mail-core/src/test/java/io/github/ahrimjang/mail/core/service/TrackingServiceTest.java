package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock
    private MailMessageRepository messages;

    @Mock
    private CampaignRepository campaigns;

    @Mock
    private EmailEventPublisher events;

    private final TrackingLinkSigner signer = new TrackingLinkSigner("test-secret");
    private TrackingService service;

    @BeforeEach
    void setUp() {
        service = new TrackingService(messages, campaigns, events, signer);
        // Default: the campaign has no period end, so events are collected.
        org.mockito.Mockito.lenient().when(campaigns.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
    }

    private MailMessage messageWithIds(Long messageId, Long campaignId) {
        MailMessage m = MailMessage.queued(campaignId, "to@x.com");
        m.setId(messageId);
        return m;
    }

    private Campaign campaignEndingAt(Instant endsAt) {
        Campaign c = Campaign.draft("s", "b");
        c.setEndsAt(endsAt);
        return c;
    }

    @Test
    void recordOpen_savesOpenEventForKnownToken() {
        when(messages.findByTrackingToken("tok")).thenReturn(Optional.of(messageWithIds(11L, 5L)));

        service.recordOpen("tok");

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publish(captor.capture());
        EmailEvent saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo(11L);
        assertThat(saved.getCampaignId()).isEqualTo(5L);
        assertThat(saved.getType()).isEqualTo(EventType.OPEN);
        assertThat(saved.getUrl()).isNull();
    }

    @Test
    void recordClick_savesClickEventWithUrlForKnownToken() {
        when(messages.findByTrackingToken("tok")).thenReturn(Optional.of(messageWithIds(11L, 5L)));

        String url = "https://example.com/promo";
        boolean ok = service.recordClick("tok", url, signer.sign("tok", url));

        assertThat(ok).isTrue();
        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publish(captor.capture());
        EmailEvent saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(EventType.CLICK);
        assertThat(saved.getUrl()).isEqualTo(url);
        assertThat(saved.getMessageId()).isEqualTo(11L);
        assertThat(saved.getCampaignId()).isEqualTo(5L);
    }

    @Test
    void recordClick_rejectsMissingOrForgedSignature() {
        // 서명이 없거나 틀리면 리다이렉트를 거부(false)하고 이벤트도 남기지 않는다 — 오픈
        // 리다이렉트 차단(AUDIT SEC-5). 토큰 조회조차 하지 않는다.
        assertThat(service.recordClick("tok", "https://evil.example", null)).isFalse();
        assertThat(service.recordClick("tok", "https://evil.example", "forged-signature")).isFalse();
        // 다른 URL 로 서명한 걸 재사용해도 실패(서명은 URL 에 바인딩)
        assertThat(service.recordClick("tok", "https://evil.example", signer.sign("tok", "https://ok.example"))).isFalse();
        verify(events, never()).publish(any());
        verify(messages, never()).findByTrackingToken(any());
    }

    @Test
    void recordOpen_unknownTokenSavesNothingAndDoesNotThrow() {
        when(messages.findByTrackingToken("nope")).thenReturn(Optional.empty());

        service.recordOpen("nope");

        verify(events, never()).publish(any());
    }

    @Test
    void recordClick_unknownTokenSavesNothingAndDoesNotThrow() {
        when(messages.findByTrackingToken("nope")).thenReturn(Optional.empty());

        String url = "https://example.com";
        boolean ok = service.recordClick("nope", url, signer.sign("nope", url));

        assertThat(ok).isTrue();   // 서명은 유효 — 링크는 우리 것이나 메시지가 없어 기록만 생략
        verify(events, never()).publish(any());
    }

    @Test
    void recordOpen_afterThePeriodEnd_recordsNothing() {
        when(messages.findByTrackingToken("tok")).thenReturn(Optional.of(messageWithIds(11L, 5L)));
        when(campaigns.findById(5L)).thenReturn(Optional.of(campaignEndingAt(Instant.now().minusSeconds(60))));

        service.recordOpen("tok");

        verify(events, never()).publish(any());
    }

    @Test
    void recordClick_beforeThePeriodEnd_stillRecords() {
        when(messages.findByTrackingToken("tok")).thenReturn(Optional.of(messageWithIds(11L, 5L)));
        when(campaigns.findById(5L)).thenReturn(Optional.of(campaignEndingAt(Instant.now().plusSeconds(3600))));

        service.recordClick("tok", "https://x.com", signer.sign("tok", "https://x.com"));

        verify(events).publish(any());
    }
}
