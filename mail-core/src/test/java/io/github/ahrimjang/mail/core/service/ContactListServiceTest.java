package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactListRequest;
import io.github.ahrimjang.mail.common.ContactListView;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.ContactList;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactListServiceTest {

    @Mock
    private ContactListRepository lists;

    @Mock
    private ContactRepository contacts;

    private ContactListService service;

    @BeforeEach
    void setUp() {
        service = new ContactListService(lists, contacts);
    }

    private ContactList listWithId(Long id, String name, String description) {
        ContactList l = ContactList.of(name, description);
        l.setId(id);
        return l;
    }

    private Contact contactWithId(Long id, String email) {
        Contact c = Contact.of(email, null, null, Map.of());
        c.setId(id);
        return c;
    }

    @Test
    void update_changesNameAndDescription() {
        when(lists.findById(5L)).thenReturn(Optional.of(listWithId(5L, "old", "old desc")));
        when(lists.save(any(ContactList.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lists.countMembers(5L)).thenReturn(3L);

        ContactListView view = service.update(5L, new ContactListRequest("new", "new desc"));

        ArgumentCaptor<ContactList> captor = ArgumentCaptor.forClass(ContactList.class);
        verify(lists).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("new");
        assertThat(captor.getValue().getDescription()).isEqualTo("new desc");
        assertThat(view.name()).isEqualTo("new");
        assertThat(view.memberCount()).isEqualTo(3L);
    }

    @Test
    void update_unknownListThrowsNotFound() {
        when(lists.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new ContactListRequest("x", null)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("list not found: 99");
        verify(lists, never()).save(any());
    }

    @Test
    void update_blankNameThrowsBadRequest() {
        when(lists.findById(5L)).thenReturn(Optional.of(listWithId(5L, "old", null)));

        assertThatThrownBy(() -> service.update(5L, new ContactListRequest("  ", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(lists, never()).save(any());
    }

    @Test
    void delete_delegatesToRepository() {
        service.delete(5L);

        verify(lists).deleteById(5L);
    }

    @Test
    void listsOf_returnsListIdsForContact() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "a@x.com")));
        when(lists.findListIdsByContactId(7L)).thenReturn(List.of(1L, 2L));

        assertThat(service.listsOf(7L)).containsExactly(1L, 2L);
    }

    @Test
    void listsOf_unknownContactThrowsNotFound() {
        when(contacts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listsOf(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("contact not found: 99");
    }

    @Test
    void replaceListsOf_replacesMembershipsWithDistinctIdsAndReturnsNewSet() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "a@x.com")));
        when(lists.findById(1L)).thenReturn(Optional.of(listWithId(1L, "A", null)));
        when(lists.findById(2L)).thenReturn(Optional.of(listWithId(2L, "B", null)));
        when(lists.findListIdsByContactId(7L)).thenReturn(List.of(1L, 2L));

        List<Long> result = service.replaceListsOf(7L, List.of(1L, 2L, 1L));

        verify(lists).replaceMembershipsForContact(7L, List.of(1L, 2L));
        assertThat(result).containsExactly(1L, 2L);
    }

    @Test
    void replaceListsOf_unknownListThrowsNotFoundAndReplacesNothing() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "a@x.com")));
        when(lists.findById(1L)).thenReturn(Optional.of(listWithId(1L, "A", null)));
        when(lists.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceListsOf(7L, List.of(1L, 99L)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("list not found: 99");
        verify(lists, never()).replaceMembershipsForContact(anyLong(), anyList());
    }

    @Test
    void replaceListsOf_emptySetClearsMemberships() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "a@x.com")));
        when(lists.findListIdsByContactId(7L)).thenReturn(List.of());

        List<Long> result = service.replaceListsOf(7L, List.of());

        verify(lists).replaceMembershipsForContact(7L, List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void replaceListsOf_nullListIdsThrowsBadRequest() {
        when(contacts.findById(7L)).thenReturn(Optional.of(contactWithId(7L, "a@x.com")));

        assertThatThrownBy(() -> service.replaceListsOf(7L, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(lists, never()).replaceMembershipsForContact(anyLong(), anyList());
    }
}
