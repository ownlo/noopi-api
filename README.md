# NOOPI Backend MVP

Java 26 · Spring Boot 3.5 · Spring MVC/WebSocket · JPA · MySQL 8 · Flyway.

## 문서 기준과 윷놀이 구현 범위

`docs/common/`은 `noopi-web/docs/common/`과 파일 구성 및 내용이 동일하다.
공통 명세는 라이어·블라인드·마피아·윷놀이를 포함한다. 현재 서버 코드는
라이어/블라인드/마피아/윷놀이를 지원한다. 윷놀이는 개인전/팀전, 추가 던지기,
이동권, 지름길, 업기/잡기/완주와 승리를 서버 Runtime에서 처리한다.

- [윷놀이 규칙](docs/common/games/YUT_GAME_SPEC.md): 개인전/팀전, 턴, 지름길, 업기/잡기/완주.
- [API 계약](docs/common/API_SPEC.md): 윷놀이 행동 API, 개인화 상태, 이벤트와 오류.
- [백엔드 구조](docs/BACKEND_ARCHITECTURE.md): Runtime/동시성/Projection 구현 목표.
- [구현 노트](docs/IMPLEMENTATION_NOTES.md): 구현 체크리스트, 미확정 보드 ID 및 연동 차이.
- [운영 API](docs/OPERATIONS_API.md): 기존 일별 통계 계약 보존. 공통 게임 계약과 별도 관리.

## 실행

```sh
docker compose up -d mysql
./gradlew bootRun
```

기본 주소는 `http://localhost:8080`이다. 로컬 MySQL 접속값은 `compose.yaml`과
`application-local.yml`에 있으며, 별도 프로필을 지정하지 않으면 `local` 프로필이 적용된다.

운영 환경에서는 `prod` 프로필과 DB 환경 변수를 지정한다.

```sh
SPRING_PROFILES_ACTIVE=prod \
DB_URL='jdbc:mysql://<host>:3306/noopi?characterEncoding=UTF-8&serverTimezone=UTC' \
DB_USERNAME='<username>' \
DB_PASSWORD='<password>' \
./gradlew bootRun
```

`prod` 프로필에는 DB 접속 기본값이 없으므로 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가
모두 필요하다.

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

반환된 `roomId`로 다른 브라우저의 Player를 참가시킨 뒤 GameSession을 생성·시작한다.
라이어, 블라인드, 마피아, 윷놀이의 상세 행동·응답·이벤트 계약은
`docs/common/API_SPEC.md`를 따른다. 윷놀이의 5개 전용 엔드포인트와 공통
카탈로그·생성·시작·취소·상태 조회가 연결되어 있다.

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
- `game/blind`: 블라인드 제시어 배정, 개인별 상대 제시어 projection, 정답 판정과 원자적 승자 확정.
- `game/mafia`: 마피아 역할·phase, 밤 행동, 의심, 처형 투표, 사망·승패 판정과 개인별 projection.
- `game/yut`: Node/Edge 경로, 팀·턴·이동권, 업기/잡기/완주와 개인별 행동 projection.
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
| `DISCONNECT_GRACE` | `PT2M` | 라이어 제외 가능 시간, 장기 라이어 이탈 기준 |
| `ROOM_TTL` | `PT12H` | 마지막 Room 활동 이후 Runtime 만료 |
| `CLEANUP_INTERVAL_MS` | `60000` | 정리 주기 |
| `RECENT_KEYWORD_COUNT` | `10` | Room별 최근 제시어 회피 개수 |
| `ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | REST CORS 및 WebSocket 허용 Origin |

명시적 방장 탈퇴 시 `ROOM_CLOSED`를 `HOST_LEFT` 사유로 발행하고 Room을 종료한다.
방장 미접속만으로 자동 종료/승계하지 않는다. 참가 가능한 Room의 신규 조회/참가를 허용한다.
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
- 마피아는 4~12명이며 인원별 역할 구성을 서버가 자동 배정한다.
- 마피아의 첫 밤에는 공격·치료를 하지 않고, 밤 행동은 생존자가 동시에 제출한다.
- 경찰 조사 결과, 시민별 의심 대상, 마피아 동료는 개인화 정보로 보호한다.
- 마피아 처형 지목은 동률 후보 무제한 재투표로 결정하고, 최후의 변론 후 찬반 투표에서 동률이면 살린다.

## 계약상 남아 있는 범위

윷놀이 Node/Path ID는 공통 게임 명세 9.1절로 확정했다. 던지기는 기존 body
없는 POST를 유지한다. 현재 단계와 Room 잠금으로 중복 처리를 방어하지만,
연속 추가 던지기 중 지연 재전송과 의도된 다음 던지기의 완전한 구분에는
별도 요청 식별 계약이 필요하다. Client는 자동 재시도하지 않고 `/state`로 복구한다.

윷놀이 테스트는 `./gradlew test --tests com.noopi.YutGameRulesTest --tests com.noopi.YutHttpIntegrationTest`로 실행한다.
전체 회귀 검증은 `./gradlew test build`를 사용한다. 프론트는 개발 모드에서
`http://localhost:8080/api`와 `ws://localhost:8080/ws`에 연결하며 Mock을 꺼야 한다.

서비스 문서에 Room 종료 권한이 있으나 API_SPEC에 명시적 Room 종료 엔드포인트는 없다.
새 엔드포인트를 임의로 만들지 않았다. 방장의 명시적 나가기 및 TTL 만료로 Room은 정리된다.
exclude 전용 WebSocket 이벤트도 정의되어 있지 않아 임의 이벤트를 추가하지 않았다.
제외 요청 완료 후 `/state`를 다시 조회한다. 집계/취소가 발생하면 해당 표준 게임 이벤트를 전달한다.

## 일별 운영 통계

서버 실행 환경에 `METRICS_API_KEY`를 설정한 뒤 `/swagger-ui/index.html`의
**운영 통계 → GET /api/metrics/daily**에서 `X-Metrics-Key`, `from`, `to`를 입력한다.
예: `from=2026-09-01`, `to=2026-09-13` (양 끝 포함, 최대 366일). 키 미설정 시 조회는 차단된다.

한국 시간 기준 방 생성/종료 수, 종료된 방의 총·평균·최대 지속시간(분),
현재 조회 구현은 LIAR/BLIND별 시작·완료·취소 수와 참가 인원 합계를 반환한다.
MAFIA/YUT 통계 응답 확장은 별도 구현 대상이다. 상세 계약은 `docs/OPERATIONS_API.md`를 따른다.
`started`가 실제 플레이 횟수이고 READY 취소는 제외한다. 시작된 게임이 방 삭제로
사라지면 통계상 취소로 집계한다. 자정을 넘긴 방의 전체 지속시간은 종료일에 반영한다.

Flyway V5가 통계 전용 테이블을 생성한다. 기본 10초마다 자동 저장하며
`METRICS_FLUSH_INTERVAL_MS`로 주기를 조정한다. 조회는 DB 저장분만 반환한다.
정상 종료 시 마지막 저장을 시도하며 DB 실패 시 메모리 누적값을 유지하고 재시도한다.
강제 종료 시 마지막 저장 이후 집계 및 진행 중 방/게임의 종료 기록은 유실될 수 있다.
기존 과거 기록의 소급 수집은 지원하지 않는다.
