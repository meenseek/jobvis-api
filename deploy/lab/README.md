# 모두의 AI 실험실 단일 VM 배포

이 디렉터리는 공개 HTTPS 프록시(Caddy), Jobvis API, 내부 PostgreSQL을 한 VM에서 실행하는
운영 경계입니다. 외부에는 TCP 80/443과 QUIC용 UDP 443만 공개하며 API 8080과 PostgreSQL
5432는 호스트 포트에 연결하지 않습니다. 기본 resource limit과 OS·Docker 여유를 함께
보장하려면 모두의 AI 실험실 VM은 **최소 2 vCPU·4 GB RAM**으로 신청합니다. 1 vCPU·2 GB는
이 운영 구성의 지원 사양이 아닙니다.

## 연결 전에 필요한 값

- Ubuntu 계열 VM의 공개 IP, SSH 사용자·포트, 로컬 SSH 개인키 경로
- VM 공개 IP를 가리키는 API hostname 하나(예: `api.jobvis.example`)
- Cloudflare Workers에 배포된 Jobvis Web의 정확한 HTTPS origin
- 인증서 갱신 알림을 받을 이메일

공인 HTTPS 인증서를 자동 발급하려면 API hostname이 VM을 가리켜야 합니다. 소유한 도메인이
없으면 체험 배포에서는 공개 IPv4가 `203.0.113.10`일 때 `203-0-113-10.sslip.io`처럼 IP 기반
무료 DNS hostname을 사용할 수 있어 별도 DNS 키가 필요 없습니다. 이 외부 DNS 서비스는 장기
운영 의존성이므로 안정적인 배포에는 소유한 도메인을 권장합니다. hostname 없이 IP만 Caddy에
넣으면 자체 서명 인증서가 발급되어 브라우저와 Web BFF가 신뢰하지 않습니다.
Google 로그인과 Gmail·Outlook·Calendar 연결은 각각 공급자 OAuth 앱 키가 있어야
활성화됩니다.

## 1. VM 준비

Docker Engine과 Compose plugin을 설치한 뒤 이 저장소를 `/opt/jobvis-api` 같은 고정 경로에
checkout합니다. Docker가 시작 시 자동 실행되는지도 확인합니다.

방화벽은 현재 SSH 접속 포트를 먼저 허용한 뒤 80/443만 추가합니다. 아래의 `OpenSSH` 대신
SSH가 다른 포트를 사용하면 그 정확한 포트를 허용해야 합니다. 기존 SSH 세션을 유지한 채 새
세션 접속이 되는지 확인한 다음 방화벽을 활성화합니다.

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw enable
sudo ufw status verbose
```

클라우드/실험실 보안 그룹이 따로 있으면 동일하게 SSH와 80/443만 허용합니다. 8080과 5432는
열지 않습니다.

## 2. 비밀 생성과 기동

```bash
cd /opt/jobvis-api/deploy/lab
./prepare-env.sh api.jobvis.example https://jobvis-web.example.workers.dev operator@example.com
./verify.sh
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
curl --fail https://api.jobvis.example/actuator/health/readiness
```

`prepare-env.sh`는 기존 `.env`를 덮어쓰지 않고 DB 비밀번호와 32바이트 AES 키를
생성합니다. `.env`는 Git과 Docker build context에서 제외됩니다. 키를 잃으면 저장된 외부 연동
토큰을 복호화할 수 없으므로 암호화된 별도 위치에 백업합니다. 초기화 뒤 `.env`의 DB 이름·사용자·
비밀번호만 바꿔도 기존 PostgreSQL volume의 계정은 자동 변경되지 않습니다. 비밀 교체는 별도 DB
계정 변경 절차와 Web/API 동시 전환을 준비한 경우에만 수행합니다.

기동 시 Flyway가 마이그레이션을 실행합니다. 기존 운영 데이터가 있다면 먼저
[`docs/deployment.md`](../../docs/deployment.md)의 migration preflight와 rollout 절차를
적용해야 합니다. 새 빈 DB에서는 V1부터 순서대로 적용됩니다.

## 3. Cloudflare Workers 연결

API `.env`와 Web의 `wrangler.jsonc`를 다음처럼 맞춥니다.

| 위치 | 변수 | 값 |
| --- | --- | --- |
| API `.env` | `JOBVIS_WEB_ORIGIN` | 실제 Workers HTTPS origin |
| Web `wrangler.jsonc` | `JOBVIS_API_BASE_URL` | `https://<JOBVIS_API_HOST>` |
| Web `wrangler.jsonc` | `JOBVIS_API_MODE` | `api` |
| Web `wrangler.jsonc` | `NEXT_PUBLIC_JOBVIS_API_MODE` | `api` |
| Web `wrangler.jsonc` | `JOBVIS_WEB_ORIGIN` | 실제 Workers HTTPS origin |

API의 `JOBVIS_WEB_ORIGIN`에는 Workers URL의 origin만 넣고 경로와 마지막 `/`를 넣지 않습니다.
Workers URL이 바뀌면 API `.env`와 Web `wrangler.jsonc`를 함께 고친 뒤 API와 Web을 각각
재배포합니다.

```bash
docker compose --env-file .env up -d --no-deps --force-recreate api
```

Web은 브라우저 토큰을 API로 직접 보내지 않고 서버의 `HttpOnly` session cookie를 단일 인증
경계로 사용합니다.

## 4. 외부 OAuth

`.env`에서 사용할 공급자 값만 채웁니다. 공급자 콘솔의 허용 Web origin은 Workers origin이며,
외부 연결 기능의 redirect URI는 다음 하나입니다.

```text
https://<Workers origin>/oauth/callback
```

