package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactRequest;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.common.ImportResult;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
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

    @Mock
    private ContactRepository contacts;

    @Mock
    private ContactListRepository lists;

    private ContactService service;

    @BeforeEach
    void setUp() {
        service = new ContactService(contacts, lists);
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
        when(contacts.existsByEmail("a@b.com")).thenReturn(false);

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
        when(contacts.existsByEmail("dup@b.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new ContactRequest("dup@b.com", null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(contacts, never()).save(any());
    }

    @Test
    void importCsv_countsNewAsImportedAndDuplicateOrInvalidAsSkipped() {
        stubSaveAssignsIds();
        Contact existing = Contact.of("dup@x.com", "Old", "Timer", null);
        existing.setId(1L);
        when(contacts.findByEmail(anyString())).thenReturn(Optional.empty());
        when(contacts.findByEmail("dup@x.com")).thenReturn(Optional.of(existing));

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

    @Test
    void importCsv_withListId_addsEveryValidLineIncludingDuplicatesToList() {
        stubSaveAssignsIds();
        Contact existing = Contact.of("dup@x.com", null, null, null);
        existing.setId(7L);
        when(contacts.findByEmail(anyString())).thenReturn(Optional.empty());
        when(contacts.findByEmail("dup@x.com")).thenReturn(Optional.of(existing));

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
        when(contacts.findByEmail(anyString())).thenReturn(Optional.empty());

        service.importCsv("only@x.com,Only,One", null);

        verify(lists, never()).addMember(any(), any());
    }

    @Test
    void importCsv_lineWithOnlyEmailStillImports() {
        stubSaveAssignsIds();
        when(contacts.findByEmail("bare@x.com")).thenReturn(Optional.empty());

        ImportResult result = service.importCsv("bare@x.com", null);

        assertThat(result).isEqualTo(new ImportResult(1, 0));
        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contacts).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("bare@x.com");
        assertThat(captor.getValue().getFirstName()).isNull();
        assertThat(captor.getValue().getLastName()).isNull();
    }
}
