# NOOPI (누피) Realtime Specification

## 1. 목적

여러 Player가 동일한 Room과 GameSession의 상태 변화를 실시간으로 공유할
수 있도록 한다.

## 2. 기본 원칙

게임 상태의 최종 기준은 서버다.

클라이언트가 자체적으로 게임 진행 상태나 결과를 결정하지 않는다.

실시간 통신은 서버의 상태 변경을 빠르게 전달하기 위한 수단이다.

## 3. 실시간 연결

Player가 Room에 참가하면 해당 Room의 실시간 연결을 생성한다.

Room 및 GameSession의 상태 변경을 참가자에게 전달한다.

## 4. 서버 상태 우선

실시간 이벤트 자체를 영구적인 상태로 간주하지 않는다.

클라이언트가 이벤트를 놓치거나 연결이 끊겨도 서버의 현재 상태를 다시
조회하여 정상적으로 복구할 수 있어야 한다.

## 5. 연결 종료

실시간 연결이 종료됐다는 이유만으로 Player가 Room을 나간 것으로 판단하지
않는다.

Player를 연결 종료 상태로 변경하고 일정 시간 동안 재접속을 허용한다.

## 6. 재접속

동일 Client가 기존 Room에 다시 접속하면 기존 Player를 복구한다.

새로운 Player를 생성하지 않는다.

재접속 후 서버의 현재 상태를 기준으로 화면과 게임 상태를 복구한다.

## 7. 상태 복구

과거 실시간 이벤트를 처음부터 재생하는 방식에 의존하지 않는다.

최소한 다음 정보를 복구할 수 있어야 한다.

-   Room 상태
-   Player 상태
-   현재 GameSession
-   현재 게임 단계
-   해당 Player에게 공개되어야 하는 개인 정보
-   이미 수행한 행동

## 8. 공통 이벤트

-   `PLAYER_JOINED`
-   `PLAYER_DISCONNECTED`
-   `PLAYER_RECONNECTED`
-   `PLAYER_LEFT`
-   `HOST_CHANGED`
-   `ROOM_CLOSED`
-   `ROOM_RETURNED_TO_LOBBY`
-   `GAME_SESSION_CREATED`
-   `GAME_STARTED`
-   `GAME_FINISHED`
-   `GAME_CANCELLED`

구체적인 이벤트 payload는 `API_SPEC.md`에서 정의한다.

## 9. 라이어 게임 대표 이벤트

-   `ROLE_CHECKED`
-   `DISCUSSION_STARTED`
-   `VOTE_STARTED`
-   `PLAYER_VOTED`
-   `VOTE_COMPLETED`
-   `VOTE_RESULT`
-   `REVOTE_STARTED`
-   `LIAR_REVEALED`
-   `LIAR_GUESS_STARTED`
-   `LIAR_GUESS_SUBMITTED`

구체적인 payload는 `API_SPEC.md`에서 정의한다.

## 10. 개인 정보 이벤트

모든 정보를 Room 전체에 broadcast해서는 안 된다.

자신의 역할, 시민의 제시어, 라이어 전용 행동 정보 등 특정 Player만 볼 수
있는 정보는 해당 Player에게만 전달한다.

블라인드 게임에서는 게임 종료 전 현재 Player 본인의 제시어를 어떤
WebSocket 이벤트에도 포함하지 않는다. 상대방 제시어는 개인화된
`/state` 응답으로 전달하며 Room 전체 broadcast payload에 포함하지 않는다.

블라인드 게임은 별도 진행 이벤트를 추가하지 않고
`GAME_SESSION_CREATED`, `GAME_STARTED`, `GAME_FINISHED`,
`GAME_CANCELLED`를 재사용한다. `GAME_STARTED`와 `GAME_FINISHED`를 수신한
Client는 `/state`를 다시 조회한다. 오답 제출은 제출자에게 HTTP 응답으로만
알리며 상대방에게 broadcast하지 않아도 된다.

마피아 게임의 역할, 마피아 동료, 경찰 조사 결과, 시민별 의심 대상과
Player별 개인 의심 수는 개인화 정보로 취급한다. Room 전체 broadcast에는
phase 전환과 행동 완료 인원처럼 모든 참가자에게 공개 가능한 정보만
포함하고, 각 Client는 상태 변경 이벤트를 수신한 뒤 개인화된 `/state`로
자신에게 허용된 최신 정보를 조회한다. 처형 또는 밤 사망으로 공개가 확정된
Player의 역할과 게임 종료 후 전체 역할은 공개 정보로 전환할 수 있다.

마피아 게임 대표 이벤트는 다음과 같다.

-   `MAFIA_ROLE_CHECKED`
-   `MAFIA_NIGHT_ACTION_SUBMITTED`
-   `MAFIA_PHASE_CHANGED`
-   `MAFIA_VOTE_STARTED`
-   `MAFIA_PLAYER_VOTED`
-   `MAFIA_VOTE_RESULT`
-   `MAFIA_REVOTE_STARTED`
-   `MAFIA_PLAYER_DIED`

윷놀이 대표 이벤트는 다음과 같다.

-   `YUT_TEAM_CHANGED`
-   `YUT_TURN_CHANGED`
-   `YUT_THROW_RESOLVED`
-   `YUT_PIECE_MOVED`
-   `PIG_ROLL_RESOLVED`
-   `PIG_TURN_CHANGED`
-   `PIG_PLAYER_FINISHED`

