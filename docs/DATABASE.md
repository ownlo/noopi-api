# NOOPI (누피) Database Specification

## 1. 목적
NOOPI MVP에서 MySQL에 영구 저장하는 데이터의 범위를 정의한다.

공통 게임 진행 상태는 서버 In-Memory Runtime이 관리하며 MySQL은 게임에서
재사용하는 영구 제시어 콘텐츠를 관리한다.

## 2. 저장 경계
### In-Memory
- Room
- Player
- GameSession
- GameSession 참가자 Snapshot
- Liar Game phase
- 역할 / 역할 확인
- 선정 제시어
- 첫 발언자
- 투표 / voteRound / 재투표 후보
- 최종 추측 / 승패
- 연결 상태
- 같은 Room의 최근 사용 제시어
- Blind Game phase / Player별 제시어 배정 / 승자

### MySQL
- `liar_category`
- `liar_keyword`

MVP에서 Room/GameSession/Role/Vote 테이블을 만들지 않는다.

## 3. MySQL / Migration
- MySQL 8.x
- charset: `utf8mb4`
- Schema 변경: Flyway
- Flyway migration이 최종 DDL Source of Truth

## 4. liar_category
목적: 라이어 게임 제시어 카테고리.

권장 컬럼:
```text
id             BIGINT PK AUTO_INCREMENT
code           VARCHAR(50) NOT NULL
name           VARCHAR(100) NOT NULL
display_order  INT NOT NULL
active         BOOLEAN NOT NULL
created_at     DATETIME(6) NOT NULL
updated_at     DATETIME(6) NOT NULL
```

제약:
```text
UNIQUE(code)
```

`RANDOM`은 저장하지 않는다. 서버가 카테고리 조회 API에 추가하는 가상 옵션이다.

## 5. liar_keyword
목적: 실제 제시어.

권장 컬럼:
```text
id           BIGINT PK AUTO_INCREMENT
category_id  BIGINT NOT NULL
keyword      VARCHAR(255) NOT NULL
active       BOOLEAN NOT NULL
created_at   DATETIME(6) NOT NULL
updated_at   DATETIME(6) NOT NULL
```

제약/Index:
```text
FK category_id -> liar_category.id
UNIQUE(category_id, keyword)
INDEX(category_id, active)
```

각 제시어는 하나의 실제 카테고리에 속한다.

블라인드 게임은 별도 제시어 테이블을 만들지 않고 활성 `liar_keyword`
레코드를 공통 제시어 풀로 재사용한다. 내부 `category_id`는 유지하지만
블라인드 게임의 선정 조건, API 응답 또는 화면에는 카테고리를 사용하거나
노출하지 않는다. 서로 다른 두 `liar_keyword.id`를 선택해야 한다.

## 6. 정답 정규화
서버는 실제 `liar_keyword.keyword`와 추측 답안에서 모든 공백 문자를 제거한
값이 정확히 같은지 비교한다.

- 띄어쓰기 차이만 허용
- 대소문자 차이 불허
- 별칭 또는 별도의 허용 정답을 저장하지 않음

LLM 기반 의미 판정은 MVP 범위가 아니다.

## 8. RANDOM 조회
`RANDOM`은 DB Row가 아니다.

RANDOM 선택 시 서버는 모든 활성 실제 카테고리에 속한 활성 keyword를 후보로 사용한다.

## 9. 최근 제시어 반복 회피
같은 Room에서 최근 사용한 keyword id는 Room Runtime에 둘 수 있다.

가능하면 최근 사용 제시어를 후보에서 제외하고, 후보가 부족하면 다시 허용할 수 있다.

이 이력은 MVP에서 영구 저장하지 않는다.

## 10. Runtime 테이블 금지
현재 요구만으로 다음 테이블을 추가하지 않는다.
```text
client
room
player
game_session
liar_game
liar_game_role
liar_game_vote
liar_game_guess
```

서버 재시작 후 진행 중 게임 복구는 현재 MVP 요구가 아니다.

## 11. 투표 영구 저장 금지
개인별 투표는 현재 GameSession Runtime에서 집계에 필요한 동안만 관리한다.

MySQL에 `voter → target` 관계를 영구 저장하지 않는다.

향후 통계/감사 요구가 생기면 투표 비밀 정책을 포함해 별도 설계한다.

## 12. JPA
JPA Entity는 영구 콘텐츠만 표현한다.

예:
```text
LiarCategoryEntity
LiarKeywordEntity
```

Runtime 객체에 `@Entity`를 붙이지 않는다.

## 13. 활성화/삭제
운영 중 콘텐츠는 물리 삭제보다 `active=false` 비활성화를 우선한다.

Admin UI는 MVP 필수 범위가 아니다.

## 14. 권장 Migration
예:
```text
V1__create_liar_category.sql
V2__create_liar_keyword.sql
V3__drop_liar_keyword_accepted_answer.sql
```

필요하면 초기 migration을 합칠 수 있다.

## 15. Seed
개발/MVP 구동에 필요한 카테고리와 제시어를 Flyway seed로 제공할 수 있다.

Frontend에는 카테고리/제시어를 하드코딩하지 않는다.

## 16. DDL 예시
```sql
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
);

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
);

```

실제 구현에서는 Flyway migration을 기준으로 한다.

## 17. 향후 재검토 조건
다음 요구가 생기면 Persistence 경계를 다시 설계한다.
- 서버 재시작 후 진행 중 Room/Game 복구
- 다중 Backend 인스턴스
- 사용자 계정
- 게임 히스토리/전적/랭킹
- 신고/감사
- 플레이 통계

현재 요구를 예상해 테이블을 미리 만들지 않는다.

## 18. 최종 원칙
**게임 진행 상태는 Memory, 영구 게임 콘텐츠는 MySQL.**
