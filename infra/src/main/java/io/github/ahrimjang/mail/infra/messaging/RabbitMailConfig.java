package io.github.ahrimjang.mail.infra.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the send queue: a durable direct exchange routing send jobs
 * to {@code mail.send.queue}, dead-lettered to a separate DLX/DLQ on rejection.
 */
@Configuration
public class RabbitMailConfig {

    public static final String EXCHANGE = "mail.exchange";
    public static final String QUEUE = "mail.send.queue";
    public static final String ROUTING_KEY = "mail.send";
    public static final String DLX = "mail.dlx";
    public static final String DLQ = "mail.send.dlq";
    public static final String DLQ_ROUTING = "mail.send.dlq";
    public static final String FANOUT_QUEUE = "mail.fanout.queue";
    public static final String FANOUT_ROUTING_KEY = "mail.fanout";
    public static final String THROTTLE_QUEUE = "mail.send.throttled";
    public static final String THROTTLE_ROUTING_KEY = "mail.send.throttled";
    /**
     * How long a throttled job parks before re-entering the send queue. Fixed at
     * declaration — RabbitMQ rejects re-declaring a queue with different args.
     */
    static final int THROTTLE_DELAY_MS = 1000;

    @Bean
    public DirectExchange mailExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue mailQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING)
                .build();
    }

    @Bean
    public Binding mailBinding() {
        return BindingBuilder.bind(mailQueue()).to(mailExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Queue fanoutQueue() {
        return QueueBuilder.durable(FANOUT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING)
                .build();
    }

    @Bean
    public Binding fanoutBinding() {
        return BindingBuilder.bind(fanoutQueue()).to(mailExchange()).with(FANOUT_ROUTING_KEY);
    }

    /**
     * Parking lot for throttled sends: no consumer — messages just sit out the
     * per-queue TTL, then dead-letter straight back into the send queue. This
     * paces a rate-capped tenant without a busy requeue loop, and without
     * blocking consumer slots other tenants could be using.
     */
    @Bean
    public Queue throttleQueue() {
        return QueueBuilder.durable(THROTTLE_QUEUE)
                .withArgument("x-message-ttl", THROTTLE_DELAY_MS)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding throttleBinding() {
        return BindingBuilder.bind(throttleQueue()).to(mailExchange()).with(THROTTLE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(deadExchange()).with(DLQ_ROUTING);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
