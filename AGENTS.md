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
7. `docs/common/games/MAFIA_GAME_SPEC.md`
8. `docs/common/games/YUT_GAME_SPEC.md`
9. `docs/common/games/PIG_GAME_SPEC.md`
10. `docs/common/games/TOOTH_GAME_SPEC.md`
11. `docs/BACKEND_ARCHITECTURE.md`
12. `docs/DATABASE.md`
13. `docs/IMPLEMENTATION_NOTES.md`

`docs/common/`은 `noopi-web/docs/common/`과 파일 구성 및 내용을 동일하게 유지한다.
백엔드 전용 운영 계약은 `docs/OPERATIONS_API.md`에서 관리한다.
공통 명세에 게임이 추가되었다고 실제 Backend 구현까지 완료된 것으로 간주하지 않는다.

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
- 윷놀이 모드/팀/턴/이동권/말 위치/업힌 그룹/추가 던지기/개인전 순위/팀전 승자
- 마피아 게임의 역할/생존 상태/밤 행동/조사/의심/처형 투표/사망/승패
- 피그 주사위 후보/결과, 턴 점수/확정 점수, 턴 순서, FINISHED 순위
- 누피 콱! 턴 순서, 이빨 선택 상태, 꽝 이빨, 최근 결과와 당첨 Player

MySQL:
- LiarCategory
- LiarKeyword
- LiarKeywordAcceptedAnswer
- BlindKeyword

블라인드 게임 제시어는 카테고리 없이 별도 `blind_keyword` 테이블에서
관리하며 라이어 게임 제시어 풀과 분리한다.

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
- 발언 순서
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
- 발언 순서는 모든 역할 확인 후 전체 참가자를 대상으로 서버가 랜덤 선정
- 자기 자신 투표 금지
- 라운드당 1회 투표
- 제출한 투표 변경 금지

## 11-1. 블라인드 게임 불변 규칙

- 참가자 정확히 2명
- 전체 활성 블라인드 제시어 풀에서 서로 다른 제시어 2개 선정
- 게임 종료 전 각 Player 본인의 제시어 비공개
- 각 Player에게 상대방의 제시어만 제공
- 카테고리 선택/공개 없음
- 오답 페널티와 시도 횟수 제한 없음
- 최초 정답자 1명만 원자적으로 승자 확정
- 질문/답변/순서/턴/타이머는 서버가 관리하지 않음

## 11-2. 마피아 게임 불변 규칙

- 참가자 4~12명, 인원별 역할 구성은 서버가 자동 결정
- 역할은 `MAFIA`, `POLICE`, `DOCTOR`, `CITIZEN`
- 첫 밤에는 마피아 공격과 의사 치료 없음
- 둘째 밤부터 모든 생존자가 역할별 필수 행동을 동시 제출
- 마피아 공격 다수결, 최다 동률은 동률 대상 중 서버 무작위 선정
- 경찰 조사 결과는 경찰 본인에게만 공개
- 의사는 자기 자신을 치료할 수 있지만 직전 밤 대상 연속 치료 금지
- 시민 의심은 밤별 1회, 변경 금지, 본인이 받은 의심 수만 공개
- 처형 투표는 모든 생존자가 참여하며 자기 투표·중복·변경 금지
- 처형 투표 동률은 동률 후보만 대상으로 제한 없이 재투표, 랜덤 지목 금지
- 최후의 변론 후 지목자를 제외한 생존자가 `EXECUTE` 또는 `SAVE` 투표, 동률은 살림
- 사망 확정 시 승리 조건 즉시 판정, 사망자 역할 공개, 종료 후 전체 역할 공개
- 마피아 장기 미접속 Player 제외 정책은 V1 SPEC에 없으므로 임의 추가 금지

## 11-3. 윷놀이 불변 규칙

윷놀이 구현 시 다음 규칙도 반드시 따른다.

- 개인전 2~4명, 팀전 정확히 4명이며 NOOPI/DAY 각 2명. 소유자별 말 4개.
- 팀 변경과 정원 검증, 현재 턴/행동/후보 검증은 서버가 원자적으로 처리한다.
- 던지기마다 서버가 5% 확률로 낙을 먼저 판정한다. 낙이면 해당 던지기만
  무효이며 기존 이동권은 유지한다. 기존 이동권이 없을 때만 다음 턴으로 넘긴다.
- 낙이 아니면 4개 윷가락의 앞뒤 조합으로 빽도/도/개/걸/윷/모를 판정한다.
  하나의 특수 윷가락 앞면에는 누피 캐릭터가 표시되며 특수 윷가락만 앞면이면 빽도다.
- 빽도는 실제 지나온 경로를 한 칸 역행한다. `OUTER_1`에서는 출발칸
  `OUTER_20`으로 이동한다. `OUTER_20`에서는 완주하지 않고 실제 진입 경로에
  따라 `OUTER_19` 또는 `CENTER_9`로 이동한다.
