package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.service.MailDispatchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the send queue on a fixed delay and hands each batch to the dispatch
 * service. {@code batchSize} caps how many mails one tick sends — the crude
 * throttle that keeps a "large" campaign from firing all at once.
 */
@Component
public class MailDispatchScheduler {

    private final MailDispatchService dispatch;
    private final int batchSize;

    public MailDispatchScheduler(MailDispatchService dispatch,
                                 @Value("${mail.worker.batch-size:50}") int batchSize) {
        this.dispatch = dispatch;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${mail.worker.poll-interval-ms:2000}")
    public void poll() {
        dispatch.dispatchBatch(batchSize);
    }
}
