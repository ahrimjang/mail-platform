package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.TemplateRequest;
import io.github.ahrimjang.mail.common.TemplateView;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    /** The acting tenant every scoped call resolves to in these tests. */
    private static final long WS = 7L;

    @Mock
    private WorkspaceContext ctx;

    @BeforeEach
    void stubWorkspaceContext() {
        org.mockito.Mockito.lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

    @Mock
    private TemplateRepository templates;
    @Mock
    private TemplateRenderer renderer;

    @InjectMocks
    private TemplateService service;

    private static Template builtin(long id, String key) {
        Template t = Template.create("edited name", "edited subject", "<p>edited</p>");
        t.setId(id);
        t.setBuiltinKey(key);
        return t;
    }

    @Test
    void seedBuiltins_insertsOnlyMissingSeeds() {
        // one seed already present (possibly user-edited), the rest missing
        when(templates.findByBuiltinKey(anyString())).thenAnswer(inv ->
                "newsletter".equals(inv.getArgument(0))
                        ? Optional.of(builtin(1L, "newsletter"))
                        : Optional.empty());
        when(templates.save(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));

        int inserted = service.seedBuiltins();

        assertThat(inserted).isEqualTo(BuiltinTemplates.ALL.size() - 1);
        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(templates, times(inserted)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(t -> assertThat(t.isBuiltin()).isTrue())
                .noneSatisfy(t -> assertThat(t.getBuiltinKey()).isEqualTo("newsletter"));
    }

    @Test
    void seedBuiltins_secondRunIsANoOp() {
        when(templates.findByBuiltinKey(anyString())).thenAnswer(inv ->
                Optional.of(builtin(1L, inv.getArgument(0))));

        assertThat(service.seedBuiltins()).isZero();
        verify(templates, never()).save(any());
        verify(templates, atLeastOnce()).findByBuiltinKey(anyString());
    }

    @Test
    void resetBuiltin_restoresTheOriginalSeedContent() {
        Template edited = builtin(7L, "promo");
        when(templates.findById(7L)).thenReturn(Optional.of(edited));
        when(templates.save(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));
        BuiltinTemplates.Seed seed = BuiltinTemplates.ALL.stream()
                .filter(s -> s.key().equals("promo")).findFirst().orElseThrow();

        TemplateView view = service.resetBuiltin(7L);

        assertThat(view.name()).isEqualTo(seed.name());
        assertThat(view.subject()).isEqualTo(seed.subject());
        assertThat(view.htmlBody()).isEqualTo(seed.htmlBody());
        assertThat(view.builtinKey()).isEqualTo("promo");
    }

    @Test
    void resetBuiltin_onUserTemplate_throwsIllegalState() {
        Template mine = Template.create("mine", "sub", "<p>b</p>");
        mine.setId(9L);
        when(templates.findById(9L)).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> service.resetBuiltin(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9");
        verify(templates, never()).save(any());
    }

    @Test
    void delete_builtin_throwsIllegalStateInsteadOfDeleting() {
        when(templates.findById(7L)).thenReturn(Optional.of(builtin(7L, "promo")));

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(IllegalStateException.class);
        verify(templates, never()).deleteById(7L);
    }

    @Test
    void delete_userTemplate_deletes() {
        Template mine = Template.create("mine", "sub", "<p>b</p>");
        mine.setId(9L);
        when(templates.findById(9L)).thenReturn(Optional.of(mine));

        service.delete(9L);

        verify(templates).deleteById(9L);
    }

    @Test
    void update_ofABuiltin_isReadOnlyAndPointsAtCopy() {
        Template builtin = Template.create("빌트인", "s", "b");
        builtin.setBuiltinKey("welcome");
        when(templates.findById(1L)).thenReturn(Optional.of(builtin));

        assertThatThrownBy(() -> service.update(1L, new TemplateRequest("이름", "제목", "<p>본문</p>")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
        verify(templates, never()).save(any());
    }

    @Test
    void copy_duplicatesIntoTheActingWorkspace() {
        Template builtin = Template.create("환영 메일", "환영해요", "<p>hi</p>");
        builtin.setBuiltinKey("welcome");
        when(templates.findById(1L)).thenReturn(Optional.of(builtin));
        when(templates.save(any())).thenAnswer(inv -> {
            Template t = inv.getArgument(0);
            t.setId(9L);
            return t;
        });

        var view = service.copy(1L);

        org.mockito.ArgumentCaptor<Template> captor = org.mockito.ArgumentCaptor.forClass(Template.class);
        verify(templates).save(captor.capture());
        assertThat(captor.getValue().getWorkspaceId()).isEqualTo(WS);
        assertThat(captor.getValue().getBuiltinKey()).isNull(); // the copy is an ordinary template
        assertThat(view.name()).isEqualTo("환영 메일 (복사)");
        assertThat(view.subject()).isEqualTo("환영해요");
    }
}
