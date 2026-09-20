# NOOPI (누피) PIG Game Specification

## 1. 개요

피그(PIG)는 플레이어가 차례대로 주사위를 던져 현재 턴의 점수를 쌓고 원하는 시점에 멈춰 점수를 확정하는 실시간 개인전 게임이다. 한 턴에서 성공한 횟수가 늘어날수록 `1`이 나올 위험이 10%p씩 커지며, 같은 숫자가 한 턴에 여러 번 나올 수 있다. 게임 상태와 모든 판정의 최종 권한은 서버에 있다.

## 2. 참가 인원과 기본 설정

-   최소 인원: **2명**
-   최대 인원: **6명**
-   모드: 개인전
-   목표 점수: **50점**
-   별도 사용자 설정은 없다.

서버는 GameSession 시작 시 참가 인원이 2~6명인지 검증한다. 목표 점수, 주사위 면, 최초 `1` 발생 확률, 확률 증가 폭과 상한은 Client 설정으로 변경할 수 없다.

## 3. 핵심 상태

Player별 상태는 `totalScore`, `status`(`PLAYING` 또는 `FINISHED`), `rank`다. 진행 중인 Player의 `rank`는 `null`이다.

현재 턴 상태는 `currentPlayerId`, `turnScore`, `successfulRollCount`, `lastDiceValue`, `lastTurnOutcome`, `lostTurnScore`, `bustProbability`를 포함한다. `successfulRollCount`는 현재 턴에서 `2`~`6`이 나온 횟수다. `turnScore`는 `STOP` 전까지 `totalScore`에 반영하지 않는다.

## 4. 턴 시작과 순서

서버는 GameSession 시작 시 참가자 순서를 확정한다. 각 턴은 `turnScore = 0`, `successfulRollCount = 0`, `lastDiceValue = null`, `bustProbability = 0.2`로 시작한다. 턴이 끝나면 확정된 순서에서 다음 `PLAYING` Player에게 턴을 넘긴다. `FINISHED` Player는 턴 대상에서 제외한다.

## 5. 주사위 던지기

현재 Player만 주사위 던지기를 요청할 수 있다. 서버는 현재 `bustProbability`에 따라 먼저 `1` 발생 여부를 무작위로 확정한다. `1`이 아니면 `2`~`6` 중 하나를 동일한 확률로 확정하며, 이전에 나온 숫자도 다시 나올 수 있다. Client는 주사위 결과를 생성하거나 결과 확률을 이용해 판정하지 않는다. 동일 요청의 재전송이나 연타가 여러 번 처리되지 않도록 서버는 중복 요청을 방어한다. Client도 mutation 처리 중 행동 버튼을 비활성화한다.

## 6. `1`이 나온 경우

`1`이 나오면 서버는 직전 `turnScore`를 `lostTurnScore`로 기록하고 `turnScore = 0`으로 만든다. `totalScore`는 유지하며 현재 턴을 `BUSTED`로 종료한 뒤 다음 `PLAYING` Player의 턴을 초기 상태로 시작한다. Client는 `1`이 나온 사실과 잃은 점수를 명확하게 보여준다.

## 7. `2`~`6`이 나온 경우

서버는 `turnScore`에 결과를 더하고 `successfulRollCount`를 1 증가시킨다. 다음 던지기의 `bustProbability`는 성공 횟수에 따라 갱신한다. 성공 결과 이후 현재 Player에게 `ROLL`과 `STOP` 행동을 허용한다.

## 8. 숫자 반복 규칙

-   한 턴에서 나온 `2`~`6`은 제거하지 않는다.
-   같은 숫자는 같은 턴에 여러 번 나올 수 있다.
-   `1`이 아닌 결과가 확정될 때마다 `2`~`6`은 각각 동일한 확률을 가진다.
-   `STOP` 또는 `BUSTED`로 턴이 끝나면 `successfulRollCount`와 `bustProbability`를 다음 Player의 초기값으로 재설정한다.

## 9. `1` 발생 확률

서버가 반환하는 `bustProbability`는 현재 던지기에서 `1`이 나올 확률이다. 턴 시작 시 20%이며, 성공한 던지기마다 다음 던지기의 확률이 10%p 증가하고 최대 90%에서 유지된다.

| 현재 턴의 성공 횟수 | 다음 던지기의 `1` 발생 확률 |
| --- | --- |
| 0회 | 20% |
| 1회 | 30% |
| 2회 | 40% |
| 3회 | 50% |
| 4회 | 60% |
| 5회 | 70% |
| 6회 | 80% |
| 7회 이상 | 90% |

