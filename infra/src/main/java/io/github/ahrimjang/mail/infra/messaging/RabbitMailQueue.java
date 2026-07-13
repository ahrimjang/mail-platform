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
    }

    @Override
    public void enqueueFanout(Long campaignId) {
        rabbitTemplate.convertAndSend(RabbitMailConfig.EXCHANGE, RabbitMailConfig.FANOUT_ROUTING_KEY,
                new io.github.ahrimjang.mail.common.FanoutJob(campaignId));
    }
}
