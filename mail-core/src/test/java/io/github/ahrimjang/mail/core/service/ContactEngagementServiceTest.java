package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactEngagementView;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactEngagementServiceTest {

    /** The acting tenant every scoped call resolves to in these tests. */
    private static final long WS = 7L;

    @Mock
    private WorkspaceContext ctx;

    @BeforeEach
    void stubWorkspaceContext() {
        org.mockito.Mockito.lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

    @Mock
    private ContactRepository contacts;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private EmailEventRepository events;

    @InjectMocks
    private ContactEngagementService service;

    private static Contact contact(long id, String email) {
        Contact c = Contact.of(email, null, null, null);
        c.setWorkspaceId(WS);
        c.setId(id);
        return c;
    }

    /** Three contacts: heavy clicker, opener-only, and one never delivered to. */
    private void stubTypicalAudience() {
        when(contacts.findByWorkspace(WS)).thenReturn(List.of(
                contact(1L, "clicker@x.com"),
                contact(2L, "opener@x.com"),
                contact(3L, "silent@x.com")));
        when(messages.countSentByContact()).thenReturn(List.of(
                new MailMessageRepository.ContactSentCount(1L, 4),
                new MailMessageRepository.ContactSentCount(2L, 4)));
        when(events.countEngagementByContact()).thenReturn(List.of(
                new EmailEventRepository.ContactEngagement(1L, 4, 2),
                new EmailEventRepository.ContactEngagement(2L, 2, 0)));
    }

    @Test
    void engagementExcludesContactsWithoutDeliveriesAndRanksEngagedFirst() {
        stubTypicalAudience();

        List<ContactEngagementView> result = service.engagement(1, 0, 0);

        // silent@x.com has no deliveries — no rate to speak of, so no row.
        assertThat(result).extracting(ContactEngagementView::email)
                .containsExactly("clicker@x.com", "opener@x.com");
        assertThat(result.get(0).sent()).isEqualTo(4);
        assertThat(result.get(0).opened()).isEqualTo(4);
        assertThat(result.get(0).clicked()).isEqualTo(2);
    }

    @Test
    void openRateThresholdFiltersBelowIt() {
        stubTypicalAudience();

        // opener has 2/4 = 50% open rate; a 75% floor keeps only the clicker (100%).
        List<ContactEngagementView> result = service.engagement(1, 75, 0);

        assertThat(result).extracting(ContactEngagementView::email)
                .containsExactly("clicker@x.com");
    }

    @Test
    void clickRateThresholdFiltersBelowIt() {
        stubTypicalAudience();

        // clicker has 2/4 = 50% click rate; opener has 0%.
        List<ContactEngagementView> result = service.engagement(1, 0, 50);

        assertThat(result).extracting(ContactEngagementView::email)
                .containsExactly("clicker@x.com");
    }

    @Test
    void minSentExcludesContactsWithTooFewDeliveries() {
        stubTypicalAudience();

        assertThat(service.engagement(5, 0, 0)).isEmpty();
    }

    @Test
    void minSentIsFlooredAtOneSoRatesAlwaysHaveADenominator() {
        stubTypicalAudience();

        // Even asking for minSent=0 must not admit the never-delivered contact.
        assertThat(service.engagement(0, 0, 0))
                .extracting(ContactEngagementView::email)
                .doesNotContain("silent@x.com");
    }
}
