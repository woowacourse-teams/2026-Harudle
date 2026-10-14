# 회원 200명으로 5분 동안 부하 테스트하기

**dev 서버가 회원 200명의 방문을 처리할 수 있는지 확인합니다.**
5분 동안 분당 40명이 방문합니다. 한 회원이 여러 행동을 할 수 있습니다.
요청을 보낼 주소는 `https://dev.harudle.com`입니다.

| 행동 | 회원 수 | API 요청 수 |
| --- | ---: | ---: |
| 일기 생성 후 피드에 게시 | 20명 | 80건 |
| 피드 목록과 상세 보기 | 160명 | 800건 |
| 댓글 작성과 좋아요 누르기 | 60명 | 240건 |
| 이번 달과 지난달 일기 보기 | 100명 | 300건 |
| 합계 | 서로 겹치는 회원 포함 | **1,420건** |

테스트 전용 일기 생성 API는 **30초를 기다린 뒤 준비된 이미지로 일기를 저장**합니다.
이때 실제 AI를 대신하는 코드를 AI Stub이라고 합니다.
일기 저장, 피드 게시, S3 이미지 저장은 실제로 실행합니다.
마지막 요청이 끝날 때까지 기다리므로 전체 실행은 5분보다 조금 길어질 수 있습니다.

## 각 파일이 하는 일

| 파일 | 하는 일 | 우리가 실행하는 시점 |
| --- | --- | --- |
| `prepare.py` | 테스트 회원 정보와 DB에 넣을 SQL 파일 만들기 | 테스트 준비를 시작할 때 |
| `assets.py` | S3에 테스트 이미지를 복사하거나 삭제하기 | 테스트 전에는 `copy`, 정리할 때는 `delete` |
| `runs/실행이름/seed.sql` | 준비한 회원과 일기, 피드 등을 실제 DB에 넣기 | 이미지를 복사한 뒤 `psql`로 실행 |
| `run.sh` | 사용할 회원 파일을 고르고 Docker에서 k6 실행하기 | 데이터 준비가 끝났을 때 |
| `members.js` | 회원별로 로그인하고 일기, 피드, 댓글 API 호출하기 | `run.sh`가 실행한 k6에서 자동으로 실행 |

**`prepare.py`를 실행하면 파일만 만들어집니다.** 계정과 데이터는 `seed.sql`을 실행할 때 dev DB에 들어갑니다.
준비 파일과 실행 결과는 `runs/실행이름/`에 저장합니다. 로그인 토큰이 포함되므로 `runs/` 전체를 Git에서 제외합니다.

## members.js는 어떻게 테스트하나요?

| 코드에서 볼 이름 | 실제로 하는 일 |
| --- | --- |
| `setup()` | 회원 파일을 읽어 준비한 계정으로 로그인합니다. CSRF 토큰과 Bearer 토큰을 받고, 테스트 데이터와 AI Stub이 준비됐는지 확인합니다. |
| `options` | 5분 동안 1분에 40명씩, 총 200명의 방문을 시작하도록 정합니다. `smoke`는 4명만 확인합니다. |
| `memberVisit()` | 회원 한 명에게 정해진 행동을 실행합니다. 예를 들어 일기를 만든 회원은 피드 게시와 자기 기록 조회도 합니다. |
| `request()` | 각 API 요청에 로그인 토큰을 넣고, 성공 여부와 응답 시간을 기록합니다. CSRF 쿠키와 헤더도 함께 보냅니다. |
| `journey()` | 일기 생성 후 게시처럼 여러 요청으로 된 행동을 끝까지 마쳤는지 기록합니다. |

로그인 준비가 끝나면 5분 테스트를 시작합니다. 각 회원은 한 번씩 방문하며, 한 방문에서 여러 행동을 할 수 있습니다.
일기 생성은 30초 AI Stub API를 사용합니다. 이후 피드 게시와 댓글 작성 등은 기존 API로 진행합니다.

## 1. dev 서버에 이 브랜치 배포하기

이 브랜치의 백엔드를 dev에 배포합니다. **DB 구조를 갱신하는 V23 적용까지 끝낸 뒤** 다음 단계로 넘어갑니다.

**서버 환경 설정은 그대로 사용합니다.** `load-test` 프로필을 추가할 필요가 없습니다.
기존 `DEPLOY_ENV=dev` 값을 보고 테스트 API가 자동으로 등록됩니다.

| 호출 주소 | 일기 생성 방식 |
| --- | --- |
| `POST /api/v1/diaries` | 평소처럼 실제 AI 사용 |
| `POST /api/v1/load-test/diaries` | 30초 AI Stub 사용, 실제 일기 저장 |

k6는 일기를 만들 때 테스트 전용 주소를 호출합니다. 피드 게시와 조회는 기존 API를 호출합니다.

