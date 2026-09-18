# NOOPI (누피) API Specification

## 1. 목적

이 문서는 모바일 웹 Frontend와 Backend 사이의 REST API 및 WebSocket
이벤트 계약을 정의한다.

공통 서비스 규칙은 `SERVICE_SPEC.md`, GameSession 생명주기는
`GAME_SESSION_SPEC.md`, 실시간 원칙은 `REALTIME_SPEC.md`, 게임별 규칙은
`games/LIAR_GAME_SPEC.md`, `games/BLIND_GAME_SPEC.md`,
`games/MAFIA_GAME_SPEC.md`, `games/YUT_GAME_SPEC.md`를 따른다.

서버의 현재 상태가 Source of Truth이며, 클라이언트는 게임
상태·역할·승패·투표 결과를 자체 계산하지 않는다.

------------------------------------------------------------------------

## 2. 공통 규칙

### Base URL

``` text
/api
```

### Content-Type

``` text
Content-Type: application/json
```

### Client 식별

Room 관련 요청은 익명 브라우저 식별값을 다음 헤더로 전달한다.

``` text
X-Client-Id: <clientId>
```

`clientId`는 최초 접속 시 클라이언트가 UUID 등 충분히 충돌 가능성이 낮은
값으로 생성하고 동일 브라우저에서 재사용한다.

`clientId`는 인증 토큰이 아니다.

### ID 타입

API에서 ID는 숫자형 식별자를 사용한다.

``` json
{
  "roomId": 100,
  "playerId": 12,
  "gameSessionId": 55
}
```

Room 참가용 코드는 별도의 `roomCode` 문자열을 사용한다. `roomCode`는
정확히 6자리 숫자(`^[0-9]{6}$`)이며 앞자리 `0`을 보존한다.

### 시간

시간 값은 ISO-8601 문자열을 사용한다.

``` text
2026-09-09T14:30:00+09:00
```

------------------------------------------------------------------------

## 3. 공통 성공 응답

조회 API는 필요한 데이터를 직접 응답한다.

``` json
{
  "roomId": 100,
  "roomCode": "042731"
}
```

행동 API에서 별도 응답 데이터가 필요하지 않으면 `204 No Content`를
사용할 수 있다.

리소스 생성 API는 `201 Created`, 일반 성공 API는 `200 OK` 또는
`204 No Content`를 사용한다.

------------------------------------------------------------------------

## 4. 공통 오류 응답

모든 도메인 오류는 동일한 형태를 사용한다.

``` json
{
  "code": "ALREADY_VOTED",
  "message": "이미 이번 투표에 참여했습니다."
}
```

필드:

  필드        설명
  ----------- ----------------------------------
  `code`      클라이언트 분기용 고정 오류 코드
  `message`   사용자에게 표시 가능한 메시지

HTTP Status 기본 원칙:

  Status   용도
  -------- ------------------------------------------
  `400`    잘못된 요청 값
  `403`    권한 없음
  `404`    Room, Player, GameSession 등 리소스 없음
  `409`    현재 상태와 충돌하거나 이미 수행한 행동
  `422`    형식은 유효하지만 게임 규칙상 수행 불가

클라이언트는 `message` 문자열이 아니라 `code`를 기준으로 분기한다.

------------------------------------------------------------------------

# Room API

Room 생성과 참가 요청의 `nickname`은 앞뒤 공백을 제거한 뒤 1글자 이상
5글자 이하여야 한다. 조건을 만족하지 않으면 `INVALID_NICKNAME`을 반환한다.

## 5. Room 생성

``` http
POST /api/rooms
```

Header:

``` text
X-Client-Id: <clientId>
```

Request:

``` json
{
  "nickname": "종윤",
  "gender": "MALE"
}
```

`gender`:

``` text
MALE
FEMALE
```

Response `201 Created`:

``` json
{
  "room": {
    "roomId": 100,
    "roomCode": "042731",
    "status": "WAITING"
  },
  "me": {
    "playerId": 12,
    "nickname": "종윤",
    "gender": "MALE",
    "host": true,
    "connectionStatus": "CONNECTED"
  }
}
```

생성자는 자동으로 해당 Room의 첫 Player이자 방장이 된다.

주요 오류:

``` text
INVALID_NICKNAME
INVALID_GENDER
```

------------------------------------------------------------------------

## 6. Room Code로 Room 조회

QR 또는 Room Code 입력 후 참가 화면을 구성할 때 사용한다.

``` http
GET /api/rooms/by-code/{roomCode}
```

Response `200 OK`:

``` json
{
  "roomId": 100,
  "roomCode": "042731",
  "status": "WAITING",
  "playerCount": 4,
  "joinable": true
}
```

이 API는 역할, 제시어, 투표 정보 등 게임의 비공개 정보를 반환하지
않는다.

방장의 연결 상태는 Room 조회 가능 여부에 영향을 주지 않는다. 현재 방장이
`DISCONNECTED` 상태여도 Room이 참가 가능한 상태라면 `joinable`은 `true`이며
정상적으로 Room 정보를 반환한다.

주요 오류:

``` text
ROOM_NOT_FOUND
ROOM_CLOSED
```

------------------------------------------------------------------------

## 7. Room 참가

``` http
POST /api/rooms/{roomId}/players
```

Header:

``` text
X-Client-Id: <clientId>
```

Request:

``` json
{
  "nickname": "예은",
  "gender": "FEMALE"
}
```

Response `201 Created`:

``` json
{
  "playerId": 13,
  "nickname": "예은",
  "gender": "FEMALE",
  "host": false,
  "connectionStatus": "CONNECTED"
}
```

동일 `clientId`가 이미 해당 Room의 Player라면 새로운 Player를 중복
생성하지 않는다. 기존 Player 복구가 가능한 경우 기존 Player 기준으로
처리한다.

진행 중인 GameSession이 있는 Room에 새로 참가한 Player는 Room에는
참가하지만 현재 GameSession 참가자에는 포함되지 않는다.

방장의 연결 상태는 신규 참가 가능 여부에 영향을 주지 않는다. 현재 방장이
`DISCONNECTED` 상태여도 Room이 참가 가능한 상태라면 새로운 Player의 참가
요청을 정상적으로 처리한다.

주요 오류:

``` text
ROOM_NOT_FOUND
ROOM_CLOSED
NICKNAME_ALREADY_EXISTS
INVALID_NICKNAME
INVALID_GENDER
```

------------------------------------------------------------------------

## 8. Room 나가기

``` http
DELETE /api/rooms/{roomId}/players/me
```

Header:

``` text
X-Client-Id: <clientId>
```

Response:

``` text
204 No Content
```

명시적인 나가기는 단순 WebSocket 연결 종료와 구분한다.

방장이 나가면 `ROOM_CLOSED` 이벤트를 `HOST_LEFT` 사유로 전송하고 Room을
삭제한다. 남은 참가자는 홈 화면으로 이동한다.

현재 GameSession 참가자의 이탈 처리는 해당 게임 규칙을 따른다.

주요 오류:

``` text
ROOM_NOT_FOUND
PLAYER_NOT_IN_ROOM
```

------------------------------------------------------------------------

## 8.1 Room 대기 로비로 이동

방장이 Room을 유지한 채 모든 참가자를 공통 대기 로비로 이동시킬 때
사용한다.

``` http
POST /api/rooms/{roomId}/lobby
```

Header:

``` text
X-Client-Id: <clientId>
```

Response:

``` text
204 No Content
```

진행 중인 GameSession이 있으면 서버는 `HOST_RETURNED_TO_LOBBY` 사유로
취소한 뒤 현재 GameSession을 제거한다. 이미 종료된 GameSession이 있으면
현재 GameSession에서 제거한다. Room과 Player는 유지하며 이후 `/state`는
`gameSession: null`, Room `status: WAITING`을 반환한다.

서버는 전체 참가자에게 `ROOM_RETURNED_TO_LOBBY` 이벤트를 전송한다.

주요 오류:

``` text
ROOM_NOT_FOUND
PLAYER_NOT_IN_ROOM
NOT_ROOM_HOST
```

------------------------------------------------------------------------

# State API

## 9. 현재 Room/Game 상태 조회

새로고침, 재접속, WebSocket 이벤트 유실 또는 최초 Room 화면 진입 시
사용하는 핵심 복구 API다.

``` http
GET /api/rooms/{roomId}/state
```

Header:

``` text
X-Client-Id: <clientId>
```

Response `200 OK` 예시:

``` json
{
  "room": {
    "roomId": 100,
    "roomCode": "042731",
    "status": "ACTIVE",
    "hostPlayerId": 12
  },
  "me": {
    "playerId": 13,
    "nickname": "예은",
    "gender": "FEMALE",
    "host": false,
    "connectionStatus": "CONNECTED"
  },
  "players": [
    {
      "playerId": 12,
      "nickname": "종윤",
      "gender": "MALE",
      "host": true,
      "connectionStatus": "CONNECTED",
      "currentGameParticipant": true
    },
    {
      "playerId": 13,
      "nickname": "예은",
      "gender": "FEMALE",
      "host": false,
      "connectionStatus": "CONNECTED",
      "currentGameParticipant": true
    }
  ],
  "gameSession": {
    "gameSessionId": 55,
    "gameType": "LIAR",
    "status": "PLAYING",
    "gameState": {
      "type": "LIAR",
      "phase": "ROLE_REVEAL",
      "myRole": "CITIZEN",
      "keyword": "바다",
      "roleChecked": false,
      "roleCheckedCount": 2,
      "participantCount": 4,
      "playerRoleCheckStatuses": [
        { "playerId": 11, "checked": true },
        { "playerId": 12, "checked": false },
        { "playerId": 13, "checked": true },
        { "playerId": 14, "checked": false }
      ]
    }
  }
}
```