피그 이벤트는 서버가 확정한 공개 결과와 상태 변경만 알린다. Client는 이벤트 payload만으로 주사위 후보, 점수, 다음 턴, 순위 또는 종료를 계산하지 않고 개인화된 `/state`를 다시 조회한다. 전체 종료는 공통 `GAME_FINISHED`를 사용한다.

누피 콱! 대표 이벤트는 다음과 같다.

-   `TOOTH_SELECTED`

이 이벤트에는 서버가 확정한 선택 이빨, 선택 Player, `SAFE` 또는 `BOMB`
결과와 다음 턴 Player만 포함한다. 숨겨진 `bombToothId`는 `BOMB`이 실제로
확정되기 전에는 어떤 broadcast payload에도 포함하지 않는다. Client는
이벤트 수신 후 개인화된 `/state`를 다시 조회하며, 이벤트 payload만으로
남은 이빨 수나 다음 행동 권한을 계산하지 않는다. 전체 종료는 공통
`GAME_FINISHED`를 사용한다.

이 이벤트에는 서버가 확정한 공개 결과만 포함한다. 행동 가능 여부와 이동
가능한 말·경로 후보는 개인화된 `/state`로 조회한다. Client는 이벤트
payload만으로 말 위치, 업기, 잡기, 완주, 개인전 순위 또는 팀전 승자를
계산하지 않는다.
`YUT_THROW_RESOLVED`의 `NAK`는 해당 던지기에 이동권이 생성되지 않은 공개 결과다.
Client는 `/state`의 `lastThrow`와 기존 이동권 또는 새 턴을 다시 조회해 낙 연출과
화면을 동기화한다. 기존 이동권이 정확히 하나라면 해당 이동권을 자동 선택하고
말 선택 단계로 진행한다.

밤 행동 이벤트에는 행동 타입, 대상, 경찰 조사 결과, 치료 성공 여부를
broadcast하지 않는다. 상태 변경을 알리는 데 필요한 Player와 완료 인원만
포함하고, 개인 결과는 제출 응답과 개인화된 `/state`로 제공한다.

## 11. 투표 이벤트

투표 진행 중에는 투표 대상 정보를 다른 참가자에게 전달하지 않는다.

`PLAYER_VOTED`는 해당 Player가 투표를 완료했다는 사실만 나타낸다.

실제 투표 내용은 투표 종료 후 결과 공개 단계에서 전달한다.

## 12. 중복 요청

네트워크 재시도나 중복 입력으로 동일 요청이 여러 번 전달될 수 있다.

게임 시작, 역할 확인, 투표, 라이어 최종 추측, 블라인드 정답 제출, 누피 콱!
이빨 선택 등 중요한 행동은 서버에서
중복 실행을 방지해야 한다.

블라인드 게임은 정답 시도 횟수에 제한이 없으므로 서로 다른 제출 요청을
중복으로 간주하지 않는다. 단, 최초 정답으로 승자가 확정되는 처리는
원자적으로 수행하여 한 명의 승자만 생성해야 한다.

클라이언트 버튼 비활성화만으로 중복 요청을 방지했다고 간주하지 않는다.

## 13. 이벤트 유실 및 순서

클라이언트는 모든 이벤트가 반드시 전달된다고 가정하지 않는다.

이벤트 순서만으로 전체 게임 상태를 재구성하지 않는다.

상태가 의심되거나 불일치하면 서버의 현재 상태를 다시 조회한다.

## 14. 방장 연결 종료

방장의 연결이 종료되어도 방을 자동 종료하거나 방장 권한을 이전하지
않는다. 방장은 `DISCONNECTED` 상태로 유지되며 기존 참가자는 제한 없이
재접속을 기다릴 수 있다. 이 시간에도 Room이 참가 가능한 상태라면 새로운
참가자의 Room 조회와 참가를 허용한다.

방장이 화면의 나가기 행동으로 Room을 명시적으로 떠나면 서버는
`ROOM_CLOSED` 이벤트를 `HOST_LEFT` 사유와 함께 전송하고 Room을 삭제한다.
남아 있던 Client는 홈 화면으로 이동한다.

## 15. 게임 중 연결 종료

Realtime 영역은 연결 종료 감지, Player 연결 상태 변경, 재접속 감지,
Player 상태 복구, 관련 이벤트 전달을 담당한다.

게임 취소 또는 Player 제외 여부는 각 게임 규칙에서 판단한다.

## 16. 기본 처리 흐름

`사용자 행동 → 서버 요청 → 서버 권한/상태 검증 → 서버 상태 변경 → 실시간 이벤트 전달 → 각 Client 갱신`

Client끼리 직접 게임 상태를 공유하거나 결정하지 않는다.

## 17. 권한 검증

화면에 버튼이 보이지 않는다는 사실을 권한 검증으로 사용하지 않는다.

서버는 요청마다 Room 참가 여부, GameSession 참가 여부, 방장 권한, 현재
게임 단계, 기존 행동 여부, 역할별 권한 등을 검증한다.

## 18. 최종 원칙

**서버의 현재 상태가 Source of Truth다.**

실시간 이벤트는 서버 상태 변경을 빠르게 알려주기 위한 수단이다.

새로고침하거나 연결이 끊어졌다가 돌아와도 서버 상태를 기준으로 현재
게임을 정상적으로 복구할 수 있어야 한다.

## 투표 정보 공개 원칙

개별 플레이어의 투표 대상은 투표 진행 중뿐 아니라 투표 종료 후에도 다른
클라이언트에 공개하지 않는다.

실시간 이벤트와 상태 조회 응답에는 다른 플레이어의 투표 대상을 포함하지
않는다. 게임 결과에는 후보별 최종 득표수와 최종 지목 결과만 제공한다.
