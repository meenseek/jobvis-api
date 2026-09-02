# Jobvis API 배포 계약

Jobvis API는 특정 클라우드 SDK에 의존하지 않는 OCI 컨테이너와 PostgreSQL로 구성됩니다. CPU·스토리지·네트워크·컨테이너 실행 환경을 제공하는 클라우드라면 동일 이미지를 사용할 수 있습니다. 모두의 AI 실험실 단일 VM에서는 이 저장소의 [검증된 Compose 운영 경계](../deploy/lab/README.md)를 사용합니다. 실제 VM의 영구 디스크·고정 공개 주소와 90일 만료일은 콘솔에서 확인하고, 만료 14일 전부터 문서의 이전 절차를 실행합니다.

## Migration history

2026-08-29 기준 Flyway V1과 V2는 애플리케이션 데이터가 있는 비폐기 PostgreSQL에
적용되어 있습니다. `meenseek` Jobvis 데이터 운영자가 이 이력을 소유하며 기존 migration은
append-only로 보존합니다. 모든 V1·V2 database를 검증된 백업·복구로 새 baseline DB에
이전하고 이전 DB와 그 이력에 의존하는 애플리케이션을 폐기한 경우에만 이 보존 의무가
끝납니다.

V3~V9는 순서대로 `TEST` 단계, 단일 활성 메일 연결, import 실행 멱등성, 메일 직접 확정
확장 스키마, 일괄 검토 mutation, 종일 일정, 메일 확정 rollout 상태와 Naver reconciliation
감사를 추가합니다. 적용 책임자는 `meenseek` Jobvis 데이터 운영자입니다. V9 rollout의
`completed_at` 확인과 필요한 Naver ledger reconciliation이 모두 끝날 때 이 배포 부채가
끝납니다. V6~V9는 기존 데이터를 보존하기 위한 append-only 전환이므로 squash하지 않습니다.

V10은 V9가 이미 공유된 뒤 발견된 신규 설치 차단을 해소합니다. V10 적용 시점에
`import_drafts`와 `mail_ingestion_ledger`가 모두 비어 있고 rollout이 미완료인 DB에만 target
constraint와 `completed_at`을 같은 migration transaction에서 설치합니다. 메일 이력이 하나라도
있는 DB는 자동 완료하지 않고 기존 V9 운영 절차를 그대로 따릅니다. 대상은 V9를 적용하는 신규·
비폐기 PostgreSQL이며 적용 책임자는 `meenseek` Jobvis 데이터 운영자입니다. 모든 활성 DB가
V10 이상이고 빈 신규 DB의 import smoke test와 기존 DB의 V9 rollout이 완료되면 V10 전환 부채가
끝납니다. V10도 적용 뒤 append-only로 보존합니다.

V3는 어떤 V3+ schema 변경도 커밋하기 전에 다음 legacy preflight를 한 transaction에서
검사합니다. `PENDING` draft가 0이고, 사용자별 non-revoked MAIL 연결이 최대 하나이며,
기존 일정의 `scheduled_at`이 모두 채워져 있어야 합니다. V4·V6·V8도 각 조건을 다시
검사하지만 이는 방어선이며 최초 rollback-safe gate의 소유자는 V3입니다.

## 필수 런타임

- Java 17 컨테이너 실행 환경
- PostgreSQL 18 호환 데이터베이스와 영구 볼륨
- HTTPS를 종료하는 리버스 프록시 또는 인그레스
- 외부 비밀 저장소 또는 비공개 환경변수 주입 기능
- Gmail, Outlook, Google Calendar OAuth 콜백이 도달할 고정 HTTPS 프론트엔드 주소

애플리케이션 컨테이너 자체는 상태를 저장하지 않습니다. Flyway가 시작 시 스키마를 검증·마이그레이션하며, 여러 인스턴스의 가져오기 워커는 PostgreSQL `FOR UPDATE SKIP LOCKED`, lease/heartbeat, 활성 실행 unique 인덱스로 작업을 분배합니다.

