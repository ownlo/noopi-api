CREATE TABLE liar_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_order INT NOT NULL,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_liar_category_code (code)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE liar_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    keyword VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_liar_keyword_category_keyword (category_id, keyword),
    KEY idx_liar_keyword_category_active (category_id, active),
    CONSTRAINT fk_liar_keyword_category
        FOREIGN KEY (category_id) REFERENCES liar_category(id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE liar_keyword_accepted_answer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    keyword_id BIGINT NOT NULL,
    answer VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_liar_keyword_answer (keyword_id, answer),
    KEY idx_liar_keyword_answer_keyword (keyword_id),
    CONSTRAINT fk_liar_keyword_answer_keyword
        FOREIGN KEY (keyword_id) REFERENCES liar_keyword(id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
