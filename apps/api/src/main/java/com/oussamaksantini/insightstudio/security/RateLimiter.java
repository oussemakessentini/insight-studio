package com.oussamaksantini.insightstudio.security;

import com.oussamaksantini.insightstudio.common.web.TooManyRequestsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sliding-window rate limits kept in PostgreSQL ({@code rate_limit_hits}), so every API instance
 * counts the same events and restarts forget nothing. Time comes from the database clock, so
 * instances never disagree about a window.
 *
 * <p>Each call runs in its own transaction: a counted failure stays counted even when the caller's
 * work then fails and rolls back. {@link #acquire} takes a per-bucket advisory lock, so concurrent
 * requests cannot all pass the check before any of them is recorded.
 */
@Component
public class RateLimiter {

    /** Hits older than this are purged; every limit's window must fit in it. */
    static final Duration RETENTION = Duration.ofDays(1);
    private static final int PURGE_EVERY = 500;
    private static final String MESSAGE = "Too many attempts. Try again later.";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AtomicInteger writes = new AtomicInteger();

    RateLimiter(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @throws TooManyRequestsException when {@code subject} has reached {@code limit} (nothing is recorded) */
    public void check(RateLimit limit, String subject) {
        OptionalLong wait = transactions.execute(status -> waitSeconds(bucket(limit, subject), limit));
        if (wait.isPresent()) {
            throw new TooManyRequestsException(MESSAGE, wait.getAsLong());
        }
    }

    /** Counts one event (e.g. a failed sign-in) for {@code subject}. */
    public void record(RateLimit limit, String subject) {
        transactions.executeWithoutResult(status -> insert(bucket(limit, subject)));
        purgeOccasionally();
    }

    /**
     * Counts one event unless the limit is reached.
     *
     * @throws TooManyRequestsException when {@code subject} has reached {@code limit} (nothing is recorded)
     */
    public void acquire(RateLimit limit, String subject) {
        OptionalLong wait = tryAcquireOrWait(limit, subject);
        if (wait.isPresent()) {
            throw new TooManyRequestsException(MESSAGE, wait.getAsLong());
        }
    }

    /** Like {@link #acquire}, answering false instead of throwing. */
    public boolean tryAcquire(RateLimit limit, String subject) {
        return tryAcquireOrWait(limit, subject).isEmpty();
    }

    /** Forgets the subject's events (e.g. a successful sign-in clears the email's failures). */
    public void clear(RateLimit limit, String subject) {
        transactions.executeWithoutResult(status ->
                jdbc.update("DELETE FROM rate_limit_hits WHERE bucket = :bucket", Map.of("bucket", bucket(limit, subject))));
    }

    private OptionalLong tryAcquireOrWait(RateLimit limit, String subject) {
        String bucket = bucket(limit, subject);
        OptionalLong wait = transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:bucket, 0))", Map.of("bucket", bucket),
                    (ResultSetExtractor<Void>) rs -> null);
            OptionalLong seconds = waitSeconds(bucket, limit);
            if (seconds.isEmpty()) {
                insert(bucket);
            }
            return seconds;
        });
        if (wait.isEmpty()) {
            purgeOccasionally();
        }
        return wait;
    }

    /**
     * Empty when the bucket has fewer than {@code max} events in the window; otherwise the seconds
     * until the oldest of the latest {@code max} events leaves it.
     */
    private OptionalLong waitSeconds(String bucket, RateLimit limit) {
        List<Long> waits = jdbc.queryForList("""
                SELECT GREATEST(1, CEIL(EXTRACT(EPOCH FROM (hit_at + make_interval(secs => :window) - now()))))::bigint
                FROM rate_limit_hits
                WHERE bucket = :bucket AND hit_at > now() - make_interval(secs => :window)
                ORDER BY hit_at DESC
                OFFSET :offset LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("bucket", bucket)
                        .addValue("window", limit.window().toSeconds())
                        .addValue("offset", limit.max() - 1), Long.class);
        return waits.isEmpty() ? OptionalLong.empty() : OptionalLong.of(waits.getFirst());
    }

    private void insert(String bucket) {
        jdbc.update("INSERT INTO rate_limit_hits (bucket) VALUES (:bucket)", Map.of("bucket", bucket));
    }

    private void purgeOccasionally() {
        if (writes.incrementAndGet() % PURGE_EVERY == 0) {
            transactions.executeWithoutResult(status -> jdbc.update(
                    "DELETE FROM rate_limit_hits WHERE hit_at < now() - make_interval(secs => :retention)",
                    Map.of("retention", RETENTION.toSeconds())));
        }
    }

    /** {@code name:sha256(subject)}; subjects are compared case-insensitively. */
    static String bucket(RateLimit limit, String subject) {
        String normal = subject == null ? "" : subject.strip().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normal.getBytes(StandardCharsets.UTF_8));
            return limit.name() + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