진행 중인 GameSession이 없으면:

``` json
{
  "room": {
    "roomId": 100,
    "roomCode": "042731",
    "status": "WAITING",
    "hostPlayerId": 12
  },
  "me": {
    "playerId": 13,
    "nickname": "예은",
    "gender": "FEMALE",
    "host": false,
    "connectionStatus": "CONNECTED"
  },
  "players": [],
  "gameSession": null
}
```

### 개인별 응답 원칙

`gameState`는 요청 Player에게 허용된 정보만 포함한다.

시민:

``` json
{
  "myRole": "CITIZEN",
  "keyword": "바다"
}
```

라이어:

``` json
{
  "myRole": "LIAR",
  "keyword": null
}
```

`ROLE_REVEAL`과 `DISCUSSION` 단계에서는 본인이 역할과 제시어를 다시 확인할
수 있도록 위 개인별 필드를 동일하게 반환한다. 시민의 `keyword`는 유지하며,
라이어의 `keyword`는 항상 `null`이다.

`DISCUSSION` 단계의 `gameState` 예:

``` json
{
  "type": "LIAR",
  "phase": "DISCUSSION",
  "myRole": "CITIZEN",
  "keyword": "바다",
  "speakingOrderPlayerIds": [14, 9, 21, 3]
}
```

`speakingOrderPlayerIds`는 전체 GameSession 참가자의 `playerId`를 발언
순서대로 담는다. 모든 참가자는 정확히 한 번 포함되며 첫 번째 값이 첫
발언자다.

다른 Player의 역할은 게임 종료 전 절대 반환하지 않는다.

다른 Player가 누구에게 투표했는지도 투표 전·진행 중·종료 후 모두
반환하지 않는다.

블라인드 게임 `GUESSING` 단계의 `gameState` 예:

``` json
{
  "type": "BLIND",
  "phase": "GUESSING",
  "opponentPlayer": {
    "playerId": 12,
    "nickname": "종윤"
  },
  "opponentKeyword": "기린"
}
```

게임 종료 전에는 현재 Player 본인의 실제 제시어를 응답에 포함하지 않는다.
상대방의 제시어 카테고리도 반환하지 않는다.

### 마피아 게임 개인화 상태

마피아 게임의 `gameState`는 phase와 현재 Player의 생존 여부, 허용된 행동,
서버가 계산한 공개 결과를 기준으로 구성한다. 역할과 개인 기록은 요청
Player에게 허용된 범위만 반환한다.

모든 마피아 게임 상태에는 다음 공통 필드를 포함한다.

``` json
{
  "type": "MAFIA",
  "phase": "DAY",
  "myRole": "POLICE",
  "alive": true,
  "players": [
    {
      "playerId": 12,
      "nickname": "종윤",
      "alive": true,
      "revealedRole": null
    },
    {
      "playerId": 14,
      "nickname": "철수",
      "alive": false,
      "revealedRole": "DOCTOR"
    }
  ]
}
```

`revealedRole`은 처형 또는 밤 사망으로 역할이 공개된 Player에게만 값을
제공하며, 그 외 Player는 게임 종료 전 항상 `null`이다. `myRole`은 현재
Player 본인의 역할이므로 게임 중 모든 phase에서 다시 제공할 수 있다.

`ROLE_REVEAL` 예시:

``` json
{
  "type": "MAFIA",
  "phase": "ROLE_REVEAL",
  "myRole": "MAFIA",
  "alive": true,
  "roleChecked": false,
  "roleCheckedCount": 3,
  "participantCount": 6,
  "mafiaTeammates": [
    { "playerId": 12, "nickname": "종윤", "alive": true }
  ]
}
```

`mafiaTeammates`는 현재 Player가 `MAFIA`일 때만 포함한다. 다른 역할에는
빈 배열이 아니라 필드 자체를 포함하지 않는다. 역할 확인 완료 Player의
신원은 공개하지 않고 완료 수만 반환한다.

`FIRST_NIGHT` 또는 `NIGHT` 예시:

``` json
{
  "type": "MAFIA",
  "phase": "NIGHT",
  "nightNo": 2,
  "alive": true,
  "myRole": "POLICE",
  "nightAction": {
    "actionType": "INVESTIGATE",
    "submitted": false,
    "eligibleTargets": [
      { "playerId": 12, "nickname": "종윤" },
      { "playerId": 14, "nickname": "철수" }
    ]
  },
  "nightProgress": {
    "completedActionCount": 3,
    "requiredActionCount": 6
  },
  "investigationHistory": [
    {
      "nightNo": 1,
      "targetPlayerId": 14,
      "targetNickname": "철수",
      "mafia": false
    }
  ]
}
```

`eligibleTargets`는 역할, 생존 여부, 자기 선택 가능 여부, 마피아 동료 제외,
의사의 직전 치료 대상 제외 규칙을 서버가 적용한 결과다. Frontend는 전체
Player 목록으로 후보를 다시 계산하지 않는다. 행동을 제출한 뒤에는
`submitted = true`이며 후보 목록을 제공하지 않는다.

마피아에게는 `mafiaTeammates`를 `ROLE_REVEAL` 이후 게임 종료 전까지 계속
반환하여 재접속 후에도 복구할 수 있게 한다. 경찰에게는 본인의
`investigationHistory`를 게임 중 계속 반환한다. 다른 역할이나 다른
Player에게 이 정보를 반환하지 않는다.

첫 번째 밤의 마피아와 의사에게는 `nightAction.actionType = "CONFIRM"`과
빈 `eligibleTargets`를 반환한다. 일반 밤에는 역할에 따라 `ATTACK`,
`INVESTIGATE`, `HEAL`, `SUSPECT` 중 하나만 반환한다. 사망한 Player에게는
`nightAction`과 `nightProgress`를 반환하지 않는다.

`DAY` 예시:

``` json
{
  "type": "MAFIA",
  "phase": "DAY",
  "dayNo": 2,
  "alive": true,
  "remainingTeamCounts": {
    "mafia": 1,
    "citizenTeam": 3
  },
  "lastNightResult": {
    "nightNo": 2,
    "deadPlayer": {
      "playerId": 14,
      "nickname": "철수",
      "revealedRole": "DOCTOR"
    },
    "mySuspicionCount": 2
  }
}
```

사망자가 없으면 `deadPlayer`는 `null`이다. `mySuspicionCount`는 현재 요청
Player가 직전 밤에 받은 시민 의심 수만 나타내며 다른 Player의 수나
시민별 의심 대상은 포함하지 않는다.

첫 번째 낮은 `dayNo = 1`이며 공격이 없으므로 `deadPlayer = null`이다.

`remainingTeamCounts`는 서버가 현재 생존 역할을 기준으로 집계한 공개 정보다.
`mafia`는 생존 마피아 수, `citizenTeam`은 경찰과 의사를 포함한 생존 시민팀
수를 나타낸다. Frontend는 공개된 사망자 역할로 이 값을 직접 계산하지 않는다.

`VOTING` 또는 `REVOTING` 예시:

``` json
{
  "type": "MAFIA",
  "phase": "VOTING",
  "myRole": "CITIZEN",
  "alive": true,
  "vote": {
    "round": 1,
    "eligibleCandidates": [
      { "playerId": 12, "nickname": "종윤" },
      { "playerId": 14, "nickname": "철수" }
    ],
    "requiredVoteCount": 5,
    "completedVoteCount": 2,
    "myVoteSubmitted": false
  }
}
```

마피아 게임에서는 투표 중 누가 완료했는지도 공개하지 않으므로
`playerVoteStatuses`를 포함하지 않는다. 사망한 Player의 `vote`에는
`eligibleCandidates`와 `myVoteSubmitted`를 포함하지 않고 전체 완료 수만
제공할 수 있다.

`VOTE_RESULT` 예시:

``` json
{
  "type": "MAFIA",
  "phase": "VOTE_RESULT",
  "voteResult": {
    "round": 1,
    "tied": false,
    "counts": [
      { "playerId": 12, "nickname": "종윤", "voteCount": 3 },
      { "playerId": 14, "nickname": "철수", "voteCount": 2 }
    ],
    "executionTargetPlayerId": 12
  },
  "canAdvance": true
}
```

`canAdvance`는 현재 요청 Player가 방장이고 현재 phase에서 진행 요청을 할 수
있을 때만 `true`다. 동률이면 phase는 `REVOTING`이고 서버가 동률 후보만
`eligibleCandidates`로 제공한다.

`EXECUTION` 예시:

``` json
{
  "type": "MAFIA",
  "phase": "EXECUTION",
  "executionResult": {
    "playerId": 12,
    "nickname": "종윤",
    "revealedRole": "MAFIA"
  },
  "canAdvance": true
}
```

