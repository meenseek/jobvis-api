# Jobvis API 배포 계약

Jobvis API는 특정 클라우드 SDK에 의존하지 않는 OCI 컨테이너와 PostgreSQL로 구성됩니다. CPU·스토리지·네트워크·컨테이너 실행 환경을 제공하는 클라우드라면 동일 이미지를 사용할 수 있습니다. 모두의 AI 실험실에도 이 경계를 전제로 올릴 수 있지만, 실제 계정에 영구 PostgreSQL 볼륨·고정 공개 주소·TLS 인그레스가 제공되는지는 배포 전에 콘솔에서 확인해야 합니다.

## 필수 런타임

- Java 17 컨테이너 실행 환경
- PostgreSQL 18 호환 데이터베이스와 영구 볼륨
- HTTPS를 종료하는 리버스 프록시 또는 인그레스
- 외부 비밀 저장소 또는 비공개 환경변수 주입 기능
- Gmail, Outlook, Google Calendar OAuth 콜백이 도달할 고정 HTTPS 프론트엔드 주소

애플리케이션 컨테이너 자체는 상태를 저장하지 않습니다. Flyway가 시작 시 스키마를 검증·마이그레이션하며, 여러 인스턴스의 가져오기 워커는 PostgreSQL `FOR UPDATE SKIP LOCKED`, lease/heartbeat, 활성 실행 unique 인덱스로 작업을 분배합니다.

## 이미지

