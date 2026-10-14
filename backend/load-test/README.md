# 회원 200명으로 5분 동안 부하 테스트하기

**dev 서버에 5분 동안 회원 200명이 방문하도록 요청을 보냅니다.**
1분에 40명씩 방문하며, 한 회원이 여러 행동을 할 수 있습니다. 게스트는 포함하지 않습니다.

| 행동 | 회원 수 | API 요청 수 |
| --- | ---: | ---: |
| 일기 생성 후 피드 게시 | 20명 | 80건 |
| 피드 목록과 상세 보기 | 160명 | 800건 |
| 댓글 작성과 좋아요 누르기 | 60명 | 240건 |
| 이번 달과 지난달 일기 보기 | 100명 | 300건 |
| 합계 | 같은 회원이 여러 행동 수행 | **1,420건** |

일기는 실제 AI 대신 **30초를 기다리는 AI Stub**으로 만듭니다.
일기와 피드, S3 이미지는 실제로 저장하며, R2 백업은 실행하지 않습니다.
마지막 요청까지 기다리므로 전체 실행 시간은 5분보다 조금 길 수 있습니다.

## 1. 실행 전에 준비하기

- 이 브랜치의 백엔드를 dev에 배포하고 DB 변경인 **V23 적용**을 마칩니다.
- Python 3.9 이상, Docker, AWS CLI v2, `psql`을 준비합니다. macOS 또는 Linux에서 실행합니다.
- dev DB 접속 권한과 테스트용 S3 이미지의 업로드, 삭제 권한을 확인합니다.
- **dev와 prod가 같은 RDS 서버를 쓰는지 확인합니다.** DB 이름이 달라도 서버가 같으면 운영 서비스에 부하가 생길 수 있습니다.

기존 `DEPLOY_ENV=dev` 설정을 사용합니다. `load-test` 프로필은 추가하지 않습니다.
이미지 저장 기능이 켜져 있어야 테스트 API가 등록됩니다.

| 일기 생성 주소 | 동작 |
| --- | --- |
| `POST /api/v1/diaries` | 실제 AI 사용 |
| `POST /api/v1/load-test/diaries` | 준비한 테스트 계정으로 30초 AI Stub 사용 |

k6는 테스트용 생성 주소를 사용합니다. 피드, 댓글, 좋아요는 기존 API로 요청합니다.

## 2. dev DB 연결 정보 넣기

아래 명령은 저장소 최상위 폴더에서 시작합니다. 값을 실제 dev DB 정보로 바꿉니다.

```bash
cd backend
export PGHOST='dev DB 주소'
export PGPORT='5432'
export PGDATABASE='harudle_dev'
export PGUSER='dev DB 사용자 이름'
```

비밀번호를 요청하면 입력합니다. DB에 데이터를 넣거나 지우는 작업은 `harudle_dev`에서만 실행됩니다.

이미지는 저장소의 `src/main/resources/load-test-diary.png`를 사용합니다. 실제 회원의 이미지는 사용하지 않습니다.
S3 작업 범위는 `techcourse-project-2026` 버킷의 `harudle/generated/diary-images/dev/` 아래입니다.
실행마다 다른 경로를 사용하며, 같은 경로에 이미지가 있으면 덮어쓰지 않고 멈춥니다.

## 3. 먼저 회원 4명으로 확인하기

**아래 명령을 순서대로 실행합니다. 실패하면 다음 단계로 진행하지 않습니다.**

```bash
RUN=smoke

# 테스트 회원 정보와 SQL 파일 만들기
python3 load-test/prepare.py --output "load-test/runs/$RUN" &&

# 테스트 이미지 올리기
python3 load-test/assets.py upload --run-dir "load-test/runs/$RUN" &&

# dev DB에 테스트 데이터 넣기
psql -W -f "load-test/runs/$RUN/seed.sql" &&

# 회원 4명으로 요청 보내기
bash load-test/run.sh smoke "$RUN"
```

준비 단계에서 **회원 200명, 일기 400개, 피드 200개와 댓글, 좋아요**를 만듭니다.
이미지도 400개 올립니다. 여기서는 준비한 회원 중 4명으로 요청 28건을 확인합니다.

`prepare.py`는 파일만 만듭니다. DB에 데이터가 들어가는 시점은 `psql` 실행 때입니다.
준비 중 실패했다면 6번의 정리 명령을 실행한 뒤 새 이름으로 다시 준비합니다.

## 4. 회원 200명으로 실행하기

4명 테스트가 통과하면 새 데이터로 실행합니다. 요청 주소는 `https://dev.harudle.com`입니다.

```bash
RUN=peak
python3 load-test/prepare.py --output "load-test/runs/$RUN" &&
python3 load-test/assets.py upload --run-dir "load-test/runs/$RUN" &&
psql -W -f "load-test/runs/$RUN/seed.sql" &&
bash load-test/run.sh peak "$RUN"
```

마지막 명령의 `peak`는 200명 테스트를 뜻하고, `"$RUN"`은 데이터 폴더 이름입니다.
다시 실행할 때는 `RUN=peak2`처럼 새 이름으로 바꾸고 위 명령을 모두 실행합니다.
**같은 데이터는 한 번만 사용하고, 준비와 실행은 한국 시간으로 같은 날에 진행합니다.**

