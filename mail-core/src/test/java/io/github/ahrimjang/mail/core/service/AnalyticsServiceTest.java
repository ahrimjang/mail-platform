package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.AudienceHealthView;
import io.github.ahrimjang.mail.common.LinkClicksView;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository.LinkClicks;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository.ListCount;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository.ReasonCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private EmailEventRepository events;

    @Mock
    private SuppressionRepository suppressions;

    @Mock
    private ListUnsubscribeRepository listUnsubscribes;

    @Mock
    private ContactListRepository lists;

    @InjectMocks
    private AnalyticsService service;

    @Test
    void topLinks_mapsPortRowsAndClampsLimit() {
        when(events.topClickedLinks(any(Instant.class), eq(AnalyticsService.MAX_LINKS)))
                .thenReturn(List.of(new LinkClicks("https://x.com/a", 12, 9)));

        // Absurd inputs are clamped to sane ceilings instead of hitting the DB raw.
        List<LinkClicksView> links = service.topLinks(9_999, 9_999);

        assertThat(links).containsExactly(new LinkClicksView("https://x.com/a", 12, 9));
        verify(events).topClickedLinks(any(Instant.class), eq(AnalyticsService.MAX_LINKS));
    }

    @Test
    void audienceHealth_mergesTotalsWithPeriodCountsPerReason() {
        when(suppressions.countByReason()).thenReturn(List.of(
                new ReasonCount("bounce", 40),
                new ReasonCount("unsubscribe", 25)));
        when(suppressions.countByReasonSince(any(Instant.class))).thenReturn(List.of(
                new ReasonCount("unsubscribe", 3)));
        when(listUnsubscribes.countByList()).thenReturn(List.of());

        AudienceHealthView view = service.audienceHealth(30);

        assertThat(view.suppressionReasons()).containsExactly(
                new AudienceHealthView.ReasonCount("bounce", 40, 0),
                new AudienceHealthView.ReasonCount("unsubscribe", 25, 3));
    }

    @Test
    void audienceHealth_resolvesListNamesAndMarksDeletedOnes() {
        when(suppressions.countByReason()).thenReturn(List.of());
        when(suppressions.countByReasonSince(any(Instant.class))).thenReturn(List.of());
        when(listUnsubscribes.countByList()).thenReturn(List.of(
                new ListCount(5L, 7),
                new ListCount(9L, 2)));
        ContactList newsletter = ContactList.of("뉴스레터", null);
        newsletter.setId(5L);
        when(lists.findById(5L)).thenReturn(Optional.of(newsletter));
        when(lists.findById(9L)).thenReturn(Optional.empty());

        AudienceHealthView view = service.audienceHealth(30);

        assertThat(view.listOptOuts()).containsExactly(
                new AudienceHealthView.ListOptOut(5L, "뉴스레터", 7),
                new AudienceHealthView.ListOptOut(9L, "(삭제된 리스트)", 2));
    }
}