`NIGHT_RESULT`는 `DAY`의 `lastNightResult`와 동일한 구조로 해당 밤의
`nightResult`를 제공하고 방장에게만 `canAdvance = true`를 반환한다. 승리
조건이 충족되면 `EXECUTION` 또는 `NIGHT_RESULT`에 머무르지 않고 즉시
`FINISHED`를 반환한다.

사망한 Player도 공개된 Player 생존 상태, 공개 역할, 투표 결과, 처형 결과,
밤 사망 결과를 조회할 수 있다. 다만 `alive = false`이며 밤 행동과 처형
투표를 위한 후보나 제출 가능 상태는 절대 제공하지 않는다.

------------------------------------------------------------------------

# Game Catalog API

## 10. 지원 게임 목록 조회

``` http
GET /api/games
```

Response `200 OK`:

``` json
{
  "games": [
    {
      "gameType": "LIAR",
      "name": "라이어 게임",
      "minPlayers": 3,
      "maxPlayers": 12,
      "enabled": true
    },
    {
      "gameType": "BLIND",
      "name": "블라인드 게임",
      "minPlayers": 2,
      "maxPlayers": 2,
      "enabled": true
    },
    {
      "gameType": "MAFIA",
      "name": "마피아 게임",
      "minPlayers": 4,
      "maxPlayers": 12,
      "enabled": true
    },
    {
      "gameType": "YUT",
      "name": "윷놀이",
      "minPlayers": 2,
      "maxPlayers": 4,
      "enabled": true
    }
  ]
}
```

------------------------------------------------------------------------

# Liar Game Configuration API

## 11. 라이어 게임 카테고리 조회

``` http
GET /api/games/liar/categories
```

Response `200 OK`:

``` json
{
  "categories": [
    {
      "code": "RANDOM",
      "name": "랜덤",
      "virtual": true
    },
    {
      "code": "FOOD",
      "name": "음식",
      "virtual": false
    },
    {
      "code": "PLACE",
      "name": "장소",
      "virtual": false
    }
  ]
}
```

`RANDOM`은 DB의 실제 카테고리가 아니라 서버가 제공하는 가상 옵션이다.

전체 제시어 목록은 API로 제공하지 않는다.

------------------------------------------------------------------------

# GameSession API

## 12. GameSession 생성

방장이 게임과 설정을 선택하면 `READY` 상태의 GameSession을 생성한다.

``` http
POST /api/rooms/{roomId}/game-sessions
```

Header:

``` text
X-Client-Id: <clientId>
```

Request --- 라이어 게임:

``` json
{
  "gameType": "LIAR",
  "config": {
    "categoryCode": "FOOD"
  }
}
```

랜덤 카테고리:

``` json
{
  "gameType": "LIAR",
  "config": {
    "categoryCode": "RANDOM"
  }
}
```

Request --- 블라인드 게임:

``` json
{
  "gameType": "BLIND",
  "config": {}
}
```

블라인드 게임에는 카테고리 설정이 없다. Client가 카테고리 값을 보내면
`INVALID_GAME_CONFIG`를 반환한다.

Request --- 마피아 게임:

``` json
{
  "gameType": "MAFIA",
  "config": {}
}
```

Request --- 윷놀이 개인전:

``` json
{
  "gameType": "YUT",
  "config": {
    "mode": "INDIVIDUAL"
  }
}
```

Request --- 윷놀이 팀전:

``` json
{
  "gameType": "YUT",
  "config": {
    "mode": "TEAM"
  }
}
```

개인전은 2~4명, 팀전은 정확히 4명만 허용한다. 팀전 생성 직후 게임 phase는
`TEAM_SELECT`이며 양 팀이 2명씩 채워지기 전에는 시작할 수 없다.

마피아 게임의 역할 구성은 시작 시점의 참가 인원에 따라 서버가 자동으로
결정한다. Client가 역할별 인원이나 기타 게임 설정을 보내면
`INVALID_GAME_CONFIG`를 반환한다.

마피아 게임 `READY` 상태의 `gameState`에는 현재 참가 인원에 따른 역할 구성
미리보기를 서버가 제공한다.

``` json
{
  "type": "MAFIA",
  "phase": "READY",
  "participantCount": 4,
  "roleComposition": {
    "mafia": 1,
    "police": 1,
    "doctor": 0,
    "citizen": 2
  }
}
```

시작 전 참가자가 바뀌면 서버는 `participantCount`와 `roleComposition`을
현재 인원 기준으로 다시 제공한다. 최종 역할 구성과 배정은 게임 시작
요청을 처리하는 시점에 확정한다.

Response `201 Created`:

``` json
{
  "gameSessionId": 55,
  "gameType": "LIAR",
  "status": "READY"
}
```

응답의 `gameType`은 요청한 게임 타입을 그대로 반환한다.

생성 시점에는 아직 역할/제시어를 클라이언트에 공개하지 않는다.

주요 오류:

``` text
NOT_ROOM_HOST
ACTIVE_GAME_SESSION_EXISTS
UNSUPPORTED_GAME_TYPE
INVALID_GAME_CONFIG
INVALID_CATEGORY
```

------------------------------------------------------------------------

## 13. GameSession 시작

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/start
```

Header:

``` text
X-Client-Id: <clientId>
```

Request body 없음.

Response:

``` text
204 No Content
```

서버는 시작 시점의 정상 참가 Player를 GameSession 참가자로 확정한다.

라이어 게임의 경우 서버가 다음을 수행한다.

``` text
참가 인원 검증
→ 활성 제시어 선정
→ 라이어 1명 랜덤 선정
→ 역할 배정
→ GameSession PLAYING
→ Liar phase ROLE_REVEAL
```

블라인드 게임의 경우 서버가 다음을 수행한다.

``` text
참가 인원이 정확히 2명인지 검증
→ 전체 활성 제시어 풀에서 서로 다른 제시어 2개 선정
→ Player별 제시어 배정
→ GameSession PLAYING
→ Blind phase GUESSING
```

마피아 게임의 경우 서버가 다음을 수행한다.

``` text
참가 인원이 4~12명인지 검증
→ 참가 인원에 맞는 역할 구성 결정
→ 역할 무작위 배정
→ GameSession PLAYING
→ Mafia phase ROLE_REVEAL
```

주요 오류:

``` text
NOT_ROOM_HOST
GAME_SESSION_NOT_FOUND
GAME_SESSION_NOT_READY
NOT_ENOUGH_PLAYERS
TOO_MANY_PLAYERS
NO_AVAILABLE_KEYWORD
```

------------------------------------------------------------------------

## 14. GameSession 취소

방장이 정상 진행이 불가능한 게임을 취소할 때 사용한다.

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/cancel
```

Header:

``` text
X-Client-Id: <clientId>
```

Response:

``` text
204 No Content
```

주요 오류:

``` text
NOT_ROOM_HOST
GAME_SESSION_NOT_FOUND
GAME_SESSION_ALREADY_FINISHED
```

------------------------------------------------------------------------

# Liar Game Action API

## 15. 역할 확인 완료

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/role-check
```

Header:

``` text
X-Client-Id: <clientId>
```

Response:

``` text
204 No Content
```

모든 참가자가 역할 확인을 완료하면 서버가 전체 참가자의 발언 순서를
랜덤으로 선정하고 `DISCUSSION` 단계로 전환한다.

주요 오류:

``` text
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
ROLE_ALREADY_CHECKED
```

------------------------------------------------------------------------

## 16. 투표 시작

방장만 호출할 수 있다.

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/votes/start
```

Header:

``` text
X-Client-Id: <clientId>
```

Response `200 OK`:

``` json
{
  "voteRound": 1
}
```

최초 투표는 `voteRound = 1`이다.

재투표는 서버가 이전 라운드 집계 결과에 따라 자동으로 생성하므로 방장이
다시 투표 시작 API를 호출하지 않는다.

주요 오류:

``` text
NOT_ROOM_HOST
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
VOTE_ALREADY_STARTED
```

------------------------------------------------------------------------

