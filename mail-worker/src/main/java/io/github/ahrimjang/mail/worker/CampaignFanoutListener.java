package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.common.FanoutJob;
import io.github.ahrimjang.mail.core.service.CampaignFanoutService;
import io.github.ahrimjang.mail.infra.messaging.RabbitMailConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes fan-out jobs and expands each list campaign's recipients into send jobs.
 * At-least-once delivery is fine: {@code expand} is idempotent via the atomic
 * QUEUED-&gt;EXPANDING claim, so a redelivered job creates no duplicate messages.
 */
@Component
public class CampaignFanoutListener {

    private final CampaignFanoutService fanout;

    public CampaignFanoutListener(CampaignFanoutService fanout) {
        this.fanout = fanout;
    }

    @RabbitListener(queues = RabbitMailConfig.FANOUT_QUEUE)
    public void onFanoutJob(FanoutJob job) {
        fanout.expand(job.campaignId());
    }
}
