package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;

/**
 * Persistence port for recorded recipient engagement events (opens/clicks).
 */
public interface EmailEventRepository {

    /** Persist a newly observed engagement event. */
    void save(EmailEvent event);

    /** Count distinct messages having at least one event of the given type in a campaign. */
    long countDistinctMessages(Long campaignId, EventType type);
}
