package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactActivityView;
import io.github.ahrimjang.mail.common.ContactMessageView;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Read side of the recipient detail view: assembles one contact's activity
 * timeline (and delivery list) from data the platform already records —
 * deliveries, engagement events, the suppression list and per-list opt-outs.
 * Nothing new is written; this only merges existing sources, newest first.
 */
@Service
public class ContactActivityService {

    private static final int MAX_LIMIT = 100;

    private final ContactRepository contacts;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;
    private final SuppressionRepository suppressions;
    private final ListUnsubscribeRepository listUnsubscribes;
    private final ContactListRepository lists;
    private final CampaignRepository campaigns;

    public ContactActivityService(ContactRepository contacts,
                                  MailMessageRepository messages,
                                  EmailEventRepository events,
                                  SuppressionRepository suppressions,
                                  ListUnsubscribeRepository listUnsubscribes,
                                  ContactListRepository lists,
                                  CampaignRepository campaigns) {
        this.contacts = contacts;
        this.messages = messages;
        this.events = events;
        this.suppressions = suppressions;
        this.listUnsubscribes = listUnsubscribes;
        this.lists = lists;
        this.campaigns = campaigns;
    }

    /**
     * Merged activity timeline of one contact, newest first, capped at
     * {@code limit} (clamped to 1..100). Sources: signup, delivery outcomes,
     * open/click events, global suppression and per-list opt-outs.
     */
    public List<ContactActivityView> activity(Long contactId, int limit) {
        int capped = clamp(limit);
        Contact contact = requireContact(contactId);

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("SIGNUP", contact.getCreatedAt(), null, null));

        for (MailMessage m : messages.findRecentByContact(contactId, capped)) {
            if (m.getStatus() == MessageStatus.SENT) {
                rows.add(new Row("SENT", m.getUpdatedAt(), null, m.getCampaignId()));
            } else if (m.getStatus() == MessageStatus.BOUNCED) {
                rows.add(new Row("BOUNCED", m.getUpdatedAt(), m.getErrorMessage(), m.getCampaignId()));
            } else if (m.getStatus() == MessageStatus.SUPPRESSED) {
                rows.add(new Row("SUPPRESSED_SKIP", m.getUpdatedAt(), null, m.getCampaignId()));
            }
        }

        for (EmailEventRepository.ContactEvent e : events.findRecentByContact(contactId, capped)) {
            if (e.type() == EventType.OPEN) {
                rows.add(new Row("OPENED", e.occurredAt(), null, e.campaignId()));
            } else if (e.type() == EventType.CLICK) {
                rows.add(new Row("CLICKED", e.occurredAt(), e.url(), e.campaignId()));
            }
        }

        suppressions.findByEmail(contact.getEmail())
                .ifPresent(s -> rows.add(new Row("UNSUBSCRIBED", s.getCreatedAt(), s.getReason(), null)));

        for (ListUnsubscribeRepository.OptOut optOut : listUnsubscribes.findByContact(contactId)) {
            String listName = lists.findById(optOut.listId())
                    .map(ContactList::getName)
                    .orElse("(삭제된 리스트)");
            rows.add(new Row("LIST_OPTOUT", optOut.createdAt(), listName, null));
        }

        Map<Long, String> campaignNames = campaignNamesOf(rows.stream().map(Row::campaignId).toList());
        return rows.stream()
                .sorted(Comparator.comparing(Row::occurredAt,
                        Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed())
                .limit(capped)
                .map(r -> new ContactActivityView(
                        r.type(), r.occurredAt(), r.detail(), r.campaignId(),
                        r.campaignId() == null ? null : campaignNames.get(r.campaignId())))
                .toList();
    }

    /** Deliveries to one contact, newest first, capped at {@code limit} (clamped to 1..100). */
    public List<ContactMessageView> messages(Long contactId, int limit) {
        int capped = clamp(limit);
        requireContact(contactId);
        List<MailMessage> recent = messages.findRecentByContact(contactId, capped);
        Map<Long, String> campaignNames = campaignNamesOf(recent.stream().map(MailMessage::getCampaignId).toList());
        return recent.stream()
                .map(m -> new ContactMessageView(
                        m.getId(), m.getCampaignId(), campaignNames.get(m.getCampaignId()),
                        m.getStatus(), m.getUpdatedAt()))
                .toList();
    }

    /** Resolve each distinct campaign id once: display name, falling back to the subject. */
    private Map<Long, String> campaignNamesOf(List<Long> campaignIds) {
        Map<Long, String> names = new HashMap<>();
        for (Long id : campaignIds) {
            if (id == null || names.containsKey(id)) {
                continue;
            }
            campaigns.findById(id)
                    .ifPresent(c -> names.put(id, displayNameOf(c)));
        }
        return names;
    }

    private static String displayNameOf(Campaign campaign) {
        return campaign.getName() != null ? campaign.getName() : campaign.getSubject();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private Contact requireContact(Long contactId) {
        return contacts.findById(contactId)
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + contactId));
    }

    /** Internal accumulator row before campaign names are resolved. */
    private record Row(String type, Instant occurredAt, String detail, Long campaignId) {
    }
}