- 빽도 도착 칸에도 일반 이동과 동일한 업기/잡기 규칙을 적용한다.
- 윷/모 추가 던지기를 먼저 처리하고 이동권을 소비한 뒤 잡기 추가 던지기를 처리한다.
- 서버 Node/Edge 그래프로 경로를 판정한다. 갈림길을 지나치는 것과 정확히 도착하는 것을 구분한다.
- 같은 소유자의 말은 업고, 상대 그룹은 정확히 도착했을 때 전부 READY로 돌린다.
- 잡힌 그룹 크기에 관계없이 추가 던지기 1회, 업힌 그룹은 함께 이동/완주한다.
- 도착점을 통과해야 완주한다. 개인전은 말 4개를 완주한 Player의 순위를
  확정하고 이후 턴에서 제외하며, 순위 미확정 Player가 한 명만 남으면 해당
  Player를 꼴등으로 자동 확정하고 종료한다.
  팀전은 공유 말 4개를 먼저 완주한 팀이 즉시 승리한다.
- `/state`는 모든 말의 확정 상태와 요청 Player의 `myAction`/후보를 제공한다.
- 파워 게이지·밀쳐내기·한 칸씩 이동은 Client 연출이다. 요청 필드나 서버 판정으로 사용하지 않는다.
- Mock의 고정 윷 결과/말 위치/단순 외곽 이동 로직은 Product 규칙으로 복사하지 않는다.
- 구현 체크리스트와 미확정 계약은 `docs/IMPLEMENTATION_NOTES.md`의 윷놀이 절을 따른다.

## 11-4. 피그 불변 규칙

- 참가자 2~6명 개인전, 목표 점수 50점 고정
- 현재 Player만 `ROLL` 또는 허용된 시점의 `STOP` 수행
- 턴의 첫 `1` 발생 확률은 10%, 성공할 때마다 다음 확률이 10%p 증가하며 최대 90%
- `1`이 아니면 `2`~`6`은 동일 확률이고 같은 숫자가 한 턴에 반복될 수 있음
- 1이면 턴 점수만 소멸하고 다음 PLAYING Player에게 진행
- STOP이면 턴 점수를 totalScore에 확정
- 50점 이상을 STOP으로 먼저 확정한 순서가 순위이며 점수순 재정렬 금지
- FINISHED Player는 턴에서 제외하고 마지막 PLAYING Player는 자동 마지막 순위
- 주사위 결과, 점수, 턴, 순위와 종료는 Room 원자 경계에서 서버가 결정
- `/state`는 성공 횟수, 서버 확정 확률, Player 상태와 요청 Player의 허용 행동을 제공
- 행동 요청은 `Idempotency-Key`로 재전송 중복을 방지
- PIG 구현은 `game/pig`에 격리하며 윷놀이 전용 코드에 의존하지 않음

## 11-5. 누피 콱! 불변 규칙

- 게임 타입 `TOOTH`, 표시 이름 `누피 콱!`, 참가자 2~8명 개인전
- 윗니 12개와 아랫니 12개, 총 24개이며 꽝은 정확히 1개
- 시작 시 서버가 전체 턴 순서와 꽝 위치를 무작위로 확정
- 현재 Player만 `AVAILABLE` 이빨 하나를 선택할 수 있고 턴 넘기기 없음
- `bombToothId`는 실제 BOMB 선택이 확정되기 전까지 Client에 비공개
- 안전/꽝, 다음 턴, 당첨 Player와 즉시 종료는 서버가 원자적으로 확정
- 이빨 선택은 `Idempotency-Key`와 Room 단위 동시성으로 중복 처리 방지
- 결과는 당첨 Player 한 명이며 순위와 별도 승자는 없음
- 한 판 더는 새 GameSession으로 이빨, 꽝 위치와 턴 순서를 전부 재초기화
- `/state`는 24개 공개 선택 상태와 요청 Player의 `allowedActions`를 제공
- TOOTH 구현은 `game/tooth`에 격리하며 다른 게임 전용 코드에 의존하지 않음

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
- 윷놀이 개인전 완주자의 턴 제외와 관전 Projection
- 윷놀이 개인전 마지막 한 명이 남으면 꼴등 자동 확정 후 종료
- 피그 2~6명 시작 검증과 현재 Player 행동 검증
- 피그 숫자 반복, 성공별 1 발생 확률 증가, 1 발생 시 턴 점수 소멸, STOP 점수 확정
- 피그 FINISHED 순서와 마지막 Player 자동 순위
- 피그 행동 idempotency와 재접속 Projection
- 누피 콱! 2~8명 시작 검증과 턴 순서/꽝 정확히 1개 초기화
- 누피 콱! 현재 턴·이빨 범위·중복 선택 검증과 Idempotency-Key 처리
- 누피 콱! SAFE 다음 턴과 BOMB 즉시 종료의 원자성
- 누피 콱! 진행 중 꽝 비노출과 재접속 Projection

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
