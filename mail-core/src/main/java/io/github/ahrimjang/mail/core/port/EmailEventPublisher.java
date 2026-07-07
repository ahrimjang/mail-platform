package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.EmailEvent;

/**
 * Publishes recipient engagement events (open/click/bounce) onto the async event
 * stream. The write side of engagement: producers fire-and-forget, a consumer
 * projects the stream into the {@link EmailEventRepository} read model that
 * campaign metrics query. Kept separate from the repository so the core never
 * sees the broker.
 */
public interface EmailEventPublisher {

    /** Publish one engagement event to the stream. */
    void publish(EmailEvent event);
}
