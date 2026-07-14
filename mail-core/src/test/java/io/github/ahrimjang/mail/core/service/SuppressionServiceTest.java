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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuppressionServiceTest {

    @Mock
    private MailMessageRepository messages;

    @Mock
    private SuppressionRepository suppressions;

    @Mock
    private ContactRepository contacts;

    @Mock
    private CampaignRepository campaigns;

    @Mock
    private ContactListRepository lists;

    @Mock
    private ListUnsubscribeRepository listUnsubscribes;

    private SuppressionService service;

    @BeforeEach
    void setUp() {
        service = new SuppressionService(messages, suppressions, contacts, campaigns, lists, listUnsubscribes);
    }

    private Contact contactWithId(Long id, String email) {
        Contact c = Contact.of(email, null, null, Map.of());
        c.setId(id);
        return c;
    }

    /** A message from a list campaign: contactId set, campaign targets list 5 ("뉴스레터"). */
    private MailMessage listCampaignMessage() {
        MailMessage message = MailMessage.queued(10L, "member@x.com", 77L);
        Campaign campaign = Campaign.draft("s", "b");
        campaign.setId(10L);
        campaign.setListId(5L);
        ContactList list = ContactList.of("뉴스레터", null);
        list.setId(5L);
        when(campaigns.findById(10L)).thenReturn(Optional.of(campaign));
        when(lists.findById(5L)).thenReturn(Optional.of(list));
        return message;
    }

    @Test
    void unsubscribeContext_listCampaignRecipientGetsTheListChoice() {
        MailMessage message = listCampaignMessage();
        when(messages.findByUnsubToken("tok")).thenReturn(Optional.of(message));

        var ctx = service.unsubscribeContext("tok");

        assertThat(ctx).isPresent();
        assertThat(ctx.get().canUnsubscribeFromList()).isTrue();
        assertThat(ctx.get().listId()).isEqualTo(5L);
        assertThat(ctx.get().listName()).isEqualTo("뉴스레터");
        assertThat(ctx.get().recipient()).isEqualTo("member@x.com");
    }

    @Test
    void unsubscribeContext_adHocRecipientOnlyGetsTheGlobalChoice() {
        // No contactId — an ad-hoc recipient has no membership to remove.
        MailMessage message = MailMessage.queued(10L, "adhoc@x.com");
        when(messages.findByUnsubToken("tok")).thenReturn(Optional.of(message));

        var ctx = service.unsubscribeContext("tok");

        assertThat(ctx).isPresent();
        assertThat(ctx.get().canUnsubscribeFromList()).isFalse();
    }

    @Test
    void unsubscribeContext_unknownTokenIsEmpty() {
        when(messages.findByUnsubToken("nope")).thenReturn(Optional.empty());

        assertThat(service.unsubscribeContext("nope")).isEmpty();
    }

    @Test
    void unsubscribeFromList_recordsOptOutKeepingMembershipAndSuppressionUntouched() {
        MailMessage message = listCampaignMessage();
        when(messages.findByUnsubToken("tok")).thenReturn(Optional.of(message));

        var ctx = service.unsubscribeFromList("tok");

        assertThat(ctx).isPresent();
        // The opt-out is its own durable record; the operator's membership row stays,
        // so a CSV re-import cannot silently re-subscribe the recipient.
        verify(listUnsubscribes).save(5L, 77L, "unsubscribe");
        verify(lists, never()).removeMember(any(), any());
        verify(suppressions, never()).save(any());
    }

    @Test
    void unsubscribeFromList_adHocTokenRemovesNothing() {
        MailMessage message = MailMessage.queued(10L, "adhoc@x.com");
        when(messages.findByUnsubToken("tok")).thenReturn(Optional.of(message));

        var ctx = service.unsubscribeFromList("tok");

        assertThat(ctx).isEmpty();
        verify(listUnsubscribes, never()).save(any(), any(), any());
        verify(suppressions, never()).save(any());
    }

    @Test
    void suppressByUnsubToken_knownTokenSuppressesRecipientWithUnsubscribeReason() {
        MailMessage message = MailMessage.queued(1L, "leaver@x.com");
        when(messages.findByUnsubToken("tok")).thenReturn(Optional.of(message));

        service.suppressByUnsubToken("tok");

        ArgumentCaptor<Suppression> captor = ArgumentCaptor.forClass(Suppression.class);
        verify(suppressions).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("leaver@x.com");
        assertThat(captor.getValue().getReason()).isEqualTo("unsubscribe");
    }

    @Test
    void suppressByUnsubToken_unknownTokenSavesNothing() {
        when(messages.findByUnsubToken("nope")).thenReturn(Optional.empty());

        service.suppressByUnsubToken("nope");

        verify(suppressions, never()).save(any());
    }

    @Test
    void isSuppressed_delegatesToRepository() {
        when(suppressions.existsByEmail("gone@x.com")).thenReturn(true);
        when(suppressions.existsByEmail("here@x.com")).thenReturn(false);

        assertThat(service.isSuppressed("gone@x.com")).isTrue();
        assertThat(service.isSuppressed("here@x.com")).isFalse();
    }

    @Test
    void subscriptionOf_suppressedContactReturnsReasonAndSince() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "gone@x.com")));
        Suppression suppression = Suppression.of("gone@x.com", "unsubscribe");
        when(suppressions.findByEmail("gone@x.com")).thenReturn(Optional.of(suppression));

        SubscriptionView view = service.subscriptionOf(7L);

        assertThat(view.suppressed()).isTrue();
        assertThat(view.reason()).isEqualTo("unsubscribe");
        assertThat(view.since()).isEqualTo(suppression.getCreatedAt());
    }

    @Test
    void subscriptionOf_unsuppressedContactReturnsFalseWithNullFields() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "here@x.com")));
        when(suppressions.findByEmail("here@x.com")).thenReturn(Optional.empty());

        SubscriptionView view = service.subscriptionOf(7L);

        assertThat(view.suppressed()).isFalse();
        assertThat(view.reason()).isNull();
        assertThat(view.since()).isNull();
    }

    @Test
    void subscriptionOf_unknownContactThrowsNotFound() {
        when(contacts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscriptionOf(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("contact not found: 99");
    }

    @Test
    void updateSubscription_trueRegistersManualSuppression() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "target@x.com")));
        Suppression stored = Suppression.of("target@x.com", "manual");
        when(suppressions.findByEmail("target@x.com")).thenReturn(Optional.of(stored));

        SubscriptionView view = service.updateSubscription(7L, true);

        ArgumentCaptor<Suppression> captor = ArgumentCaptor.forClass(Suppression.class);
        verify(suppressions).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("target@x.com");
        assertThat(captor.getValue().getReason()).isEqualTo("manual");
        assertThat(view.suppressed()).isTrue();
        assertThat(view.reason()).isEqualTo("manual");
    }

    @Test
    void updateSubscription_falseDeletesSuppression() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "back@x.com")));
        when(suppressions.findByEmail("back@x.com")).thenReturn(Optional.empty());

        SubscriptionView view = service.updateSubscription(7L, false);

        verify(suppressions).deleteByEmail("back@x.com");
        verify(suppressions, never()).save(any());
        assertThat(view.suppressed()).isFalse();
    }

    @Test
    void updateSubscription_unknownContactThrowsNotFound() {
        when(contacts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSubscription(99L, true))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("contact not found: 99");
        verify(suppressions, never()).save(any());
        verify(suppressions, never()).deleteByEmail(any());
    }
}
