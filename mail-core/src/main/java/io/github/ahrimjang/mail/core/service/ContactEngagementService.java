package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactEngagementView;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ranks contacts by how they engage with delivered mail — distinct opened and
 * clicked messages over SENT deliveries. Backs the recipients page's
 * engagement column/filter and the campaign form's segment preview; the
 * campaign's own engagement segment is applied at fan-out time
 * (CampaignFanoutService), not here.
 */
@Service
public class ContactEngagementService {

    private final ContactRepository contacts;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;

    public ContactEngagementService(ContactRepository contacts,
                                    MailMessageRepository messages,
                                    EmailEventRepository events) {
        this.contacts = contacts;
        this.messages = messages;
        this.events = events;
    }

    /**
     * Engagement rows of every contact with at least {@code minSent} deliveries
     * whose open/click percentages clear the thresholds (0 = no filter), most
     * engaged first (click rate, then open rate, then delivery count).
     * {@code minSent} is floored at 1 — a rate needs a denominator.
     */
    public List<ContactEngagementView> engagement(int minSent, int minOpenPercent, int minClickPercent) {
        return engagement(minSent, minOpenPercent, minClickPercent, null);
    }

    /**
     * Same, scoped to one list's members when {@code listId} is non-null — the
     * campaign form's "expected audience" preview for an engagement segment.
     */
    public List<ContactEngagementView> engagement(int minSent, int minOpenPercent, int minClickPercent, Long listId) {
        int sentFloor = Math.max(1, minSent);
        int openFloor = clampPercent(minOpenPercent);
        int clickFloor = clampPercent(minClickPercent);

        Map<Long, Long> sentByContact = messages.countSentByContact().stream()
                .collect(Collectors.toMap(
                        MailMessageRepository.ContactSentCount::contactId,
                        MailMessageRepository.ContactSentCount::sent));
        Map<Long, EmailEventRepository.ContactEngagement> engagedByContact = events.countEngagementByContact().stream()
                .collect(Collectors.toMap(
                        EmailEventRepository.ContactEngagement::contactId,
                        Function.identity()));

        return (listId == null ? contacts.findAll() : contacts.findByListId(listId)).stream()
                .map(c -> {
                    long sent = sentByContact.getOrDefault(c.getId(), 0L);
                    EmailEventRepository.ContactEngagement engaged = engagedByContact.get(c.getId());
                    return new ContactEngagementView(
                            c.getId(), c.getEmail(), c.getFirstName(), c.getLastName(),
                            sent,
                            engaged == null ? 0 : engaged.opened(),
                            engaged == null ? 0 : engaged.clicked());
                })
                .filter(v -> v.sent() >= sentFloor)
                .filter(v -> percentOf(v.opened(), v.sent()) >= openFloor)
                .filter(v -> percentOf(v.clicked(), v.sent()) >= clickFloor)
                .sorted(Comparator
                        .comparingInt((ContactEngagementView v) -> percentOf(v.clicked(), v.sent()))
                        .thenComparingInt(v -> percentOf(v.opened(), v.sent()))
                        .thenComparingLong(ContactEngagementView::sent)
                        .reversed())
                .toList();
    }

    /** Rounded percentage 0..100; 0 when nothing was delivered. */
    private static int percentOf(long part, long whole) {
        return whole == 0 ? 0 : (int) Math.round(part * 100.0 / whole);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
