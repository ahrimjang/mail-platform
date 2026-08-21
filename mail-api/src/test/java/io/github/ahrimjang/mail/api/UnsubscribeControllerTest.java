package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.core.service.SuppressionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 수신거부 토큰 형식 게이트(AUDIT SEC-7 반사형 XSS). 형식 밖 토큰은 어떤 서비스 호출·
 * HTML 반사도 하기 전에 일반 오류 페이지로 끊어야 한다.
 */
class UnsubscribeControllerTest {

    @Test
    void malformedToken_isNeverReflected_andTouchesNoService() {
        SuppressionService suppressions = mock(SuppressionService.class);
        UnsubscribeController controller = new UnsubscribeController(suppressions);

        String payload = "\" onfocus=alert(1) autofocus x=\"";
        String choose = controller.choose(payload);
        String all = controller.unsubscribeAll(payload);
        String list = controller.unsubscribeList(payload);

        // 페이로드가 응답에 그대로 반사되지 않는다
        assertThat(choose).doesNotContain("onfocus");
        assertThat(all).doesNotContain("onfocus");
        assertThat(list).doesNotContain("onfocus");
        // 서비스는 호출조차 되지 않는다(토큰 추측/부작용 차단)
        verify(suppressions, never()).unsubscribeContext(any());
        verify(suppressions, never()).suppressByUnsubToken(any());
        verify(suppressions, never()).unsubscribeFromList(any());
    }

    @Test
    void validUuidToken_reachesTheService() {
        SuppressionService suppressions = mock(SuppressionService.class);
        UnsubscribeController controller = new UnsubscribeController(suppressions);

        controller.choose("123e4567-e89b-12d3-a456-426614174000");

        verify(suppressions).unsubscribeContext("123e4567-e89b-12d3-a456-426614174000");
    }
}
