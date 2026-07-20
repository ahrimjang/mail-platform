package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.port.SendRateLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter: token bucket in Postgres, one row per rate-limited workspace
 * ({@code workspace_send_buckets}). The refill-and-take is a single atomic
 * conditional UPDATE — the same pattern as the send/fan-out claims — so any
 * number of workers share one bucket without extra infrastructure. If this
 * UPDATE ever becomes the hot-path bottleneck, the port swaps to a Redis
 * implementation without touching core.
 *
 * <p>The workspace's configured rate is cached briefly so the common case
 * (unlimited workspace) costs zero extra queries per message after the first,
 * and a rate change from the console applies within {@link #RATE_CACHE_MS}.
 */
@Component
public class JdbcSendRateLimiter implements SendRateLimiter {

    private static final long RATE_CACHE_MS = 3_000;

    private final JdbcTemplate jdbc;
    private final Map<Long, CachedRate> rates = new ConcurrentHashMap<>();

    private record CachedRate(Integer ratePerSec, long readAtMillis) {
    }

    public JdbcSendRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(long workspaceId) {
        Integer rate = ratePerSec(workspaceId);
        if (rate == null) {
            return true;
        }
        return acquireToken(workspaceId, rate);
    }

    private boolean acquireToken(long workspaceId, int rate) {
        // Lazy bucket row, born full — a fresh limit starts with one second of burst.
        jdbc.update("""
                insert into workspace_send_buckets (workspace_id, tokens, refilled_at)
                values (?, ?, clock_timestamp())
                on conflict (workspace_id) do nothing
                """, workspaceId, rate);
        // Refill by elapsed time (capped at the configured rate = 1s of burst),
        // then take one token — atomically, so concurrent workers can't overdraw.
        // The WHERE needs no LEAST: rate >= 1 (CHECK), so the cap never rejects
        // an acquire the uncapped balance would allow.
        int taken = jdbc.update("""
                update workspace_send_buckets
                set tokens = least(cast(? as numeric),
                                   tokens + extract(epoch from (clock_timestamp() - refilled_at)) * ?) - 1,
                    refilled_at = clock_timestamp()
                where workspace_id = ?
                  and tokens + extract(epoch from (clock_timestamp() - refilled_at)) * ? >= 1
                """, rate, rate, workspaceId, rate);
        return taken == 1;
    }

    private Integer ratePerSec(long workspaceId) {
        long now = System.currentTimeMillis();
        CachedRate cached = rates.get(workspaceId);
        if (cached != null && now - cached.readAtMillis() < RATE_CACHE_MS) {
            return cached.ratePerSec();
        }
        // No Stream.findFirst here: an unlimited workspace maps to a null element,
        // which Optional.of inside findFirst rejects with an NPE.
        java.util.List<Integer> rows = jdbc.query(
                "select send_rate_per_sec from workspaces where id = ?",
                (rs, i) -> (Integer) rs.getObject("send_rate_per_sec"), workspaceId);
        Integer rate = rows.isEmpty() ? null : rows.get(0);
        rates.put(workspaceId, new CachedRate(rate, now));
        return rate;
    }
}
