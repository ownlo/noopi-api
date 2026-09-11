# AGENTS.md — NOOPI Backend

## 1. 프로젝트
이 저장소는 **NOOPI(누피)** Backend다.

서비스 핵심 문구:
> **게임은 사람끼리, 진행은 웹이.**

## 2. 작업 전 필수 문서
반드시 다음 순서로 확인한다.
1. `docs/common/SERVICE_SPEC.md`
2. `docs/common/GAME_SESSION_SPEC.md`
3. `docs/common/REALTIME_SPEC.md`
4. `docs/common/API_SPEC.md`
5. `docs/common/games/LIAR_GAME_SPEC.md`
6. `docs/common/games/BLIND_GAME_SPEC.md`
7. `docs/BACKEND_ARCHITECTURE.md`
8. `docs/DATABASE.md`

공통 서비스/API/Realtime/Game 규칙은 `docs/common/`이 Source of Truth다.

문서끼리 충돌하면 임의로 한쪽을 선택해 구현하지 말고 다음 형식으로 보고한다.
```text
현재 SPEC:
충돌/누락:
제안:
영향 범위:
```

## 3. 현재 확인된 공통 SPEC 충돌
현재 `LIAR_GAME_SPEC.md` 18절/29절에는 다른 최신 공통 계약과 충돌하는 오래된 문장이 남아 있을 수 있다.

최신 확정 규칙은 다음과 같다.
- 누가 누구에게 투표했는지는 항상 비공개
- 투표 종료/게임 종료 후에도 voter -> target 비공개
- 공개 가능 정보는 후보별 최종 득표수와 최종 지목 결과
- 동률이면 동률 후보만 대상으로 재투표
- 재투표 횟수 제한 없음
- 단독 최다 득표자가 나올 때까지 반복
- 서버 랜덤 지목 금지

이 규칙은 `API_SPEC.md`와 `REALTIME_SPEC.md`의 최신 계약과 일치한다.

가능하면 공통 `LIAR_GAME_SPEC.md` 자체를 먼저 정정한 뒤 구현한다.

## 4. SPEC 없는 기능 금지
API, WebSocket payload, 게임 규칙, DB 테이블을 편의상 임의 추가하지 않는다.

필요한 구현 세부사항은 공통 계약을 바꾸지 않는 범위에서 가장 단순하게 결정한다.

## 5. 기술 스택
- Java 26
- Spring Boot 3.5.x
- Spring MVC
- Spring WebSocket
- Spring Data JPA
- MySQL 8.x
- Flyway
- Swagger/OpenAPI
- Gradle
- Actuator

MVP에 Redis/Kafka/RabbitMQ/WebFlux를 임의로 추가하지 않는다.

## 6. Runtime / DB 경계
Memory:
- Room
- Player
- GameSession
- 게임별 phase
- 역할
- 제시어
- 역할 확인
- 투표/재투표
- 최종 추측/결과
- 연결 상태
- 블라인드 게임의 Player별 제시어 배정/정답 시도/승자

MySQL:
- LiarCategory
- LiarKeyword
- LiarKeywordAcceptedAnswer

블라인드 게임은 별도 콘텐츠 테이블을 만들지 않고 기존 활성 제시어 풀을
재사용한다.

Runtime 상태를 JPA Entity로 바꾸지 않는다. 개인별 투표를 DB에 영구 저장하지 않는다.

## 7. Room과 게임 분리
Room은 특정 게임의 규칙을 알지 않는다.

GameSession이 `gameType`을 가진다. 게임별 상세 규칙은 `game/liar` 같은 게임 모듈이 소유한다.

복잡한 범용 Rule Engine/Plugin Framework/Event Sourcing/CQRS를 미리 만들지 않는다.

## 8. GameSession
한 판마다 새로운 GameSession을 생성한다.

게임 시작 시 정상 참가 Player를 참가자로 확정한다. 진행 중 새 Room 참가자는 현재 GameSession에 자동 편입하지 않는다.

상태:
`READY`, `PLAYING`, `FINISHED`, `CANCELLED`.

## 9. Client / Player
`X-Client-Id`는 익명 브라우저 식별값이며 인증 토큰이 아니다.

