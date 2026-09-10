# NOOPI (누피) Backend Architecture

## 1. 목적
이 문서는 `docs/common/`의 공통 서비스/API/실시간/게임 규칙을 Backend에서 구현하기 위한 기술 구조를 정의한다.

공통 계약의 Source of Truth:
- `docs/common/SERVICE_SPEC.md`
- `docs/common/GAME_SESSION_SPEC.md`
- `docs/common/REALTIME_SPEC.md`
- `docs/common/API_SPEC.md`
- `docs/common/games/LIAR_GAME_SPEC.md`

Backend 전용 구현 원칙은 이 문서와 `DATABASE.md`를 따른다. 공통 계약과 충돌할 경우 공통 계약을 임의로 변경하지 말고 충돌을 보고한다.

## 2. 기술 스택
- Java 26
- Spring Boot 3.5.x
- Spring MVC
- Spring WebSocket
- Spring Data JPA
- MySQL 8.x
- Flyway
- Swagger / OpenAPI
- Gradle
- Spring Boot Actuator

MVP에서 Redis, Kafka, RabbitMQ, WebFlux를 선도입하지 않는다.

## 3. 핵심 구조
NOOPI는 모바일 웹 Client가 REST와 WebSocket으로 단일 Spring Boot 애플리케이션에 연결하는 구조다.

```text
Mobile Web
 ├─ REST
 └─ WebSocket
       ↓
 Spring Boot
 ├─ Room / Player Runtime
 ├─ GameSession Runtime
 ├─ Liar Game Runtime
 ├─ Realtime
 └─ Persistence
       ↓
     MySQL
```

서버의 현재 상태가 Source of Truth다. WebSocket 이벤트만으로 상태를 재구성하지 않으며 재접속/새로고침 시 `GET /api/rooms/{roomId}/state`로 복구한다.

## 4. Room과 GameSession
Room은 사람들이 모여 있는 공간이며 특정 게임에 종속되지 않는다.

GameSession은 Room에서 진행되는 게임 한 판이다. 한 Room에서 여러 GameSession을 순차적으로 진행할 수 있고 다시하기도 새 GameSession을 생성한다.

GameSession 상태:
- `READY`
- `PLAYING`
- `FINISHED`
- `CANCELLED`

Room 모듈은 라이어, 제시어, 투표 등 게임별 규칙을 알지 않는다.

## 5. GameSession 참가자 Snapshot
게임 시작 시점의 정상 참가 Player를 GameSession 참가자로 확정한다.

진행 중 Room에 새로 참가한 Player는 Room에는 존재하지만 현재 GameSession에는 자동 편입하지 않는다. 다음 GameSession부터 참가한다.

## 6. In-Memory Runtime
MVP에서 다음은 서버 Memory에 둔다.
- Room / roomCode / host
- Player / nickname / gender / connectionStatus
- 현재 GameSession 및 참가자
- 게임별 phase
- 역할과 역할 확인 상태
- 선정된 제시어와 첫 발언자
- 투표, voteRound, 재투표 후보
- 라이어 최종 추측과 게임 결과
- 최근 사용 제시어 식별값
- WebSocket 연결 관련 Runtime 정보

개념 예:
```java
class RoomRuntime {
    Room room;
    Map<Long, PlayerRuntime> players;
    GameSessionRuntime currentGameSession;
}
```

라이어 상세 상태는 `LiarGameRuntime`이 소유한다.

## 7. Persistence
MySQL은 영구 관리가 필요한 라이어 게임 콘텐츠에 사용한다.
- LiarCategory
- LiarKeyword

Room, Player, GameSession, Role, Vote는 MVP에서 JPA Entity로 만들지 않는다. 상세 스키마는 `DATABASE.md`를 따른다.

## 8. 익명 Client와 Player
Frontend가 생성한 익명 `clientId`를 `X-Client-Id` Header로 받는다.

`clientId`는 인증 토큰이 아니다. 동일 Client는 동일 Room에서 하나의 Player로만 존재하며 재접속 시 기존 Player를 복구한다.

Player 정보는 Room 단위다.
- nickname: trim 후 1~5글자
- gender: `MALE` 또는 `FEMALE`
- 같은 Room에서 nickname 중복 불가

