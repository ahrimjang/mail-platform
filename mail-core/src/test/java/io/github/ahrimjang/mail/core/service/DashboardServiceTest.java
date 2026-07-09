package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.DashboardDay;
import io.github.ahrimjang.mail.common.DashboardView;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository.DailyEngagement;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.DailyOutcome;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private MailMessageRepository messages;
    @Mock
    private EmailEventRepository events;
    @Mock
    private ContactRepository contacts;
    @Mock
    private SuppressionRepository suppressions;

    @InjectMocks
    private DashboardService service;

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    @Test
    void stats_fillsEveryDayOfTheWindowEvenWithoutActivity() {
        when(messages.aggregateDailyOutcomes(any(), any())).thenReturn(List.of());
        when(events.aggregateDailyEngagement(any(), any())).thenReturn(List.of());
        when(contacts.count()).thenReturn(3L);
        when(suppressions.count()).thenReturn(1L);

        DashboardView view = service.stats(7);

        assertThat(view.contacts()).isEqualTo(3);
        assertThat(view.suppressed()).isEqualTo(1);
        assertThat(view.daily()).hasSize(7);
        assertThat(view.daily().get(0).date()).isEqualTo(today().minusDays(6));
        assertThat(view.daily().get(6).date()).isEqualTo(today());
        assertThat(view.daily()).allSatisfy(day -> {
            assertThat(day.sent()).isZero();
            assertThat(day.failed()).isZero();
            assertThat(day.opened()).isZero();
            assertThat(day.clicked()).isZero();
        });
    }

    @Test
    void stats_mergesOutcomesAndEngagementOntoTheSameCalendar() {
        LocalDate yesterday = today().minusDays(1);
        when(messages.aggregateDailyOutcomes(any(), any())).thenReturn(List.of(
                new DailyOutcome(yesterday, MessageStatus.SENT, 100),
                new DailyOutcome(yesterday, MessageStatus.FAILED, 2),
                new DailyOutcome(yesterday, MessageStatus.BOUNCED, 3),
                new DailyOutcome(today(), MessageStatus.SENT, 40)));
        when(events.aggregateDailyEngagement(any(), any())).thenReturn(List.of(
                new DailyEngagement(yesterday, EventType.OPEN, 55),
                new DailyEngagement(yesterday, EventType.CLICK, 12),
                // BOUNCE events must not leak into opened/clicked
                new DailyEngagement(yesterday, EventType.BOUNCE, 3)));

        DashboardView view = service.stats(3);

        DashboardDay mid = view.daily().get(1);
        assertThat(mid.date()).isEqualTo(yesterday);
        assertThat(mid.sent()).isEqualTo(100);
        assertThat(mid.failed()).isEqualTo(5); // FAILED + BOUNCED folded together
        assertThat(mid.opened()).isEqualTo(55);
        assertThat(mid.clicked()).isEqualTo(12);

        DashboardDay last = view.daily().get(2);
        assertThat(last.sent()).isEqualTo(40);
        assertThat(last.opened()).isZero();
    }

    @Test
    void stats_ignoresRowsOutsideTheRequestedWindow() {
        when(messages.aggregateDailyOutcomes(any(), any())).thenReturn(List.of(
                new DailyOutcome(today().minusDays(30), MessageStatus.SENT, 999)));
        when(events.aggregateDailyEngagement(any(), any())).thenReturn(List.of());

        DashboardView view = service.stats(7);

        assertThat(view.daily()).allSatisfy(day -> assertThat(day.sent()).isZero());
    }

    @Test
    void stats_clampsTheWindowToSaneBounds() {
        when(messages.aggregateDailyOutcomes(any(), any())).thenReturn(List.of());
        when(events.aggregateDailyEngagement(any(), any())).thenReturn(List.of());

        assertThat(service.stats(0).daily()).hasSize(1);
        assertThat(service.stats(10_000).daily()).hasSize(DashboardService.MAX_DAYS);

        // The since-boundary handed to the ports must match the clamped window.
        verify(messages).aggregateDailyOutcomes(
                today().minusDays(DashboardService.MAX_DAYS - 1L)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
    }

    @Test
    void stats_windowBoundaryUsesInstantOfLocalMidnight() {
        when(messages.aggregateDailyOutcomes(any(), any())).thenReturn(List.of());
        when(events.aggregateDailyEngagement(any(), any())).thenReturn(List.of());

        service.stats(1);

        Instant startOfToday = today().atStartOfDay(ZoneId.systemDefault()).toInstant();
        verify(messages).aggregateDailyOutcomes(startOfToday, ZoneId.systemDefault());
        verify(events).aggregateDailyEngagement(startOfToday, ZoneId.systemDefault());
    }
}
