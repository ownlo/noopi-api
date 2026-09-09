# NOOPI (누피) API Specification

## 1. 목적

이 문서는 모바일 웹 Frontend와 Backend 사이의 REST API 및 WebSocket
이벤트 계약을 정의한다.

공통 서비스 규칙은 `SERVICE_SPEC.md`, GameSession 생명주기는
`GAME_SESSION_SPEC.md`, 실시간 원칙은 `REALTIME_SPEC.md`, 라이어 게임
규칙은 `games/LIAR_GAME_SPEC.md`를 따른다.

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

Room 참가용 코드는 별도의 `roomCode` 문자열을 사용한다.

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
  "roomCode": "AB12CD"
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
    "roomCode": "AB12CD",
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
  "roomCode": "AB12CD",
  "status": "WAITING",
  "playerCount": 4,
  "joinable": true
}
```

이 API는 역할, 제시어, 투표 정보 등 게임의 비공개 정보를 반환하지
않는다.

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

방장이 나가면 Room 정책에 따라 새로운 방장을 선정한다.

현재 GameSession 참가자의 이탈 처리는 해당 게임 규칙을 따른다.

주요 오류:

``` text
ROOM_NOT_FOUND
PLAYER_NOT_IN_ROOM
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
    "roomCode": "AB12CD",
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
      "participantCount": 4
    }
  }
}
```

진행 중인 GameSession이 없으면:

``` json
{
  "room": {
    "roomId": 100,
    "roomCode": "AB12CD",
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
  "firstSpeakerPlayerId": 14
}
```

다른 Player의 역할은 게임 종료 전 절대 반환하지 않는다.

다른 Player가 누구에게 투표했는지도 투표 전·진행 중·종료 후 모두
반환하지 않는다.

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

Response `201 Created`:

``` json
{
  "gameSessionId": 55,
  "gameType": "LIAR",
  "status": "READY"
}
```

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

모든 참가자가 역할 확인을 완료하면 서버가 첫 발언자를 랜덤으로 선정하고
`DISCUSSION` 단계로 전환한다.

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

# Finished State

## 22. 라이어 게임 최종 결과

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

------------------------------------------------------------------------

# WebSocket API

## 23. 연결

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

## 24. 이벤트 공통 Envelope

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

## 25. Room 이벤트

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

------------------------------------------------------------------------

## 26. GameSession 이벤트

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

## 27. 라이어 게임 이벤트

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
    "firstSpeakerPlayerId": 14
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

------------------------------------------------------------------------

# Security / Information Exposure

## 28. 절대 공개하면 안 되는 정보

게임 종료 전 다음 정보를 권한 없는 Client에게 전달하지 않는다.

-   다른 Player의 실제 역할
-   라이어에게 현재 제시어
-   전체 역할 배정 정보
-   전체 제시어 목록
-   다른 Player의 투표 대상
-   서버 내부 정답 데이터
-   다른 Room의 상태

UI에서 숨기는 것으로 보안을 대체하지 않는다.

Backend DTO 생성 단계부터 현재 Player에게 허용된 정보만 포함한다.

------------------------------------------------------------------------

## 29. 행동 권한 검증

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

## 30. 중복 요청 처리

네트워크 재시도 또는 더블 클릭으로 같은 요청이 여러 번 전달될 수 있다.

다음 행동은 서버에서 중복 실행을 방지한다.

-   GameSession 시작
-   역할 확인
-   투표 제출
-   라이어 추측 제출
-   GameSession 취소

이미 완료된 행동을 다시 요청하면 상태에 따라 `409 Conflict`와 고정 오류
코드를 반환한다.

특히 투표는 다음 논리 키로 한 번만 허용한다.

``` text
gameSessionId + voteRound + voterPlayerId
```

------------------------------------------------------------------------

# Reconnect

## 31. 재접속 처리

WebSocket 연결 종료 자체는 Room 탈퇴가 아니다.

동일 브라우저가 동일 `clientId`로 돌아오면 기존 Player를 복구한다.

재접속 흐름:

``` text
WebSocket 재연결
→ GET /api/rooms/{roomId}/state
→ 서버 상태 기준 화면 복구
```

이미 역할을 확인했거나 투표했거나 라이어 추측을 제출했다면 재접속 후
해당 행동을 다시 수행할 수 없다.

------------------------------------------------------------------------

# API Error Codes

## 32. 공통 오류 코드 목록

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

------------------------------------------------------------------------

# Frontend Integration Rules

## 33. Frontend 구현 원칙

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

------------------------------------------------------------------------

# MVP Endpoint Summary

## 34. REST Endpoint 목록

  ------------------------------------------------------------------------------------------------------------
  Method        Endpoint                                                                         설명
  ------------- -------------------------------------------------------------------------------- -------------
  `POST`        `/api/rooms`                                                                     Room 생성

  `GET`         `/api/rooms/by-code/{roomCode}`                                                  Room Code
                                                                                                 조회

  `POST`        `/api/rooms/{roomId}/players`                                                    Room 참가

  `DELETE`      `/api/rooms/{roomId}/players/me`                                                 Room 나가기

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

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/role-check`              역할 확인

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/votes/start`             투표 시작

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/votes`                   투표 제출

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/players/{playerId}/exclude`   장기 미접속
                                                                                                 참가자 제외

  `POST`        `/api/rooms/{roomId}/game-sessions/{gameSessionId}/liar/guess`                   라이어 최종
                                                                                                 추측
  ------------------------------------------------------------------------------------------------------------

------------------------------------------------------------------------

## 35. 확정된 MVP API 원칙

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
-   모든 승패 판정은 서버가 수행한다.
