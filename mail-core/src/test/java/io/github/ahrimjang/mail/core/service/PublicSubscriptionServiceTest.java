package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.SubscribeRequest;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicSubscriptionServiceTest {

    private static final long WS = 7L;
    private static final String KEY = "opk_valid";

    @Mock WorkspaceRepository workspaces;
    @Mock ContactRepository contacts;
    @Mock ContactListRepository lists;
    @Mock PlanLimits planLimits;
    @Mock WorkspaceContext ctx;

    PublicSubscriptionService service;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new PublicSubscriptionService(workspaces, contacts, lists, planLimits, ctx);
        workspace = Workspace.of("acme");
        workspace.setId(WS);
        workspace.setApiKey(KEY);
        lenient().when(workspaces.findByApiKey(KEY)).thenReturn(Optional.of(workspace));
    }

    @Test
    void subscribe_invalidKey_unauthorized() {
        when(workspaces.findByApiKey("opk_wrong")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscribe("opk_wrong",
                new SubscribeRequest("a@x.com", null, null, null)))
                .isInstanceOf(InvalidApiKeyException.class);
        assertThatThrownBy(() -> service.subscribe(null,
                new SubscribeRequest("a@x.com", null, null, null)))
                .isInstanceOf(InvalidApiKeyException.class);
        verify(contacts, never()).save(any());
    }

    @Test
    void subscribe_newAddress_createsContactWithApiConsent() {
        when(contacts.findByWorkspaceAndEmail(WS, "new@x.com")).thenReturn(Optional.empty());
        when(contacts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean created = service.subscribe(KEY, new SubscribeRequest("new@x.com", "길동", "홍", null));

        assertThat(created).isTrue();
        ArgumentCaptor<Contact> saved = ArgumentCaptor.forClass(Contact.class);
        verify(contacts).save(saved.capture());
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(WS);
        assertThat(saved.getValue().getConsentSource()).isEqualTo("API");   // 구독 신청 = 동의
        assertThat(saved.getValue().getConsentedAt()).isNotNull();
        verify(planLimits).assertContactsAddable(WS, 1);
    }

    @Test
    void subscribe_existingAddress_isIdempotentAndSkipsLimitCheck() {
        Contact existing = Contact.of("dup@x.com", null, null, null);
        existing.setId(11L);
        existing.setWorkspaceId(WS);
        when(contacts.findByWorkspaceAndEmail(WS, "dup@x.com")).thenReturn(Optional.of(existing));

        boolean created = service.subscribe(KEY, new SubscribeRequest("dup@x.com", null, null, null));

        assertThat(created).isFalse();
        verify(contacts, never()).save(any());
        verify(planLimits, never()).assertContactsAddable(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void subscribe_withList_addsMembership() {
        Contact existing = Contact.of("dup@x.com", null, null, null);
        existing.setId(11L);
        existing.setWorkspaceId(WS);
        when(contacts.findByWorkspaceAndEmail(WS, "dup@x.com")).thenReturn(Optional.of(existing));
        ContactList list = new ContactList();
        list.setId(3L);
        list.setWorkspaceId(WS);
        when(lists.findById(3L)).thenReturn(Optional.of(list));

        service.subscribe(KEY, new SubscribeRequest("dup@x.com", null, null, 3L));

        verify(lists).addMember(3L, 11L);
    }

    @Test
    void subscribe_foreignList_notFound() {
        Contact existing = Contact.of("dup@x.com", null, null, null);
        existing.setId(11L);
        existing.setWorkspaceId(WS);
        when(contacts.findByWorkspaceAndEmail(WS, "dup@x.com")).thenReturn(Optional.of(existing));
        ContactList foreign = new ContactList();
        foreign.setId(3L);
        foreign.setWorkspaceId(99L);   // 남의 리스트 — 존재를 숨긴다
        when(lists.findById(3L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.subscribe(KEY, new SubscribeRequest("dup@x.com", null, null, 3L)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(lists, never()).addMember(anyLong(), anyLong());
    }

    @Test
    void issueApiKey_adminOnly_andReplacesKey() {
        when(ctx.isAdmin()).thenReturn(true);
        when(ctx.currentWorkspaceId()).thenReturn(WS);
        when(workspaces.findById(WS)).thenReturn(Optional.of(workspace));
        when(workspaces.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String key = service.issueApiKey();

        assertThat(key).startsWith("opk_").hasSize(52);
        assertThat(workspace.getApiKey()).isEqualTo(key);   // 이전 키 즉시 대체
    }

    @Test
    void issueApiKey_nonAdmin_forbidden() {
        when(ctx.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> service.issueApiKey()).isInstanceOf(ForbiddenException.class);
        verify(workspaces, never()).save(any());
    }
}