`run.sh`가 Docker에서 k6와 `members.js`를 실행합니다.
`members.js`는 로그인 준비를 마친 뒤 회원별 API를 호출하고 결과를 기록합니다.
CSRF와 Bearer 토큰은 자동으로 처리하므로 카카오 계정을 만들거나 토큰을 복사할 필요가 없습니다.

| 파일 | 하는 일 |
| --- | --- |
| `members.json` | 테스트 회원과 로그인에 쓸 Refresh Token 보관 |
| `seed.sql` | DB에 테스트 데이터 넣기 |
| `summary.json`, `report.html`, `k6.log` | 결과 숫자, 보고서, 실행 로그 보관 |
| `cleanup.json` | 이번 실행에서 지울 이미지 목록 보관 |

이 파일들은 `load-test/runs/실행이름/`에 생깁니다. 로그인 정보가 있으므로 **`runs/` 전체를 Git에서 제외합니다.**
시작 30초 뒤부터 누적 API 실패율이 1% 이상이면 테스트가 자동으로 중단됩니다.

## 5. 결과 확인하기

### 결과 화면 열기

실행 중에는 브라우저에서 <http://localhost:5665>를 엽니다.
테스트가 끝나면 `Report` 메뉴에서 확인하고 탭을 닫습니다. 탭을 닫아야 k6가 종료됩니다.
나중에는 `load-test/runs/peak/report.html`을 엽니다. 실행 이름이 다르면 폴더 이름도 바꿉니다.

### 통과했는지 확인하기

터미널 마지막의 `THRESHOLDS`와 아래 값을 확인합니다. **200명 테스트 기준**입니다.

| 확인할 값 | 결과에서 찾을 이름 | 기준 |
| --- | --- | --- |
| 방문 수 | `member_visits` | 200회 |
| API 요청 수 | `business_requests` | 1,420건 |
| 끝까지 성공한 행동 | `completed_journeys`, `journey_success` | 340회, 100% |
| 실패한 API 비율 | `business_failures` | 1% 미만, 목표는 0% |
| 시작하지 못한 방문 | `dropped_iterations` | 0회 |
| 일반 API 응답 시간 | `ordinary_api_duration`의 `p(95)` | 1초 미만 |
| 일기 생성 시간 | `generation_duration`의 `p(95)` | 45초 미만 |

전체 HTTP 요청 수에는 로그인 준비도 포함됩니다. 1,420건인지는 `business_requests`로 확인합니다.

### 결과 기록하기

- **응답 시간:** 평균, p50, p95, p99, 최댓값과 요청 수를 적습니다. p95가 1초이면 응답의 95%가 1초 안에 끝났다는 뜻입니다. p50은 50%, p99는 99% 기준입니다.
- **RPS:** 1초에 응답을 받은 API 요청 수입니다. `business_requests`의 `rate`이며 실패도 포함합니다.
- **TPS:** 1초에 끝까지 성공한 행동 수입니다. `completed_journeys`의 `rate`입니다. 일기 생성 후 피드 게시를 마치면 요청 4건, 행동 1회로 셉니다.

일반 API와 30초 생성 API는 따로 기록합니다. 생성 요청은 20건이라 p99가 한두 건에 크게 달라질 수 있습니다.
`summary.json`의 시간 단위는 ms이며, 1,000ms가 1초입니다. 초당 처리량에는 로그인 준비와 마지막 요청 대기 시간도 영향을 줍니다.

실행 시각, 배포 커밋, 서버 사양, 데이터 수를 적고 결과 파일 3개도 보관합니다.
실패한 API는 `k6.log`에서 확인합니다. 느린 원인은 같은 시간대의 [dev CloudWatch 대시보드](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#dashboards/dashboard/DASHBOARD-harudle-dev)에서 CPU, 메모리, DB 연결 대기와 함께 확인합니다.

**DB 연결 시간과 Java 메모리의 새 지표는 아직 AWS에 적용하지 않았습니다.**
적용 후에는 [모니터링 안내](../../deploy/monitoring/README.md#dev-부하-테스트에서-추가로-볼-값)에 따라 확인합니다. 서버 지표는 k6 보고서에 저장되지 않으므로 그래프를 따로 보관합니다.

## 6. 테스트 데이터 지우기

**테스트와 서버의 생성 작업이 끝나고, 보고서 탭까지 닫은 뒤 실행합니다.**
`RUN`에 정리할 이름을 넣습니다. `peak`를 정리한 뒤 `smoke`도 같은 방법으로 정리합니다.

```bash
RUN=peak
python3 load-test/cleanup.py --run-dir "load-test/runs/$RUN"
```

이번 실행의 회원, 일기, 피드, 댓글, 좋아요와 관련 알림을 지웁니다. **DB 정리가 성공한 뒤 이번 테스트의 S3 이미지만 삭제합니다.**
DB 오류가 나거나 생성 중인 일기가 있으면 이미지 삭제 없이 멈춥니다. 준비 중 일부만 올린 이미지도 정리합니다.

이미지 삭제에 실패하면 같은 명령을 다시 실행합니다.
`members.json`과 `cleanup.json`은 정리가 끝날 때까지 보관하고 직접 수정하지 않습니다.
S3의 이전 버전은 남아 저장 비용이 생길 수 있습니다. 이전 코드로 만든 실행 폴더는 거부하므로 현재 `prepare.py`로 준비합니다.

**검증 상태:** 로컬에서 200명 실행과 데이터 정리를 확인했습니다. 로컬 검증은 이미지 저장소를 대체했으며, 실제 dev 부하 테스트는 아직 진행하지 않았습니다.
