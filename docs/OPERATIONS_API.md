# NOOPI Backend 운영 API

이 문서는 기존 Backend 전용 운영 통계 계약을 보존한다. 2026-09-15에
공통 문서를 Frontend와 동일하게 맞추면서 기존 공통 API_SPEC의 37절을
이곳으로 이동했다. Endpoint 및 통계 동작은 변경하지 않는다.
현재 `MetricsQuery`의 응답은 LIAR/BLIND를 포함한다. MAFIA/YUT의 통계 응답
확장은 별도 구현 및 계약 검토 대상이며 게임 지원과 구분한다.

## 운영 일별 통계 조회

`GET /api/metrics/daily?from=2026-09-01&to=2026-09-13`

Swagger UI에서 직접 조회하며 별도 화면은 제공하지 않는다. `X-Client-Id`는 필요 없다.
조회는 설정된 `METRICS_API_KEY`와 일치하는 `X-Metrics-Key` 헤더가 필요하다.
키가 비어 있거나 불일치하면 `403 METRICS_ACCESS_DENIED`를 반환한다.
`from`, `to`는 필수 ISO 날짜이며 양 끝을 포함해 최대 366일이다.
누락/형식 오류/역순/범위 초과는 `400 BAD_REQUEST`다.

응답 `200 OK`: `timezone`은 `Asia/Seoul`, `days`는 날짜 오름차순 배열이다.
각 날짜는 `date`, `roomsCreated`, `roomsClosed`, `totalRoomDurationMinutes`,
`averageRoomDurationMinutes`, `maxRoomDurationMinutes`, `games`를 갖는다.
`games`는 LIAR, BLIND 각각의 `gameType`, `started`, `finished`, `cancelled`,
`participantCount`를 포함한다. 데이터 없는 날짜/게임도 0을 반환한다.

- 방 생성은 생성일, 종료 수와 지속시간은 종료일에 귀속한다.
- 지속시간은 생성부터 방장 나가기 또는 TTL 삭제 시점까지이며 대기/미접속 시간을 포함한다.
- 게임 횟수는 실제 PLAYING 전환일 기준이다. READY 취소는 집계하지 않는다.
- 완료/취소는 각각 발생일 기준이다. 시작된 게임이 방 삭제로 소멸하면 통계상 취소다.
- 참가 인원은 시작 시 snapshot 인원 합계이며 고유 사용자 수가 아니다.
- 평균 지속시간의 분모는 종료된 방 수이며, 분 단위 소수 둘째 자리까지 반올림한다.
- 서버가 자동 수집하고 기본 10초마다 DB에 저장한다. 조회는 저장된 데이터만 읽는다.
- 통계에는 개인 식별자, 닉네임, 제시어, 역할, 투표, 정답 내용을 저장하지 않는다.
- 수집 도입 전 과거 데이터는 복원하지 않는다. 정상 종료 시 마지막 저장을 시도한다.
  프로세스 강제 종료 시 미저장 집계와 진행 중 방/게임의 종료 기록은 유실될 수 있다.
  DB 장애 중 메모리 집계를 유지해 다음 저장 때 재시도한다.