## 17. 투표 제출

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/votes
```

Header:

``` text
X-Client-Id: <clientId>
```

Request:

``` json
{
  "voteRound": 1,
  "targetPlayerId": 15
}
```

Response:

``` text
204 No Content
```

서버 검증:

-   요청자가 현재 GameSession 참가자인지
-   현재 단계가 `VOTING` 또는 `REVOTING`인지
-   요청의 `voteRound`가 현재 서버 투표 라운드와 일치하는지
-   현재 라운드에서 아직 투표하지 않았는지
-   자기 자신에게 투표하지 않았는지
-   대상 Player가 현재 라운드의 유효 후보인지

최초 투표에서는 자기 자신을 제외한 모든 유효 참가자가 후보가 된다.

재투표에서는 직전 라운드의 최다 득표 동률 후보만 후보가 된다.

모든 필요한 Player가 투표를 완료하면 서버가 자동 집계한다.

단독 최다 득표자가 있으면 최종 지목자로 결정한다.

최다 득표자가 여러 명이면 해당 동률 후보만 대상으로 다음 `voteRound`를
생성하고 재투표한다.

재투표 횟수에는 제한이 없으며 서버가 동률 후보 중 한 명을 랜덤으로
선정하지 않는다.

주요 오류:

``` text
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
INVALID_VOTE_ROUND
ALREADY_VOTED
CANNOT_VOTE_SELF
INVALID_VOTE_TARGET
```

------------------------------------------------------------------------

## 18. 투표 상태 표현

투표 중 `GET /state`의 라이어 게임 상태 예시:

``` json
{
  "type": "LIAR",
  "phase": "VOTING",
  "vote": {
    "round": 1,
    "eligibleCandidates": [
      {
        "playerId": 12,
        "nickname": "종윤"
      },
      {
        "playerId": 14,
        "nickname": "철수"
      }
    ],
    "requiredVoteCount": 4,
    "completedVoteCount": 2,
    "myVoteSubmitted": true,
    "playerVoteStatuses": [
      {
        "playerId": 12,
        "submitted": true
      },
      {
        "playerId": 13,
        "submitted": true
      },
      {
        "playerId": 14,
        "submitted": false
      },
      {
        "playerId": 15,
        "submitted": false
      }
    ]
  }
}
```

`eligibleCandidates`는 현재 요청자가 실제로 선택 가능한 후보만 제공한다.
따라서 자기 자신은 제외될 수 있다.

`playerVoteStatuses`에는 완료 여부만 포함하며 투표 대상은 포함하지
않는다.

------------------------------------------------------------------------

## 19. 투표 결과 표현

투표 종료 후 공개 가능한 정보:

``` json
{
  "type": "LIAR",
  "phase": "VOTE_RESULT",
  "voteResult": {
    "round": 1,
    "tied": false,
    "counts": [
      {
        "playerId": 12,
        "nickname": "종윤",
        "voteCount": 3
      },
      {
        "playerId": 14,
        "nickname": "철수",
        "voteCount": 1
      }
    ],
    "accusedPlayerId": 12
  }
}
```

동률이면:

``` json
{
  "type": "LIAR",
  "phase": "REVOTING",
  "vote": {
    "round": 2,
    "eligibleCandidates": [
      {
        "playerId": 12,
        "nickname": "종윤"
      },
      {
        "playerId": 14,
        "nickname": "철수"
      }
    ],
    "requiredVoteCount": 4,
    "completedVoteCount": 0,
    "myVoteSubmitted": false
  },
  "previousVoteResult": {
    "round": 1,
    "tied": true,
    "counts": [
      {
        "playerId": 12,
        "nickname": "종윤",
        "voteCount": 2
      },
      {
        "playerId": 14,
        "nickname": "철수",
        "voteCount": 2
      }
    ]
  }
}
```

**어떤 응답에서도 `voterPlayerId → targetPlayerId` 관계를 다른
참가자에게 공개하지 않는다.**

후보별 총 득표수만 공개한다.

------------------------------------------------------------------------

## 20. 장기 미접속 참가자 현재 게임에서 제외

투표 등 진행이 막힌 경우 방장이 장기 미접속 Player를 현재
GameSession에서 제외할 수 있다.

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/players/{playerId}/exclude
```

Header:

``` text
X-Client-Id: <clientId>
```

Response:

``` text
204 No Content
```

제외된 Player가 이미 제출한 현재 라운드 투표가 있다면 서버는 해당 표를
무효 처리하고 필요한 투표 인원을 다시 계산한다.

라이어를 제외해야 하는 상황이면 라이어 게임 규칙에 따라 GameSession을
`CANCELLED` 처리한다.

실제 "장기 미접속"으로 판단하는 시간 기준은 Backend 운영 설정에서
정의한다.

마피아 게임의 장기 미접속 참가자 제외 정책은 V1에서 정의하지 않는다.
따라서 마피아 GameSession에서는 이 API를 제공하지 않는다.

주요 오류:

``` text
NOT_ROOM_HOST
PLAYER_NOT_IN_GAME
PLAYER_NOT_DISCONNECTED
PLAYER_NOT_EXCLUDABLE
```

------------------------------------------------------------------------

## 21. 라이어 마지막 제시어 추측

최종 지목자가 실제 라이어일 때만 `LIAR_GUESS` 단계로 진입한다.

이 API는 실제 라이어만 호출할 수 있다.

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/guess
```

Header:

``` text
X-Client-Id: <clientId>
```

Request:

``` json
{
  "answer": "바다"
}
```

Response `200 OK`:

``` json
{
  "correct": true
}
```

제출과 동시에 서버가 정답을 판정하고 게임을 종료한다.

한 번 제출한 답은 변경할 수 없다.

시민이 호출하면 서버에서 거부한다.

주요 오류:

``` text
PLAYER_NOT_IN_GAME
NOT_LIAR
INVALID_GAME_PHASE
GUESS_ALREADY_SUBMITTED
INVALID_ANSWER
```

------------------------------------------------------------------------

# Blind Game Action API

## 22. 블라인드 게임 정답 제출

게임 참가자는 자신에게 배정된 제시어라고 추리한 답을 제출한다.

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/blind/guesses
```

Header:

``` text
X-Client-Id: <clientId>
```

Request:

``` json
{
  "answer": "피자"
}
```

오답 Response `200 OK`:

``` json
{
  "correct": false
}
```

오답이어도 `GUESSING` 단계를 유지한다. 탈락, 턴 변경, 시도 횟수 제한은
없으며 상대방에게 오답 사실이나 답안 내용을 공개하지 않는다.

최초 정답 Response `200 OK`:

``` json
{
  "correct": true
}
```

서버는 제출 Player 본인에게 배정된 제시어와 답안을 비교한다. 정답이면
해당 Player를 승자로 확정하고 GameSession을 즉시 `FINISHED`로 변경한다.
거의 동시에 정답이 제출되어도 승자 확정과 종료 전환을 하나의 원자적
처리로 수행하여 승자는 정확히 한 명이어야 한다. 종료가 먼저 확정된 뒤
도착한 요청은 `GAME_SESSION_ALREADY_FINISHED`를 반환한다.

주요 오류:

``` text
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
INVALID_ANSWER
GAME_SESSION_ALREADY_FINISHED
```

------------------------------------------------------------------------

# Mafia Game Action API

아래 모든 요청은 공통으로 다음 Header를 사용한다.

``` text
X-Client-Id: <clientId>
```

## 마피아 역할 확인 완료

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/role-check
```

Request body 없음. 각 Player가 자신의 역할과, 마피아라면 동료 목록을 확인한
뒤 호출한다. 모든 참가자가 완료하면 서버는 `FIRST_NIGHT`로 전환한다.

Response: `204 No Content`

주요 오류:

``` text
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
ROLE_ALREADY_CHECKED
```

## 마피아 밤 행동 제출

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/night-actions
```

Request는 역할과 현재 밤에 허용된 행동을 discriminated union으로 표현한다.

``` json
{ "actionType": "ATTACK", "targetPlayerId": 15 }
```

``` json
{ "actionType": "INVESTIGATE", "targetPlayerId": 15 }
```

``` json
{ "actionType": "HEAL", "targetPlayerId": 13 }
```

``` json
{ "actionType": "SUSPECT", "targetPlayerId": 15 }
```

첫 번째 밤에 행동이 없는 의사와 공격할 수 없는 마피아는 다음 요청으로
확인을 제출한다.

``` json
{ "actionType": "CONFIRM" }
```

일반 Response `200 OK`:

``` json
{ "actionType": "HEAL" }
```

경찰 조사 Response `200 OK`:

``` json
{
  "actionType": "INVESTIGATE",
  "result": {
    "targetPlayerId": 15,
    "mafia": true
  }
}
```

조사 결과는 제출 응답에서 즉시 반환하고 개인 조사 기록에 저장한다. 이후
경찰 본인의 `/state`에 `investigationHistory`로 다시 제공한다.

모든 생존자의 필수 행동이 완료되면 첫 번째 밤은 `DAY`로, 일반 밤은
`NIGHT_RESULT`로 자동 전환한다.

주요 오류:

``` text
PLAYER_NOT_IN_GAME
PLAYER_DEAD
INVALID_GAME_PHASE
INVALID_NIGHT_ACTION
ACTION_ALREADY_SUBMITTED
INVALID_ACTION_TARGET
```

## 마피아 낮 투표 시작

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/votes/start
```

방장만 `DAY`에서 호출할 수 있다. Response와 투표 라운드 규칙은 라이어
게임의 투표 시작 API와 동일하다.

Response `200 OK`:

``` json
{ "voteRound": 1 }
```

주요 오류:

``` text
NOT_ROOM_HOST
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
VOTE_ALREADY_STARTED
```

## 마피아 처형 투표 제출

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/votes
```

``` json
{
  "voteRound": 1,
  "targetPlayerId": 15
}
```

모든 생존자가 투표하며 `eligibleCandidates`는 서버가 제공한다. 비밀투표,
자기 자신 투표 금지, 동률 후보 재투표, 무제한 재투표 규칙은 라이어 게임
투표 API와 동일하다. 단독 최다 득표자가 결정되면 역할을 공개하지 않은 채
`VOTE_RESULT`로 전환한다.

Response: `204 No Content`

주요 오류:

``` text
PLAYER_NOT_IN_GAME
PLAYER_DEAD
INVALID_GAME_PHASE
INVALID_VOTE_ROUND
ALREADY_VOTED
CANNOT_VOTE_SELF
INVALID_VOTE_TARGET
```

