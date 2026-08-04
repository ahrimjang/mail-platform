package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EmailDraftView;
import io.github.ahrimjang.mail.common.SaveEmailDraftRequest;
import io.github.ahrimjang.mail.core.domain.EmailDraft;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.EmailDraftRepository;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailDraftServiceTest {

    private static final long WS = 7L;

    @Mock EmailDraftRepository drafts;
    @Mock TemplateRepository templates;
    @Mock WorkspaceContext ctx;

    EmailDraftService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
        service = new EmailDraftService(drafts, templates, ctx);
    }

    private static Template template(Long workspaceId) {
        Template t = new Template();
        t.setId(3L);
        t.setWorkspaceId(workspaceId);
        t.setName("빌트인 뉴스레터");
        t.setSubject("템플릿 제목");
        t.setHtmlBody("<p>템플릿 본문</p>");
        return t;
    }

    @Test
    void create_fromTemplate_copiesContentAndTracksLineage() {
        when(templates.findById(3L)).thenReturn(Optional.of(template(null)));   // 빌트인
        when(drafts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailDraftView view = service.create(new SaveEmailDraftRequest(null, null, null, 3L));

        assertThat(view.name()).isEqualTo("빌트인 뉴스레터");
        assertThat(view.subject()).isEqualTo("템플릿 제목");
        assertThat(view.htmlBody()).isEqualTo("<p>템플릿 본문</p>");
        assertThat(view.sourceTemplateId()).isEqualTo(3L);
    }

    @Test
    void create_fromForeignTemplate_notFound() {
        // 남의 워크스페이스 템플릿은 존재 자체를 숨긴다 (404 — V16 원칙)
        when(templates.findById(3L)).thenReturn(Optional.of(template(99L)));

        assertThatThrownBy(() -> service.create(new SaveEmailDraftRequest(null, null, null, 3L)))
                .isInstanceOf(NoSuchElementException.class);
        verify(drafts, never()).save(any());
    }

    @Test
    void create_blank_requiresAllFields() {
        assertThatThrownBy(() -> service.create(new SaveEmailDraftRequest("이름만", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_requestFieldsOverrideTemplateContent() {
        when(templates.findById(3L)).thenReturn(Optional.of(template(WS)));
        when(drafts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailDraftView view = service.create(
                new SaveEmailDraftRequest("내 이름", "내 제목", null, 3L));

        assertThat(view.name()).isEqualTo("내 이름");
        assertThat(view.subject()).isEqualTo("내 제목");
        assertThat(view.htmlBody()).isEqualTo("<p>템플릿 본문</p>");   // 안 넘긴 것만 템플릿에서
    }

    @Test
    void create_duplicateName_getsAutoNumbered() {
        EmailDraft first = EmailDraft.of(WS, "새 이메일", "s", "<p>b</p>", null);
        EmailDraft second = EmailDraft.of(WS, "새 이메일 2", "s", "<p>b</p>", null);
        when(drafts.findByWorkspaceId(WS)).thenReturn(java.util.List.of(first, second));
        when(drafts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailDraftView view = service.create(new SaveEmailDraftRequest("새 이메일", "제목", "<p>본문</p>", null));

        assertThat(view.name()).isEqualTo("새 이메일 3");   // 2까지 차 있으면 3
    }

    @Test
    void get_foreignDraft_notFound() {
        EmailDraft foreign = EmailDraft.of(99L, "남의 것", "s", "<p>b</p>", null);
        foreign.setId(5L);
        when(drafts.findById(5L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.get(5L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void update_replacesContentAndTouchesTimestamp() {
        EmailDraft mine = EmailDraft.of(WS, "이전", "이전 제목", "<p>이전</p>", null);
        mine.setId(5L);
        java.time.Instant before = mine.getUpdatedAt();
        when(drafts.findById(5L)).thenReturn(Optional.of(mine));
        when(drafts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailDraftView view = service.update(5L,
                new SaveEmailDraftRequest("새 이름", "새 제목", "<p>새 본문</p>", null));

        assertThat(view.subject()).isEqualTo("새 제목");
        assertThat(mine.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void delete_foreignDraft_notFound() {
        EmailDraft foreign = EmailDraft.of(99L, "남의 것", "s", "<p>b</p>", null);
        foreign.setId(5L);
        when(drafts.findById(5L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.delete(5L)).isInstanceOf(NoSuchElementException.class);
        verify(drafts, never()).deleteById(any());
    }
}
