package io.github.ahrimjang.mail.infra.messaging;

import io.github.ahrimjang.mail.common.SendJob;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapter: implements the core {@link MailQueue} port by publishing a {@link SendJob}
 * to the RabbitMQ send exchange.
 */
@Component
public class RabbitMailQueue implements MailQueue {

    private final RabbitTemplate rabbitTemplate;

    public RabbitMailQueue(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void enqueue(Long messageId) {
        rabbitTemplate.convertAndSend(RabbitMailConfig.EXCHANGE, RabbitMailConfig.ROUTING_KEY, new SendJob(messageId));
        count("send");
    }

    @Override
    public void enqueueFanout(Long campaignId) {
        rabbitTemplate.convertAndSend(RabbitMailConfig.EXCHANGE, RabbitMailConfig.FANOUT_ROUTING_KEY,
                new io.github.ahrimjang.mail.common.FanoutJob(campaignId));
        count("fanout");
    }

    @Override
    public void enqueueThrottled(Long messageId) {
        // Lands in the TTL'd parking queue and dead-letters back to the send queue.
        rabbitTemplate.convertAndSend(RabbitMailConfig.EXCHANGE, RabbitMailConfig.THROTTLE_ROUTING_KEY,
                new SendJob(messageId));
        count("throttled");
    }

    private static void count(String type) {
        io.micrometer.core.instrument.Metrics.counter("mail.enqueue", "type", type).increment();
    }
}
