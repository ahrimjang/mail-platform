package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.UsageSnapshot;
import io.github.ahrimjang.mail.core.port.UsageSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * 어댑터: 월 마감 캡처를 INSERT...SELECT 한 문장으로 — 전 워크스페이스를 셋 기반으로
 * 캡처하고, 이미 있는 (워크스페이스, 월)은 PK 충돌 무시(ON CONFLICT DO NOTHING)로
 * 건너뛴다. 몇 번을 다시 실행해도 결과가 같다(멱등) — 스케줄러 중복 실행·부팅
 * 캐치업·워커 다중 기동 모두 이 성질 하나로 안전해진다.
 */
@Component
public class JdbcUsageSnapshotRepository implements UsageSnapshotRepository {

    private final JdbcTemplate jdbc;

    public JdbcUsageSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int captureMonth(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        // 그 달이 끝난 뒤에 생긴 워크스페이스는 청구 대상이 아니므로 제외
        return jdbc.update("""
                insert into workspace_usage_snapshots (workspace_id, period_month, sent_count, plan, captured_at)
                select w.id, ?,
                       (select count(*) from mail_messages m
                          join campaigns c on c.id = m.campaign_id
                         where c.workspace_id = w.id and m.status = 'SENT'
                           and m.updated_at >= ? and m.updated_at < ?),
                       w.plan, clock_timestamp()
                  from workspaces w
                 where w.created_at < ?
                on conflict (workspace_id, period_month) do nothing
                """,
                Date.valueOf(month.atDay(1)), Timestamp.from(start), Timestamp.from(end),
                Timestamp.from(end));
    }

    @Override
    public List<UsageSnapshot> findByWorkspace(Long workspaceId) {
        return jdbc.query("""
                select workspace_id, period_month, sent_count, plan, captured_at
                  from workspace_usage_snapshots
                 where workspace_id = ?
                 order by period_month desc
                """,
                (rs, i) -> new UsageSnapshot(
                        rs.getLong("workspace_id"),
                        YearMonth.from(rs.getDate("period_month").toLocalDate()),
                        rs.getLong("sent_count"),
                        Plan.valueOf(rs.getString("plan")),
                        rs.getTimestamp("captured_at").toInstant()),
                workspaceId);
    }
}
