package io.github.ahrimjang.mail.infra.messaging;

import io.github.ahrimjang.mail.common.EmailEventMessage;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapter: implements the core {@link EmailEventPublisher} port by publishing the
 * event as JSON to the {@code mail.events} Kafka topic, keyed by campaign id so
 * events of one campaign stay ordered within a partition.
 */
@Component
public class KafkaEmailEventPublisher implements EmailEventPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public KafkaEmailEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(EmailEvent e) {
        EmailEventMessage message = new EmailEventMessage(
                e.getMessageId(), e.getCampaignId(), e.getType(), e.getUrl(),
                e.getOccurredAt().toEpochMilli());
        kafkaTemplate.send(KafkaEventConfig.TOPIC, String.valueOf(e.getCampaignId()), message);
    }
}
