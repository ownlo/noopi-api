package com.noopi.metrics;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MetricsPersistence {
    private final String instanceId = UUID.randomUUID().toString();
    private final MetricsCollector collector;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public MetricsPersistence(MetricsCollector collector, JdbcTemplate jdbc,
                              org.springframework.transaction.PlatformTransactionManager manager) {
        this.collector = collector; this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    // Serializes flushes only, never room actions. Absolute snapshots also tolerate an uncertain commit.
    public synchronized void flush() {
        var snapshot = collector.snapshot();
        transaction.executeWithoutResult(status -> {
            for (var row : snapshot.rooms()) jdbc.update("""
                INSERT INTO daily_room_metrics
                  (instance_id, metric_date, rooms_created, rooms_closed, duration_millis, max_duration_millis)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rooms_created = VALUES(rooms_created), rooms_closed = VALUES(rooms_closed),
                  duration_millis = VALUES(duration_millis), max_duration_millis = VALUES(max_duration_millis)
                """, instanceId, row.date(), row.created(), row.closed(), row.duration(), row.maxDuration());
            for (var row : snapshot.games()) jdbc.update("""
                INSERT INTO daily_game_metrics
                  (instance_id, metric_date, game_type, started, finished, cancelled, participant_count)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE started = VALUES(started), finished = VALUES(finished),
                  cancelled = VALUES(cancelled), participant_count = VALUES(participant_count)
                """, instanceId, row.key().date(), row.key().type(), row.started(), row.finished(), row.cancelled(), row.participants());
        });
    }
    @PreDestroy
    @Scheduled(fixedDelayString = "${noopi.metrics.flush-interval-ms:10000}")
    public void persist() {
        try { flush(); }
        catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).error("Daily metrics persistence failed; retained for retry ({})", ex.getClass().getSimpleName());
        }
    }
}