## 이미지

```bash
docker build -t jobvis-api:local .
```

이미지는 root가 아닌 `jobvis` 사용자로 실행됩니다. 로컬에서 이미지만 점검할 때도 데이터베이스 URL과 암호화 키가 필요합니다.

## 환경변수

운영에서 최소한 다음 값을 비밀 또는 설정으로 주입합니다.

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `JOBVIS_DATABASE_URL` | 예 | `jdbc:postgresql://host:5432/jobvis` 형식 |
| `JOBVIS_DATABASE_USERNAME` | 예 | PostgreSQL 사용자 |
| `JOBVIS_DATABASE_PASSWORD` | 예 | PostgreSQL 비밀번호 |
| `JOBVIS_ENCRYPTION_KEY_BASE64` | 외부 연결 사용 시 예 | 정확히 32바이트인 AES 키의 Base64 값 |
| `JOBVIS_SERVER_ADDRESS` | 예 | 컨테이너에서는 일반적으로 `0.0.0.0` |
| `PORT` | 예 | 플랫폼이 할당한 포트, 기본값 `8080` |
| `JOBVIS_CORS_ALLOWED_ORIGINS` | 예 | 쉼표로 구분한 정확한 프론트엔드 origin 목록 |
| `JOBVIS_GOOGLE_CLIENT_ID` | Google 로그인 시 | Google OIDC 클라이언트 ID |
| `JOBVIS_GMAIL_CLIENT_ID` / `JOBVIS_GMAIL_CLIENT_SECRET` | Gmail 연결 시 | Gmail 읽기 전용 OAuth 클라이언트 |
| `JOBVIS_MICROSOFT_CLIENT_ID` / `JOBVIS_MICROSOFT_CLIENT_SECRET` | Outlook 연결 시 | Microsoft Graph `Mail.Read` OAuth 클라이언트 |
| `JOBVIS_GOOGLE_CALENDAR_CLIENT_ID` / `JOBVIS_GOOGLE_CALENDAR_CLIENT_SECRET` | Calendar 내보내기 시 | 별도 Calendar events OAuth 클라이언트 |
| `JOBVIS_OAUTH_REDIRECT_URIS` | OAuth 연결 시 | 허용할 콜백 URI의 정확한 쉼표 구분 목록 |
| `JOBVIS_FORWARD_HEADERS_STRATEGY` | 신뢰 프록시 사용 시 | 기본값 `none`; 인그레스가 외부 전달 헤더를 제거·재작성할 때만 `framework` |
| `JOBVIS_IMPORT_AUTO_COMPLETE_ROLLOUT` | V9 전환 작업 시 | 기본값 `false`; 단일 작업 인스턴스에서만 `true` |
| `JOBVIS_NAVER_RECONCILIATION_ENABLED` | Naver legacy ledger 정리 시 | 기본값 `false`; 증빙 파일을 적용하는 작업 인스턴스에서만 `true` |
| `JOBVIS_NAVER_RECONCILIATION_FILE` | 위 작업 시 | 컨테이너 내부의 읽기 전용 JSON 파일 절대 경로 |

암호화 키는 예를 들어 `openssl rand -base64 32`로 만들 수 있습니다. 키를 잃으면 저장된 외부 토큰과 네이버 앱 비밀번호를 복구할 수 없습니다. 키 교체 기능은 아직 없으므로 키를 버전 관리에 넣지 말고 별도 백업해야 합니다.