동일 Client는 동일 Room에서 하나의 Player로만 존재한다.

Player nickname은 trim 후 1~5글자다. 같은 Room에서 중복 불가.

gender는 `MALE`, `FEMALE`만 지원한다.

## 10. Backend가 Source of Truth
Backend가 다음을 직접 결정/검증한다.
- Room/Host 상태
- GameSession
- Game phase
- 역할과 제시어
- 첫 발언자
- 투표 유효성/집계
- 재투표
- 최종 지목
- 정답 여부
- 승패

Frontend 계산/버튼 숨김을 권한 검증으로 신뢰하지 않는다.

## 11. 라이어 게임 불변 규칙
- 참가자 3~12명
- 라이어 정확히 1명
- 카테고리/제시어 서버 관리
- `RANDOM`은 서버 제공 가상 옵션
- 전체 제시어 목록 Client 전달 금지
- 시민에게만 제시어 제공
- 라이어 keyword는 항상 `null`
- 역할은 GameSession마다 서버 랜덤 선정
- 첫 발언자는 모든 역할 확인 후 서버 랜덤 선정
- 자기 자신 투표 금지
- 라운드당 1회 투표
- 제출한 투표 변경 금지

## 11-1. 블라인드 게임 불변 규칙

- 참가자 정확히 2명
- 전체 활성 제시어 풀에서 서로 다른 제시어 2개 선정
- 게임 종료 전 각 Player 본인의 제시어 비공개
- 각 Player에게 상대방의 제시어만 제공
- 카테고리 선택/공개 없음
- 오답 페널티와 시도 횟수 제한 없음
- 최초 정답자 1명만 원자적으로 승자 확정
- 질문/답변/순서/턴/타이머는 서버가 관리하지 않음

## 12. 투표 비밀
**다른 Player가 누구에게 투표했는지는 항상 비공개다.**

금지:
- 투표 중 voter -> target 공개
- 투표 종료 후 공개
- 게임 종료 후 공개
- `/state` 노출
- WebSocket payload 노출

투표 중에는 전체/완료 인원과 Player별 제출 완료 여부만 공개한다.

모든 필요한 투표가 끝난 뒤 후보별 최종 득표수와 최종 지목 결과만 공개할 수 있다.

`PLAYER_VOTED`에 `targetPlayerId`를 넣지 않는다.

## 13. 재투표
동률이면 해당 라운드 최다 득표 동률 후보만 대상으로 다음 `voteRound`를 서버가 자동 생성한다.

횟수 제한은 없다. 단독 최다 득표자가 나올 때까지 반복한다.

동률 후보 중 랜덤/임의 지목 로직을 절대 구현하지 않는다.

## 14. Liar Guess
최종 지목자가 시민이면 라이어 즉시 승리.

실제 라이어가 지목되면 `LIAR_GUESS`로 전환하고 실제 라이어에게만 정확히 한 번 제출을 허용한다.

시민의 guess 요청은 서버에서 거부한다.

정답 판정은 서버가 수행한다.

## 15. `/state`
`GET /api/rooms/{roomId}/state`는 새로고침/재접속/이벤트 유실 복구의 핵심 API다.

Runtime 객체를 그대로 반환하지 말고 요청 Player별 Projection을 만든다.

게임 종료 전 다른 Player 역할을 노출하지 않는다. 라이어에게 제시어를 노출하지 않는다. 다른 Player의 투표 대상을 어떤 단계에서도 노출하지 않는다.

## 16. WebSocket
개념 endpoint는 `/ws`.

연결 시 clientId/roomId를 식별하고 Room 참가 여부를 검증한다.

WebSocket은 상태 저장소가 아니다. 재접속 후 `/state`로 복구한다.

`REALTIME_SPEC.md`와 `API_SPEC.md`에 정의된 이벤트명/payload를 따른다.

## 17. Disconnect / Exclude
WebSocket disconnect는 Room leave가 아니다.

동일 clientId 재접속 시 기존 Player를 복구한다.