## 9. State Projection
Runtime 객체를 그대로 JSON으로 직렬화하지 않는다.

`GET /api/rooms/{roomId}/state`는 요청 Player 기준 Projection을 생성한다. 예를 들어 시민은 자신의 역할과 제시어를 볼 수 있지만 라이어의 `keyword`는 항상 `null`이다.

게임 종료 전 다른 Player의 역할을 반환하지 않는다. 다른 Player의 `voter → target` 관계는 투표 종료 후와 게임 종료 후에도 반환하지 않는다.

## 10. Liar Game Runtime
라이어 게임은 정확히 한 명의 라이어를 가진다.

주요 phase:
- `READY`
- `ROLE_REVEAL`
- `DISCUSSION`
- `VOTING`
- `VOTE_RESULT`
- `REVOTING`
- `LIAR_REVEAL`
- `LIAR_GUESS`
- `FINISHED`
- `CANCELLED`

서버는 현재 phase에서 허용되는 행동만 처리한다.

## 11. 카테고리와 제시어
카테고리와 제시어는 서버가 관리하며 Frontend에 전체 제시어 목록을 제공하지 않는다.

`RANDOM`은 DB Row가 아니라 서버가 카테고리 API에 포함하는 가상 선택 옵션이다.

일반 카테고리는 해당 카테고리의 활성 제시어에서, RANDOM은 전체 활성 제시어에서 서버가 선택한다. 같은 Room의 최근 제시어는 가능한 한 반복을 피한다.

## 12. 역할과 첫 발언자
게임 시작 시 참가자 3~12명을 검증한 후 정확히 한 명의 라이어를 서버가 랜덤 선정한다.

모든 참가자가 역할 확인을 완료하면 서버가 참가자 중 첫 발언자 한 명을 랜덤 선정하고 `DISCUSSION`으로 전환한다.

## 13. 투표
MVP에서 방장이 투표 시작을 요청한다.

투표 규칙:
- 모든 활성 GameSession 참가자가 투표
- 자기 자신 투표 불가
- 라운드당 한 번만 투표
- 제출 후 변경 불가
- 최초 라운드는 자기 자신을 제외한 유효 참가자가 후보
- 재투표는 직전 라운드의 최다 득표 동률 후보만 후보

투표 중에는 완료 인원과 Player별 제출 완료 여부만 공개한다. 현재 득표수/현재 1위/개별 투표 대상은 공개하지 않는다.

모든 필요한 Player가 투표하면 서버가 자동 집계한다.

## 14. 무제한 재투표
최다 득표자가 여러 명이면 동률 후보만 대상으로 다음 `voteRound`를 자동 생성한다.

재투표 횟수 제한은 없다. 단독 최다 득표자가 나올 때까지 반복하며 서버가 동률 후보 중 임의/랜덤 지목하지 않는다.

## 15. 투표 비밀
개별 Player의 투표 대상은 항상 비공개다.

API, `/state`, WebSocket 이벤트에 다른 Player의 `voterPlayerId → targetPlayerId` 관계를 포함하지 않는다.

`PLAYER_VOTED`는 제출 완료 사실만 전달한다. 라운드 종료 후 후보별 최종 득표수와 최종 지목 결과만 공개할 수 있다.

## 16. 최종 판정과 Liar Guess
최종 지목자가 시민이면 라이어 승리로 즉시 종료하고 실제 라이어를 공개한다.

최종 지목자가 라이어이면 `LIAR_GUESS`로 전환한다. 실제 라이어만 한 번 제출할 수 있다.

정답 판정은 서버가 수행한다. 실제 제시어와 추측 답안에서 모든 공백 문자를 제거한 값이 정확히 같을 때만 정답이다. 띄어쓰기 외의 문자 차이와 대소문자 차이는 허용하지 않으며 별칭 정답은 관리하지 않는다. LLM 의미 판정은 MVP 범위가 아니다.

## 17. Disconnect / Reconnect
WebSocket 연결 종료는 Room 탈퇴가 아니다. Player를 `DISCONNECTED`로 표시하고 일정 시간 재접속을 허용한다.

