package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.SubscriptionView;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Manages the global suppression list: resolving unsubscribe tokens to addresses,
 * answering whether a given address is suppressed, and exposing a contact's
 * subscription state (the suppression list is the single do-not-send source of truth).
 *
 * <p>Unsubscribing is two-tier: a recipient of a list campaign may opt out of just
 * that list (a durable {@code list_unsubscribes} record — the membership itself is
 * the operator's grouping and stays, but fan-out excludes the contact), or opt out
 * of everything (global suppression, checked on every send).
 */
@Service
public class SuppressionService {

    /** Reason recorded when an operator toggles the subscription state by hand. */
    static final String MANUAL_REASON = "manual";

    private final MailMessageRepository messages;
    private final SuppressionRepository suppressions;
    private final ContactRepository contacts;
    private final CampaignRepository campaigns;
    private final ContactListRepository lists;
    private final ListUnsubscribeRepository listUnsubscribes;

    public SuppressionService(MailMessageRepository messages,
                              SuppressionRepository suppressions,
                              ContactRepository contacts,
                              CampaignRepository campaigns,
                              ContactListRepository lists,
                              ListUnsubscribeRepository listUnsubscribes) {
        this.messages = messages;
        this.suppressions = suppressions;
        this.contacts = contacts;
        this.campaigns = campaigns;
        this.lists = lists;
        this.listUnsubscribes = listUnsubscribes;
    }

    /**
     * What an unsubscribe token can do: a global opt-out always, plus a list-only
     * opt-out when the mail came from a list campaign sent to a known contact.
     */
    public record UnsubscribeContext(String recipient, Long listId, String listName) {
        public boolean canUnsubscribeFromList() {
            return listId != null;
        }
    }

    /** Resolve an unsubscribe token to its choices; empty if the token is unknown. */
    public Optional<UnsubscribeContext> unsubscribeContext(String token) {
        return messages.findByUnsubToken(token).map(this::contextOf);
    }

    private UnsubscribeContext contextOf(MailMessage message) {
        // The list-only choice needs the campaign to target a list AND the recipient
        // to be a contact (ad-hoc recipients have no membership to remove).
        Long listId = null;
        String listName = null;
        if (message.getContactId() != null) {
            listId = campaigns.findById(message.getCampaignId())
                    .map(Campaign::getListId)
                    .orElse(null);
            if (listId != null) {
                listName = lists.findById(listId).map(ContactList::getName).orElse(null);
                if (listName == null) {
                    listId = null; // list deleted since the send — only the global opt-out remains
                }
            }
        }
        return new UnsubscribeContext(message.getRecipient(), listId, listName);
    }

    /**
     * List-only opt-out: record the recipient's own decision in {@code list_unsubscribes}
     * so fan-outs to that list exclude them from now on. The membership row (the
     * operator's grouping) is deliberately kept — a CSV re-import or manual re-add
     * cannot silently override the opt-out. The global suppression list is untouched,
     * so other lists and transactional mail keep flowing. Idempotent; returns the
     * resolved context, or empty if the token is unknown or has no list to leave.
     */
    public Optional<UnsubscribeContext> unsubscribeFromList(String token) {
        return messages.findByUnsubToken(token)
                .map(m -> {
                    UnsubscribeContext ctx = contextOf(m);
                    if (!ctx.canUnsubscribeFromList()) {
                        return null;
                    }
                    listUnsubscribes.save(ctx.listId(), m.getContactId(), "unsubscribe");
                    return ctx;
                })
                .filter(ctx -> ctx != null);
    }

    /** Lists this contact has opted out of (for the console's recipient view). */
    public java.util.List<Long> listUnsubscribesOf(Long contactId) {
        requireContact(contactId);
        return listUnsubscribes.findListIdsByContactId(contactId);
    }

    /** Operator-side re-subscribe: drop the opt-out so the list can reach the contact again. */
    public void resubscribeToList(Long contactId, Long listId) {
        requireContact(contactId);
        listUnsubscribes.delete(listId, contactId);
    }

    /**
     * Operator-side list opt-out (the console's "해지" status): recorded with the
     * "manual" reason so it stays distinguishable from the recipient's own
     * "unsubscribe" clicks. Idempotent — an existing opt-out keeps its record.
     */
    public void optOutOfList(Long contactId, Long listId) {
        requireContact(contactId);
        lists.findById(listId)
                .orElseThrow(() -> new NoSuchElementException("list not found: " + listId));
        listUnsubscribes.save(listId, contactId, MANUAL_REASON);
    }

    /** Suppress the address behind an unsubscribe token, if the token resolves. */
    public void suppressByUnsubToken(String token) {
        messages.findByUnsubToken(token)
                .ifPresent(m -> suppressions.save(Suppression.of(m.getRecipient(), "unsubscribe")));
    }

    public boolean isSuppressed(String email) {
        return suppressions.existsByEmail(email);
    }

    /** Subscription state of the given contact, derived from the suppression list. */
    public SubscriptionView subscriptionOf(Long contactId) {
        Contact contact = requireContact(contactId);
        return toView(suppressions.findByEmail(contact.getEmail()));
    }

    /**
     * Set the contact's subscription state: suppressed=true registers a manual
     * suppression (idempotent), false removes any existing suppression.
     * Returns the refreshed state.
     */
    public SubscriptionView updateSubscription(Long contactId, boolean suppressed) {
        Contact contact = requireContact(contactId);
        if (suppressed) {
            suppressions.save(Suppression.of(contact.getEmail(), MANUAL_REASON));
        } else {
            suppressions.deleteByEmail(contact.getEmail());
        }
        return toView(suppressions.findByEmail(contact.getEmail()));
    }

    private Contact requireContact(Long contactId) {
        return contacts.findById(contactId)
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + contactId));
    }

    private SubscriptionView toView(Optional<Suppression> suppression) {
        return suppression
                .map(s -> new SubscriptionView(true, s.getReason(), s.getCreatedAt()))
                .orElseGet(() -> new SubscriptionView(false, null, null));
    }
}