## 2. 작업용 터미널 준비하기

Python 3.9 이상, Docker, AWS CLI, PostgreSQL 접속 도구인 `psql`이 필요합니다.
**dev DB에 접속하고 dev S3 파일을 복사할 권한이 있는 터미널**에서 진행합니다.

저장소의 최상위 폴더에서 `backend`로 이동하고, 아래 값을 실제 dev DB 정보로 바꿉니다.

```bash
cd backend
export PGHOST='dev DB 주소'
export PGPORT='5432'
export PGDATABASE='dev DB 이름'
export PGUSER='dev DB 사용자 이름'
```

이후 `psql` 명령을 실행하면 DB 비밀번호를 입력합니다. **DB 주소가 dev인지 확인하세요.**

이미 만들어진 일기 이미지 중 하나를 찾아 테스트용으로 복사합니다. 아래 명령으로 이미지 경로를 조회합니다.

```bash
psql -W -c "SELECT image_object_key FROM diary_generations
WHERE status = 'SUCCEEDED'
  AND image_object_key LIKE 'harudle/generated/diary-images/dev/%/image-960.webp'
ORDER BY completed_at DESC LIMIT 1;"
```

조회된 경로를 아래 따옴표 안에 넣습니다. 같은 S3 폴더에 `image-240.webp`도 있어야 합니다.

```bash
SOURCE_DETAIL_KEY='조회된 image_object_key 값'
```

## 3. 먼저 회원 4명으로 확인하기

아래 명령을 **위에서부터 순서대로 실행**합니다. 앞 명령이 실패하면 오류를 해결한 뒤 진행합니다.

```bash
# 테스트 계정과 데이터 파일 만들기
RUN=smoke
python3 load-test/prepare.py --output "load-test/runs/$RUN"

# 테스트에서 사용할 이미지 복사하기
python3 load-test/assets.py copy --bucket techcourse-project-2026 \
  --keys "load-test/runs/$RUN/image-keys.json" \
  --source-detail-key "$SOURCE_DETAIL_KEY"

# dev DB에 테스트 계정과 데이터 넣기
psql -W -v expected_database="$PGDATABASE" -f "load-test/runs/$RUN/seed.sql"

# 회원 4명으로 실제 API 호출하기
bash load-test/run.sh smoke "$RUN"
```

위 준비 명령을 모두 실행하면 **회원 200명, 일기 400개, 피드 200개와 댓글, 좋아요**가 dev DB에 들어갑니다.
각 회원은 이번 달과 지난달에 일기 1개씩 갖습니다. 여기서는 그중 4명만 사용해 요청 28건을 확인합니다.

카카오 계정을 직접 만들거나 Bearer 토큰을 복사할 필요는 없습니다.
`members.js`가 `members.json`에 있는 Refresh Token으로 Bearer 토큰을 발급받습니다.

## 4. 회원 200명으로 실행하기

4명 테스트가 통과하면 **새 계정과 데이터**로 200명 테스트를 실행합니다.

```bash
RUN=peak
python3 load-test/prepare.py --output "load-test/runs/$RUN"
python3 load-test/assets.py copy --bucket techcourse-project-2026 \
  --keys "load-test/runs/$RUN/image-keys.json" \
  --source-detail-key "$SOURCE_DETAIL_KEY"
psql -W -v expected_database="$PGDATABASE" -f "load-test/runs/$RUN/seed.sql"
bash load-test/run.sh peak "$RUN"
```

마지막 명령의 첫 번째 값 `peak`는 **200명 테스트 방식**, 두 번째 값 `"$RUN"`은 **사용할 데이터 폴더 이름**입니다.
예를 들어 `bash load-test/run.sh peak peak2`는 `runs/peak2/members.json`으로 200명을 테스트합니다.
결과는 같은 폴더의 `summary.json`, `report.html`, `k6.log`에 저장합니다. `members.js`는 따로 실행하지 않아도 됩니다.

**같은 데이터는 한 번만 사용합니다.** 실행하면 로그인 토큰이 바뀌고 새 일기와 반응이 저장되기 때문입니다.
데이터 준비와 테스트 실행은 한국 시간으로 같은 날에 진행합니다.
다시 실행할 때는 첫 줄을 `RUN=peak2`처럼 새 이름으로 바꾸고 이 단계 전체를 반복합니다.

## 5. 결과 확인하기

**결과 화면을 열고, 요청이 모두 끝났는지 확인한 다음, 걸린 시간과 실패 내용을 기록합니다.**

**첫째, 결과 화면을 엽니다.**

테스트가 실행되는 동안 내 컴퓨터의 브라우저에서 <http://localhost:5665>를 엽니다.
테스트가 끝나면 `Report` 메뉴에서 보고서를 봅니다. 확인한 뒤 브라우저 탭을 닫으면 k6가 종료됩니다.
나중에 다시 볼 때는 `load-test/runs/peak/report.html`을 브라우저로 엽니다. 실행 이름이 `peak2`였다면 폴더 이름도 `peak2`입니다.

