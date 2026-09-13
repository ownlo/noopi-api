CREATE TABLE daily_room_metrics (
    instance_id VARCHAR(36) NOT NULL,
    metric_date DATE NOT NULL,
    rooms_created BIGINT NOT NULL,
    rooms_closed BIGINT NOT NULL,
    duration_millis BIGINT NOT NULL,
    max_duration_millis BIGINT NOT NULL,
    PRIMARY KEY (instance_id, metric_date),
    INDEX idx_room_metrics_date (metric_date)
);
CREATE TABLE daily_game_metrics (
    instance_id VARCHAR(36) NOT NULL,
    metric_date DATE NOT NULL,
    game_type VARCHAR(20) NOT NULL,
    started BIGINT NOT NULL,
    finished BIGINT NOT NULL,
    cancelled BIGINT NOT NULL,
    participant_count BIGINT NOT NULL,
    PRIMARY KEY (instance_id, metric_date, game_type),
    INDEX idx_game_metrics_date (metric_date)
);