일반 조정값은 `JOBVIS_IMPORT_MAX_MESSAGES`, `JOBVIS_GMAIL_FETCH_CONCURRENCY`, `JOBVIS_IMPORT_WORKER_CONCURRENCY`, `JOBVIS_IMPORT_WORKER_SHUTDOWN_GRACE`, `JOBVIS_IMPORT_HEARTBEAT_INTERVAL`, `JOBVIS_IMPORT_RETENTION`, `JOBVIS_IMPORT_POLL_DELAY`, `JOBVIS_IMPORT_MONITOR_DELAY`, `JOBVIS_IMPORT_CLEANUP_DELAY`, `JOBVIS_AUTH_CLEANUP_DELAY`, `JOBVIS_SESSION_TTL`, `JOBVIS_LOGIN_CHALLENGE_TTL`, `JOBVIS_LOGIN_RATE_LIMIT_WINDOW`, `JOBVIS_LOGIN_RATE_LIMIT_PER_IP`, `JOBVIS_LOGIN_RATE_LIMIT_MAX_CLIENTS`, `JOBVIS_LOGIN_MAX_OUTSTANDING_CHALLENGES`, `JOBVIS_OAUTH_MAX_OUTSTANDING_PER_USER`, `JOBVIS_OAUTH_START_RATE_WINDOW`, `JOBVIS_OAUTH_START_RATE_PER_USER`, `JOBVIS_OAUTH_EXCHANGE_LEASE`, `JOBVIS_OAUTH_REFRESH_LEASE`, `JOBVIS_NAVER_VALIDATION_WINDOW`, `JOBVIS_NAVER_VALIDATION_ATTEMPTS`, `JOBVIS_NAVER_VALIDATION_MAX_CLIENTS`, `JOBVIS_NAVER_VALIDATION_MAX_CONCURRENT`, `JOBVIS_EXTERNAL_CONNECT_TIMEOUT`, `JOBVIS_EXTERNAL_READ_TIMEOUT`, `JOBVIS_DB_MAX_POOL_SIZE`입니다. OAuth lease는 외부 HTTP timeout보다 충분히 길어야 하며 애플리케이션이 시작 시 이 관계를 검증합니다. Calendar 확인 claim은 토큰 갱신과 일정 등록에 적용되는 외부 HTTP timeout 합계에서 자동 계산됩니다. rollout/reconciliation 변수는 상시 조정값이 아니며 작업 종료 즉시 기본값으로 되돌립니다.

각 import claim의 heartbeat가 서로를 막지 않도록 `JOBVIS_IMPORT_WORKER_CONCURRENCY`는 1~32이면서 `JOBVIS_DB_MAX_POOL_SIZE` 이하여야 합니다. `JOBVIS_GMAIL_FETCH_CONCURRENCY`는 1~16, `JOBVIS_IMPORT_HEARTBEAT_INTERVAL`은 1~30초, `JOBVIS_IMPORT_WORKER_SHUTDOWN_GRACE`는 0초~5분이어야 합니다. heartbeat는 worker 수만큼 병렬 실행되며 애플리케이션이 시작 시 이 범위를 모두 검증합니다.

전달 헤더는 기본적으로 신뢰하지 않습니다. 인그레스가 클라이언트의 `Forwarded`/`X-Forwarded-*` 값을 제거하고 직접 다시 쓰는 구성이 확인된 경우에만 `JOBVIS_FORWARD_HEADERS_STRATEGY=framework`를 사용합니다. Jobvis Web 인증 BFF는 이 정리된 `Forwarded`/`X-Forwarded-For`를 API까지 전달하며, API의 로그인 rate limit은 복원된 클라이언트 주소를 기준으로 적용됩니다.

## 헬스체크와 종료

- liveness: `GET /actuator/health/liveness`
- readiness: `GET /actuator/health/readiness`
- 전체 상태: `GET /actuator/health`

플랫폼 종료 신호에는 Spring의 graceful shutdown을 사용합니다. readiness가 성공한 뒤 트래픽을 보냅니다. 플랫폼 종료 유예 시간은 Spring 종료 단계 30초, `JOBVIS_IMPORT_WORKER_SHUTDOWN_GRACE`, Gmail executor 정리 5초와 여유 시간을 모두 합한 값보다 길어야 합니다. 기본 설정에서는 최소 60초를 두고, worker 종료 유예를 늘리면 플랫폼 유예도 같은 만큼 늘립니다. 유예가 끝난 import는 실패로 기록하거나 즉시 재큐잉하지 않고 기존 lease 만료 후 다른 인스턴스가 회수합니다.

