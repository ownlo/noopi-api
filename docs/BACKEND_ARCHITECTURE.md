# NOOPI (누피) Backend Architecture

## 1. 목적
이 문서는 `docs/common/`의 공통 서비스/API/실시간/게임 규칙을 Backend에서 구현하기 위한 기술 구조를 정의한다.

공통 계약의 Source of Truth:
- `docs/common/SERVICE_SPEC.md`
- `docs/common/GAME_SESSION_SPEC.md`
- `docs/common/REALTIME_SPEC.md`
- `docs/common/API_SPEC.md`
- `docs/common/games/LIAR_GAME_SPEC.md`
- `docs/common/games/BLIND_GAME_SPEC.md`
- `docs/common/games/MAFIA_GAME_SPEC.md`
- `docs/common/games/YUT_GAME_SPEC.md`
- `docs/common/games/PIG_GAME_SPEC.md`
- `docs/common/games/TOOTH_GAME_SPEC.md`

`docs/common/`은 Frontend의 동일 디렉터리와 내용까지 일치시킨다. 아래 구조는
구현 목표이며 게임별 실제 구현 여부는 `IMPLEMENTATION_NOTES.md`를 확인한다.

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
 ├─ Blind Game Runtime
 ├─ Mafia Game Runtime
 ├─ Yut Game Runtime
 ├─ Pig Game Runtime
 ├─ Tooth Game Runtime
 ├─ Realtime
 └─ Persistence
       ↓
     MySQL