## 마피아 처형 찬반 투표 제출

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/judgment-votes
```

``` json
{ "choice": "EXECUTE" }
```

`choice`는 `EXECUTE` 또는 `SAVE`다. 최종 지목자를 제외한 생존 Player만 한
번 제출할 수 있다. 투표 중에도 `executeCount`, `saveCount`, 완료 인원을
`/state`와 상태 변경 이벤트로 공개한다. 개인별 선택은 공개하지 않는다.
전원 완료 시 `executeCount > saveCount`일 때만 처형하며 동률은 살린다.

Response: `204 No Content`

## 마피아 결과 단계 진행

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/advance
```

Request body 없음. 방장만 다음 전환을 요청할 수 있다.

``` text
VOTE_RESULT → JUDGMENT
JUDGMENT_RESULT → EXECUTION 또는 NIGHT
EXECUTION → NIGHT 또는 FINISHED
NIGHT_RESULT → DAY 또는 FINISHED
```

`VOTE_RESULT`는 최후의 변론 화면으로 방장이 종료할 때까지 유지한다. 방장이
종료하면 `JUDGMENT`로 전환한다. `JUDGMENT_RESULT`에서 처형으로
결정된 경우에만 `EXECUTION` 진입 시 최종 지목자를 사망 처리하고 역할을
공개한다. 살리기로 결정되면 역할을 공개하지 않고 `NIGHT`로 이동한다. 사망이
확정되는 `EXECUTION` 및 `NIGHT_RESULT` 처리 시 서버가 승리 조건을 검사하며,
승리 조건이 충족되면 다음 진행 phase 대신 즉시 `FINISHED`가 된다.

Response: `204 No Content`

동일 phase에서 중복 호출하여 두 단계를 한 번에 건너뛸 수 없도록 서버가
phase 전환을 원자적으로 처리한다. 이미 전환된 요청은
`INVALID_GAME_PHASE`로 거부한다.

주요 오류:

``` text
NOT_ROOM_HOST
PLAYER_NOT_IN_GAME
INVALID_GAME_PHASE
```

------------------------------------------------------------------------

# Finished State

## 23. 라이어 게임 최종 결과

게임 종료 후 `GET /state`에서는 전체 참가자에게 최종 결과를 공개한다.

시민 승리 예시:

``` json
{
  "type": "LIAR",
  "phase": "FINISHED",
  "result": {
    "winner": "CITIZEN",
    "liarPlayer": {
      "playerId": 13,
      "nickname": "예은"
    },
    "keyword": "바다",
    "accusedPlayer": {
      "playerId": 13,
      "nickname": "예은"
    },
    "liarGuess": {
      "answer": "수영장",
      "correct": false
    }
  }
}
```

시민을 잘못 지목하여 라이어가 승리한 경우:

``` json
{
  "type": "LIAR",
  "phase": "FINISHED",
  "result": {
    "winner": "LIAR",
    "liarPlayer": {
      "playerId": 13,
      "nickname": "예은"
    },
    "keyword": "바다",
    "accusedPlayer": {
      "playerId": 12,
      "nickname": "종윤"
    },
    "liarGuess": null
  }
}
```

게임 종료 후에는 실제 라이어와 제시어를 전체 참가자에게 공개할 수 있다.

### 블라인드 게임 최종 결과

``` json
{
  "type": "BLIND",
  "phase": "FINISHED",
  "result": {
    "winnerPlayer": {
      "playerId": 13,
      "nickname": "예은"
    },
    "keywordAssignments": [
      {
        "playerId": 12,
        "nickname": "종윤",
        "keyword": "피자"
      },
      {
        "playerId": 13,
        "nickname": "예은",
        "keyword": "기린"
      }
    ]
  }
}
```

종료 후에는 두 Player의 실제 제시어를 두 참가자 모두에게 공개한다.

### 마피아 게임 최종 결과

``` json
{
  "type": "MAFIA",
  "phase": "FINISHED",
  "result": {
    "winnerTeam": "CITIZEN_TEAM",
    "players": [
      {
        "playerId": 12,
        "nickname": "종윤",
        "role": "MAFIA",
        "alive": false
      },
      {
        "playerId": 13,
        "nickname": "예은",
        "role": "POLICE",
        "alive": true
      }
    ]
  }
}
```

`winnerTeam`은 `MAFIA_TEAM` 또는 `CITIZEN_TEAM`이다. 게임 종료 후에만 전체
참가자의 역할을 공개한다.

------------------------------------------------------------------------

# WebSocket API

## 24. 연결

개념적 WebSocket endpoint:

``` text
/ws
```

연결 시 최소한 `clientId`와 `roomId`를 서버가 식별할 수 있어야 한다.

구체적인 transport 방식은 Backend 구현에서 정할 수 있지만, 인증되지 않은
임의 Client가 다른 Room 이벤트를 구독할 수 없도록 Room 참가 여부를
검증해야 한다.

WebSocket은 상태 저장소가 아니다. 연결 직후 또는 재접속 후에는
`GET /api/rooms/{roomId}/state`로 현재 상태를 동기화한다.

------------------------------------------------------------------------

## 25. 이벤트 공통 Envelope

``` json
{
  "eventId": "01JXYZ...",
  "type": "PLAYER_JOINED",
  "roomId": 100,
  "gameSessionId": 55,
  "occurredAt": "2026-09-09T14:30:00+09:00",
  "payload": {}
}
```

필드:

  필드              설명
  ----------------- ----------------------------------------------
  `eventId`         이벤트 식별자
  `type`            이벤트 종류
  `roomId`          Room ID
  `gameSessionId`   GameSession 관련 이벤트가 아니면 `null` 가능
  `occurredAt`      서버 이벤트 발생 시각
  `payload`         이벤트별 최소 데이터

이벤트는 화면 갱신 신호다. 클라이언트가 이벤트만으로 전체 상태를 영구
재구성할 필요는 없다.

------------------------------------------------------------------------

## 26. Room 이벤트

### PLAYER_JOINED

``` json
{
  "type": "PLAYER_JOINED",
  "payload": {
    "playerId": 16,
    "nickname": "민수"
  }
}
```

### PLAYER_DISCONNECTED

``` json
{
  "type": "PLAYER_DISCONNECTED",
  "payload": {
    "playerId": 16
  }
}
```

### PLAYER_RECONNECTED

``` json
{
  "type": "PLAYER_RECONNECTED",
  "payload": {
    "playerId": 16
  }
}
```

### PLAYER_LEFT

``` json
{
  "type": "PLAYER_LEFT",
  "payload": {
    "playerId": 16
  }
}
```

### HOST_CHANGED

``` json
{
  "type": "HOST_CHANGED",
  "payload": {
    "hostPlayerId": 13
  }
}
```

### ROOM_CLOSED

방장이 명시적으로 Room을 나가 Room이 종료될 때 전체 참가자에게 전달한다.

``` json
{
  "type": "ROOM_CLOSED",
  "payload": {
    "reason": "HOST_LEFT"
  }
}
```

수신한 Client는 저장된 마지막 Room 정보를 제거하고 홈 화면으로 이동한다.

### ROOM_RETURNED_TO_LOBBY

방장이 Room의 모든 참가자를 대기 로비로 이동시켰을 때 전달한다.

``` json
{
  "type": "ROOM_RETURNED_TO_LOBBY",
  "gameSessionId": null,
  "payload": {}
}
```

수신한 Client는 `GET /state`를 다시 조회하고 Room 대기 로비를 표시한다.
화면에는 방장이 모두를 대기실로 이동했다는 안내를 표시할 수 있다.

------------------------------------------------------------------------

## 27. GameSession 이벤트

### GAME_SESSION_CREATED

``` json
{
  "type": "GAME_SESSION_CREATED",
  "gameSessionId": 55,
  "payload": {
    "gameType": "LIAR"
  }
}
```

### GAME_STARTED

``` json
{
  "type": "GAME_STARTED",
  "gameSessionId": 55,
  "payload": {
    "gameType": "LIAR"
  }
}
```

역할이나 제시어는 이 broadcast 이벤트에 포함하지 않는다.

### GAME_FINISHED

``` json
{
  "type": "GAME_FINISHED",
  "gameSessionId": 55,
  "payload": {}
}
```

수신 후 `GET /state`로 최종 결과를 조회한다.

### GAME_CANCELLED

``` json
{
  "type": "GAME_CANCELLED",
  "gameSessionId": 55,
  "payload": {
    "reason": "LIAR_LEFT"
  }
}
```

------------------------------------------------------------------------

## 28. 라이어 게임 이벤트

### ROLE_CHECKED

Room broadcast:

``` json
{
  "type": "ROLE_CHECKED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 13,
    "roleCheckedCount": 3,
    "participantCount": 4
  }
}
```

역할 자체는 포함하지 않는다.

### DISCUSSION_STARTED

``` json
{
  "type": "DISCUSSION_STARTED",
  "gameSessionId": 55,
  "payload": {
    "speakingOrderPlayerIds": [14, 9, 21, 3]
  }
}
```

### VOTE_STARTED

``` json
{
  "type": "VOTE_STARTED",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 1
  }
}
```

### PLAYER_VOTED

``` json
{
  "type": "PLAYER_VOTED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 13,
    "voteRound": 1,
    "completedVoteCount": 3,
    "requiredVoteCount": 4
  }
}
```

`targetPlayerId`는 포함하지 않는다.

### VOTE_COMPLETED

``` json
{
  "type": "VOTE_COMPLETED",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 1
  }
}
```

### VOTE_RESULT