## 외부 서비스 권한

- Gmail: `gmail.readonly`
- Outlook: delegated `Mail.Read`, `User.Read`, `offline_access`
- Naver: IMAP/SMTP 사용 설정, 2단계 인증, 앱 비밀번호. 일반 계정 비밀번호는 입력받지 않습니다.
- Google Calendar: `calendar.events`; 메일 동의와 별도의 연결·확인 절차입니다.

메일 수집은 읽기 전용이며 원문과 첨부파일을 DB에 저장하지 않습니다. 제한된 미리보기에서 만든 요약만 지원 이력에 반영하고, 가져오기 실행은 기본 보존기한 30일 뒤 삭제합니다. legacy draft는 rollout 중 terminal 상태로 바꾼 뒤 기존 `purgeAfter`까지만 보존합니다.

## V9 메일 확정 rollout

V9를 적용하면 새 import claim은 `mail_finalization_rollout_state.completed_at`이 채워질 때까지
닫혀 있습니다. legacy draft에는 provider process key가 없으므로 새 버전이 이를 추측해서
자동 확정하지 않습니다. 다음 순서를 바꾸지 않습니다.

1. 기존 버전에서 새 import 생성과 monitor를 멈추고 public draft 검토 흐름으로 모든
   `PENDING` draft를 accept 또는 reject합니다. 사용자별 non-revoked MAIL 연결은 하나만
   남기고, `scheduled_at IS NULL`인 legacy 일정은 정확한 시각으로 보정하거나 삭제합니다.
   세 조건을 모두 확인하기 전에는 새 migration을 적용하지 않습니다. V3가 어긋난 조건을
   어떤 V3+ schema 변경보다 먼저 명시적으로 거부합니다.
2. 이전 draft writer가 있는 모든 인스턴스를 완전히 종료하고 더 이상 `DRAFTED` ledger가
   추가되지 않는지 확인합니다.
3. V9를 포함한 새 이미지를 일반 전환 변수 `false`로 배포합니다. 새 import claim은 아직
   닫힌 상태가 정상입니다.
4. 작업 인스턴스 하나만 `JOBVIS_IMPORT_AUTO_COMPLETE_ROLLOUT=true`로 시작합니다. 작업은
   orphan `DRAFTED` ledger를 제거하고, legacy `ACCEPTED` ledger의 application을 exact
   `application_emails` 또는 terminal draft로 확인한 뒤 terminal 상태와 제약을 target으로
   교체합니다. 증빙 누락이나 충돌은 완료하지 않고 명시적으로 실패합니다.
5. 아래 쿼리의 두 count가 0이고 `completed_at`이 채워졌으며 target constraint 네 개가
   존재하는지 확인합니다. 이 완료와 constraint 교체는 같은 DB transaction입니다.
6. 작업 인스턴스를 종료하고 전환 변수를 `false`로 되돌린 일반 fleet을 배포합니다.

```sql
SELECT completed_at, pending_draft_count, drafted_ledger_count,
       orphan_drafted_deleted_count
FROM mail_finalization_rollout_state
WHERE singleton = true;

SELECT conname
FROM pg_constraint
WHERE conname IN (
  'ck_mail_ingestion_ledger_state_target',
  'ck_import_drafts_status_target',
  'ck_import_drafts_decision_target',
  'uq_mail_ingestion_ledger_owner_id'
)
ORDER BY conname;
```

rollout 전후 row count와 실패 draft의 `error_code`를 운영 기록에 남깁니다. `completed_at`이
비어 있으면 일반 worker가 claim하지 않는 것이 정상이며 강제로 DB 값을 수정하지 않습니다.

### Naver legacy ledger reconciliation

V6 이후 stable provider message key가 없는 기존 Naver 연결은
`NAVER_LEDGER_MIGRATION_REQUIRED`로 차단됩니다. 이전 writer 종료 후 공급자 재조회·내보내기
또는 사용자 확인 증빙으로 모든 미해결 ledger를 정확히 한 번 분류합니다. JSON 예시는 다음과
같습니다.

