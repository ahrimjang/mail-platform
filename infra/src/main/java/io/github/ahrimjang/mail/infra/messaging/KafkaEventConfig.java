package io.github.ahrimjang.mail.infra.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topology for the engagement event stream: {@code mail.events} carries
 * one {@link io.github.ahrimjang.mail.common.EmailEventMessage} per open/click/
 * bounce. mail-api produces (fire-and-forget on the tracking hot path);
 * mail-worker consumes and projects into the {@code email_events} read model.
 *
 * <p>Contrast with {@link RabbitMailConfig}: RabbitMQ remains the send WORK
 * QUEUE (each job consumed once, acked, retried, dead-lettered) while Kafka is
 * the append-only EVENT LOG (facts that any number of consumers may replay).
 */
@Configuration
public class KafkaEventConfig {

    public static final String TOPIC = "mail.events";
    public static final String PROJECTION_GROUP = "mail-worker-projection";

    @Bean
    public NewTopic mailEventsTopic() {
        // Single partition/replica is a dev sizing; keyed by campaignId either way
        // so per-campaign ordering survives a future partition increase.
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }
}
