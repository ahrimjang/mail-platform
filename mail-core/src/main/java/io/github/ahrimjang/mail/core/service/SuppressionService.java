package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.SubscriptionView;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Manages the global suppression list: resolving unsubscribe tokens to addresses,
 * answering whether a given address is suppressed, and exposing a contact's
 * subscription state (the suppression list is the single do-not-send source of truth).
 */
@Service
public class SuppressionService {

    /** Reason recorded when an operator toggles the subscription state by hand. */
    static final String MANUAL_REASON = "manual";

    private final MailMessageRepository messages;
    private final SuppressionRepository suppressions;
    private final ContactRepository contacts;

    public SuppressionService(MailMessageRepository messages,
                              SuppressionRepository suppressions,
                              ContactRepository contacts) {
        this.messages = messages;
        this.suppressions = suppressions;
        this.contacts = contacts;
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
