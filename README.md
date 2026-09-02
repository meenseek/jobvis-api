# Jobvis API

Jobvis의 로그인, 외부 메일 연결, 채용 메일 자동 반영, 지원 현황, 일정, Google Calendar 내보내기와 통계를 제공하는 Kotlin/Spring Boot API입니다. PostgreSQL이 단일 원본이며 Flyway가 스키마를 관리합니다.

## 기술 구성

- Kotlin 2.3.21, Java 17, Spring Boot 4.1.0
- Spring Web MVC, Validation, Data JPA, Actuator
- PostgreSQL 18.4, Flyway
- Google OIDC 검증, 서버 발급 opaque session
- Gmail REST, Microsoft Graph, Naver IMAP 읽기 전용 수집
- AES-256-GCM 외부 자격증명 암호화
- Testcontainers PostgreSQL 통합 테스트

## 로컬 실행

```bash
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필은 서버를 `127.0.0.1`에만 바인딩하고 loopback 요청의 `X-Jobvis-User-Id` UUID만 개발 사용자로 허용합니다. 다른 프로필에서는 이 헤더를 신뢰하지 않고 `Authorization: Bearer <session>`만 사용합니다.

```text
GET http://localhost:8080/actuator/health
```

## API 개요

지원 현황 UI가 소비하는 정식 계약은
[`openapi/jobvis-v1.yaml`](openapi/jobvis-v1.yaml)입니다. 이 파일은 API 저장소가 소유하며,
응답 DTO나 공개 endpoint를 바꿀 때 구현·통합 테스트와 같은 변경에서 함께 갱신합니다.
웹은 이 계약에서 생성한 TypeScript 타입을 import하고 런타임에는 API JSON만 호출합니다.

### 인증

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| `GET` | `/api/v1/auth/providers` | Google 로그인 설정 가능 여부 |
| `POST` | `/api/v1/auth/challenges` | 일회용 로그인 challenge와 nonce 발급 |
| `POST` | `/api/v1/auth/exchange` | 검증된 ID token과 challenge를 서버 세션으로 교환 |
| `GET` | `/api/v1/auth/me` | 현재 사용자 |
| `POST` | `/api/v1/auth/logout` | 현재 세션 즉시 폐기 |

로그인 challenge는 5분 동안 한 번만 사용할 수 있습니다. IP별 요청 상한, 메모리에 유지하는 IP 키 수의 고정 상한, 전체 미사용 challenge 상한을 함께 적용합니다. ID token/JWK 검증은 DB 트랜잭션 밖에서 수행하고, 검증 뒤 짧은 트랜잭션에서 challenge를 다시 확인해 소비합니다. 세션 원문은 응답 시 한 번만 전달하고 DB에는 SHA-256 digest만 저장합니다.

### 외부 연결과 가져오기

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| `GET` | `/api/v1/connections/capabilities` | 공급자별 연결 방식과 현재 설정 여부 |
| `GET` | `/api/v1/connections` | 내 연결 목록 |
| `POST` | `/api/v1/connections/naver` | Naver 앱 비밀번호 검증 후 암호화 저장 |
| `POST` | `/api/v1/connections/{provider}/oauth/begin` | Gmail/Outlook/Google Calendar PKCE 시작 |
| `POST` | `/api/v1/connections/{provider}/oauth/complete` | OAuth 연결 완료 |
| `PATCH` | `/api/v1/connections/{id}/monitoring-consent` | 자동 확인 동의 변경 |
| `POST` | `/api/v1/connections/{id}/monitoring/resume` | 동의가 유지된 일시중지 연결의 자동 확인 재개 |
| `DELETE` | `/api/v1/connections/{id}` | 연결 해제와 저장 자격증명 제거 |
| `POST` | `/api/v1/import-runs` | 날짜 범위의 비동기 메일 가져오기 큐잉 |
| `GET` | `/api/v1/import-runs?page=0&size=50` | 페이지가 제한된 가져오기 실행 목록 |
| `GET` | `/api/v1/import-runs/{id}` | 실행 상태와 확정·제외·중복 집계 |
| `POST` | `/api/v1/import-runs/{id}/cancel` | 대기 실행 취소 |

메일은 공식 읽기 전용 경로만 사용합니다. Gmail은 `gmail.readonly`, Outlook은 `Mail.Read`와 immutable message ID, Naver는 IMAPS `READ_ONLY`와 `UIDVALIDITY:UID` 식별자를 사용합니다. Gmail metadata 조회는 계정 식별자의 SHA-256 hash로 관리하는 PostgreSQL quota gate를 통해 사용자·애플리케이션 인스턴스 전체에서 분당 240회로 제한합니다. 403 quota·429·5xx에는 최대 3회 bounded exponential backoff를 적용하고, `Retry-After` 지시를 최대 24시간까지 해석해 같은 계정의 다른 조회도 함께 차단합니다. 같은 Gmail 계정의 active import 생성도 PostgreSQL transaction advisory lock으로 직렬화합니다. Naver는 요청 날짜 조건을 `UID SEARCH`로 서버에 전달하되 IMAP 날짜 검색의 일 단위 경계를 보정하려고 양끝에 24시간 안전 여백을 둡니다. 최신 UID 구간부터 구간당 20,000개·최대 100구간까지만 검색하고, 안전 여백을 포함한 검색 후보 UID는 전체 20,000개로 제한하며 MIME 객체 트리를 만들지 않고 200개씩 제한된 raw stream만 읽습니다. 전체 INBOX가 2,000,000건을 넘으면 `NAVER_SEARCH_RANGE_LIMIT_EXCEEDED`로 실패하며, 이 경우 날짜 범위 축소가 아니라 오래된 메일을 보관 처리하거나 삭제해 메일함 크기를 줄여야 합니다. 안전 여백을 포함한 후보가 20,000개를 넘는 `NAVER_SEARCH_CANDIDATE_LIMIT_EXCEEDED`는 날짜 범위를 좁히거나 경계 인접 날짜의 대량 메일을 정리해야 합니다. 원문과 첨부파일은 저장하지 않고, 제목·보낸 사람·수신 시각·제한된 미리보기에서 만든 요약만 지원 이력에 남깁니다.

한 실행의 기본 상한은 2,000개이고 목록 `size`는 최대 100입니다. 상한을 넘으면 일부 결과를 조용히 확정하지 않고 `IMPORT_LIMIT_EXCEEDED`로 실패하므로 날짜 범위를 좁혀야 합니다. 이런 실행 단위 오류와 일시적 공급자 장애는 정상 연결을 비활성화하지 않으며, 401/명시적 OAuth `invalid_grant`만 재승인 상태로 전환합니다. 실행 worker는 lease와 heartbeat로 중단된 작업을 회수하고, 같은 연결의 활성 실행은 DB에서도 하나로 제한합니다. 각 메시지는 별도 트랜잭션에서 지원·일정·메일·활동·검토 상태와 terminal ledger를 함께 확정합니다. 연결 세대와 자동 확인 동의는 메시지마다 다시 잠금 확인하므로 수집 중 철회된 동의의 결과를 반영하지 않습니다. provider message key와 process key는 실행 보존기간과 분리해 이미 처리한 메일의 중복 반영과 근거 없는 회사명 병합을 막습니다. rollout 전 `FINALIZED` ledger를 exact 재수집하면 보존된 application 소유권과 provider process key만 충돌 검사 후 연결하며 application 내용·history·검토 상태는 바꾸지 않습니다.

새 채용 메일은 provider가 제공한 동일 채용 프로세스 key가 있을 때만 기존 지원에 연결하고, 아니면 새 지원을 만듭니다. 자동 반영된 지원은 항상 `needsReview=true`이며 사용자가 지원 상세 또는 일괄 검토 API에서 확인 완료합니다. 가져오기 응답은 `scannedCount`, `finalizedCount`, `ignoredCount`, `duplicateCount`를 제공합니다. 공개 draft 승인 API는 없습니다.

### 지원서, 일정, 캘린더, 통계

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| `POST` | `/api/v1/applications` | 지원 직접 추가 |
| `GET` | `/api/v1/applications/counts` | shell용 전체 지원 수 |
| `GET` | `/api/v1/applications/page` | `q`·`status`·`page`·`limit(1~100)` 기반 지원 목록과 집계 |
| `POST` | `/api/v1/applications/review/complete-bulk` | 현재 검토 revision 기준 일괄 확인 |
| `GET` | `/api/v1/applications/{id}` | 이력을 제외한 지원 core 상세 |
| `GET` | `/api/v1/applications/{id}/{emails\|activities\|changes}` | `before` cursor와 `limit(1~100)` 기반 전체 이력 |
| `PATCH` | `/api/v1/applications/{id}/details` | 기본 정보 수정 |
| `PUT` | `/api/v1/applications/{id}/memo` | 메모 저장 |
| `POST` | `/api/v1/applications/{id}/status` | 단계·합격·종료 변경 |
| `GET/PUT/PATCH` | `/api/v1/applications/{id}/schedule` | 현재 일정 조회·전체 수정·날짜 UI용 부분 수정 |
| `POST` | `/api/v1/applications/{id}/schedule/complete` | 현재 일정 완료 |
| `POST` | `/api/v1/applications/{id}/review/complete` | 검토 완료 |
| `DELETE` | `/api/v1/applications/{id}/activities/{activityId}` | 진행 타임라인 항목 삭제 |
| `GET` | `/api/v1/home/summary` | 서울 기준 오늘의 bounded 홈 요약 |
| `GET` | `/api/v1/calendar/schedules?from=&to=` | 기간 내 일정 목록 |
| `GET` | `/api/v1/activities/recent?from=&to=&type=&limit=` | 최대 31일·100개의 홈 최근 활동 |
| `POST` | `/api/v1/calendar-exports/previews` | Calendar에 쓸 정확한 내용 미리보기 |
| `GET` | `/api/v1/calendar-exports/{id}` | 내보내기 상태 조회 |
| `POST` | `/api/v1/calendar-exports/{id}/confirm` | 확인한 preview hash로 실제 생성 |
| `GET` | `/api/v1/analytics/summary?from=&to=` | 기간별 현재 지원 통계 |

연결 응답의 `nextSyncAfter`, `lastErrorCode`, `monitoringPaused`로 자동 확인의 실제 동작 상태를 확인할 수 있습니다. `ongoingSyncConsent=true`이면서 `monitoringPaused=true`인 연결은 위 재개 API에 현재 `expectedVersion`을 보내 다시 시작합니다.

현재 프론트 계약에 맞춰 지원 하나당 일정은 정확히 하나입니다. `application_schedules`의 `(user_id, application_id)` unique 제약을 유지합니다. 일정 수정 응답은 일정 `version`과 새 `applicationVersion`을 함께 반환하고 모든 일정 필드 차이를 변경기록에 남깁니다. Calendar 내보내기는 별도 동의가 필요하며, 미리보기와 확인을 분리합니다. 확인은 짧은 DB claim, 제한 시간이 있는 Google 호출, 짧은 finalize 트랜잭션으로 처리합니다. 결정적 공급자 이벤트 ID를 사용해 네트워크 재시도도 중복 일정을 만들지 않습니다.

변경 요청은 UUID `mutationId`와 최신 `expectedVersion`을 사용합니다. 같은 요청은 동시에 재시도되어도 한 번만 반영되고, 이후 데이터가 바뀌어도 최초 완료 시 저장한 응답을 그대로 재생합니다. 다른 내용이나 다른 precondition에 mutation을 재사용하거나 오래된 버전을 보내면 `409 Conflict`입니다. 값이 실제로 바뀌지 않은 일정 PUT은 지원·일정 버전을 증가시키지 않습니다.

## 데이터 보존과 보안 경계

- OAuth access/refresh token과 Naver 앱 비밀번호는 AES-256-GCM으로 암호화합니다.
- 일반 Naver 계정 비밀번호는 받지 않습니다.
- 해제한 연결의 모든 저장 자격증명은 즉시 제거합니다.
- 가져오기 실행의 기본 보존기한은 30일입니다. legacy draft는 rollout audit에 필요한 terminal row만 기존 `purgeAfter`까지 보존합니다.
- 로그인/OAuth challenge와 만료·폐기 세션도 주기적으로 삭제합니다.
- 모든 소유 데이터 쿼리는 `user_id`를 포함하고, 복합 FK가 교차 사용자 연결을 막습니다.
- 운영 CORS는 정확한 프론트 origin allowlist만 허용합니다.
- Gmail, Outlook, Google OAuth·OIDC, Calendar HTTP 호출에는 공통 연결·응답 제한 시간을 적용합니다.
- 제품의 날짜 경계는 `Asia/Seoul` 하나를 사용합니다.

## 검증과 배포

```bash
./gradlew cleanTest test --rerun-tasks
./gradlew bootJar
docker build -t jobvis-api:local .
```

통합 테스트는 실제 `postgres:18.4-alpine`에서 Flyway 재실행, 인증 경계, 테넌트 격리, 상태 불변식, 멱등성, 낙관적 잠금, 메시지 직접 확정, legacy preflight/rollout, 지원당 일정 1개, Calendar 확인과 통계를 검증합니다.

클라우드 환경변수, 헬스체크, 권한, 백업과 모두의 AI 실험실 적용 전 확인사항은 [배포 계약](docs/deployment.md)을 따릅니다. 실제 OAuth 앱 등록과 인프라 생성은 별도 운영 작업입니다.
