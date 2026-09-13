package com.noopi;

import com.noopi.metrics.*;
import com.noopi.room.RoomRuntime;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"noopi.metrics.api-key=test-metrics-key", "spring.datasource.url=jdbc:h2:mem:metrics;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MetricsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MetricsQuery query;
    @Autowired MockMvc mvc;

    @Test void snapshotsAreIdempotentAndSumAcrossProcessRestarts() {
        var clock = new GameRulesTest.MutableClock();
        clock.now = Instant.parse("2025-01-01T00:00:00Z");
        var collector = new MetricsCollector(clock);
        var persistence = new MetricsPersistence(collector, jdbc, transactions);
        var room = new RoomRuntime(1, "ABCDEF", clock.instant());
        collector.created(room);
        room.session = new com.noopi.game.session.GameSessionRuntime(1, "BLIND", null);
        room.session.start(java.util.List.of(new com.noopi.game.session.GameSessionRuntime.Participant(1, "가")));
        collector.observe(room);
        clock.now = clock.now.plusSeconds(90);
        room.session.status = com.noopi.game.session.GameSessionRuntime.Status.FINISHED;
        room.closed = true;
        collector.closed(room);
        persistence.flush(); persistence.flush();
        var other = new MetricsCollector(clock);
        other.created(new RoomRuntime(1, "ABCDEF", clock.instant()));
        new MetricsPersistence(other, jdbc, transactions).flush();
        var days = query.daily(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-02")).days();
        assertThat(days.getFirst().roomsCreated()).isEqualTo(2);
        assertThat(days.getFirst().roomsClosed()).isEqualTo(1);
        assertThat(days.getFirst().games().get(1).started()).isEqualTo(1);
        assertThat(days.getFirst().games().get(1).finished()).isEqualTo(1);
        assertThat(days.getFirst().averageRoomDurationMinutes()).isEqualByComparingTo("1.50");
        assertThat(days.getLast().roomsCreated()).isZero();
        assertThat(days.getLast().games()).hasSize(2).allSatisfy(g -> assertThat(g.started()).isZero());
    }
    @Test void failedBatchRollsBackAndRetainedSnapshotCanBeRetried() {
        var clock = Clock.fixed(Instant.parse("2023-01-01T00:00:00Z"), ZoneOffset.UTC);
        var collector = new MetricsCollector(clock);
        var room = new RoomRuntime(1, "ABCDEF", clock.instant());
        collector.created(room);
        room.session = new com.noopi.game.session.GameSessionRuntime(1, "LIAR", null);
        room.session.start(java.util.List.of(new com.noopi.game.session.GameSessionRuntime.Participant(1, "가")));
        collector.observe(room);
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var failingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql, Object... args) {
                if (sql.contains("daily_game_metrics") && fail.getAndSet(false))
                    throw new org.springframework.dao.DataAccessResourceFailureException("test outage");
                return super.update(sql, args);
            }
        };
        var persistence = new MetricsPersistence(collector, failingJdbc, transactions);
        assertThatThrownBy(persistence::flush).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var day = LocalDate.parse("2023-01-01");
        assertThat(query.daily(day, day).days().getFirst().roomsCreated()).isZero();
        persistence.flush(); persistence.flush();
        assertThat(query.daily(day, day).days().getFirst().roomsCreated()).isEqualTo(1);
        assertThat(query.daily(day, day).days().getFirst().games().getFirst().started()).isEqualTo(1);
    }
    @Test void swaggerAndApiAuthenticationValidationAndEmptyDays() throws Exception {
        String path = "/api/metrics/daily?from=2024-01-01&to=2024-01-02";
        mvc.perform(get(path)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("METRICS_ACCESS_DENIED"));
        mvc.perform(get(path).header("X-Metrics-Key", "wrong")).andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-Metrics-Key", "test-metrics-key"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.days.length()").value(2)).andExpect(jsonPath("$.days[0].roomsCreated").value(0));
        for (String params : new String[]{"from=2024-02-01&to=2024-01-01", "from=2024-01-01&to=2025-01-01", "from=no&to=2024-01-01", "from=2024-01-01"}) {
            mvc.perform(get("/api/metrics/daily?" + params).header("X-Metrics-Key", "test-metrics-key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/metrics/daily'].get").exists());
    }
}
