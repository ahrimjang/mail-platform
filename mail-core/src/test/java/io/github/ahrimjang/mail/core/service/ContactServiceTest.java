package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactRequest;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.common.ImportResult;
import io.github.ahrimjang.mail.common.UpdateContactRequest;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

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
    private ContactListRepository lists;

    @Mock
    private io.github.ahrimjang.mail.core.port.SuppressionRepository suppressions;
    @Mock
    private io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository listUnsubscribes;
    @Mock
    private ContactEngagementService engagement;
    @Mock
    private PlanLimits planLimits;   // mock 기본은 no-op(한도 여유), 임포트 예산은 null(무제한)

    private ContactService service;

    @BeforeEach
    void setUp() {
        service = new ContactService(contacts, lists, suppressions, listUnsubscribes, engagement, ctx, planLimits);
        // 주의: Mockito 는 스텁 없는 Long 반환에 null 이 아니라 0을 돌려준다 —
        // 0은 "수용량 0"으로 읽히므로, 기본을 무제한(null)으로 명시한다.
        org.mockito.Mockito.lenient()
                .when(planLimits.remainingContactCapacity(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(null);
    }

    /** Makes save(...) behave like a real repository: assigns an id and returns the entity. */
    private void stubSaveAssignsIds() {
        AtomicLong seq = new AtomicLong(100);
        when(contacts.save(any(Contact.class))).thenAnswer(inv -> {
            Contact c = inv.getArgument(0);
            c.setId(seq.incrementAndGet());
            return c;
        });
    }

    @Test
    void create_savesAndReturnsViewOfValidContact() {
        stubSaveAssignsIds();
        when(contacts.existsByWorkspaceAndEmail(WS, "a@b.com")).thenReturn(false);

        ContactView view = service.create(new ContactRequest("a@b.com", "Ahrim", "Jang", Map.of("plan", "pro")));

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contacts).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("a@b.com");
        assertThat(view.email()).isEqualTo("a@b.com");
        assertThat(view.firstName()).isEqualTo("Ahrim");
        assertThat(view.lastName()).isEqualTo("Jang");
    }

    @Test
    void create_rejectsEmailWithoutAtSign() {
        assertThatThrownBy(() -> service.create(new ContactRequest("not-an-email", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(contacts, never()).save(any());
    }

    @Test
    void create_rejectsExistingEmail() {
        when(contacts.existsByWorkspaceAndEmail(WS, "dup@b.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new ContactRequest("dup@b.com", null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(contacts, never()).save(any());
    }

    @Test
    void update_renamesButKeepsTheEmail() {
        Contact existing = Contact.of("a@b.com", "Old", "Name", Map.of());
        existing.setWorkspaceId(WS);
        existing.setId(7L);
        when(contacts.findById(7L)).thenReturn(Optional.of(existing));
        when(contacts.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactView view = service.update(7L, new UpdateContactRequest("아림", "장"));

        assertThat(view.email()).isEqualTo("a@b.com");
        assertThat(view.firstName()).isEqualTo("아림");
        assertThat(view.lastName()).isEqualTo("장");
        verify(contacts).save(existing);
    }

    @Test
    void update_blankNamesBecomeNull() {
        Contact existing = Contact.of("a@b.com", "Old", "Name", Map.of());
        existing.setWorkspaceId(WS);
        existing.setId(7L);
        when(contacts.findById(7L)).thenReturn(Optional.of(existing));
        when(contacts.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactView view = service.update(7L, new UpdateContactRequest("  ", null));

        assertThat(view.firstName()).isNull();
        assertThat(view.lastName()).isNull();
    }

    @Test
    void update_unknownContactThrows() {
        when(contacts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new UpdateContactRequest("a", "b")))
                .isInstanceOf(NoSuchElementException.class);
        verify(contacts, never()).save(any());
    }

    @Test
    void importCsv_countsNewAsImportedAndDuplicateOrInvalidAsSkipped() {
        stubSaveAssignsIds();
        Contact existing = Contact.of("dup@x.com", "Old", "Timer", null);
        existing.setWorkspaceId(WS);
        existing.setId(1L);
        when(contacts.findByWorkspaceAndEmail(eq(WS), anyString())).thenReturn(Optional.empty());
        when(contacts.findByWorkspaceAndEmail(WS, "dup@x.com")).thenReturn(Optional.of(existing));

        String csv = """
                new1@x.com,First,One
                new2@x.com,Second,Two
                dup@x.com,Dup,Line
                not-an-email,Bad,Line
                new3@x.com,Third,Three
                """;

        ImportResult result = service.importCsv(csv, null);

        assertThat(result).isEqualTo(new ImportResult(3, 2));
        verify(contacts, times(3)).save(any(Contact.class));
    }

    @org.junit.jupiter.api.Test
    void importCsv_stopsAtThePlanContactCapacity_keepingWhatWasAlreadyImported() {
        stubSaveAssignsIds();
        when(contacts.findByWorkspaceAndEmail(eq(WS), anyString())).thenReturn(Optional.empty());
        // 플랜의 남은 수용량 2명 — 3번째 신규부터는 예산 초과
        when(planLimits.remainingContactCapacity(WS)).thenReturn(2L);

        String csv = """
                new1@x.com,First,One
                new2@x.com,Second,Two
                new3@x.com,Third,Three
                """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importCsv(csv, null))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("2명은 추가");
        verify(contacts, times(2)).save(any(Contact.class));   // 예산만큼은 실제로 추가됨
    }

    @org.junit.jupiter.api.Test
    void create_isBlockedWhenTheContactLimitIsReached() {
        when(contacts.existsByWorkspaceAndEmail(WS, "full@x.com")).thenReturn(false);
        org.mockito.Mockito.doThrow(new PlanLimitExceededException("연락처 한도"))
                .when(planLimits).assertContactsAddable(WS, 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.create(new io.github.ahrimjang.mail.common.ContactRequest("full@x.com", null, null, null)))
                .isInstanceOf(PlanLimitExceededException.class);
        verify(contacts, org.mockito.Mockito.never()).save(any(Contact.class));
    }

    @Test
    void importCsv_withListId_addsEveryValidLineIncludingDuplicatesToList() {
        io.github.ahrimjang.mail.core.domain.ContactList target =
                io.github.ahrimjang.mail.core.domain.ContactList.of("타깃", null);
        target.setId(42L);
        target.setWorkspaceId(WS);
        when(lists.findById(42L)).thenReturn(Optional.of(target));
        stubSaveAssignsIds();
        Contact existing = Contact.of("dup@x.com", null, null, null);
        existing.setWorkspaceId(WS);
        existing.setId(7L);
        when(contacts.findByWorkspaceAndEmail(eq(WS), anyString())).thenReturn(Optional.empty());
        when(contacts.findByWorkspaceAndEmail(WS, "dup@x.com")).thenReturn(Optional.of(existing));

        String csv = """
                new1@x.com,First,One
                dup@x.com,Dup,Line
                not-an-email
                """;

        service.importCsv(csv, 42L);

        // Both the newly imported and the pre-existing contact join the list; the invalid line does not.
        verify(lists, times(2)).addMember(eq(42L), any(Long.class));
        verify(lists).addMember(42L, 7L);
    }

    @Test
    void importCsv_withoutListId_neverTouchesListMembership() {
        stubSaveAssignsIds();
        when(contacts.findByWorkspaceAndEmail(eq(WS), anyString())).thenReturn(Optional.empty());

        service.importCsv("only@x.com,Only,One", null);

        verify(lists, never()).addMember(any(), any());
    }

    @Test
    void importCsv_lineWithOnlyEmailStillImports() {
        stubSaveAssignsIds();
        when(contacts.findByWorkspaceAndEmail(WS, "bare@x.com")).thenReturn(Optional.empty());

        ImportResult result = service.importCsv("bare@x.com", null);

        assertThat(result).isEqualTo(new ImportResult(1, 0));
        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contacts).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("bare@x.com");
        assertThat(captor.getValue().getFirstName()).isNull();
        assertThat(captor.getValue().getLastName()).isNull();
    }

    @Test
    void create_stampsManualConsentProvenance() {
        stubSaveAssignsIds();

        service.create(new ContactRequest("new@x.com", null, null, null));

        org.mockito.ArgumentCaptor<Contact> captor = org.mockito.ArgumentCaptor.forClass(Contact.class);
        verify(contacts).save(captor.capture());
        assertThat(captor.getValue().getConsentSource()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getConsentedAt()).isNotNull();
    }

    @Test
    void importCsv_stampsCsvImportConsentProvenance() {
        stubSaveAssignsIds();
        when(contacts.findByWorkspaceAndEmail(eq(WS), anyString())).thenReturn(Optional.empty());

        service.importCsv("fresh@x.com,F,One", null);

        org.mockito.ArgumentCaptor<Contact> captor = org.mockito.ArgumentCaptor.forClass(Contact.class);
        verify(contacts).save(captor.capture());
        assertThat(captor.getValue().getConsentSource()).isEqualTo("CSV_IMPORT");
        assertThat(captor.getValue().getConsentedAt()).isNotNull();
    }

    @Test
    void page_enrichesRowsWithBatchLookupsInsteadOfPerRowCalls() {
        Contact a = Contact.of("a@x.com", null, null, null);
        a.setWorkspaceId(WS);
        a.setId(1L);
        Contact b = Contact.of("b@x.com", null, null, null);
        b.setWorkspaceId(WS);
        b.setId(2L);
        when(contacts.search(WS, null, null, null, 0, 25)).thenReturn(List.of(a, b));
        when(contacts.countSearch(WS, null, null, null)).thenReturn(2L);
        when(lists.findMembershipsByContactIds(List.of(1L, 2L)))
                .thenReturn(List.of(new io.github.ahrimjang.mail.core.port.ContactListRepository.Membership(1L, 5L)));
        when(listUnsubscribes.findByContactIds(List.of(1L, 2L)))
                .thenReturn(List.of(new io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository.ContactOptOut(1L, 5L)));
        when(suppressions.findSuppressedEmails(WS, List.of("a@x.com", "b@x.com")))
                .thenReturn(List.of("b@x.com"));

        var page = service.page(null, null, null, null, null, 0, 25);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().get(0).listIds()).containsExactly(5L);
        assertThat(page.rows().get(0).optOutListIds()).containsExactly(5L);
        assertThat(page.rows().get(0).suppressed()).isFalse();
        assertThat(page.rows().get(1).suppressed()).isTrue();
    }
}
