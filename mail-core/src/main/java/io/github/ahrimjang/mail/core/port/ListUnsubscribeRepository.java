package io.github.ahrimjang.mail.core.port;

import java.util.List;

/**
 * Persistence port for per-list opt-outs — the recipient's own durable decision
 * to stop one list's mail, kept apart from {@code list_memberships} (the
 * operator's grouping). Re-importing a CSV restores a membership but never this
 * record; list fan-out excludes opted-out contacts at send time.
 */
public interface ListUnsubscribeRepository {

    /** Record the opt-out. Idempotent — an existing (list, contact) pair is kept as-is. */
    void save(Long listId, Long contactId, String reason);

    boolean exists(Long listId, Long contactId);

    /** Lists this contact has opted out of, ordered by list id. */
    List<Long> findListIdsByContactId(Long contactId);

    /** Remove the opt-out (an explicit re-subscribe); a no-op if none exists. */
    void delete(Long listId, Long contactId);

    /** Opt-out counts per list, largest first (analytics audience health). */
    List<ListCount> countByList();

    /** One list's opt-out count. */
    record ListCount(Long listId, long count) {
    }

    /** This contact's opt-outs with their reason and time (activity timeline). */
    List<OptOut> findByContact(Long contactId);

    /** One recorded opt-out. */
    record OptOut(Long listId, String reason, java.time.Instant createdAt) {
    }
}
