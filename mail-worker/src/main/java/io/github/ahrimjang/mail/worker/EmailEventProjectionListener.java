package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.common.EmailEventMessage;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.infra.messaging.KafkaEventConfig;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Projects the {@code mail.events} Kafka stream (opens/clicks/bounces published
 * by mail-api) into the {@code email_events} read model that campaign metrics
 * query. Consuming keeps the tracking endpoints write-free; the projection is
 * append-only, so replays at-least-once merely add duplicate rows, which the
 * distinct-message metric aggregation already tolerates.
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
    }
}
