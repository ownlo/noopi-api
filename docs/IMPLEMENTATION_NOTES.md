# MVP 구현 결정과 SPEC 정합성

## 블라인드 게임 명세 추가

공통 `BLIND_GAME_SPEC.md`와 API/Realtime 계약에 `BLIND`를 추가했다.

Backend는 기존 Room, GameSession, `/state`, WebSocket 갱신 구조를 재사용하고
제시어는 카테고리 없는 블라인드 전용 Repository에서 조회한다. 블라인드 전용 Runtime은 Player별 제시어
배정, `GUESSING` phase와 승자만 관리하며 질문·턴·타이머 상태는 만들지
않는다.

동시 정답은 Room 단위 동시성 경계에서 한 명만 승자로 확정한다. 명시적
퇴장 또는 장기 미접속 시 승패/취소 정책은 아직 확정되지 않았으므로 임의
구현하지 않는다.

## 확정 규칙 정정

현재 SPEC: LIAR_GAME_SPEC 18절/29절 일부 문구가 개인 투표 공개·최대 재투표 2회·랜덤 지목을 기술했다.

충돌/누락: AGENTS.md 및 최신 API/Realtime 계약과 충돌했다.

제안 및 반영: 사용자 지시대로 개별 투표는 항상 비공개, 무제한 동률 후보 재투표로 정정했다.
REALTIME_SPEC의 ‘실제 투표 내용’ 문구도 후보별 최종 득표수로 명확히 했다.

영향 범위: 라이어 규칙, 상태 projection, 모든 투표 이벤트, 100회 연속 동률 테스트.

## 종료 결과 제시어

현재 SPEC: 라이어 keyword는 항상 null이라는 일반 규칙과 API_SPEC 22절 전체 참가자에게 최종 keyword 공개가 충돌했다.

충돌/누락: FINISHED의 공개 범위가 상충했다.

제안 및 사용자 확정: 게임 진행 중 비공개, 종료 결과에서는 라이어에게도 공개한다.

영향 범위: FINISHED의 result.keyword, AGENTS.md 보완, 공통 게임 문서, 최종 결과 테스트.

## 제외된 Player가 받은 표

현재 SPEC: 제외된 Player가 제출한 표를 무효화하고 필요한 투표 수를 다시 계산한다.

충돌/누락: 다른 Player가 제외 대상에게 제출한 표와 후보 자격 처리가 빠져 있었다.

사용자 확정: 이미 받은 표는 유지하고 해당 라운드 후보 자격을 유지한다.

영향 범위: 제외 후 집계, eligibleCandidates, 투표 완료 여부. 이후 동률은 기존 동률 후보 규칙을 따른다.

## 명시적 Room 종료 API

현재 SPEC: SERVICE_SPEC에 방장의 Room 종료 권한이 있으나 API_SPEC 34절에 종료 endpoint가 없다.

충돌/누락: method/path, 응답, 오류 및 이벤트 계약이 없다.

제안: 별도 계약 확정 후 추가한다. 임의 API를 추가하지 않았다.
현재는 마지막 Player 탈퇴 및 설정된 TTL 만료 시 Room과 연결 참조를 정리한다.

영향 범위: 방장 전용 Room 종료 버튼을 연동하려면 계약 확정이 필요하다.

## 제외 전용 실시간 알림

현재 SPEC: exclude API는 204를 반환하며 제외 이벤트는 API/REALTIME_SPEC의 이벤트 목록에 없다.

충돌/누락: 제외만 발생하고 아직 집계되지 않을 때 다른 Client에 즉시 알릴 이벤트가 없다.

제안: 새 이벤트가 필요하면 이름/payload를 계약에 추가한다. 임의 이벤트는 추가하지 않았다.
현재는 요청 완료 후 /state로 복구하며 집계 또는 취소 시 정의된 게임 이벤트를 전달한다.

영향 범위: 제외 이후 다른 Client의 즉시 진행 인원 갱신. 재접속 및 상태 재조회는 정상 복구한다.

## 운영 구현 세부사항

- 명시적 방장 탈퇴: 남은 참가 순서의 첫 Player에게 승계.
- 장기 방장 미접속: 연결된 Player 중 참가 순서의 첫 Player에게 승계.
- 운영 기본값: disconnect grace 2분, Room TTL 12시간, cleanup 60초, 최근 제시어 10개.
- 값은 환경 변수로 변경한다. 별도의 gameplay 제한이나 재투표 제한이 아니다.
- VOTE_RESULT와 LIAR_REVEAL은 표준 이벤트를 발행하는 자동 전환 단계다.
- 본인 투표 금지로 더 진행할 수 없는 극단적 중도 이탈 상황에 대한 새 승패 규칙은 추가하지 않는다.
  방장은 기존 cancel API로 취소할 수 있다.
- 실제 라이어 검거 후 `LIAR_GUESS` 상태에는 공개된 `liarPlayer`를 모든 참가자에게 제공한다.
  시민 대기 화면은 이 값을 사용하며, 제시어와 투표 대상의 공개 범위는 바뀌지 않는다.
- `READY` 상태에는 GameSession 생성 시 확정한 `categoryCode`, `categoryName`을 제공한다.
  방장이 아닌 참가자와 새로고침한 사용자도 준비 화면을 `/state`만으로 복구한다.
