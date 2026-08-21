package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.api.webhook.SesNotificationParser;
import io.github.ahrimjang.mail.api.webhook.SnsSignatureVerifier;
import io.github.ahrimjang.mail.api.webhook.SnsSubscriptionConfirmer;
import io.github.ahrimjang.mail.core.service.BounceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TopicArn 허용목록 게이트(AUDIT SEC-2) 검증. 서명 검증은 꺼서 게이트만 격리한다 —
 * 게이트는 서명 검증 성공 이후에 돌므로, "서명이 유효해도 우리 토픽이 아니면 거른다"는
 * 동작을 서명 없이도 그대로 재현한다.
 */
class WebhookControllerTest {

    private static final String ALLOWED = "arn:aws:sns:ap-northeast-2:111:outpace-bounces";

    private static String notificationBody(String topicArn) {
        return "{\"Type\":\"Notification\",\"MessageId\":\"m1\",\"TopicArn\":\"" + topicArn
                + "\",\"Message\":\"{}\",\"Timestamp\":\"2026-08-21T00:00:00.000Z\"}";
    }

    private WebhookController controller(BounceService bounce, SesNotificationParser parser, String allowlist) {
        return new WebhookController(
                bounce,
                new SnsSignatureVerifier(url -> null),   // 사용 안 됨(검증 off)
                new SnsSubscriptionConfirmer(),
                parser,
                "dev-webhook-secret",
                false,          // verify-signature off — 게이트만 본다
                allowlist);
    }

    @Test
    void rejectsForeignTopicArn_whenAllowlistSet() {
        BounceService bounce = mock(BounceService.class);
        SesNotificationParser parser = mock(SesNotificationParser.class);

        ResponseEntity<Void> res = controller(bounce, parser, ALLOWED)
                .ses(notificationBody("arn:aws:sns:ap-northeast-2:999:attacker-topic"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(bounce, never()).handle(any());
        verify(parser, never()).parse(any());
    }

    @Test
    void acceptsMatchingTopicArn() {
        BounceService bounce = mock(BounceService.class);
        SesNotificationParser parser = mock(SesNotificationParser.class);
        when(parser.parse(any())).thenReturn(List.of());

        ResponseEntity<Void> res = controller(bounce, parser, ALLOWED).ses(notificationBody(ALLOWED));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(parser).parse(any());
    }

    @Test
    void emptyAllowlist_allowsAnyTopic() {
        BounceService bounce = mock(BounceService.class);
        SesNotificationParser parser = mock(SesNotificationParser.class);
        when(parser.parse(any())).thenReturn(List.of());

        ResponseEntity<Void> res = controller(bounce, parser, "")
                .ses(notificationBody("arn:aws:sns:ap-northeast-2:999:whatever"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