```json
{
  "operationId": "11111111-1111-4111-8111-111111111111",
  "connectionId": "22222222-2222-4222-8222-222222222222",
  "expectedLedgerCount": 2,
  "expectedStateCounts": { "FINALIZED": 2 },
  "reconciledBy": "operator@example.com",
  "entries": [
    {
      "ledgerId": "33333333-3333-4333-8333-333333333333",
      "disposition": "STABLE_KEY",
      "stableProviderMessageKey": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "evidenceType": "PROVIDER_REFETCH",
      "evidenceReference": "secure-ops-ticket-123"
    },
    {
      "ledgerId": "44444444-4444-4444-8444-444444444444",
      "disposition": "VERIFIED_UID_ONLY",
      "stableProviderMessageKey": null,
      "evidenceType": "USER_CONFIRMED",
      "evidenceReference": "secure-ops-ticket-124"
    }
  ]
}
```

DB snapshot과 정확히 일치하는 `expectedLedgerCount`·`expectedStateCounts`를 사용하고 파일은
비밀 저장소에서 작업 컨테이너에 읽기 전용으로 마운트합니다. 작업 인스턴스 하나를
`JOBVIS_NAVER_RECONCILIATION_ENABLED=true`와 `JOBVIS_NAVER_RECONCILIATION_FILE=<path>`로
시작하고 완료 로그를 확인한 뒤 즉시 종료합니다. 동일 `operationId`와 동일 내용은 안전하게
재생되며 다른 내용으로 재사용하면 거부됩니다. 원문 provider message ID, 메일 본문, 앱
비밀번호를 JSON·로그·ticket에 남기지 않습니다. 완료 뒤 연결이 `CONNECTED`이고
`last_error_code`가 비어 있는지 확인합니다. rollout과 Naver reconciliation은 같은 DB
advisory transaction lock을 사용해 서로 다른 인스턴스에서도 직렬화되지만, 운영자는 한 번에
하나의 작업 인스턴스만 실행합니다.

## 운영 체크리스트

1. 첫 공유·운영 DB 생성 시점을 기록합니다. 그 시점부터 적용된 Flyway migration은 수정·삭제·재정렬하지 않고 새 migration만 추가합니다.
2. PostgreSQL 백업과 실제 복구 훈련을 먼저 구성합니다.
3. HTTPS 주소를 확정하고 각 공급자 콘솔의 redirect URI와 `JOBVIS_OAUTH_REDIRECT_URIS`를 정확히 일치시킵니다.
4. 32바이트 암호화 키와 DB/OAuth 비밀을 비밀 저장소에 넣습니다.
5. 인그레스에도 로그인 challenge/exchange IP rate limit을 구성합니다. 애플리케이션의 메모리 제한은 단일 인스턴스 보호용이며 여러 인스턴스의 전체 상한을 대신하지 않습니다.
6. 이미지를 배포하고 readiness가 성공하는지 확인합니다.
7. 테스트 계정으로 로그인 챌린지, 메일 연결, 좁은 날짜 범위 가져오기, 자동 생성된 지원의 검토 완료, Calendar 미리보기·확인을 순서대로 smoke test 합니다.
8. 로그에 토큰·앱 비밀번호·메일 본문이 출력되지 않는지 확인합니다.
9. DB 용량, 만료 lease, 가져오기 실패 코드, transient 재시도, 연결 재승인 상태, readiness를 모니터링합니다.

모두의 AI 실험실 VM의 디스크가 재시작 뒤에도 유지되는지는 첫 배포 전에 확인합니다. VM 자체 삭제·만료에는 로컬 volume도 함께 사라질 수 있으므로 매일 VM 외부 백업이 필수입니다. 실제 VM 생성, DNS 변경, 외부 OAuth 앱 등록은 운영 계정 권한이 있어야 수행할 수 있습니다.
