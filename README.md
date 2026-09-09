# NOOPI Backend MVP

Java 21 · Spring Boot 3.5 · Spring MVC/WebSocket · JPA · MySQL 8 · Flyway.

## 실행

```sh
docker compose up -d mysql
./gradlew bootRun
```

기본 주소는 `http://localhost:8080`이다. 로컬 MySQL 접속값은 `compose.yaml`과
`application.yml`에 있다. 외부 환경에서는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 설정한다.

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

```sh
./gradlew test
./gradlew build
java -Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -jar build/libs/noopi-api-0.0.1-SNAPSHOT.jar
```

`test`는 도메인 규칙·동시성 테스트, H2(MySQL 호환 모드) HTTP/WebSocket 통합 테스트를 실행한다.
Docker가 있으면 Testcontainers의 실제 `mysql:8.4`에서도 같은 계약 테스트와 콘텐츠 검증을 실행한다.
Docker가 없으면 MySQL 테스트만 skip된다. MySQL 검증 여부는 테스트 리포트에서 확인한다.
리포트: `build/reports/tests/test/index.html`.

## REST 사용

Room 요청에는 `X-Client-Id`가 필요하다. 익명 브라우저 식별값이며 인증 토큰이 아니다.

```sh
curl -X POST http://localhost:8080/api/rooms \
  -H 'Content-Type: application/json' -H 'X-Client-Id: local-browser-1' \
  -d '{"nickname":"누피","gender":"MALE"}'
```

반환된 `roomId`로 다른 브라우저의 Player를 참가시킨 뒤 GameSession 생성 → 시작 → 역할 확인 →
방장 투표 시작 → 투표 → 필요시 자동 재투표 → 라이어 추측 순으로 진행한다.
전체 계약은 `docs/common/API_SPEC.md`를 따른다. 15개 MVP 엔드포인트를 구현했다.

## WebSocket

브라우저에서 Room 참가 후 연결한다. query parameter는 transport 식별을 위한 구현 방식이다.

```js
const socket = new WebSocket(
  `ws://localhost:8080/ws?roomId=${roomId}&clientId=${encodeURIComponent(clientId)}`
);
socket.onopen = () => fetch(`/api/rooms/${roomId}/state`, {
  headers: { 'X-Client-Id': clientId }
});
socket.onmessage = ({ data }) => {
  const event = JSON.parse(data);
  // 이벤트를 갱신 신호로 사용하고 /state로 최신 상태를 조회한다.
};
```

서버는 handshake와 실제 연결 등록 시 Room 참가 여부를 다시 검증한다.
클라이언트가 Room 구독을 변경하거나 WebSocket으로 게임 행동을 제출하는 프로토콜은 없다.
REST로 행동을 제출하며 역할/제시어는 개인별 `/state`로 조회한다.
같은 Player의 여러 탭은 별도 연결로 관리하며 마지막 연결이 닫힐 때만 DISCONNECTED가 된다.
명시적 탈퇴는 해당 Player의 모든 연결을 닫는다.

## 구조와 원자성

- `room`: 게임 규칙을 모르는 Room/Player Runtime, RoomStore.
- `game/session`: 한 판의 상태와 시작 시 참가자 snapshot.
- `game/liar`: 라이어 규칙, 비공개 runtime, 개인별 allowlist projection.
- `application`: Room 잠금 안에서 REST 행동을 조정, TTL 및 장기 disconnect 처리.
- `content`: 콘텐츠 3개 JPA Entity, Repository, 활성 콘텐츠 선택.
- `realtime`: Room 검증, 연결 관리, 계약에 정의된 공개 이벤트 전달.
- `api`: REST Controller, 응답 DTO, 고정 도메인 오류 처리.

각 Room 객체의 monitor 안에서 검증·상태 변경·집계·이벤트 발행·상태 projection을 처리한다.
전역 게임 잠금은 없다. Runtime은 JSON 직접 직렬화 대상이 아니며 Entity도 아니다.
투표 관계는 라운드 집계 또는 취소 시 폐기하고 DB/응답/이벤트/로그에 남기지 않는다.
`VOTE_RESULT`, `LIAR_REVEAL`은 자동 전환 단계다. 이벤트로 알린 후 즉시
REVOTING/LIAR_GUESS/FINISHED로 이동하며 별도 타이머나 진행 버튼을 추가하지 않는다.

## 운영 설정

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| `DISCONNECT_GRACE` | `PT2M` | 제외 가능 시간, 장기 라이어 이탈/방장 승계 기준 |
| `ROOM_TTL` | `PT12H` | 마지막 Room 활동 이후 Runtime 만료 |
| `CLEANUP_INTERVAL_MS` | `60000` | 정리 주기 |
| `RECENT_KEYWORD_COUNT` | `10` | Room별 최근 제시어 회피 개수 |
| `ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | REST CORS 및 WebSocket 허용 Origin |

명시적 방장 탈퇴 시 남아 있는 참가 순서상 첫 Player가 승계한다.
장기 방장 미접속 시 연결된 Player 중 참가 순서상 첫 Player가 승계한다.
장기 라이어 미접속은 정리 주기에서 취소한다. 재접속이 잠금을 먼저 획득하면 취소되지 않는다.
최근 제시어를 모두 소진하면 활성 후보를 다시 허용한다.
단일 인스턴스용이며 서버 재시작 시 Room과 진행 중 게임이 사라진다.

## 스키마와 확정 규칙

Flyway V1은 `liar_category`, `liar_keyword`, `liar_keyword_accepted_answer` 및 제약·인덱스를 생성한다.
V2는 실제 카테고리 3개, 제시어 15개, 허용 별칭 5개를 넣는다. RANDOM은 DB에 저장하지 않는다.
Hibernate는 `validate`만 수행한다.

- 라이어는 정확히 1명, 자기 투표 금지, 라운드당 한 표, 무제한 동률 재투표.
- 다른 Player의 투표 대상은 모든 단계에서 비공개다.
- 사용자 확정: 제시어는 게임 종료 결과에서 라이어에게도 공개한다.
- 사용자 확정: 제외된 Player의 제출 표만 무효화하고 이미 받은 표와 해당 라운드 후보 자격은 유지한다.
  이후 동률은 공통 규칙대로 최다 득표 동률 후보를 대상으로 재투표한다.

## 계약상 남아 있는 범위

서비스 문서에 Room 종료 권한이 있으나 API_SPEC에 명시적 Room 종료 엔드포인트는 없다.
새 엔드포인트를 임의로 만들지 않았다. 마지막 Player 탈퇴 및 TTL 만료로 Room은 정리된다.
exclude 전용 WebSocket 이벤트도 정의되어 있지 않아 임의 이벤트를 추가하지 않았다.
제외 요청 완료 후 `/state`를 다시 조회한다. 집계/취소가 발생하면 해당 표준 게임 이벤트를 전달한다.