```

서버의 현재 상태가 Source of Truth다. WebSocket 이벤트만으로 상태를 재구성하지 않으며 재접속/새로고침 시 `GET /api/rooms/{roomId}/state`로 복구한다.

## 4. Room과 GameSession
Room은 사람들이 모여 있는 공간이며 특정 게임에 종속되지 않는다.

Room Code는 `000000`부터 `999999`까지의 정확히 6자리 숫자 문자열로
생성한다. 앞자리 `0`을 보존하며, 현재 존재하는 Room의 코드와 충돌하면
새 코드를 다시 생성한다.

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
- 선정된 제시어와 발언 순서
- 투표, voteRound, 재투표 후보
- 라이어 최종 추측과 게임 결과
- 블라인드 Player별 제시어 배정, 정답 시도와 승자
- 마피아 역할·생존 상태, 밤 번호와 phase, 밤 행동, 경찰 조사 기록, 시민 의심 기록·집계
- 마피아 공격·의사 치료·사망 판정, 처형 투표·재투표·찬반 투표, 승리 팀
- 윷놀이 모드, 팀 구성, 턴 순서/phase, 이동권, 추가 던지기 수
- 윷놀이 말 위치/경로/그룹/완주 현황, 선택 중인 이동권/말/경로 후보,
  개인전 완주 순위와 팀전 승자
- 피그 Player별 확정 점수/상태/순위, 현재 턴 점수, 성공 횟수/1 발생 확률과 최근 결과
- 누피 콱! 턴 순서, 현재 턴, 24개 이빨 선택 상태, 꽝 이빨, 최근 선택과 당첨 Player
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

블라인드 상세 상태는 `BlindGameRuntime`이 소유한다. Room과 공통
GameSession은 Player별 제시어 공개 규칙이나 승리 조건을 알지 않는다.

마피아 상세 상태는 `MafiaGameRuntime`이 소유한다. Room과 공통
GameSession은 역할별 밤 행동, 사망·승패 판정, 개인 정보 공개 규칙을 알지 않는다.

## 7. Persistence
MySQL은 영구 관리가 필요한 게임별 콘텐츠에 사용한다.
- LiarCategory
- LiarKeyword
- BlindKeyword

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

## 9-1. Game Catalog

지원 게임 카탈로그는 게임 선택 UI가 사용하는 카테고리 정의와 각 게임의
복수 카테고리 소속을 함께 제공한다. Backend가 카테고리 코드, 표시 이름,
표시 순서와 소속 관계의 Source of Truth다.

`GET /api/games`는 다음을 하나의 응답으로 반환한다.

- `catalogCategories`: `code`, `name`, `order`
- `games`: 기존 게임 정보와 `catalogCategoryCodes`

활성 게임에는 하나 이상의 유효한 카테고리 코드를 지정한다. 하나의 게임이
여러 카테고리에 속할 수 있으며, 동일 코드를 중복 지정하지 않는다. 카탈로그
분류는 조회용 메타데이터이므로 Room Runtime이나 GameSession Runtime에
복제하지 않고 게임 생성 `config`에도 포함하지 않는다.

`ALL`은 Backend 카테고리로 만들지 않는다. Frontend가 전체 게임을 표시할 때
사용하는 가상 필터다. 라이어 제시어 카테고리와 이름, 타입, 저장 경계를
공유하지 않는다.

초기 분류는 `MINI_GAME`, `PARTY_GAME`, `DEDUCTION`, `STRATEGY`, `LUCK`,
`INDIVIDUAL`, `TEAM`이며 정확한 표시 이름과 게임 소속은
`common/API_SPEC.md`를 따른다.

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

## 12. 역할과 발언 순서
게임 시작 시 참가자 3~12명을 검증한 후 정확히 한 명의 라이어를 서버가 랜덤 선정한다.

모든 참가자가 역할 확인을 완료하면 서버가 전체 GameSession 참가자의 발언 순서를 랜덤으로 선정하고 `DISCUSSION`으로 전환한다. 모든 참가자는 순서에 정확히 한 번 포함되며 첫 번째 Player가 첫 발언자다. 서버는 최초 순서만 보관하고 현재 발언자나 순서 진행 상태는 관리하지 않는다.

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

## 16-1. Blind Game Runtime

블라인드 게임은 정확히 두 명의 참가자를 가진다. 시작 시 라이어 콘텐츠와
분리된 활성 BlindKeyword Repository를 사용해 서로 다른 제시어 두 개를
선택하고 Player별로 배정한다. 블라인드 제시어는 카테고리를 갖지 않는다.

Runtime 원본에는 두 배정을 보관할 수 있지만 `/state` Projection은
`GUESSING` 동안 요청 Player의 배정값을 제외하고 상대방의 제시어만
포함한다. 전체 Runtime 객체를 직렬화하지 않는다.

정답 제출은 Room 단위 동시성 경계 안에서 검증·판정한다. 오답이면 phase를
유지하고 이후 제출을 허용한다. 정답이면 승자 기록과 `FINISHED` 전환을
원자적으로 수행한다. 먼저 종료를 확정한 요청 이후의 요청은 공통 계약의
`GAME_SESSION_ALREADY_FINISHED`로 거부한다.

질문, 답변, 순서, 턴, 질문 횟수와 타이머 상태는 Runtime에 추가하지 않는다.

## 16-2. Mafia Game Runtime

마피아 게임은 4~12명을 검증하고 참가자 수에 따라 `MAFIA`, `POLICE`,
`DOCTOR`, `CITIZEN` 구성을 서버가 자동 결정한다. 주요 phase는
`ROLE_REVEAL`, `FIRST_NIGHT`, `DAY`, `VOTING`, `REVOTING`, `VOTE_RESULT`,
`JUDGMENT`, `JUDGMENT_RESULT`, `EXECUTION`, `NIGHT`, `NIGHT_RESULT`, `FINISHED`다.

모든 생존자의 필수 밤 행동은 동시에 받되 Room 단위 동시성 경계 안에서
한 번만 적용한다. 첫 밤에는 경찰 조사·시민 의심·의사 확인만 수행하고
공격·치료는 하지 않는다. 둘째 밤부터 마피아 공격, 경찰 조사, 의사 치료,
시민 의심을 동시 처리한다. 마피아 공격 최다 동률은 동률 대상 중 서버가
무작위 선정하며, 의사는 직전 밤의 치료 대상을 연속으로 선택할 수 없다.

낮 처형 투표는 모든 생존자가 참여하고, 최다 동률이면 동률 후보만으로
제한 없이 재투표한다. 단독 지목자가 결정되면 최후의 변론 후 지목자를
제외한 생존자가 `EXECUTE` 또는 `SAVE`를 선택한다. `executeCount > saveCount`일
때만 처형하고 동률은 살린다. 사망이 확정될 때마다 `aliveMafia == 0`이면
시민팀, `aliveMafia >= aliveCitizenTeam`이면 마피아팀 승리로 즉시 종료한다.

`/state`는 요청 Player 기준 Projection을 만든다. 요청자에게 허용되지 않은
살아 있는 다른 Player의 역할, 마피아 동료, 경찰 조사 결과, 시민별
의심 대상과 다른 Player의 의심 수를 노출하지 않는다. 마피아 동료는
마피아 본인에게만, 경찰 조사 기록은 경찰 본인에게만 제공한다. 사망과
함께 공개된 역할과 게임 종료 후 전체 역할은 공개한다.
방장의 결과 단계 진행은 API 계약의 `VOTE_RESULT → JUDGMENT`,
`JUDGMENT_RESULT → EXECUTION 또는 NIGHT`, `EXECUTION → NIGHT 또는 FINISHED`,
`NIGHT_RESULT → DAY 또는 FINISHED`에서만 허용한다.

## 16-3. 윷놀이 Runtime

윷놀이 상세 규칙은 `game/yut` 모듈이 소유한다. Room은 팀 정원, 경로,
업기/잡기/완주를 계산하지 않는다. 공통 GameSession의 생명주기와 윷놀이의
`READY`/`TEAM_SELECT`/`PLAYING`/`FINISHED` phase를 구분한다.

서버는 Node/Edge 그래프와 말의 경로 정보를 보관한다. 정확히 갈림길에
도착한 경우에만 경로 선택을 허용하고, 통과 시 기존 경로를 유지한다.
Client가 전달하는 것은 계약에 정의된 팀/이동권/말/경로 ID와 던지기 의도뿐이다.

Room 잠금 안에서 현재 턴, 행동 단계, 소유자와 후보를 재검증한 후 이동권
소비, 그룹 이동, 업기/잡기, 추가 던지기, 완주/순위·승리와 다음 행동을
원자적으로 확정한다. 개인전 완주자는 순위를 확정하고 턴 순서에서 제외하며,
마지막 한 명만 남으면 해당 Player를 꼴등으로 자동 확정하고 종료한다. 팀전은 같은 팀 말 4개를 공유하며 양
팀 Player를 교차 배치하고 한 팀이 완주하면 즉시 종료한다.

윷/모에 의한 추가 던지기는 이동권 선택보다 우선한다. 잡기로 얻은 추가
던지기는 기존 이동권을 모두 사용한 뒤 처리한다. 같은 이동권의 중복 소비,
같은 이동의 중복 잡기 보너스 지급, 종료 후 추가 행동을 막는다.

개인화 Projection은 `turn`, 모든 `pieces`, `finishedPieceCounts`, 개인전의
`rankings`/`myRank`와 `myAction`을 제공한다. 행동할 수 없거나 이미 완주한
요청 Player의 `myAction`은 `null`이다.
`SELECT_PIECE`/`SELECT_PATH` 후보는 서버 계산 결과만 반환한다. 팀 선택 시
`myTeam`, `selectableTeams`, `canStart`도 요청 Player 기준으로 계산한다.

`YUT_TEAM_CHANGED`, `YUT_TURN_CHANGED`, `YUT_THROW_RESOLVED`,
`YUT_PIECE_MOVED`는 확정 상태의 갱신 신호다. 공통 시작/종료/취소 이벤트를
재사용하며 전체 payload는 공통 API 명세를 따른다. 연출 종료를 기다려
서버 상태를 확정하거나 Client의 애니메이션 완료 요청을 추가하지 않는다.

던지기 API에는 body가 없다. 길게 누르는 파워는 Client 연출일 뿐 서버의
결과 확률이나 이동 수를 바꾸지 않는다. 재접속 시 이벤트를 재생하지 않고
현재 선택, 남은 이동권과 보너스를 포함한 `/state`로 복구한다.

윷놀이에는 연결 종료를 이유로 자동 턴 넘김, 자동 이동, 임의 승리 또는
장기 미접속 제외를 도입하지 않는다. 상세 미확정 항목은 구현 노트를 따른다.

## 16-4. 피그 Runtime

피그 상세 규칙은 `game/pig` 모듈이 소유한다. `PigGameRuntime`은 Player별
`totalScore`/상태/순위, 턴 순서와 현재 턴 점수, 성공 횟수, `1` 발생 확률,
최근 주사위 결과를 보관한다. Room과 윷놀이 모듈은 피그 규칙을 알지 않는다.

`PigGameService`의 ROLL/STOP은 Room 잠금 안에서 현재 Player, phase,
허용 행동과 `Idempotency-Key`를 검증하고 상태 변경과 이벤트 발행을 함께
처리한다. 50점 도달 순위 및 마지막 Player 자동 순위도 같은 원자 경계에서
확정한다.

`PigStateProjection`은 서버 확정 상태와 요청 Player의 `allowedActions`만
반환한다. Client가 확률이나 Player 목록으로 주사위 결과, 다음 턴, 순위 또는
종료를 계산할 수 있도록 별도 판정 데이터를 만들지 않는다.

`PIG_ROLL_RESOLVED`, `PIG_TURN_CHANGED`, `PIG_PLAYER_FINISHED`는 상태 갱신
신호이며 전체 종료에는 공통 `GAME_FINISHED`를 사용한다. 재접속은 이벤트
재생이 아니라 `/state` projection으로 복구한다.

## 16-5. 누피 콱! Runtime

누피 콱! 상세 규칙은 `game/tooth` 모듈이 소유한다. `ToothGameRuntime`은
참가자 턴 순서, 현재 턴 Player, 24개 이빨 선택 상태, 서버 전용 꽝 이빨,
증가하는 선택 `sequence`, 최근 결과와 당첨 Player를 보관한다. Room과 다른
게임 모듈은 꽝 선정, 다음 턴 또는 당첨 판정을 알지 않는다.

시작 시 서버는 2~8명을 검증하고 턴 순서와 꽝 이빨 정확히 하나를 무작위로
확정한다. `selectTooth`는 Room 잠금 안에서 현재 Player, `PLAYING` phase,
`SELECT_TOOTH` 허용 여부, 이빨 범위·선택 여부와 `Idempotency-Key`를 검증한다.
`SAFE`면 이빨 선택과 다음 턴을, `BOMB`이면 이빨 선택·당첨 Player·
GameSession `FINISHED` 전환을 하나의 원자적 변경으로 처리한다.

`ToothStateProjection`은 진행 중 24개 이빨의 공개 선택 상태, 남은 수, 턴,
최근 확정 결과와 요청 Player의 `allowedActions`만 반환한다. 서버 내부
`bombToothId`는 `FINISHED` 전까지 Projection과 이벤트에 포함하지 않는다.
종료 후에는 당첨 Player와 실제 꽝 이빨을 공개하며 순위나 승자를 만들지 않는다.

`TOOTH_SELECTED`는 확정된 선택 결과의 갱신 신호이며 전체 종료에는 공통
`GAME_FINISHED`를 사용한다. 재접속은 이벤트 재생이 아니라 `/state`
Projection으로 복구한다. 연결 종료만으로 자동 턴 전환이나 당첨 처리를 하지
않고, 진행 중 명시적 이탈이면 공통 게임 규칙에 따라 취소한다.

## 17. Disconnect / Reconnect
WebSocket 연결 종료는 Room 탈퇴가 아니다. Player를 `DISCONNECTED`로 표시하고 일정 시간 재접속을 허용한다.

재접속 시 동일 `clientId`로 기존 Player를 복구한 뒤 `/state`로 현재 상태를 동기화한다.

투표 중 장기 미접속 Player는 방장이 현재 GameSession에서 제외할 수 있다. 이미 제출한 현재 라운드 표가 있으면 무효 처리하고 required vote count를 다시 계산한다.

실제 라이어를 제외해야 하면 GameSession을 `CANCELLED` 처리하고 게임 중 새 라이어를 선정하지 않는다.

정확한 장기 미접속 시간은 Backend 운영 설정으로 둔다.

## 18. Host
Room 생성자가 최초 Host다.

Host가 Disconnect되어도 Room을 자동 종료하거나 Host 권한을 이전하지 않는다. 참가 가능한 Room이면 신규 조회/참가를 허용하며 기존 참가자는 제한 없이 재접속을 기다리거나 직접 Room을 떠날 수 있다. Host가 나가기 API로 명시적으로 Room을 떠나면 `ROOM_CLOSED` 이벤트를 `HOST_LEFT` 사유로 발행한 뒤 Room을 삭제한다.

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
- 블라인드 동시 정답 제출과 승자 확정
- 마피아 마지막 역할 확인·밤 행동·처형 투표 제출과 phase 전환
- 마피아 밤 결과·사망·승리 판정과 방장의 결과 단계 진행
- 윷놀이 팀 정원 경쟁, 이동권 중복 소비, 그룹 이동/잡기/완주와
  개인전 순위·팀전 승리 확정
- 피그 중복 ROLL/STOP, 점수 확정·턴 전환과 FINISHED/마지막 순위 확정
- 누피 콱! 동시 이빨 선택, 중복 키, SAFE 턴 전환과 BOMB 당첨/FINISHED 확정

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

Actuator health와 기본 구조화 로그를 사용한다. 일반 운영 로그에 제시어, 전체 역할 목록, 개인별 투표 대상, 마피아 동료, 경찰 조사 결과, 시민별 의심 대상 같은 게임 비밀을 불필요하게 남기지 않는다.

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

## 운영 통계 확장 (2026-09-13 요청)

콘텐츠 전용 저장 경계에 일별 운영 집계를 추가한다. Runtime 객체를 영속화하지 않는다.
`daily_room_metrics`: instance_id(UUID), metric_date 복합 PK, rooms_created, rooms_closed,
duration_millis, max_duration_millis (모두 BIGINT).
`daily_game_metrics`: instance_id, metric_date, game_type 복합 PK, started, finished,
cancelled, participant_count (모두 BIGINT). 날짜는 한국 시간이다.
프로세스별 누적 snapshot을 upsert하여 저장 재시도가 중복 합산되지 않도록 한다.
조회는 인스턴스별 행을 날짜/게임별 합산한다. SQL은 Flyway V5가 정의하며 JdbcTemplate으로 처리한다.
방 단위 lock 안에서는 메모리 집계만 변경하고 DB 저장은 별도 주기 작업으로 수행한다.
운영 통계 API 계약과 지표의 정의/유실 한계는 `OPERATIONS_API.md`를 따른다.