``` json
{
  "type": "VOTE_RESULT",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 1,
    "tied": false,
    "counts": [
      {
        "playerId": 12,
        "voteCount": 3
      },
      {
        "playerId": 14,
        "voteCount": 1
      }
    ],
    "accusedPlayerId": 12
  }
}
```

개별 Player의 투표 대상은 포함하지 않는다.

### REVOTE_STARTED

``` json
{
  "type": "REVOTE_STARTED",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 2,
    "candidatePlayerIds": [12, 14]
  }
}
```

동률이 반복되면 `voteRound`를 증가시키며 `REVOTE_STARTED`를 다시
발생시킨다.

재투표 횟수 제한은 없다.

### LIAR_REVEALED

최종 지목 결과가 판정된 뒤 발생한다.

``` json
{
  "type": "LIAR_REVEALED",
  "gameSessionId": 55,
  "payload": {
    "accusedPlayerId": 12,
    "accusedWasLiar": true
  }
}
```

실제 라이어를 검거하지 못해 게임이 즉시 종료되는 경우 실제 라이어 정보는
최종 상태에서 공개한다.

### LIAR_GUESS_STARTED

Room broadcast:

``` json
{
  "type": "LIAR_GUESS_STARTED",
  "gameSessionId": 55,
  "payload": {}
}
```

라이어에게만 필요한 입력 가능 여부는 개인별 `GET /state` 응답으로
판단한다.

### LIAR_GUESS_SUBMITTED

``` json
{
  "type": "LIAR_GUESS_SUBMITTED",
  "gameSessionId": 55,
  "payload": {}
}
```

정답 내용은 broadcast하지 않는다. 최종 결과는 `GAME_FINISHED` 후 상태
조회로 확인한다.

## 마피아 게임 이벤트

### MAFIA_ROLE_CHECKED

``` json
{
  "type": "MAFIA_ROLE_CHECKED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 13,
    "roleCheckedCount": 3,
    "participantCount": 6
  }
}
```

### MAFIA_NIGHT_ACTION_SUBMITTED

``` json
{
  "type": "MAFIA_NIGHT_ACTION_SUBMITTED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 13,
    "nightNo": 2,
    "completedActionCount": 4,
    "requiredActionCount": 6
  }
}
```

행동 타입과 대상, 조사 결과, 치료 성공 여부는 포함하지 않는다.

### MAFIA_PHASE_CHANGED

``` json
{
  "type": "MAFIA_PHASE_CHANGED",
  "gameSessionId": 55,
  "payload": {
    "phase": "NIGHT_RESULT"
  }
}
```

### MAFIA_VOTE_STARTED

``` json
{
  "type": "MAFIA_VOTE_STARTED",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 1,
    "requiredVoteCount": 5
  }
}
```

### MAFIA_PLAYER_VOTED

``` json
{
  "type": "MAFIA_PLAYER_VOTED",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 1,
    "completedVoteCount": 3,
    "requiredVoteCount": 5
  }
}
```

마피아 게임에서는 투표 완료 Player의 신원도 공개하지 않으므로
`playerId`와 `targetPlayerId`를 모두 포함하지 않는다.

### MAFIA_VOTE_RESULT

``` json
{
  "type": "MAFIA_VOTE_RESULT",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 1,
    "tied": false,
    "counts": [
      { "playerId": 12, "voteCount": 3 },
      { "playerId": 14, "voteCount": 2 }
    ],
    "executionTargetPlayerId": 12
  }
}
```

개인별 투표 관계는 포함하지 않는다.

### MAFIA_REVOTE_STARTED

``` json
{
  "type": "MAFIA_REVOTE_STARTED",
  "gameSessionId": 55,
  "payload": {
    "voteRound": 2,
    "candidatePlayerIds": [12, 14],
    "requiredVoteCount": 5
  }
}
```

### MAFIA_PLAYER_DIED

``` json
{
  "type": "MAFIA_PLAYER_DIED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 14,
    "revealedRole": "DOCTOR",
    "cause": "NIGHT_ATTACK"
  }
}
```

`cause`는 `NIGHT_ATTACK` 또는 `EXECUTION`이다. 수신 Client는 `/state`를
다시 조회한다.

------------------------------------------------------------------------

# Yut Game API

## 윷놀이 상태 조회 계약

`GET /api/rooms/{roomId}/state`의 `gameState.type = "YUT"`이면 모든 판정이
끝난 서버 상태와 현재 Player에게 허용된 행동만 반환한다.

팀 선택 단계 예시:

``` json
{
  "type": "YUT",
  "phase": "TEAM_SELECT",
  "mode": "TEAM",
  "teams": [
    {
      "team": "NOOPI",
      "name": "누피팀",
      "capacity": 2,
      "players": [{ "playerId": 12, "nickname": "종윤" }]
    },
    {
      "team": "DAY",
      "name": "데이팀",
      "capacity": 2,
      "players": []
    }
  ],
  "myTeam": null,
  "selectableTeams": ["NOOPI", "DAY"],
  "canStart": false
}
```

`selectableTeams`는 서버가 정원과 현재 소속을 반영한 결과다. `canStart`는
요청자가 방장이고 양 팀이 2명씩 채워졌을 때만 `true`다.

진행 단계 예시:

``` json
{
  "type": "YUT",
  "phase": "PLAYING",
  "mode": "TEAM",
  "lastThrow": {
    "sequence": 8,
    "turnNo": 6,
    "playerId": 12,
    "result": "NAK",
    "steps": 0,
    "bonusThrowGranted": false
  },
  "turn": {
    "turnNo": 7,
    "currentPlayerId": 13,
    "turnPhase": "WAITING_MOVE",
    "throwResults": ["YUT", "BACK_DO", "GAE"],
    "moveTokens": [
      { "moveTokenId": "mt-31", "result": "YUT", "steps": 4 },
      { "moveTokenId": "mt-32", "result": "BACK_DO", "steps": -1 },
      { "moveTokenId": "mt-33", "result": "GAE", "steps": 2 }
    ],
    "pendingBonusThrows": 0
  },
  "pieces": [
    {
      "pieceId": "NOOPI-1",
      "ownerType": "TEAM",
      "ownerId": "NOOPI",
      "status": "ON_BOARD",
      "nodeId": "OUTER_3",
      "groupPieceIds": ["NOOPI-1", "NOOPI-2"]
    }
  ],
  "finishedPieceCounts": [
    { "ownerId": "NOOPI", "count": 0 },
    { "ownerId": "DAY", "count": 0 }
  ],
  "rankings": [],
  "myRank": null,
  "myAction": {
    "type": "SELECT_MOVE_TOKEN",
    "moveTokenIds": ["mt-31", "mt-32"]
  }
}
```

`pieces`에는 모든 말의 서버 확정 상태를 반환한다. `nodeId`는 화면 좌표가
아닌 서버 보드 그래프의 안정적인 Node ID다. `READY`와 `FINISHED` 말의
`nodeId`는 `null`이다. 같은 그룹의 각 말은 동일한 `groupPieceIds`를 가진다.

전체 Node/Path ID와 도착점 통과 기준은 `games/YUT_GAME_SPEC.md` 9.1절을
따른다. 문자열 ID인 `pieceId`, `ownerId`, `moveTokenId`, `nodeId`, `pathId`는
숫자형 Room/Player/GameSession ID와 구분한다. 개인전 `ownerId`는 Player ID의
문자열 표현이며 팀전은 `NOOPI` 또는 `DAY`다.

`lastThrow`는 GameSession 안에서 증가하는 `sequence`와 가장 최근 던지기의
공개 결과를 제공한다. 낙으로 이동권이 생성되지 않거나 턴이 바뀐 경우에도
유지되며 Client는 이를 이용해 던지기 결과 연출을 한 번만 재생한다.
아직 던지기가 없으면 `null`이다.

`myAction`은 현재 요청 Player가 행동할 수 없으면 `null`이며, 다음 중 하나다.

``` text
THROW_YUT
SELECT_MOVE_TOKEN
SELECT_PIECE
SELECT_PATH
```

`SELECT_PIECE`는 선택한 `moveTokenId`와 서버가 계산한 `eligiblePieceIds`를,
`SELECT_PATH`는 선택한 말과 이동권 및 `eligiblePathIds`를 포함한다. Frontend는
전체 말이나 보드 그래프로 후보를 다시 계산하지 않는다.

개인전의 `rankings`는 지금까지 말 4개를 모두 완주하여 순위가 확정된 Player를
`rank` 오름차순으로 제공한다. 단, 한 명만 남은 순간에는 그 Player를 마지막
순위로 자동 확정하고 종료 응답의 `rankings`에 포함한다. 현재 요청 Player의 순위가 확정되면 `myRank`에
그 순위를 제공하고 `myAction`은 `null`로 반환한다. 해당 Player는 관전 UI를
표시한다. 서버는 순위가 확정된 Player를 이후 턴에서 제외한다. Frontend는
`finishedPieceCounts`로 순위나 관전 여부를 계산하지 않는다. 팀전에서는
`rankings`는 빈 배열이고 `myRank`는 `null`이다.

개인전 종료 단계는 `winnerPlayer` 대신 전체 `rankings`를 반환하고, 팀전은
`winnerTeam`을 반환한다. 서버가 판정한 결과만 사용한다.

개인전 종료 예시:

