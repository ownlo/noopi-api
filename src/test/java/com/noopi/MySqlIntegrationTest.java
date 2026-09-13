package com.noopi;

import com.noopi.content.LiarContent;
import com.noopi.content.BlindContent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static com.noopi.api.ErrorCode.*;

/** Runs the same HTTP/WebSocket contract suite against actual MySQL, not a substitute DDL. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlIntegrationTest extends HttpWebSocketIntegrationTest {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("noopi").withUsername("noopi").withPassword("test-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", mysql::getJdbcUrl);
        properties.add("spring.datasource.username", mysql::getUsername);
        properties.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired LiarContent content;
    @Autowired BlindContent blindContent;

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void dailyMetricsUpsertIsIdempotentOnMySql() {
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2020-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        var collector = new com.noopi.metrics.MetricsCollector(clock);
        var room = new com.noopi.room.RoomRuntime(1, "ABCDEF", clock.instant());
        collector.created(room);
        room.session = new com.noopi.game.session.GameSessionRuntime(1, "BLIND", null);
        room.session.start(List.of(new com.noopi.game.session.GameSessionRuntime.Participant(1, "가")));
        collector.observe(room);
        room.closed = true;
        collector.closed(room);
        var persistence = new com.noopi.metrics.MetricsPersistence(collector, jdbc, transactions);
        persistence.flush(); persistence.flush();
        var day = java.time.LocalDate.parse("2020-01-01");
        var totals = new com.noopi.metrics.MetricsQuery(jdbc).daily(day, day).days().getFirst();
        assertThat(totals.roomsCreated()).isEqualTo(1);
        assertThat(totals.roomsClosed()).isEqualTo(1);
        assertThat(totals.games().get(1).started()).isEqualTo(1);
        assertThat(totals.games().get(1).cancelled()).isEqualTo(1);
    }

    @Test @Transactional void migrationsAndContentFilteringAndRecentAvoidance() {
        assertThat(jdbc.queryForObject("select count(*) from liar_category where code = 'RANDOM'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from liar_keyword", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("select count(*) from blind_keyword", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success = 1", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("show tables", String.class)).containsExactlyInAnyOrder(
            "blind_keyword", "flyway_schema_history", "liar_category", "liar_keyword", "daily_room_metrics", "daily_game_metrics");
        var blind = blindContent.chooseDistinct(2, List.of());
        assertThat(blind).hasSize(2).extracting(BlindContent.Keyword::id).doesNotHaveDuplicates();
        assertThat(content.choose("FOOD", List.of(1L, 2L, 3L, 4L)).id()).isEqualTo(5L);
        assertThat(content.choose("FOOD", List.of(1L, 2L, 3L, 4L, 5L))).isNotNull();
        jdbc.update("update liar_keyword set active = false where category_id = 1 and id <> 1");
        var keyword = content.choose("FOOD", List.of());
        assertThat(keyword.keyword()).isEqualTo("피자");
        jdbc.update("update liar_category set active = false where id = 1");
        assertThat(content.categories()).noneMatch(c -> c.code().equals("FOOD"));
        assertThatThrownBy(() -> content.choose("FOOD", List.of())).isInstanceOfSatisfying(com.noopi.api.DomainException.class,
            e -> assertThat(e.code()).isEqualTo(INVALID_CATEGORY));
        for (int i = 0; i < 20; i++) assertThat(content.choose("RANDOM", List.of()).id()).isGreaterThan(5L);
        jdbc.update("update liar_keyword set active = false");
        assertThatThrownBy(() -> content.choose("RANDOM", List.of())).isInstanceOfSatisfying(com.noopi.api.DomainException.class,
            e -> assertThat(e.code()).isEqualTo(NO_AVAILABLE_KEYWORD));
        assertThat(blindContent.chooseDistinct(2, List.of())).hasSize(2);
        jdbc.update("update blind_keyword set active = false where id <> 1");
        assertThatThrownBy(() -> blindContent.chooseDistinct(2, List.of())).isInstanceOfSatisfying(com.noopi.api.DomainException.class,
            e -> assertThat(e.code()).isEqualTo(NO_AVAILABLE_KEYWORD));
    }
}