투표 중 장기 미접속 Player를 Host가 현재 GameSession에서 제외할 수 있다. 기존 현재 라운드 표는 무효 처리하고 필요한 투표 수를 다시 계산한다.

실제 라이어를 제외해야 하면 GameSession을 `CANCELLED` 처리하고 새 라이어를 선정하지 않는다.

## 18. 모든 Action의 서버 검증
최소 확인:
- Room 존재
- X-Client-Id에 대응하는 Player 존재
- GameSession 참가 여부
- GameSession 상태
- 현재 game phase
- Host 전용 행동 여부
- 역할 전용 행동 여부
- 중복 행동 여부
- voteRound
- 유효 후보
- 자기 자신 투표 여부

## 19. 동시성
Room 단위 동시성 제어를 사용한다.

특히 마지막 역할 확인/마지막 투표가 동시에 도착해도 phase 전환과 집계가 중복 실행되지 않게 한다.

블라인드 게임에서 두 Player의 정답 요청이 동시에 도착해도 승자 확정과
GameSession 종료를 하나의 원자적 변경으로 처리하여 승자가 한 명만
생성되게 한다.

모든 Room을 하나의 전역 Lock으로 막지 않는다.

## 20. Error
공통 형식:
```json
{
  "code": "ALREADY_VOTED",
  "message": "이미 이번 투표에 참여했습니다."
}
```

Global Exception Handler를 사용한다.

고정 error code와 HTTP Status는 `docs/common/API_SPEC.md`를 따른다.

## 21. API 계약
`docs/common/API_SPEC.md`에 정의된 REST Endpoint, request/response field, error code를 임의 변경하지 않는다.

계약 변경이 필요하면 먼저 보고한다.

## 22. Persistence
Flyway가 DB Schema Source of Truth다.

Room/Player/GameSession/Role/Vote용 테이블을 현재 MVP에 임의 추가하지 않는다.

## 23. Logging
로그에 roomId/gameSessionId/playerId/action/errorCode 등 운영 식별자를 남길 수 있다.

제시어, 전체 역할 목록, voter -> target 같은 게임 비밀을 일반 로그에 불필요하게 기록하지 않는다.

## 24. 테스트 우선순위
최소 다음 핵심 규칙을 테스트한다.
- nickname 1~5글자 / 같은 Room 중복
- GameSession 참가자 snapshot
- 라이어 정확히 1명
- 시민/라이어 state projection
- 다른 Player 역할 비노출
- 중복 role-check 방지
- 자기 자신 투표 금지
- 중복 투표 금지
- 투표 진행 중 득표수 비노출
- voter -> target 비노출
- 동률 후보 재투표
- 무제한 voteRound
- 랜덤 동률 해결 없음
- 시민 오지목 시 라이어 승리
- 라이어 검거 후 guess
- 시민 guess 거부
- guess 중복 제출 방지
- 마지막 투표 동시성
- reconnect 복구
- 블라인드 게임 정확히 2명 검증
- 블라인드 제시어 2개 상이성
- 요청 Player 본인 제시어 비노출 / 상대방 제시어 노출
- 블라인드 오답 후 무제한 재시도
- 블라인드 동시 정답 제출 시 승자 1명 확정
- 블라인드 종료 결과의 승자와 양쪽 제시어 공개

## 25. 구현 후
최소 다음을 실행한다.
```text
./gradlew test
./gradlew build
```

실패를 숨기지 말고 원인을 수정하거나 명확히 보고한다.

## 26. 최종 체크
작업 전/후 확인:
- SPEC에 있는가?
- 현재 Player가 이 행동을 할 권한이 있는가?
- 서버에서 상태/권한을 검증했는가?
- 다른 Player가 보면 안 되는 정보가 DTO/Event에 섞이지 않았는가?
- Room과 게임별 책임이 섞이지 않았는가?

> **Backend가 게임의 진실을 소유한다.**

## 확정 보완: 종료 결과 제시어 공개
사용자 확정: 게임 진행 중 라이어의 개인 `keyword`는 항상 `null`이다.
`FINISHED`의 `result.keyword`는 API_SPEC.md 22절에 따라 라이어를 포함한 전체 참가자에게 공개한다.