서버는 `min(0.2 + successfulRollCount * 0.1, 0.9)`로 현재 확률을 확정한다. API에서는 부동소수점 비율(`0`~`1`)로 제공한다. Client는 백분율로 표시할 수 있지만 확률을 자체 계산하거나 게임 판정에 사용하지 않는다.

## 10. 멈추기(STOP)

현재 Player는 첫 성공 결과 이후에만 `STOP`할 수 있다. 서버는 `totalScore += turnScore`를 처리하고 `turnScore = 0`으로 만든 뒤 턴을 종료한다. 확정된 점수는 이후 턴의 `BUSTED`로 사라지지 않는다.

## 11. FINISHED와 순위

-   `STOP`으로 `totalScore >= 50`이 되면 `FINISHED`가 된다. 정확히 50점을 맞출 필요는 없다.
-   50점 이상을 먼저 확정한 순서대로 순위를 부여하며 최종 점수로 다시 정렬하지 않는다.
-   `FINISHED` Player는 이후 턴에서 제외되고 남은 게임을 관전한다.

A가 먼저 51점을 확정하고 B가 나중에 58점을 확정하면 A가 1위, B가 2위다.

## 12. 게임 종료

`PLAYING` Player가 한 명만 남으면 서버는 그 Player를 마지막 순위로 자동 확정하고 GameSession을 `FINISHED`로 전환한다. 마지막 Player는 50점에 도달할 필요가 없다. 2명 게임에서 첫 번째 Player가 50점 이상을 확정하면 다른 Player가 자동으로 2위가 되며 즉시 종료한다.

## 13. Phase와 허용 행동

PIG phase는 `READY`, `PLAYING`, `FINISHED`다. 서버는 개인화된 상태의 `allowedActions`를 반환한다.

-   턴 시작: 현재 Player에게 `ROLL`
-   성공 결과 이후: 현재 Player에게 `ROLL`, `STOP`
-   다른 Player의 턴, `FINISHED` Player, 게임 종료: 빈 배열

Client는 Player 목록이나 점수를 이용해 행동 가능 여부를 임의 계산하지 않고 `allowedActions`를 기준으로 버튼을 렌더링한다.

## 14. 진행 UI

자기 턴에는 목표 점수, 자신의 총점과 턴 점수, 최근 주사위 결과, 현재 턴의 성공 횟수, 현재 `1` 발생 확률, 허용된 행동 버튼을 표시한다. 다른 Player의 턴에는 현재 Player의 닉네임, 총점, 턴 점수, 최근 결과, 위험도를 보여주고 행동 버튼은 제공하지 않는다. `FINISHED` Player에게는 확정 순위, 최종 점수, 관전 중임을 표시한다.

## 15. 최종 결과 UI

게임 종료 후 서버가 제공한 `rankings` 순서대로 최종 결과를 표시한다. 마지막 Player의 점수는 50점 미만일 수 있다. 윷놀이 결과 화면의 사용자 경험과 디자인 톤은 참고할 수 있지만 윷놀이 결과 컴포넌트, 상태, 타입, 전용 hook을 직접 사용하지 않는다. PIG 결과 화면은 PIG 모듈 내부에 별도로 구현한다.

## 16. 동기화와 재접속

주사위 결과, 점수, 성공 횟수, `1` 발생 확률, 턴, 상태, 순위, 게임 종료는 서버가 확정한다. WebSocket 이벤트는 변경 알림이며 영구 상태 저장소가 아니다. Client는 관련 이벤트 수신 또는 재접속 후 개인화된 `GET /state`를 다시 조회한다. localStorage로 PIG 상태를 복구하지 않는다.

## 17. 중도 이탈

연결 끊김은 즉시 Room 탈퇴로 간주하지 않는다. PIG 진행 중 참가자가 명시적으로 이탈하거나 영구 제외되면 해당 GameSession을 `CANCELLED`로 전환한다. Client가 남은 Player만으로 순위나 턴을 재계산하지 않는다.

## 18. 모듈 격리

PIG 코드는 `features/games/pig/` 아래에 화면, 컴포넌트, 타입, hook, API, 상태 표현, 결과 화면을 독립적으로 둔다. 공통 UI와 공통 API/Realtime Adapter는 사용할 수 있다. 다른 게임의 전용 로직, 상태, 타입, hook, 컴포넌트를 가져오지 않는다.

## 19. 서버 책임

서버는 참가 인원 2~6명, 턴 순서, 주사위 결과, 성공 횟수, 점수, 확률, 행동 허용 여부, 중복 행동 방지, FINISHED 전환, 순위, 마지막 Player 자동 순위와 게임 종료를 결정하고 검증한다. Frontend에서 이 값을 자체 계산하여 확정하지 않는다.
