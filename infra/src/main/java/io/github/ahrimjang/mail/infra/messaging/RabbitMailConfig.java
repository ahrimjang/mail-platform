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
 * RabbitMQ topology, declared in a campaign's lifecycle order: one durable
 * direct exchange routing (1) the fan-out job, (2) the per-recipient send jobs,
 * (3) throttled re-enqueues through a TTL'd parking queue, and (4) rejected
 * jobs dead-lettered to a separate DLX/DLQ.
 */
@Configuration
public class RabbitMailConfig {

    public static final String EXCHANGE = "mail.exchange";

    // (1) fan-out: one job per list campaign — the worker expands it into send jobs
    public static final String FANOUT_QUEUE = "mail.fanout.queue";
    public static final String FANOUT_ROUTING_KEY = "mail.fanout";

    // (2) send: one job per recipient message
    public static final String QUEUE = "mail.send.queue";
    public static final String ROUTING_KEY = "mail.send";

    // (3) throttle parking: rate-capped jobs wait out a TTL, then re-enter the send queue
    public static final String THROTTLE_QUEUE = "mail.send.throttled";
    public static final String THROTTLE_ROUTING_KEY = "mail.send.throttled";
    /**
     * How long a throttled job parks before re-entering the send queue. Fixed at
     * declaration — RabbitMQ rejects re-declaring a queue with different args.
     */
    static final int THROTTLE_DELAY_MS = 1000;

    // (4) dead letters: jobs that exhausted listener retries
    public static final String DLX = "mail.dlx";
    public static final String DLQ = "mail.send.dlq";
    public static final String DLQ_ROUTING = "mail.send.dlq";

    @Bean
    public DirectExchange mailExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    // ── (1) fan-out ─────────────────────────────────────────────────────
    // "mail.fanout.queue라는 우편함이 존재한다"
    //  (+ 속성: durable, 실패 시 DLX로 보내라)
    @Bean
    public Queue fanoutQueue() {
        return QueueBuilder.durable(FANOUT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING)
                .build();
    }
    // "mail.exchange에 'mail.fanout' 주소로 온 편지는
    //  그 우편함에 넣어라" — 배달 규칙
    // bind(큐).to(교환기).with(주소) = "큐를 교환기에 이 주소로 묶어라".
    @Bean
    public Binding fanoutBinding() {
        return BindingBuilder.bind(fanoutQueue()).to(mailExchange()).with(FANOUT_ROUTING_KEY);
    }

    // ── (2) send ────────────────────────────────────────────────────────

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

    // ── (3) throttle parking ────────────────────────────────────────────

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

    // ── (4) dead letters ────────────────────────────────────────────────

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
