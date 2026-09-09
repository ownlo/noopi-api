# NOOPI (누피) GameSession Specification

## 1. 목적

GameSession은 Room에서 진행되는 **게임 한 판**을 표현하는 공통 개념이다.

Room과 GameSession을 분리하여 하나의 Room에서 여러 게임을 연속해서
진행할 수 있도록 한다.

## 2. 기본 관계

-   하나의 Room은 여러 GameSession을 가질 수 있다.
-   GameSession은 정확히 하나의 Room에 속한다.
-   하나의 GameSession은 하나의 게임 종류를 가진다.
-   게임을 다시 플레이하면 기존 GameSession을 초기화하지 않고 새로운
    GameSession을 생성한다.

## 3. GameSession 상태

-   `READY`: 준비되었지만 실제 게임이 시작되지 않은 상태
-   `PLAYING`: 게임 진행 중
-   `FINISHED`: 정상 종료
-   `CANCELLED`: 정상적인 종료 조건에 도달하지 못하고 취소

게임 내부의 세부 단계는 각 게임에서 별도로 관리한다.

## 4. 게임 선택

진행 중인 GameSession이 없다면 방장은 플레이할 게임을 선택할 수 있다.

게임별 설정이 필요한 경우 게임 시작 전에 설정한다.

## 5. 게임 시작

서버는 최소한 다음을 검증한다.

-   요청자가 해당 Room의 방장인지
-   Room이 게임을 시작할 수 있는 상태인지
-   다른 GameSession이 진행 중이지 않은지
-   현재 참가자 구성이 해당 게임의 시작 조건을 만족하는지
-   게임별 필수 설정이 완료됐는지

## 6. GameSession 참가자

게임 시작 시점에 정상적으로 참가 중인 Player를 기준으로 GameSession
참가자를 확정한다.

게임 진행 중 새롭게 Room에 들어온 Player는 진행 중인 GameSession에 자동
참가하지 않으며 다음 GameSession부터 참가할 수 있다.

## 7. 게임 진행

GameSession은 전체 게임의 생명주기만 관리한다.

역할, 문제/제시어, 투표, 순서, 게임별 단계, 승패 조건, 게임별 행동은 각
게임이 관리한다.

## 8. 게임 종료

게임별 종료 조건이 충족되면 GameSession을 `FINISHED`로 변경한다.

GameSession이 종료되어도 Room과 Player는 유지한다.

## 9. 게임 취소

정상적인 진행이 불가능한 경우 `CANCELLED`로 종료할 수 있다.

취소된 GameSession을 재사용하지 않는다. 다시 시작하려면 새로운
GameSession을 생성한다.

## 10. 같은 게임 다시하기

새로운 GameSession을 생성한다.

이전 GameSession의 역할, 결과, 투표 및 기타 게임 상태는 새로운
GameSession에 영향을 주지 않는다.

## 11. 다른 게임 선택

게임 종료 후 Room은 유지된다.

방장이 다른 게임을 선택하면 해당 게임 종류를 가진 새로운 GameSession을
생성한다. 기존 Player와 닉네임은 유지한다.

## 12. 중도 참가

GameSession이 이미 진행 중이라면 새롭게 Room에 참가한 Player는 현재
게임에 중간 편입하지 않는다.

다음 GameSession부터 정상 참가한다.

## 13. 중도 이탈

게임 중 Player의 연결이 종료됐다는 이유만으로 GameSession 참가자에서
즉시 제거하지 않는다.

일시적인 연결 종료와 실제 이탈을 구분하며 구체적인 정책은 각 게임
규칙에서 정의한다.

## 14. 상태 복구

GameSession 상태는 서버를 기준으로 한다.

사용자가 새로고침하거나 재접속하면 현재 GameSession과 게임의 현재 상태를
서버에서 다시 조회하여 복구할 수 있어야 한다.

클라이언트가 이전 실시간 이벤트를 모두 보유하고 있다는 것을 전제로 하지
않는다.

## 15. 공통 설계 원칙

GameSession은 특정 게임의 세부 규칙을 알지 않는다.

누가 라이어인지, 어떤 제시어인지, 투표 내용, 특정 게임의 승리 판정 규칙
등은 각 게임 도메인의 책임이다.