```bash
docker build -t jobvis-api:0.0.1 .
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
| `JOBVIS_KAKAO_CLIENT_ID` | Kakao 로그인 시 | Kakao OIDC 앱 키/클라이언트 ID |
| `JOBVIS_GMAIL_CLIENT_ID` / `JOBVIS_GMAIL_CLIENT_SECRET` | Gmail 연결 시 | Gmail 읽기 전용 OAuth 클라이언트 |
| `JOBVIS_MICROSOFT_CLIENT_ID` / `JOBVIS_MICROSOFT_CLIENT_SECRET` | Outlook 연결 시 | Microsoft Graph `Mail.Read` OAuth 클라이언트 |
| `JOBVIS_GOOGLE_CALENDAR_CLIENT_ID` / `JOBVIS_GOOGLE_CALENDAR_CLIENT_SECRET` | Calendar 내보내기 시 | 별도 Calendar events OAuth 클라이언트 |
| `JOBVIS_OAUTH_REDIRECT_URIS` | OAuth 연결 시 | 허용할 콜백 URI의 정확한 쉼표 구분 목록 |
| `JOBVIS_FORWARD_HEADERS_STRATEGY` | 신뢰 프록시 사용 시 | 기본값 `none`; 인그레스가 외부 전달 헤더를 제거·재작성할 때만 `framework` |

암호화 키는 예를 들어 `openssl rand -base64 32`로 만들 수 있습니다. 키를 잃으면 저장된 외부 토큰과 네이버 앱 비밀번호를 복구할 수 없습니다. 키 교체 기능은 아직 없으므로 키를 버전 관리에 넣지 말고 별도 백업해야 합니다.

선택 조정값은 `JOBVIS_IMPORT_MAX_MESSAGES`, `JOBVIS_IMPORT_RETENTION`, `JOBVIS_IMPORT_POLL_DELAY`, `JOBVIS_IMPORT_MONITOR_DELAY`, `JOBVIS_IMPORT_CLEANUP_DELAY`, `JOBVIS_AUTH_CLEANUP_DELAY`, `JOBVIS_SESSION_TTL`, `JOBVIS_LOGIN_CHALLENGE_TTL`, `JOBVIS_LOGIN_RATE_LIMIT_WINDOW`, `JOBVIS_LOGIN_RATE_LIMIT_PER_IP`, `JOBVIS_LOGIN_RATE_LIMIT_MAX_CLIENTS`, `JOBVIS_LOGIN_MAX_OUTSTANDING_CHALLENGES`, `JOBVIS_OAUTH_MAX_OUTSTANDING_PER_USER`, `JOBVIS_OAUTH_START_RATE_WINDOW`, `JOBVIS_OAUTH_START_RATE_PER_USER`, `JOBVIS_OAUTH_EXCHANGE_LEASE`, `JOBVIS_OAUTH_REFRESH_LEASE`, `JOBVIS_NAVER_VALIDATION_WINDOW`, `JOBVIS_NAVER_VALIDATION_ATTEMPTS`, `JOBVIS_NAVER_VALIDATION_MAX_CLIENTS`, `JOBVIS_NAVER_VALIDATION_MAX_CONCURRENT`, `JOBVIS_EXTERNAL_CONNECT_TIMEOUT`, `JOBVIS_EXTERNAL_READ_TIMEOUT`, `JOBVIS_DB_MAX_POOL_SIZE`입니다. OAuth lease는 외부 HTTP timeout보다 충분히 길어야 하며 애플리케이션이 시작 시 이 관계를 검증합니다. Calendar 확인 claim은 토큰 갱신과 일정 등록에 적용되는 외부 HTTP timeout 합계에서 자동 계산됩니다.

전달 헤더는 기본적으로 신뢰하지 않습니다. 인그레스가 클라이언트의 `Forwarded`/`X-Forwarded-*` 값을 제거하고 직접 다시 쓰는 구성이 확인된 경우에만 `JOBVIS_FORWARD_HEADERS_STRATEGY=framework`를 사용합니다.

## 헬스체크와 종료

- liveness: `GET /actuator/health/liveness`
- readiness: `GET /actuator/health/readiness`
- 전체 상태: `GET /actuator/health`

플랫폼 종료 신호에는 Spring의 graceful shutdown을 사용합니다. readiness가 성공한 뒤 트래픽을 보내고, 배포 시 최소 30초의 종료 유예 시간을 둡니다.

## 외부 서비스 권한

- Gmail: `gmail.readonly`
- Outlook: delegated `Mail.Read`, `User.Read`, `offline_access`
- Naver: IMAP/SMTP 사용 설정, 2단계 인증, 앱 비밀번호. 일반 계정 비밀번호는 입력받지 않습니다.
- Google Calendar: `calendar.events`; 메일 동의와 별도의 연결·확인 절차입니다.

메일 수집은 읽기 전용이며 원문과 첨부파일을 DB에 저장하지 않습니다. 제한된 미리보기에서 만든 정규화 초안만 보관하고, 기본 보존기한 30일 뒤 실행과 초안을 삭제합니다. 수락된 지원에는 메일 제목·보낸 사람·수신 시각·정규화 요약만 남습니다.

## 운영 체크리스트

1. 첫 공유·운영 DB 생성 시점을 기록합니다. 그 시점부터 적용된 Flyway migration은 수정·삭제·재정렬하지 않고 새 migration만 추가합니다.
2. PostgreSQL 백업과 실제 복구 훈련을 먼저 구성합니다.
3. HTTPS 주소를 확정하고 각 공급자 콘솔의 redirect URI와 `JOBVIS_OAUTH_REDIRECT_URIS`를 정확히 일치시킵니다.
4. 32바이트 암호화 키와 DB/OAuth 비밀을 비밀 저장소에 넣습니다.
5. 인그레스에도 로그인 challenge/exchange IP rate limit을 구성합니다. 애플리케이션의 메모리 제한은 단일 인스턴스 보호용이며 여러 인스턴스의 전체 상한을 대신하지 않습니다.
6. 이미지를 배포하고 readiness가 성공하는지 확인합니다.
7. 테스트 계정으로 로그인 챌린지, 메일 연결, 좁은 날짜 범위 가져오기, 초안 수락, Calendar 미리보기·확인을 순서대로 smoke test 합니다.
8. 로그에 토큰·앱 비밀번호·메일 본문이 출력되지 않는지 확인합니다.
9. DB 용량, 만료 lease, 가져오기 실패 코드, transient 재시도, 연결 재승인 상태, readiness를 모니터링합니다.

모두의 AI 실험실 환경에서 관리형 PostgreSQL 또는 영구 볼륨과 고정 TLS 인그레스가 확인되지 않으면 API 컨테이너만 실험실에 두고 PostgreSQL은 별도의 관리형 서비스에 두는 구성이 안전합니다. 실제 인프라 생성과 외부 OAuth 앱 등록은 이 저장소의 범위에 포함하지 않습니다.