**둘째, 회원 200명의 테스트가 끝까지 실행됐는지 확인합니다.**

터미널 마지막의 `THRESHOLDS`에서 정해 둔 기준을 통과했는지 봅니다. 아래 표는 200명 테스트 기준입니다.

| 확인할 내용 | 결과에서 찾을 이름 | 기대하는 결과 |
| --- | --- | --- |
| 회원이 모두 방문했나요? | `member_visits` | 200회 |
| API 요청을 모두 보냈나요? | `business_requests` | 1,420건 |
| 정해진 행동을 모두 마쳤나요? | `completed_journeys`, `journey_success` | 340회, 성공률 100% |
| 실패한 요청이 있나요? | `business_failures` | 0% 목표, 통과 기준은 1% 미만 |
| 시작하지 못한 방문이 있나요? | `dropped_iterations` | 0회 |
| 일반 API가 빨리 응답했나요? | `ordinary_api_duration`의 `p(95)` | 1초 미만 |
| 일기 생성이 제시간에 끝났나요? | `generation_duration`의 `p(95)` | 45초 미만 |

한 회원이 여러 행동을 하므로 회원 수는 200명, 행동 수는 340회입니다.
전체 HTTP 요청 수에는 로그인 준비도 포함됩니다. 1,420건인지는 `business_requests`로 확인합니다.

**셋째, 응답 시간과 처리량을 기록합니다.**

- **응답 시간:** API별 평균, p50, p95, p99, 최대값과 요청 수를 적습니다. `p95=1초`라면 응답의 95%가 1초 안에 끝났다는 뜻입니다. p50은 50%, p99는 99% 기준입니다.
- **RPS:** 1초에 응답을 받은 API 요청 수입니다. `business_requests`의 `rate`를 적습니다. 실패한 요청도 포함됩니다.
- **TPS:** 1초에 끝까지 성공한 회원 행동 수입니다. `completed_journeys`의 `rate`를 적습니다. 일기 생성 후 게시 1회는 API 요청 4건, 성공한 행동 1건으로 셉니다.

일반 API와 30초를 기다리는 생성 API는 따로 봅니다. 생성 요청은 20건뿐이라 p99가 한두 건에 크게 달라질 수 있습니다.
결과를 비교할 때는 같은 시간 범위를 사용합니다. 요약의 초당 처리량에는 로그인 준비와 마지막 요청을 기다린 시간이 영향을 줍니다.
숫자를 자세히 보려면 같은 폴더의 `summary.json`을 엽니다. 이 파일의 시간 단위는 ms이며, `1000ms`가 1초입니다.

**넷째, 결과와 문제를 함께 남깁니다.**

실행 시각, 배포 커밋, 서버 사양, 데이터 수와 위에서 확인한 숫자를 적습니다.
`summary.json`, `report.html`, `k6.log`도 함께 보관합니다.

실패가 있으면 `k6.log`에서 실패한 API와 오류 코드를 찾습니다.
응답이 느리면 같은 시간대의 [dev CloudWatch 대시보드](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#dashboards/dashboard/DASHBOARD-harudle-dev)에서 CPU, 메모리, DB 연결 대기를 확인합니다.
확인한 원인과 다음에 확인할 내용을 짧게 적습니다. 아직 모르는 원인은 `미확인`으로 남깁니다.

## 6. 테스트 데이터 지우기

결과를 확인한 뒤 아래 명령으로 이번 테스트의 회원, 연결된 데이터, 이미지 파일을 지웁니다.
`RUN`에는 정리할 실행 이름을 넣습니다. `peak`를 정리한 뒤 `smoke`도 같은 방법으로 정리합니다.

```bash
RUN=peak
(cd "load-test/runs/$RUN" && psql -W -v expected_database="$PGDATABASE" -f cleanup.sql)
python3 load-test/assets.py delete --bucket techcourse-project-2026 \
  --keys "load-test/runs/$RUN/cleanup-image-keys.txt"
```

이미지 삭제에 실패하면 `cleanup-image-keys.txt`를 보관하고 이미지 삭제 명령만 다시 실행합니다.
삭제할 이미지 목록이 덮어써질 수 있으므로 `cleanup.sql`은 다시 실행하지 않습니다.
R2 백업을 실행했다면 해당 테스트 이미지의 백업도 별도로 정리합니다.

---

2026-10-14 로컬 검증에서는 회원 200명과 요청 1,420건이 실패 없이 완료됐습니다.
로컬 검증에서는 이미지 저장소를 대체했습니다. **dev 배포와 실제 dev 부하 테스트는 아직 진행하지 않았습니다.**
