package com.noopi.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricsQuery {
    public record Game(String gameType, long started, long finished, long cancelled, long participantCount) {}
    public record Day(LocalDate date, long roomsCreated, long roomsClosed, BigDecimal totalRoomDurationMinutes,
                      BigDecimal averageRoomDurationMinutes, BigDecimal maxRoomDurationMinutes, List<Game> games) {}
    public record Daily(String timezone, List<Day> days) {}
    private final JdbcTemplate jdbc;
    public MetricsQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Daily daily(LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> rooms = new HashMap<>();
        jdbc.query("""
            SELECT metric_date, SUM(rooms_created), SUM(rooms_closed), SUM(duration_millis), MAX(max_duration_millis)
            FROM daily_room_metrics WHERE metric_date BETWEEN ? AND ? GROUP BY metric_date
            """, rs -> { rooms.put(rs.getDate(1).toLocalDate(), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}); }, from, to);
        Map<LocalDate, Map<String, Game>> games = new HashMap<>();
        jdbc.query("""
            SELECT metric_date, game_type, SUM(started), SUM(finished), SUM(cancelled), SUM(participant_count)
            FROM daily_game_metrics WHERE metric_date BETWEEN ? AND ? GROUP BY metric_date, game_type
            """, rs -> { games.computeIfAbsent(rs.getDate(1).toLocalDate(), ignored -> new HashMap<>())
                .put(rs.getString(2), new Game(rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6))); }, from, to);
        List<Day> days = new ArrayList<>();
        for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
            var room = rooms.getOrDefault(date, new long[4]);
            var game = games.getOrDefault(date, Map.of());
            days.add(new Day(date, room[0], room[1], minutes(room[2], 1), minutes(room[2], room[1]), minutes(room[3], 1),
                List.of(game.getOrDefault("LIAR", new Game("LIAR", 0, 0, 0, 0)),
                    game.getOrDefault("BLIND", new Game("BLIND", 0, 0, 0, 0)))));
        }
        return new Daily(MetricsCollector.ZONE.getId(), List.copyOf(days));
    }
    private static BigDecimal minutes(long millis, long count) {
        return count == 0 ? BigDecimal.ZERO.setScale(2)
            : BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(60000).multiply(BigDecimal.valueOf(count)), 2, RoundingMode.HALF_UP);
    }
}