``` json
{
  "type": "YUT",
  "phase": "FINISHED",
  "mode": "INDIVIDUAL",
  "rankings": [
    { "rank": 1, "playerId": 13, "nickname": "예은" },
    { "rank": 2, "playerId": 12, "nickname": "종윤" },
    { "rank": 3, "playerId": 14, "nickname": "철수" }
  ]
}
```

## 팀 선택/변경

``` http
PUT /api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/team
```

``` json
{ "team": "NOOPI" }
```

Response `204 No Content`. 같은 Endpoint로 시작 전 팀 변경을 요청한다.

주요 오류:

``` text
NOT_GAME_PARTICIPANT
INVALID_GAME_PHASE
INVALID_TEAM
TEAM_FULL
GAME_ALREADY_STARTED
```

## 윷 던지기

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/throws
```

Request body는 없다. Response `200 OK`:

``` json
{
  "result": "YUT",
  "steps": 4,
  "moveTokenId": "mt-31",
  "bonusThrowGranted": true
}
```

낙 Response:

``` json
{
  "result": "NAK",
  "steps": 0,
  "moveTokenId": null,
  "bonusThrowGranted": false
}
```

서버가 현재 턴과 phase를 검증하고 윷가락 4개의 결과로 최종 결과를 정한다.

길게 누르는 시간/파워는 Client 연출이며 Request에 포함하지 않는다.
서버는 각 윷가락의 앞뒤를 독립적으로 결정한다. 누피 캐릭터가 표시된 특수
윷가락만 앞면이면 `BACK_DO`(-1), 특수 윷가락을 제외한 하나만 앞면이면
`DO`(1), 앞면 수가 2/3/4이면 GAE/GEOL/YUT, 0이면 MO다. `BACK_DO`는
추가 던지기를 주지 않는다.

서버는 윷가락 조합 판정 전에 5% 확률로 `NAK`를 확정한다. 낙이면 해당
던지기의 이동권을 생성하지 않고 추가 던지기를 종료한다. 현재 보유한 기존
이동권은 유지하며 `/state`를 `SELECT_MOVE_TOKEN`으로 전환한다. 기존 이동권이
없을 때만 다음 Player의 턴으로 전환한다. 기존 이동권이 정확히 하나라면 Client는
그 이동권 선택 요청을 자동으로 보내 `SELECT_PIECE` 단계로 진행한다. Client가 턴
넘김 요청을 별도로 보내지는 않는다.

`BACK_DO` 이동권을 선택하면 서버는 현재 소유자의 `ON_BOARD` 말/그룹만
`eligiblePieceIds`로 제공한다. 판 위의 말이 없으면 해당 이동권은 소멸하고 서버가
다음 이동권 또는 턴으로 전환한다. 경로와 도착점은 서버가 해당 말의 진입 경로를
기준으로 판정하며 Frontend가 역경로를 계산하지 않는다. `OUTER_1`에서는 출발칸인
`OUTER_20`으로 이동하고, `OUTER_20`에서는 `FINISHED`가 된다. `OUTER_20`에
도착한 경우에도 같은 소유자 말은 업고 상대 말은 잡는다.

중복 요청은 Room 단위 잠금과 현재 행동 단계 검증으로 보호한다. 이 body
없는 계약만으로는 같은 Player의 연속 추가 던지기와 이전 요청의 지연 재전송을
완전히 구분할 수 없다. Client는 처리 중 중복 입력과 던지기 자동 재시도를
하지 않고, 응답이 유실되면 `/state`부터 조회한다. 요청 식별자 계약은 V1에 추가하지 않는다.

## 이동권 선택

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/move-selections
```

``` json
{ "moveTokenId": "mt-31" }
```

Response `204 No Content`. 이후 `/state`의 `myAction = SELECT_PIECE`에서
서버가 계산한 이동 가능한 말 또는 그룹 대표 말 후보를 조회한다.