재접속 시 동일 `clientId`로 기존 Player를 복구한 뒤 `/state`로 현재 상태를 동기화한다.

투표 중 장기 미접속 Player는 방장이 현재 GameSession에서 제외할 수 있다. 이미 제출한 현재 라운드 표가 있으면 무효 처리하고 required vote count를 다시 계산한다.

실제 라이어를 제외해야 하면 GameSession을 `CANCELLED` 처리하고 게임 중 새 라이어를 선정하지 않는다.

정확한 장기 미접속 시간은 Backend 운영 설정으로 둔다.

## 18. Host
Room 생성자가 최초 Host다.

Host의 일시 Disconnect로 즉시 권한을 이전하지 않는다. 명시적 Room 이탈 또는 정책상 영구 이탈 시 Room 정책에 따라 Host를 이전한다.

## 19. WebSocket
개념 endpoint는 `/ws`다.

연결 시 `clientId`와 `roomId`를 식별하고 해당 Client가 실제 Room Player인지 검증해야 한다. 임의 Client가 다른 Room 이벤트를 구독할 수 없어야 한다.

공통/게임 이벤트 payload는 `docs/common/API_SPEC.md`를 정확히 따른다.

## 20. 동시성
같은 Room에 여러 요청이 동시에 들어올 수 있으므로 Room 단위 상태 변경을 원자적으로 처리한다.

특히 다음을 보호한다.
- 동시 참가와 nickname 중복
- 마지막 역할 확인 동시 요청
- 마지막 투표들의 동시 요청
- 투표 완료 집계와 phase 전환
- disconnect/exclude와 vote submit 경합

전역 Lock 하나로 모든 Room을 직렬화하지 않는다.

## 21. 오류 처리
도메인 오류는 공통 형식을 사용한다.

```json
{
  "code": "ALREADY_VOTED",
  "message": "이미 이번 투표에 참여했습니다."
}
```

Global Exception Handler를 사용하고 error code/HTTP Status는 `docs/common/API_SPEC.md`를 따른다.

## 22. Flyway / JPA
Flyway가 DB Schema의 Source of Truth다.

운영에서 Hibernate `ddl-auto=create/update`에 의존하지 않는다. JPA는 영구 콘텐츠 데이터에만 사용한다.

## 23. Room Cleanup
In-Memory Room은 `createdAt`, `lastActivityAt` 등을 갖고 Scheduled cleanup으로 오래된 Room을 제거한다.

정확한 TTL은 운영 설정으로 관리한다. 제거 시 Game Runtime과 WebSocket 관련 참조도 함께 정리한다.

## 24. 운영
1GB RAM / 1 CPU 서버를 고려해 단일 Spring Boot 인스턴스로 시작한다.

초기 JVM 예:
```text
-Xms128m
-Xmx512m
-XX:MaxMetaspaceSize=192m
```

Actuator health와 기본 구조화 로그를 사용한다. 일반 운영 로그에 제시어, 전체 역할 목록, 개인별 투표 대상 같은 게임 비밀을 불필요하게 남기지 않는다.

## 25. 확장
다중 Spring Boot 인스턴스가 필요해지면 In-Memory Runtime 공유 문제가 생긴다. 그 시점에 Redis 등 공유 Runtime 저장소/메시징을 검토한다.

MVP에 미리 도입하지 않는다.

## 26. 최종 원칙
- Room은 게임 규칙을 모른다.
- GameSession은 한 판의 생명주기를 관리한다.
- 게임별 규칙은 게임 모듈이 소유한다.
- Runtime 게임 상태는 Memory에 둔다.
- 영구 콘텐츠는 MySQL에 둔다.
- Backend가 게임 상태와 판정의 Source of Truth다.
- Client에는 현재 Player가 볼 수 있는 정보만 전달한다.

## 확정 보완: 종료 결과 제시어 공개
사용자 확정: 게임 진행 중 라이어의 개인 `keyword`는 항상 `null`이다.
`FINISHED`의 `result.keyword`는 API_SPEC.md 22절에 따라 라이어를 포함한 전체 참가자에게 공개한다.
