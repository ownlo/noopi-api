CREATE TABLE blind_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    keyword VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blind_keyword_keyword (keyword),
    KEY idx_blind_keyword_active (active)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO blind_keyword (id, keyword, active, created_at, updated_at) VALUES
(1, '피자', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(2, '기린', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(3, '우산', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(4, '자동차', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(5, '선풍기', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(6, '축구공', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(7, '냉장고', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(8, '안경', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(9, '비행기', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(10, '연필', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
