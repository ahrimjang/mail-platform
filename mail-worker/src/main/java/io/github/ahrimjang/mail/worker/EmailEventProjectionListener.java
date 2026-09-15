package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.common.EmailEventMessage;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.infra.messaging.KafkaEventConfig;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * {@code mail.events} Kafka 스트림(api 가 발행한 오픈·클릭·바운스)을 읽기 모델로 옮긴다.
 * 원본 이벤트는 {@code email_events} 에 전부 쌓고(수신자 타임라인·링크 랭킹·히트맵), 캠페인 오픈·클릭
 * 수는 메시지×종류당 첫 참여 때만 카운터를 올린다(V36). 재전달로 같은 이벤트가 다시 오면 원본 행만
 * 하나 더 생기고 카운터는 유니크 충돌로 그대로다 — 추적 엔드포인트는 여전히 DB 에 쓰지 않는다.
 */
@Component
public class EmailEventProjectionListener {

    private final EmailEventRepository events;

    public EmailEventProjectionListener(EmailEventRepository events) {
        this.events = events;
    }

    @KafkaListener(topics = KafkaEventConfig.TOPIC, groupId = KafkaEventConfig.PROJECTION_GROUP)
    public void onEvent(EmailEventMessage message) {
        EmailEvent event = EmailEvent.of(message.messageId(), message.campaignId(), message.type(), message.url());
        event.setOccurredAt(Instant.ofEpochMilli(message.occurredAtEpochMilli()));
        events.save(event);
        // 캠페인 오픈·클릭 수는 여기서 올린다 — 메시지×종류당 처음 한 번만(V36). 원본 이벤트는 위에서
        // 전부 남기고(타임라인·링크 랭킹·히트맵), 숫자는 카운터가 맡는다. BOUNCE 는 아무것도 안 한다.
        events.recordFirstEngagement(event.getMessageId(), event.getCampaignId(), event.getType(), event.getOccurredAt());
    }
}
