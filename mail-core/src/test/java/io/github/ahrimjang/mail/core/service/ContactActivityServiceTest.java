package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactActivityView;
import io.github.ahrimjang.mail.common.ContactMessageView;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactActivityServiceTest {

    /** The acting tenant every scoped call resolves to in these tests. */
    private static final long WS = 7L;

    @Mock
    private WorkspaceContext ctx;

    @BeforeEach
    void stubWorkspaceContext() {
        org.mockito.Mockito.lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

    @Mock
    private ContactRepository contacts;

    @Mock
    private MailMessageRepository messages;

    @Mock
    private EmailEventRepository events;

    @Mock
    private SuppressionRepository suppressions;

    @Mock
    private ListUnsubscribeRepository listUnsubscribes;

    @Mock
    private ContactListRepository lists;

    @Mock
    private CampaignRepository campaigns;

    private ContactActivityService service;

    @BeforeEach
    void setUp() {
        service = new ContactActivityService(
                contacts, messages, events, suppressions, listUnsubscribes, lists, campaigns, ctx);
    }

    private Contact contactAt(Instant createdAt) {
        Contact c = Contact.of("member@x.com", null, null, Map.of());
        c.setWorkspaceId(WS);
        c.setId(77L);
        c.setCreatedAt(createdAt);
        return c;
    }

    private MailMessage message(Long campaignId, MessageStatus status, String error, Instant updatedAt) {
        MailMessage m = MailMessage.queued(campaignId, "member@x.com", 77L);
        m.setStatus(status);
        m.setErrorMessage(error);
        m.setUpdatedAt(updatedAt);
        return m;
    }

    private Campaign campaign(Long id, String name, String subject) {
        Campaign c = Campaign.draft(subject, "b");
        c.setWorkspaceId(WS);
        c.setId(id);
        c.setName(name);
        return c;
    }

    // Sources a test leaves unstubbed stay empty (Mockito's defaults: empty list /
    // empty optional), which is exactly the "no activity from that source" case.

    @Test
    void activity_mapsEverySourceToItsTypeAndDetail() {
        when(contacts.findById(77L)).thenReturn(Optional.of(contactAt(T0)));
        when(messages.findRecentByContact(eq(77L), anyInt())).thenReturn(List.of(
                message(10L, MessageStatus.SENT, null, T0.plusSeconds(100)),
                message(10L, MessageStatus.BOUNCED, "mailbox full", T0.plusSeconds(200)),
                message(10L, MessageStatus.SUPPRESSED, null, T0.plusSeconds(300))));
        when(events.findRecentByContact(eq(77L), anyInt())).thenReturn(List.of(
                new EmailEventRepository.ContactEvent(EventType.OPEN, null, T0.plusSeconds(400), 10L),
                new EmailEventRepository.ContactEvent(EventType.CLICK, "https://x.com/p", T0.plusSeconds(500), 10L)));
        when(suppressions.findByWorkspaceAndEmail(WS, "member@x.com"))
                .thenReturn(Optional.of(suppressionAt("unsubscribe", T0.plusSeconds(600))));
        when(listUnsubscribes.findByContact(77L)).thenReturn(List.of(
                new ListUnsubscribeRepository.OptOut(5L, "unsubscribe", T0.plusSeconds(700))));
        ContactList list = ContactList.of("뉴스레터", null);
        list.setWorkspaceId(WS);
        list.setId(5L);
        when(lists.findById(5L)).thenReturn(Optional.of(list));
        when(campaigns.findById(10L)).thenReturn(Optional.of(campaign(10L, "7월 캠페인", "s")));

        List<ContactActivityView> rows = service.activity(77L, 30);

        assertThat(rows).extracting(ContactActivityView::type).containsExactly(
                "LIST_OPTOUT", "UNSUBSCRIBED", "CLICKED", "OPENED",
                "SUPPRESSED_SKIP", "BOUNCED", "SENT", "SIGNUP");
        assertThat(byType(rows, "LIST_OPTOUT").detail()).isEqualTo("뉴스레터");
        assertThat(byType(rows, "UNSUBSCRIBED").detail()).isEqualTo("unsubscribe");
        assertThat(byType(rows, "CLICKED").detail()).isEqualTo("https://x.com/p");
        assertThat(byType(rows, "BOUNCED").detail()).isEqualTo("mailbox full");
        assertThat(byType(rows, "SENT").campaignName()).isEqualTo("7월 캠페인");
        assertThat(byType(rows, "SENT").campaignId()).isEqualTo(10L);
        assertThat(byType(rows, "SIGNUP").occurredAt()).isEqualTo(T0);
        assertThat(byType(rows, "SIGNUP").campaignId()).isNull();
        assertThat(byType(rows, "UNSUBSCRIBED").campaignId()).isNull();
    }

    @Test
    void activity_isNewestFirstAndCapsAtLimit() {
        when(contacts.findById(77L)).thenReturn(Optional.of(contactAt(T0)));
        when(messages.findRecentByContact(eq(77L), anyInt())).thenReturn(List.of(
                message(10L, MessageStatus.SENT, null, T0.plusSeconds(300)),
                message(11L, MessageStatus.SENT, null, T0.plusSeconds(200)),
                message(12L, MessageStatus.SENT, null, T0.plusSeconds(100))));
        when(campaigns.findById(anyLong())).thenReturn(Optional.of(campaign(10L, "c", "s")));

        List<ContactActivityView> rows = service.activity(77L, 2);

        // Four candidate rows (3 sends + signup) trimmed to the 2 newest.
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).occurredAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(rows.get(1).occurredAt()).isEqualTo(T0.plusSeconds(200));
    }

    @Test
    void activity_campaignNameFallsBackToSubject() {
        when(contacts.findById(77L)).thenReturn(Optional.of(contactAt(T0)));
        when(messages.findRecentByContact(eq(77L), anyInt())).thenReturn(List.of(
                message(10L, MessageStatus.SENT, null, T0.plusSeconds(100))));
        when(campaigns.findById(10L)).thenReturn(Optional.of(campaign(10L, null, "제목만 있는 캠페인")));

        List<ContactActivityView> rows = service.activity(77L, 30);

        assertThat(byType(rows, "SENT").campaignName()).isEqualTo("제목만 있는 캠페인");
    }

    @Test
    void activity_deletedListFallsBackToPlaceholder() {
        when(contacts.findById(77L)).thenReturn(Optional.of(contactAt(T0)));
        when(listUnsubscribes.findByContact(77L)).thenReturn(List.of(
                new ListUnsubscribeRepository.OptOut(5L, "unsubscribe", T0.plusSeconds(100))));
        when(lists.findById(5L)).thenReturn(Optional.empty());

        List<ContactActivityView> rows = service.activity(77L, 30);

        assertThat(byType(rows, "LIST_OPTOUT").detail()).isEqualTo("(삭제된 리스트)");
    }

    @Test
    void activity_unknownContactThrows() {
        when(contacts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activity(99L, 30))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void messages_mapsDeliveriesWithCampaignNames() {
        when(contacts.findById(77L)).thenReturn(Optional.of(contactAt(T0)));
        MailMessage delivered = message(10L, MessageStatus.SENT, null, T0.plusSeconds(100));
        delivered.setId(1000L);
        when(messages.findRecentByContact(eq(77L), anyInt())).thenReturn(List.of(delivered));
        when(campaigns.findById(10L)).thenReturn(Optional.of(campaign(10L, "7월 캠페인", "s")));

        List<ContactMessageView> rows = service.messages(77L, 20);

        assertThat(rows).containsExactly(new ContactMessageView(
                1000L, 10L, "7월 캠페인", MessageStatus.SENT, T0.plusSeconds(100)));
    }

    @Test
    void messages_unknownContactThrows() {
        when(contacts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.messages(99L, 20))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static ContactActivityView byType(List<ContactActivityView> rows, String type) {
        return rows.stream().filter(r -> r.type().equals(type)).findFirst().orElseThrow();
    }

    private static Suppression suppressionAt(String reason, Instant createdAt) {
        Suppression s = Suppression.of(WS, "member@x.com", reason);
        s.setCreatedAt(createdAt);
        return s;
    }
}
