# 서버 상태 확인하기

**API가 느리거나 실패했을 때, 같은 시간대의 응답 시간과 서버 상태를 함께 봅니다.**
k6는 API 응답을 기록하고, CloudWatch는 서버 지표와 오류 로그를 보여 줍니다.

이번에 추가한 **dev DB 연결 시간과 Java 메모리 지표는 설정 파일만 준비한 상태**입니다.
AWS 적용과 대시보드 그래프 추가는 아직 하지 않았습니다.

## 1. 어디서 확인하나요?

| 확인할 내용 | 확인할 곳 |
| --- | --- |
| API 응답 시간, p95, p99, RPS, TPS | [k6 결과](../../backend/load-test/README.md#5-결과-확인하기) |
| CPU, 서버 메모리, DB 연결 대기 | [dev CloudWatch 대시보드](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#dashboards/dashboard/DASHBOARD-harudle-dev) |
| 백엔드 오류 | CloudWatch Logs의 `/harudle/dev/backend` |
| Nginx 요청과 오류 | CloudWatch Logs의 `/harudle/dev/frontend-nginx` |
| Agent가 수집한 앱 지표 | CloudWatch Logs의 `/harudle/dev/prometheus-emf` |

prod는 로그 경로의 `dev`를 `prod`로 바꿔 찾습니다.
앱 지표는 `Harudle/Dev` 또는 `Harudle/Prod`, 서버 메모리는 `CWAgent`에서 찾습니다.
RDS 지표는 DB 이름이 아닌 **실제 RDS 인스턴스**를 선택합니다. 같은 RDS를 쓰는 DB는 서버 자원도 함께 씁니다.

## 2. 서버에 설정 적용하기

**이 단계는 나중에 AWS에 적용할 때 실행합니다.** 먼저 dev에서 확인합니다.

1. 접속한 EC2와 `/opt/harudle/.env`의 `DEPLOY_ENV`가 맞는지 확인합니다. dev와 prod의 이미지 경로는 [배포 안내](../README.md#ec2-최초-설정)를 따릅니다.
2. 해당 환경의 위 로그 그룹 3개가 있는지 확인합니다. EC2 역할에는 로그 전송 권한인 `logs:CreateLogStream`, `logs:PutLogEvents`가 필요합니다. 실제 메모리 전송에는 `cloudwatch:PutMetricData`도 필요합니다. 공유 역할은 임의로 바꾸지 않습니다.
3. 기존 Agent 설정을 보관하고 [Agent가 지원하는 OS](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/supported-operating-systems.html)인지 확인합니다. 지표를 읽는 `19091` 포트는 외부에 열지 않습니다.

배포하면 이 폴더의 설정이 `/opt/harudle/monitoring`에 복사됩니다.
**파일 배포와 Agent 적용은 별도입니다.** 먼저 EC2 안에서 백엔드 지표가 나오는지 확인합니다.

```bash
curl --fail --silent --show-error http://127.0.0.1:19091/actuator/prometheus \
  | grep '^# HELP jvm_memory_used_bytes'
```

지표가 나오면 dev 설정을 추가합니다. 기존 설정을 유지하도록 **`append-config`**를 사용합니다.
`fetch-config`로 기존 설정을 덮어쓰지 않습니다.

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a append-config -m ec2 -s \
  -c file:/opt/harudle/monitoring/cloudwatch-agent.dev.json
```

prod에 적용할 때는 파일명만 `cloudwatch-agent.prod.json`으로 바꿉니다. 한 서버에는 해당 환경의 파일만 적용합니다.
새 DB 연결 시간과 Java 메모리 지표는 dev 설정에만 등록했습니다.
다만 공통 파일인 `prometheus.yaml`을 prod에 적용하면 새 앱 지표가 prod의 수집 로그에도 포함될 수 있습니다.

**서버 전체 메모리와 디스크 지표가 없을 때만** 아래 설정을 추가합니다.

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a append-config -m ec2 -s \
  -c file:/opt/harudle/monitoring/cloudwatch-agent.host.json
```

## 3. 적용 후 확인하기

- 해당 환경의 백엔드, Nginx, Agent 로그에 새 기록이 들어오는지 확인합니다.
- 앱 지표와 기존 CPU, 메모리, 디스크 지표가 계속 들어오는지 확인합니다. 서버 지표는 해당 EC2의 `InstanceId`를 선택합니다.
- 새 값이 **1분 간격으로 두 번 이상** 들어온 뒤 테스트를 시작합니다. 첫 수집에는 이전 값이 없어 횟수 증가분이 빠질 수 있습니다.
- 값이 없으면 0으로 기록하지 않습니다. Agent 상태와 권한을 먼저 확인합니다.

수집이 확인되면 CloudWatch의 **지표**에서 그래프를 만들고 대시보드에 추가합니다.
배포만으로 대시보드나 Discord 알림이 바뀌지는 않습니다.

### dev 부하 테스트에서 추가로 볼 값

DB와 힙 지표는 `Harudle/Dev`에서 `job=harudle-backend`를 선택합니다.
DB 지표는 같은 `pool`, 힙은 `area=heap`을 선택합니다. 그래프 기간은 모두 **1분**으로 맞춥니다.
힙은 Java 객체에 쓰는 메모리입니다. 실제 RAM 사용량에는 힙 밖의 메모리도 포함됩니다.

| 볼 값 | 지표 이름 | 읽는 방법 |
| --- | --- | --- |
| DB 연결 평균 시간 | `hikaricp_connections_acquire_seconds_sum`, `hikaricp_connections_acquire_seconds_count` | 둘 다 `Sum`으로 선택하고 시간 합을 횟수로 나눕니다. |
| DB 연결 최근 최대 시간 | `hikaricp_connections_acquire_seconds_max` | `Maximum`으로 봅니다. |
| DB 연결 시간 초과 횟수 | `hikaricp_connections_timeout_total` | `Sum`으로 봅니다. |
| Java 힙 사용량 | `jvm_memory_used_bytes` | 힙의 각 `id`를 `Average`로 선택하고 모두 더합니다. |
| Java 실제 RAM 사용량 | `CWAgent`의 `procstat_memory_rss` | dev EC2의 `InstanceId`와 백엔드 Java의 `pattern`을 골라 `Average`로 봅니다. |

**화면에서 쓰는 계산식은 다음과 같습니다.**

- DB 평균: 시간 합을 `s`, 횟수를 `c`로 정하고 `IF(c > 0, 1000 * s / c)`를 입력합니다. 결과는 밀리초(ms)이며, 시도가 없으면 표시하지 않습니다.
- DB 최대: 선택한 값에 1,000을 곱하면 ms입니다.
- 힙: 현재의 `Eden Space`, `Survivor Space`, `Tenured Gen`을 각각 `h1`, `h2`, `h3`로 정하면 `(h1 + h2 + h3) / 1048576`이 MiB 단위 사용량입니다. 모든 영역의 값이 들어오는지 확인합니다.
- 실제 RAM: 선택한 값을 1,048,576으로 나누면 MiB입니다. 힙과 실제 RAM 사용량은 서로 더하지 않습니다.

DB 시간에는 연결을 받지 못하고 시간 초과로 끝난 대기도 포함됩니다. 시간 초과 횟수는 SQL 오류 등 모든 DB 오류를 세는 값은 아닙니다.
최대 시간은 최근 구간의 값이므로 테스트 직전 대기가 포함될 수 있습니다.
실제 RAM은 `/app/app.jar`를 실행한 Java만 측정하며 Swap은 제외합니다. 1분 사이의 짧은 메모리 증가는 놓칠 수 있습니다.

횟수는 Agent가 이미 증가분으로 바꿔 보냅니다. **CloudWatch에서 `DIFF`를 다시 적용하지 않습니다.**
테스트가 끝난 뒤 마지막 수집까지 기다리고, k6와 같은 시간 범위의 그래프를 보관합니다. 서버 지표는 k6 보고서에 자동 저장되지 않습니다.

## 4. 문제가 있을 때 확인하기

| 보이는 문제 | 먼저 확인할 것 |
| --- | --- |
| API 응답이 느림 | k6의 API별 시간, CPU, Java 메모리, DB 연결 대기를 같은 시간대로 비교합니다. |
| DB 연결 시간 초과가 늘어남 | 연결 대기 수와 RDS 연결 수, CPU를 확인하고 백엔드 오류 로그를 봅니다. |
| 일기 생성이 실패함 | 백엔드 로그에서 생성 실패와 Gemini 오류를 확인합니다. |
| 이미지가 보이지 않음 | S3 조회와 URL 발급 오류를 확인합니다. 로그가 없다고 이미지가 안전하다고 판단하지 않습니다. |
| Discord 알림이 오지 않음 | CloudWatch 경보 상태, SNS 연결, 전달 Lambda의 오류 로그를 확인합니다. |

백엔드 로그는 `traceId`와 `generationId`로 관련 기록을 찾습니다.
일기 원문, 로그인 토큰, 서명 URL은 공유 기록에 넣지 않습니다.
S3 삭제 주체를 찾으려면 별도 삭제 기록이 필요합니다. 현재 지표만으로 모든 이미지의 존재나 삭제 이력을 확인할 수는 없습니다.

## 알람 설계

**아래는 시작 기준입니다. 실제 지표가 들어오는지 확인한 뒤 환경별로 적용합니다.**

| 알림 대상 | 시작 기준 |
| --- | --- |
| API 서버 오류 | 백엔드의 `api_exception` 5xx 로그가 5분에 3건 이상 |
| API 오류 비율 | 5분에 요청 20건 이상이며 5xx 비율이 5% 초과인 상태가 2회 연속 |
| EC2 또는 RDS CPU | 5분 평균이 85% 초과인 상태가 3회 연속 |
| EC2 메모리 | 사용률이 90% 초과인 상태가 5분씩 3회 연속 |
| EC2 디스크 | 사용률이 85% 초과인 상태가 5분씩 2회 연속 |
| RDS 연결 수 | 확인한 최대 연결 수의 80% 초과인 상태가 5분씩 3회 연속 |

새 경보는 알림을 끈 상태로 만들고 로그 집계가 맞는지 확인합니다.
이후 dev에서 알림을 켜고 Discord 수신을 확인합니다.
p95와 p99는 현재 k6에서 확인합니다. 현재 서버 수집 값으로 p95와 p99 경보를 만들지는 않습니다.
알림이 `OK`로 바뀌어도 이전 실패 건의 정리나 이미지 복원이 끝났다는 뜻은 아닙니다.

Discord 전송 코드는 `discord_forwarder.py`입니다. Lambda 코드 배포와 SNS 연결은 EC2 배포와 별도로 진행합니다.
환경별 `DEPLOY_ENV`, `ALARM_TOPIC_ARN`, `WEBHOOK_URL`을 사용하며 Webhook 값은 Git이나 로그에 남기지 않습니다.
정리 실패와 이미지 관련 경보의 설정, 적용 상태는 [추가 관측 운영 안내](generation-observation.md)에서 확인합니다.
과거 AWS 적용 기록과 되돌리는 방법은 [인프라 적용 계획](infrastructure-rollout.md)에 있습니다.

## 문서나 설정을 수정한 뒤 확인하기

저장소 최상위 폴더에서 설정 검사를 실행합니다. AWS에는 접속하지 않습니다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s deploy/monitoring -p 'test_monitoring_config.py' -v
```
