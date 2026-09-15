package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.common.EmailEventMessage;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * 프로젝션 — 원본 이벤트를 먼저 남기고, 그다음 첫 참여 카운터를 올린다(V36). 순서가 바뀌면 카운터만
 * 오르고 원본이 없는 상태로 죽을 수 있다. 반대 순서로 죽으면 재전달이 원본 행 하나를 더 만들 뿐이다.
 */
class EmailEventProjectionListenerTest {

    private final EmailEventRepository events = mock(EmailEventRepository.class);
    private final EmailEventProjectionListener listener = new EmailEventProjectionListener(events);

    @Test
    void open_isStoredFirst_thenCountedAsFirstEngagement() {
        long at = 1_780_000_000_000L;

        listener.onEvent(new EmailEventMessage(42L, 7L, EventType.OPEN, null, at));

        InOrder order = inOrder(events);
        ArgumentCaptor<EmailEvent> saved = ArgumentCaptor.forClass(EmailEvent.class);
        order.verify(events).save(saved.capture());
        order.verify(events).recordFirstEngagement(42L, 7L, EventType.OPEN, Instant.ofEpochMilli(at));
        assertThat(saved.getValue().getOccurredAt()).isEqualTo(Instant.ofEpochMilli(at));
    }

    @Test
    void click_carriesItsUrlIntoTheRawEvent_andCountsTheClick() {
        long at = 1_780_000_000_500L;

        listener.onEvent(new EmailEventMessage(43L, 7L, EventType.CLICK, "https://example.com/p", at));

        InOrder order = inOrder(events);
        ArgumentCaptor<EmailEvent> saved = ArgumentCaptor.forClass(EmailEvent.class);
        order.verify(events).save(saved.capture());
        order.verify(events).recordFirstEngagement(43L, 7L, EventType.CLICK, Instant.ofEpochMilli(at));
        assertThat(saved.getValue().getUrl()).isEqualTo("https://example.com/p");
    }
}