## 말 선택

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/piece-selections
```

``` json
{ "pieceId": "NOOPI-1" }
```

경로 선택이 없으면 서버가 이동을 즉시 확정하고 `200 OK`로 결과를 반환한다.
경로 선택이 필요하면 `202 Accepted`로 현재 선택만 저장하며 `/state`의
`myAction = SELECT_PATH`에서 경로 후보를 제공한다.

`202 Accepted` 응답 body는 비어 있다. Client는 이를 JSON으로 파싱하지 않는다.
이동권 선택과 경로 대기 상태도 `/state`로 복구한다.

## 경로 선택

``` http
POST /api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/path-selections
```

``` json
{ "pathId": "CENTER_SHORTCUT_A" }
```

Response `200 OK`의 이동 결과 예시:

``` json
{
  "pieceIds": ["NOOPI-1", "NOOPI-2"],
  "fromNodeId": "OUTER_3",
  "toNodeId": "CENTER_1",
  "finished": false,
  "stackedPieceIds": [],
  "capturedPieceIds": ["DAY-1"],
  "bonusThrowGranted": true
}
```

말 이동 관련 API의 공통 주요 오류:

``` text
NOT_CURRENT_TURN
INVALID_TURN_PHASE
MOVE_TOKEN_NOT_FOUND
MOVE_TOKEN_ALREADY_USED
PIECE_NOT_ELIGIBLE
PATH_NOT_ELIGIBLE
ACTION_ALREADY_PROCESSED
```

윷놀이 오류 HTTP Status: `NOT_GAME_PARTICIPANT`/`NOT_CURRENT_TURN`은 403,
`INVALID_TEAM`은 400, `MOVE_TOKEN_NOT_FOUND`는 404,
`PIECE_NOT_ELIGIBLE`/`PATH_NOT_ELIGIBLE`은 422다.
`TEAM_FULL`, `GAME_ALREADY_STARTED`, `INVALID_GAME_PHASE`, `INVALID_TURN_PHASE`,
`MOVE_TOKEN_ALREADY_USED`, `ACTION_ALREADY_PROCESSED`는 409다.
종료 후 행동은 공통 `GAME_SESSION_ALREADY_FINISHED`(409)를 사용한다.

개인전에서 한 Player의 네 번째 말 이동이 확정되면 서버는 해당 Player의
다음 순위를 확정하고 남은 이동권과 추가 던지기를 소멸시킨다. 해당 Player를
턴 순서에서 제외한 뒤 남은 Player가 두 명 이상이면 다음 Player에게 턴을 넘긴다.
순위 미확정 Player가 한 명만 남으면 그 Player를 마지막 순위로 자동 확정하고
GameSession을 `FINISHED`로 변경하여 전체 순위를 반환한다. 팀전은
기존과 같이 한 팀의 말 4개가 완주하는 즉시 종료한다.

## 윷놀이 WebSocket 이벤트

모든 이벤트는 상태 갱신 신호이며 수신 후 `room-state` Query를 invalidate한다.

``` json
{
  "type": "YUT_TEAM_CHANGED",
  "gameSessionId": 55,
  "payload": { "playerId": 13, "team": "NOOPI" }
}
```

``` json
{
  "type": "YUT_TURN_CHANGED",
  "gameSessionId": 55,
  "payload": { "turnNo": 8, "currentPlayerId": 14 }
}
```

``` json
{
  "type": "YUT_THROW_RESOLVED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 13,
    "result": "NAK",
    "steps": 0,
    "bonusThrowGranted": false
  }
}
```

``` json
{
  "type": "YUT_PIECE_MOVED",
  "gameSessionId": 55,
  "payload": {
    "playerId": 13,
    "pieceIds": ["NOOPI-1", "NOOPI-2"],
    "fromNodeId": "OUTER_3",
    "toNodeId": "CENTER_1",
    "finished": false,
    "stackedPieceIds": [],
    "capturedPieceIds": ["DAY-1"],
    "bonusThrowGranted": true
  }
}
```

후보 목록은 broadcast하지 않고 개인화된 `/state`로 제공한다.

------------------------------------------------------------------------

# Security / Information Exposure

## 29. 절대 공개하면 안 되는 정보

게임 종료 전 다음 정보를 권한 없는 Client에게 전달하지 않는다.

-   게임 규칙상 아직 공개가 확정되지 않은 다른 Player의 실제 역할
-   라이어에게 현재 제시어
-   블라인드 게임에서 종료 전 현재 Player 본인의 제시어
-   마피아 게임에서 종료 전 공개되지 않은 다른 Player의 역할
-   경찰 조사 대상과 결과(요청 경찰 외 Client 기준)
-   시민별 의심 대상과 다른 Player의 개인 의심 수
-   마피아별 공격 선택과 의사의 치료 선택
-   전체 역할 배정 정보
-   전체 제시어 목록
-   다른 Player의 투표 대상
-   서버 내부 정답 데이터
-   다른 Room의 상태

UI에서 숨기는 것으로 보안을 대체하지 않는다.

Backend DTO 생성 단계부터 현재 Player에게 허용된 정보만 포함한다.

------------------------------------------------------------------------

## 30. 행동 권한 검증

모든 게임 행동 API에서 서버는 최소한 다음을 검증한다.

``` text
Room 존재
→ X-Client-Id에 대응하는 Player 존재
→ 해당 GameSession 참가 여부
→ GameSession 상태
→ 게임 내부 phase
→ 방장 전용 행동 여부
→ 역할 전용 행동 여부
→ 이미 수행한 행동인지
```

Frontend의 버튼 표시 여부는 권한 검증 수단이 아니다.

------------------------------------------------------------------------

# Idempotency / Duplicate Request

## 31. 중복 요청 처리

네트워크 재시도 또는 더블 클릭으로 같은 요청이 여러 번 전달될 수 있다.

다음 행동은 서버에서 중복 실행을 방지한다.

-   GameSession 시작
-   역할 확인
-   투표 제출
-   라이어 추측 제출
-   블라인드 게임에서 최초 정답에 따른 승자 확정
-   GameSession 취소
-   마피아 역할 확인과 밤 행동
-   마피아 처형 투표와 결과 단계 진행

이미 완료된 행동을 다시 요청하면 상태에 따라 `409 Conflict`와 고정 오류
코드를 반환한다.

블라인드 게임의 오답 제출은 시도 횟수 제한이 없으므로 이후의 새 제출을
막지 않는다. 단, 동일 요청의 전송 중 중복과 승자 확정 경쟁은 서버에서
안전하게 처리한다.

특히 투표는 다음 논리 키로 한 번만 허용한다.

``` text
gameSessionId + voteRound + voterPlayerId
```

마피아 밤 행동은 다음 논리 키로 한 번만 허용한다.

``` text
gameSessionId + nightNo + playerId
```

------------------------------------------------------------------------

# Reconnect

## 32. 재접속 처리

WebSocket 연결 종료 자체는 Room 탈퇴가 아니다.

동일 브라우저가 동일 `clientId`로 돌아오면 기존 Player를 복구한다.

재접속 흐름:

``` text
WebSocket 재연결
→ GET /api/rooms/{roomId}/state
→ 서버 상태 기준 화면 복구
```

이미 역할을 확인했거나 투표했거나 라이어 추측을 제출했다면 재접속 후
해당 행동을 다시 수행할 수 없다. 마피아 게임에서는 본인의 역할, 생존
여부, 역할 확인 여부, 밤 행동 제출 여부, 경찰 조사 기록, 마피아 동료 목록,
현재 투표 제출 여부를 함께 복구한다.

------------------------------------------------------------------------

# API Error Codes

## 33. 공통 오류 코드 목록

### Room / Player

``` text
ROOM_NOT_FOUND
ROOM_CLOSED
PLAYER_NOT_IN_ROOM
NICKNAME_ALREADY_EXISTS
INVALID_NICKNAME
INVALID_GENDER
NOT_ROOM_HOST
```

### GameSession

``` text
GAME_SESSION_NOT_FOUND
ACTIVE_GAME_SESSION_EXISTS
GAME_SESSION_NOT_READY
GAME_SESSION_ALREADY_FINISHED
UNSUPPORTED_GAME_TYPE
INVALID_GAME_CONFIG
PLAYER_NOT_IN_GAME
NOT_ENOUGH_PLAYERS
TOO_MANY_PLAYERS
```

### Liar Game

``` text
INVALID_CATEGORY
NO_AVAILABLE_KEYWORD
INVALID_GAME_PHASE
ROLE_ALREADY_CHECKED
VOTE_ALREADY_STARTED
INVALID_VOTE_ROUND
ALREADY_VOTED
CANNOT_VOTE_SELF
INVALID_VOTE_TARGET
NOT_LIAR
GUESS_ALREADY_SUBMITTED
INVALID_ANSWER
PLAYER_NOT_DISCONNECTED
PLAYER_NOT_EXCLUDABLE
```

### Blind Game

``` text
NO_AVAILABLE_KEYWORD
INVALID_GAME_PHASE
INVALID_ANSWER
GAME_SESSION_ALREADY_FINISHED
```

### Mafia Game

``` text
INVALID_GAME_PHASE
ROLE_ALREADY_CHECKED
PLAYER_DEAD
INVALID_NIGHT_ACTION
ACTION_ALREADY_SUBMITTED
INVALID_ACTION_TARGET
VOTE_ALREADY_STARTED
INVALID_VOTE_ROUND
ALREADY_VOTED
CANNOT_VOTE_SELF
INVALID_VOTE_TARGET
```

------------------------------------------------------------------------

# Frontend Integration Rules

## 34. Frontend 구현 원칙

Frontend는 다음 규칙을 따른다.

1.  최초 접속 시 `clientId`가 없으면 생성하여 localStorage 등에
    저장한다.
2.  Room 관련 REST 요청에는 `X-Client-Id`를 전달한다.
3.  Room 진입 또는 새로고침 시 반드시 `/state`를 기준으로 화면을
    결정한다.
4.  WebSocket 이벤트를 받으면 필요한 UI를 갱신하고 상태 불일치가
    의심되면 `/state`를 다시 조회한다.
5.  역할, 승패, 투표 결과를 Frontend에서 계산하지 않는다.
6.  현재 Player에게 허용된 행동만 UI에 표시한다.
7.  시민에게 라이어 전용 제시어 추측 UI를 표시하지 않는다.
8.  다른 Player의 투표 대상을 저장하거나 표시하지 않는다.
9.  전체 제시어 목록을 Frontend에 보관하지 않는다.
10. 블라인드 게임에서는 `opponentKeyword`만 진행 화면에 표시하고 본인의
    제시어를 추론하거나 별도 Client State에 저장하지 않는다.
11. 블라인드 게임의 정답 여부와 승자는 서버 응답과 `/state`로만 확정한다.
12. 마피아 게임의 밤 행동 종류와 후보는 `nightAction`을 그대로 사용한다.
13. 경찰 조사 기록과 마피아 동료 목록은 허용된 Player에게만 표시한다.
14. 마피아 투표 중 완료 Player 신원과 현재 득표수는 표시하지 않는다.
15. 마피아 사망, 역할 공개, 의심 수, 승패를 Frontend에서 계산하지 않는다.

------------------------------------------------------------------------

# MVP Endpoint Summary

## 35. REST Endpoint 목록

  ------------------------------------------------------------------------------------------------------------
  Method        Endpoint                                                                         설명
  ------------- -------------------------------------------------------------------------------- -------------
  `POST`        `/api/rooms`                                                                     Room 생성

  `GET`         `/api/rooms/by-code/{roomCode}`                                                  Room Code
                                                                                                 조회

  `POST`        `/api/rooms/{roomId}/players`                                                    Room 참가

  `DELETE`      `/api/rooms/{roomId}/players/me`                                                 Room 나가기

  `POST`        `/api/rooms/{roomId}/lobby`                                                      전체 참가자를
                                                                                                 대기 로비로 이동

  `GET`         `/api/rooms/{roomId}/state`                                                      현재 전체
                                                                                                 상태 복구

  `GET`         `/api/games`                                                                     지원 게임
                                                                                                 목록

  `GET`         `/api/games/liar/categories`                                                     라이어
                                                                                                 카테고리 목록

  `POST`        `/api/rooms/{roomId}/game-sessions`                                              GameSession
                                                                                                 생성

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/start`                        GameSession
                                                                                                 시작

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/cancel`                       GameSession
                                                                                                 취소

  `PUT`         `/api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/team`                     윷놀이 팀 선택/변경

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/throws`                   윷 던지기

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/move-selections`          이동권 선택

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/piece-selections`         말 선택

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/yut/path-selections`          경로 선택

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/role-check`              역할 확인

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/votes/start`             투표 시작

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/votes`                   투표 제출

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/players/{playerId}/exclude`   장기 미접속
                                                                                                 참가자 제외

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/guess`                   라이어 최종
                                                                                                 추측

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/blind/guesses`                블라인드 정답
                                                                                                 제출

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/role-check`             마피아 역할 확인

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/night-actions`          마피아 밤 행동

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/votes/start`            마피아 투표 시작

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/votes`                  마피아 투표 제출

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/mafia/advance`                마피아 결과 단계 진행
  ------------------------------------------------------------------------------------------------------------

------------------------------------------------------------------------

## 36. 확정된 MVP API 원칙

-   REST는 사용자 행동과 현재 상태 조회에 사용한다.
-   WebSocket은 Room/Game 상태 변경 알림에 사용한다.
-   상태 복구는 `/state`가 담당한다.
-   `clientId`는 익명 Client 식별용이며 인증 수단으로 간주하지 않는다.
-   Room은 여러 GameSession 동안 유지된다.
-   GameSession은 한 판마다 새로 생성한다.
-   라이어는 항상 정확히 1명이다.
-   카테고리와 제시어는 서버가 관리한다.
-   전체 제시어 목록을 Client에 제공하지 않는다.
-   역할 정보는 Player별로 필터링한다.
-   투표는 비밀투표다.
-   누가 누구에게 투표했는지는 투표 종료 후에도 공개하지 않는다.
-   후보별 최종 득표수만 공개한다.
-   동률이면 동률 후보만 대상으로 제한 없이 재투표한다.
-   단독 최다 득표자가 나올 때까지 재투표한다.
-   서버가 동률 후보를 랜덤 지목하지 않는다.
-   라이어 최종 추측은 실제 라이어에게만 허용한다.
-   블라인드 게임은 정확히 2명일 때만 시작한다.
-   블라인드 게임은 전체 활성 제시어 풀에서 서로 다른 제시어 2개를 선정한다.
-   블라인드 게임의 카테고리는 선택하거나 공개하지 않는다.
-   블라인드 게임은 최초 정답자 한 명만 원자적으로 승자로 확정한다.
-   모든 승패 판정은 서버가 수행한다.