Google 로그인용 `JOBVIS_GOOGLE_CLIENT_ID`는 Web 빌드의
`NEXT_PUBLIC_JOBVIS_GOOGLE_CLIENT_ID`와 같은 Web Client ID를 사용합니다. Gmail과 Calendar
권한은 로그인과 역할이 다르므로 각 기능에 필요한 client 설정을 별도로 확인합니다. 값 변경 후
API 컨테이너를 재생성하고 실제 로그인·연결·해제·재연결을 점검합니다.

## 5. 백업과 복구

수동 백업은 다음 명령으로 만들며 30일이 지난 로컬 dump와 checksum은 정리됩니다.

```bash
cd /opt/jobvis-api/deploy/lab
./backup.sh
```

같은 VM의 `backups/`만으로는 VM 삭제·디스크 장애를 복구할 수 없습니다. 생성된 `.dump`,
`.sha256`, `.env`를 매일 VM 밖의 암호화된 저장소로 복사합니다. 예를 들어 cron은 백업 생성만
다음처럼 예약할 수 있으며, 별도 전송 성공 여부도 모니터링해야 합니다.

```cron
15 3 * * * cd /opt/jobvis-api/deploy/lab && ./backup.sh >> ./backups/backup.log 2>&1
```

복구는 checksum이 일치하는 dump를 임시 DB에 먼저 완전히 복원하고 Jobvis 핵심 schema를 확인한
뒤, API를 중지한 상태에서 기존 DB와 이름을 교체하는 파괴적 작업입니다. 새 API가 healthy인
경우에만 이전 DB를 삭제하며, 실패하면 이전 DB로 자동 rollback합니다. 따라서 복원 실패로 원본을
잃지 않고 archive에 없는 stale table이나 제약도 남지 않습니다. `.dump.sha256`을 dump와 같은
경로에 두고 정확한 dump를 지정한 뒤 두 번 확인해야 실행됩니다.

```bash
./restore.sh /secure/path/jobvis-YYYYMMDDTHHMMSSZ.dump RESTORE
docker compose --env-file .env ps
curl --fail https://api.jobvis.example/actuator/health/readiness
```

최초 운영 전에 별도 빈 VM 또는 격리 환경에서 실제 복구 훈련을 한 번 완료합니다.

## 6. 운영 확인

```bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 api
curl --fail https://api.jobvis.example/actuator/health/liveness
curl --fail https://api.jobvis.example/actuator/health/readiness
```

로그에 토큰, 앱 비밀번호, 메일 본문이 없는지 확인합니다. API 또는 DB가 unhealthy면 Caddy만
정상이라고 배포 성공으로 판단하지 않습니다. 최소 smoke 순서는 Google 로그인, 내 사용자 조회,
지원 내역 조회·등록, 일정 조회·등록, Calendar preview/confirm입니다.

## 7. 90일 만료 전 이전

만료 14일 전부터 다음 순서로 새 VM으로 옮깁니다. 소유한 hostname을 쓴 경우 현재 DNS TTL을
먼저 300초 정도로 낮추고, **기존 TTL만큼 기다린 뒤** 전환을 시작합니다. 전환 직전에 TTL을
낮추면 이미 캐시된 주소의 만료 시각은 줄어들지 않습니다.

1. 새 VM과 새 고정 IP를 준비하고 Docker·방화벽·`.env`를 동일하게 구성합니다. 새 VM에서는
   PostgreSQL만 먼저 기동하고 API/Caddy는 아직 공개 트래픽을 받지 않습니다.
2. 필요하면 일반 `backup.sh` dump로 새 VM 복구를 예행연습합니다. 이 사전 dump는 최종본이
   아니며 전환에는 다시 백업해야 합니다.
3. 쓰기 중단 시간을 공지하고 이전 VM에서 `./cutover-backup.sh CUTOVER`를 실행합니다. 이 명령은
   기존 API를 graceful stop한 다음 최종 dump와 checksum을 만듭니다. 이후 이전 API를 임의로
   재시작하지 않습니다.
4. 최종 dump·checksum을 새 VM으로 옮겨 `restore.sh`로 빈 대상 DB에 복원하고 내부 readiness를
   확인합니다.
5. 소유한 hostname은 A/AAAA 레코드를 새 IP로 전환합니다. IP 기반 `sslip.io` hostname이면
   Web의 `JOBVIS_API_BASE_URL`을 새 hostname으로 바꾸고 Workers를 재배포합니다.
6. 실제 공개 API hostname의 TLS readiness와 Google 로그인·등록·조회 smoke scenario를
   확인합니다. DNS가 아직 이전 VM을 가리키는 동안 새 VM의 공개 hostname 검증을 성공으로
   간주하지 않습니다.
7. 새 DB의 전환 시점 데이터와 VM 외부 백업을 확인한 뒤 이전 VM을 종료합니다.

Rollback이 필요하면 새 DB에 쓰기가 시작됐는지 먼저 확인합니다. 새 쓰기가 하나라도 있으면 두
DB를 단순히 역전환하지 말고 데이터 정합 절차를 세웁니다. 새 쓰기가 전혀 없을 때만 DNS 또는
Web의 API URL을 이전 주소로 되돌린 뒤 이전 API를 재시작합니다.

소유한 hostname과 `.env`를 유지하면 Web 설정과 사용자 식별자는 바뀌지 않습니다. IP가 이름에
포함된 `sslip.io` hostname을 썼다면 새 VM IP에 맞춘 hostname으로 API `.env`와 Web의
`JOBVIS_API_BASE_URL`을 함께 바꾸고 Workers를 다시 배포합니다. DB dump만 옮기고 암호화 키를
잃으면 외부 연결 토큰은 복구되지 않습니다.
