INSERT INTO liar_category (id, code, name, display_order, active, created_at, updated_at) VALUES
(1, 'FOOD', '음식', 1, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(2, 'PLACE', '장소', 2, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(3, 'ANIMAL', '동물', 3, true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
INSERT INTO liar_keyword (id, category_id, keyword, active, created_at, updated_at) VALUES
(1, 1, '피자', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(2, 1, '김치찌개', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(3, 1, '아이스크림', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(4, 1, '떡볶이', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(5, 1, '초밥', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(6, 2, '바다', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(7, 2, '도서관', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(8, 2, '공항', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(9, 2, '놀이공원', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(10, 2, '영화관', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(11, 3, '고양이', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(12, 3, '강아지', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(13, 3, '코끼리', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(14, 3, '기린', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(15, 3, '펭귄', true, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
INSERT INTO liar_keyword_accepted_answer (keyword_id, answer, created_at) VALUES
(1, 'pizza', CURRENT_TIMESTAMP(6)),
(2, '김치 찌개', CURRENT_TIMESTAMP(6)),
(3, 'ice cream', CURRENT_TIMESTAMP(6)),
(5, '스시', CURRENT_TIMESTAMP(6)),
(9, '놀이 공원', CURRENT_TIMESTAMP(6));
