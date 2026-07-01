package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.common.SendJob;
import io.github.ahrimjang.mail.core.service.MailDispatchService;
import io.github.ahrimjang.mail.infra.messaging.RabbitMailConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes send jobs off the RabbitMQ queue and hands each message id to the
 * dispatch service. RabbitMQ delivers at-least-once, so {@code dispatchOne} is
 * idempotent — a redelivered job whose message is no longer PENDING is skipped.
 */
@Component
public class MailSendListener {

    private final MailDispatchService dispatch;

    public MailSendListener(MailDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @RabbitListener(queues = RabbitMailConfig.QUEUE)
    public void onSendJob(SendJob job) {
        dispatch.dispatchOne(job.messageId());
    }
}
