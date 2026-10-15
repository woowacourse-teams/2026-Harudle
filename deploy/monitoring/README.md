# 서버 모니터링

**dev와 prod에서 API 응답과 오류, EC2 서버 자원, RDS 자원, Java 메모리를 함께 확인합니다.** dev는 부하테스트 원인 분석을 위해 GC와 힙 외 메모리도 수집합니다.

**처음에는 배포, Agent 설정 적용, 로그 필터 6개 등록, 대시보드 저장을 모두 해야 합니다.** 대시보드 JSON만 넣으면 새 지표가 수집되지 않습니다. 같은 설정을 쓰는 이후 배포에서는 이 작업을 반복하지 않습니다. 수집 설정이나 서버가 바뀌면 해당 부분을 다시 적용합니다.

## 무엇을 볼 수 있나요?

| 그래프 | 확인하는 내용 |
|---|---|
| API 평균, P95, P99 | 요청을 처리하는 데 걸린 시간. 전체 API, 일반 API, 일기 생성을 구분합니다. |
| RPS | 1초에 처리를 끝낸 API 요청 수입니다. 실패 응답도 포함합니다. |
| 작업별 TPS | 일기 생성, 피드 게시, 피드 목록 조회, 피드 상세 조회가 1초에 몇 건 성공했는지 보여줍니다. |
| EC2 서버 자원 | 서버 CPU, 전체 메모리, 디스크 사용량과 서버 상태를 확인합니다. |
| RDS 자원 | DB 서버의 CPU, 남은 메모리와 저장 공간, 연결 수, 디스크 처리 시간을 확인합니다. |
| DB 연결 획득 시간 | DB 연결을 얻기까지 걸린 평균 시간과 최근 최대 시간입니다. |
| DB 연결 대기와 시간 초과 | 현재 기다리는 요청 수와 제한 시간 안에 연결을 얻지 못한 횟수입니다. |
| Java 힙 | Java가 객체를 저장하는 메모리의 사용량입니다. |
| Java 실제 메모리 RSS | Java 프로그램이 실제 RAM에서 사용하는 양입니다. 힙 밖의 메모리도 포함합니다. |
| dev GC 정지 시간과 횟수 | 메모리 정리로 멈춘 시간과 횟수입니다. 발생 사유는 GC 로그와 함께 확인합니다. |
| dev Java 힙과 힙 외 메모리 | 객체와 클래스 정보 등에 사용하는 메모리의 사용량과 확보량입니다. |

P95가 500ms라면 요청의 약 95%가 500ms 안에 끝났다는 뜻입니다. P99도 같은 방식으로 99%를 봅니다. 요청이 적을 때는 값이 크게 바뀔 수 있으므로 함께 표시되는 요청 수도 확인합니다.

API 시간은 서버의 처리 시간입니다. 사용자와 서버 사이의 통신 시간은 포함하지 않습니다. 자동 상태 확인과 지표 수집 요청도 새 API 그래프에서 제외합니다.

일기 생성과 피드 게시는 **DB 저장이 확정된 뒤** 성공으로 셉니다. 중복 생성 결과의 재조회와 취소된 DB 저장은 성공 횟수를 늘리지 않습니다. 피드 조회는 200 응답을 성공으로 셉니다. 같은 목록을 두 번 조회하면 두 건입니다.

## 서버 자원은 어디서 확인하나요?

CloudWatch의 `지표` 화면에서 아래 지표 그룹을 선택합니다. EC2는 해당 환경의 실제 `InstanceId`, RDS는 실제 `DBInstanceIdentifier`로 찾습니다. 대시보드에 없는 그래프는 여기서 선택해 해당 환경의 대시보드에 추가합니다.

| 확인할 값 | 지표 그룹과 이름 | 살펴볼 내용 |
|---|---|---|
| EC2 CPU | `AWS/EC2`의 `CPUUtilization` | CPU 사용률이 높게 유지되는지 봅니다. |
| EC2 상태 | `AWS/EC2`의 `StatusCheckFailed` | 0은 검사 통과, 1은 검사 실패입니다. |
| EC2 전체 메모리 | `CWAgent`의 `mem_used_percent` | 서버 전체의 메모리 사용률입니다. Java 외의 프로그램도 포함합니다. |
| EC2 디스크 | `CWAgent`의 `disk_used_percent` | 파일을 저장할 공간이 부족한지 봅니다. 현재 설정은 `path=/`을 수집합니다. |
| RDS CPU | `AWS/RDS`의 `CPUUtilization` | DB 서버의 CPU 사용률이 높게 유지되는지 봅니다. |
| RDS 남은 메모리와 저장 공간 | `AWS/RDS`의 `FreeableMemory`, `FreeStorageSpace` | 값이 계속 줄거나 부족해지는지 봅니다. 단위는 바이트입니다. |
| RDS 연결 수 | `AWS/RDS`의 `DatabaseConnections` | DB 서버에 연결된 수입니다. 앱에서 연결을 기다리는 요청 수와는 다릅니다. |
| RDS 디스크 처리 | `AWS/RDS`의 `ReadLatency`, `WriteLatency`, `DiskQueueDepth` | 읽기와 쓰기 시간, 디스크 작업 대기가 늘어나는지 봅니다. SQL 실행 시간 자체는 아닙니다. |

처음에는 같은 시간 범위와 5분 간격으로 비교합니다. CPU는 평균과 최대, 남은 공간은 최소값도 확인합니다. EC2 CPU의 기본 수집 간격은 5분이며, 상세 모니터링을 켠 경우에는 1분입니다. 서버 메모리와 디스크는 아래 Agent 설정으로 1분마다 수집합니다.

T 계열 서버는 `CPUCreditBalance`도 확인합니다. 값이 줄면 기본 성능보다 높은 CPU 성능을 사용할 수 있는 양이 줄었다는 뜻입니다. Unlimited 모드는 잔여량이 0이어도 계속 사용할 수 있지만 추가 비용이 생길 수 있으므로, 0만 보고 성능 제한으로 단정하지 않습니다.

**같은 RDS를 공유하면 그 자원 지표도 함께 사용한 결과입니다.** `harudle_dev`와 `harudle_prod`처럼 DB 이름이 달라도 RDS CPU와 메모리가 따로 집계되지는 않습니다.

지표의 자세한 뜻은 [EC2 공식 안내](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/viewing_metrics_with_cloudwatch.html), [Agent 공식 안내](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/metrics-collected-by-CloudWatch-agent.html), [RDS 공식 안내](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-metrics.html)를 참고합니다.

## 1. 백엔드를 배포하고 서버 수집을 적용합니다

먼저 dev에 배포하고 확인한 뒤 prod에도 적용합니다. 배포는 설정 파일을 EC2의 `/opt/harudle/monitoring`에 복사합니다. **현재 배포 스크립트는 Agent에 새 설정을 적용하지 않으므로 아래 명령을 별도로 실행해야 합니다.**

각 EC2에서 아래 명령을 실행합니다. prod에서는 첫 줄을 `MONITORING_ENV=prod`로 바꿉니다.

```bash
MONITORING_ENV=dev
curl --fail --silent --show-error http://127.0.0.1:19091/actuator/prometheus >/dev/null &&
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a append-config -m ec2 -s \
  -c "file:/opt/harudle/monitoring/cloudwatch-agent.${MONITORING_ENV}.json"
```

기존 Agent가 설치되어 있고 로그와 지표를 보낼 권한이 있어야 합니다. 새 Agent를 설치하지 않고 기존 Agent의 설정을 갱신합니다. 적용 전 활성 설정을 보관합니다. `append-config`는 기존 서버 자원 수집을 유지하면서 새 설정을 적용하고 Agent를 재시작합니다. 같은 서버에 dev와 prod 파일을 모두 적용하지 않습니다. 명령이 실패하면 오류를 확인한 뒤 다음 단계로 넘어갑니다.

**EC2 전체 메모리와 디스크 지표가 아직 없을 때만** 같은 EC2에서 아래 설정도 추가합니다. `cloudwatch-agent.host.json`은 dev와 prod가 공통으로 사용하며, 이미 같은 지표를 수집하고 있다면 추가하지 않습니다. EC2 CPU와 위 표의 RDS 지표는 AWS가 제공하므로 이 파일로 수집하지 않습니다.

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a append-config -m ec2 -s \
  -c file:/opt/harudle/monitoring/cloudwatch-agent.host.json
```

지표 포트 `19091`은 서버 안에서만 접근해야 합니다. JSON 백엔드 로그는 기존 Docker 설정이 `/harudle/dev/backend` 또는 `/harudle/prod/backend`로 보냅니다.

## 2. 내 컴퓨터에서 대시보드 파일을 만듭니다

**이 단계는 배포 전에도 할 수 있습니다.** 내 컴퓨터에 Python 3만 있으면 됩니다. AWS CLI는 필요하지 않습니다. 저장소 최상위 경로에서 아래 명령을 실행합니다. EC2 ID와 RDS 이름은 콘솔에서 확인한 실제 값으로 바꿉니다. prod를 준비할 때는 환경도 `prod`로 바꿉니다.

```bash
MONITORING_ENV=dev
MONITORING_EC2_ID='실제 EC2 InstanceId'
MONITORING_RDS_ID='harudle-db'
MONITORING_DIR="$HOME/Downloads/harudle-monitoring-${MONITORING_ENV}"
mkdir -p "$MONITORING_DIR"
```

CloudWatch에서 해당 환경의 기존 대시보드를 열고 **Actions > View/edit source**를 선택합니다. JSON 전체를 복사해 위 폴더의 `before.json`으로 저장합니다. dev는 `DASHBOARD-harudle-dev`, prod는 `DASHBOARD-harudle-prod`를 사용합니다. 복사한 JSON에는 대시보드 이름이 없으므로 **환경이 맞는지 직접 확인해야 합니다.**

같은 터미널에서 아래 명령을 실행합니다.

```bash
python3 deploy/monitoring/prepare_dashboard.py \
  --environment "$MONITORING_ENV" \
  --instance-id "$MONITORING_EC2_ID" \
  --rds-instance-id "$MONITORING_RDS_ID" \
  --dashboard "$MONITORING_DIR/before.json" \
  --output "$MONITORING_DIR"
```

Python 스크립트는 AWS에 접속하지 않고 **내 컴퓨터에 파일만 만듭니다.**

- `before.json`: 직접 저장한 변경 전 대시보드입니다. 스크립트가 수정하지 않습니다. 적용 확인이 끝날 때까지 보관합니다.
- `dashboard.json`: 기존 그래프 아래에 dev는 11개, prod는 9개 그래프를 추가한 내용입니다.
- `metric-filter-1.json`부터 `metric-filter-6.json`: API 시간과 성공 횟수를 로그에서 읽는 설정입니다.

기존 그래프의 임시 EC2 ID `i-00000000000000000`과 RDS 이름 `harudle-dev`, `harudle-prod`는 입력한 실제 값으로 고칩니다. 다른 대상 값은 유지합니다. 같은 파일을 다시 만들 때 이번에 추가한 그래프가 중복되지 않습니다.

공통 그래프 9개는 API, DB 연결, Java 메모리용입니다. dev에는 GC와 힙 외 메모리 그래프 2개도 추가합니다. EC2와 RDS 자원 그래프는 기존 것을 사용하며, 없는 항목은 위 표에 따라 추가합니다. 대상 ID를 고쳐도 지표 그룹이나 이름이 다르면 값이 나오지 않으므로 함께 확인합니다.

## 3. 콘솔에서 로그 필터 6개를 등록합니다

로그 필터는 백엔드 로그의 응답 시간과 성공 횟수를 CloudWatch 지표로 저장합니다. **콘솔에서 필터와 대시보드를 수정할 권한이 있는 계정**으로 진행합니다. 리전은 서울 `ap-northeast-2`입니다.

1. `metric-filter-1.json`을 엽니다.
2. CloudWatch의 **Logs > Log groups**에서 파일의 `logGroupName`과 같은 로그 그룹을 엽니다.
3. **Actions > Create metric filter**를 선택하고 아래 표대로 입력해 저장합니다.
4. `metric-filter-6.json`까지 같은 방법으로 등록합니다. 같은 `filterName`이 이미 있으면 해당 필터를 수정합니다.

| 콘솔 입력란 | JSON에서 복사할 값 |
|---|---|
| Filter pattern | `filterPattern` |
| Filter name | `filterName` |
| Metric namespace | `metricNamespace` |
| Metric name | `metricName` |
| Metric value | `metricValue` |
| Unit | `unit` |
| Default value | `defaultValue`가 있을 때만 입력합니다. 없으면 비웁니다. |
| Dimensions | `dimensions`가 있을 때만 이름과 값을 입력합니다. |

`metricNamespace`부터는 `metricTransformations` 안의 첫 번째 항목에서 찾습니다. 각 입력란에 JSON의 값만 옮깁니다. 예를 들어 `"{ $.event = \"feed_publication_succeeded\" }"`는 `{ $.event = "feed_publication_succeeded" }`로 입력합니다.

**1번과 2번의 Default value는 비웁니다.** 2번만 Dimensions에 이름 `ApiGroup`, 값 `$.apiGroup`을 넣습니다. 3번부터 6번은 Metric value가 `1`, Default value가 `0`, Unit이 `Count`입니다.

필터는 등록 이후 들어오는 로그부터 집계합니다. 로그와 사용자 정의 지표에는 CloudWatch 비용이 발생합니다. 화면 설명은 [AWS 로그 필터 등록 안내](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CreateMetricFilterProcedure.html)를 참고합니다.

## 4. 완성된 대시보드 JSON을 저장합니다

1. 2단계에서 복사한 **같은 환경의 대시보드**를 엽니다.
2. **Actions > View/edit source**에서 전체 내용을 생성된 `dashboard.json`의 내용으로 바꿉니다.
3. 변경 내용을 적용하고 대시보드를 저장합니다. 기존 그래프 아래에 dev는 11개, prod는 9개의 생성 대상 그래프가 있는지 확인합니다. 이전에 적용한 그래프는 중복 추가되지 않습니다.

`dashboard.json`에는 기존 그래프도 들어 있습니다. 복사한 뒤 다른 사람이 대시보드를 수정했다면 최신 JSON으로 2단계부터 다시 만듭니다. 대시보드도 배포 전에 저장할 수 있지만, 새 코드와 수집 설정이 적용되고 데이터가 들어와야 새 그래프에 값이 나옵니다.

## 5. 적용 후 확인합니다

1. 해당 환경에서 피드 목록과 상세 화면을 엽니다.
2. CloudWatch Logs의 백엔드 로그에서 `event = api_request_completed`를 찾습니다. `durationMs`가 숫자이고 `httpStatus`가 실제 응답과 같은지 확인합니다.
3. CloudWatch의 `DASHBOARD-harudle-dev` 또는 `DASHBOARD-harudle-prod`에서 최근 시간을 선택합니다. 몇 분 뒤 API 시간, RPS, 조회 TPS에 값이 나오는지 확인합니다.
4. DB와 Java 지표는 dev는 10초, prod는 1분마다 수집합니다. dev는 `prometheus.dev.yaml`, prod는 `prometheus.yaml`을 사용합니다. 해당 환경의 `prometheus-emf` 로그와 `Harudle/Dev` 또는 `Harudle/Prod` 지표에 새 값이 들어오는지 확인합니다. RSS도 dev는 10초, prod는 1분 간격이며 `CWAgent`에서 해당 EC2와 Java 실행 조건으로 찾습니다. 대시보드 집계 간격은 1분입니다.
5. 일기 생성과 피드 게시가 실제 성공한 시각에 해당 TPS가 기록되는지 확인합니다.
6. EC2 CPU, 전체 메모리, 디스크와 RDS CPU, 남은 메모리, 연결 수에 새 값이 들어오는지 확인합니다. 디스크는 실제 `path`와 `fstype`도 맞춰 선택합니다. 기존 오류 그래프도 계속 들어와야 합니다.

DB 연결 평균은 `연결에 쓴 시간의 합 / 연결 횟수`입니다. 최근 최대는 Java가 보관하는 최근 최대값이며, 선택한 전체 기간의 모든 연결 시간을 직접 보관한 값은 아닙니다. 연결 시간 초과 횟수는 모든 DB 오류를 뜻하지 않습니다.

현재 설정의 연결 풀 이름은 `HikariPool-1`입니다. 풀 이름을 바꾸거나 서버를 여러 대로 늘리면 수집 차원과 그래프도 함께 검토합니다.

## dev 부하테스트에서 GC 원인을 확인합니다

**첫 비교에서는 GC 종류와 힙 크기를 유지합니다.** dev Compose는 기존 Java 옵션에 `-Xlog:gc*,safepoint:stdout:time,uptime,level,tags`만 추가합니다. 배포로 Java가 재시작되므로 재시작 시각과 테스트 전 사전 요청 조건도 기록합니다.

1. dev에 배포하고 위 1단계의 `MONITORING_ENV=dev` Agent 명령을 적용합니다. 이 명령은 자동 배포에 포함되지 않습니다. 기존 Agent 설정을 보관한 뒤 적용하고 Agent가 정상 실행되는지 확인합니다.
2. `/actuator/prometheus`에서 `jvm_gc_pause_seconds_{count,sum,max}`와 `jvm_memory_{used,committed,max}_bytes`의 실제 이름과 타입을 확인합니다. GC 지표는 GC가 발생한 뒤 생길 수 있습니다. dev의 `prometheus-emf`에도 새 값이 들어와야 합니다.
3. 기존 dev 대시보드의 최신 JSON으로 위 2단계를 다시 실행하고 `dashboard.json`을 적용합니다. 기존 로그 필터 6개는 변경하지 않습니다.
4. 새 실행 이름으로 데이터를 준비한 뒤 아래 OS 기록을 시작하고, 내 컴퓨터에서 같은 실행 이름으로 k6를 실행합니다. 예시는 `peak2` 데이터 준비가 끝나 해당 폴더가 존재하는 경우입니다.

**EC2의 Session Manager 터미널에서 실행합니다.** `vmstat`은 CPU, 사용 가능한 메모리와 swap 입출력을 1초마다 약 10분 동안 기록합니다. 실행 중에는 이 EC2 터미널을 열어 두고, 별도로 내 컴퓨터에서 k6를 실행합니다. 이미 같은 기록 파일이 있으면 덮어쓰지 않고 멈춥니다.

```bash
sudo sh -c 'umask 077; set -C; TZ=UTC vmstat -w -t 1 601 > /opt/harudle/load-test/runs/peak2/vmstat.log'
```

첫 데이터 행은 부팅 이후 평균이므로 비교에서 제외합니다. 이후 행의 `si/so`는 swap 읽기와 쓰기, `us/sy/wa/st`는 CPU 사용자 작업, 운영체제 작업, 입출력 대기, 가상 CPU 대기 비율입니다. 이 값들은 호스트 전체 값이며 Java만의 디스크 대기를 직접 증명하지는 않습니다. 호스트 Agent 설정이나 swap 동작은 이 명령으로 변경하지 않습니다.

5. 일반 API가 1초 이상 걸리면 로컬 `k6.log`에서 `traceId`를 찾고, `/harudle/dev/backend`에서 같은 ID의 `api_request_completed`를 검색합니다. 서버가 생성한 ID는 `X-Trace-Id` 응답 헤더로 전달됩니다. ID는 로그에만 쓰며 지표 차원으로 추가하지 않습니다.
6. 같은 시간 범위의 GC 로그와 `vmstat.log`를 비교합니다. CloudWatch Logs Insights에서는 아래처럼 GC 사유, CPU 시간, 정지 시간을 확인할 수 있습니다. GC 로그는 JSON이 아닌 JVM 표준 출력이므로 기존 API JSON 지표 필터로 집계하지 않습니다.

```text
fields @timestamp, @message
| filter @message like /Pause Full|gc,cpu|safepoint/
| sort @timestamp asc
```

GC의 `User/Sys`는 CPU 시간, `Real`은 실제 경과 시간입니다. `Real`이 길다는 사실만으로 swap이 원인이라고 확정하지 않고 같은 시각의 OS 기록을 함께 봅니다. `jvm_gc_pause_seconds_max`는 Java가 유지하는 최근 최대값이며 정확한 이벤트 시각과 발생 사유는 GC 로그로 확인합니다. 메모리 비교 그래프는 1분 평균이며 `max_bytes=-1`은 해당 메모리 영역의 상한이 정해지지 않았다는 뜻입니다.

GC의 count와 sum은 Agent가 누적값을 수집 간 증가분으로 변환하므로 CloudWatch에서는 `Sum`을 사용합니다. `DIFF/RATE`를 다시 적용하거나 sum/count에 P95를 적용하지 않습니다. **각 시계열의 첫 수집값은 증가분으로 계산되지 않으므로 첫 GC가 지표에서 빠질 수 있습니다. 전체 GC 확인에는 시작부터 남긴 JVM 로그를 사용합니다.** 10초 수집이 1초 단위 CloudWatch 그래프나 정지 중 응답을 보장하지는 않습니다.

테스트 종료 후 로컬 `summary.json`, `report.html`, `k6.log`와 EC2의 `vmstat.log`, 같은 구간의 CloudWatch 로그와 지표를 보관합니다. 최종 요약이 저장되기 전에 k6를 강제 종료하지 않습니다. 같은 조건에서 긴 지연이 재현되는지 먼저 확인한 뒤 메모리 설정이나 GC 종류를 하나씩 바꿔 비교합니다.

## 느리거나 값이 없으면 어떻게 하나요?

- **API가 느립니다:** 일반 조회와 일기 생성을 나누고, 같은 시각의 EC2 CPU, 전체 메모리, Java 메모리를 확인합니다.
- **DB 연결을 오래 기다립니다:** 연결 대기 수와 시간 초과를 확인한 뒤 RDS CPU, 연결 수, 디스크 처리 시간을 확인합니다. 대기만으로 RDS 성능 부족을 단정하지 않습니다.
- **메모리나 저장 공간이 부족합니다:** EC2 메모리와 디스크, RDS의 남은 메모리와 저장 공간 중 어느 값에 문제가 있는지 확인합니다. 서버 메모리와 Java 힙은 따로 봅니다.
- **실패가 늘었습니다:** 기존 오류 그래프와 백엔드 오류 로그를 함께 확인합니다. Nginx에서 끝난 요청은 Java의 새 API 기록에 포함되지 않습니다.
- **그래프에 값이 없습니다:** 최근 요청이 있었는지, 새 코드가 배포됐는지, Agent와 로그 필터가 적용됐는지 확인합니다. 데이터 없음은 정상이나 0을 뜻하지 않습니다.

알림은 대시보드와 별도입니다. 위 절차는 새 알람을 만들거나 기존 알림 조건을 바꾸지 않습니다. 기존 오류 알람과 Discord 전달 설정은 유지합니다. 적용 후 값이 맞는지 확인한 다음 응답 시간의 알림 기준을 정합니다.

설정 파일 검사는 다음 명령으로 실행합니다. AWS에 접속하지 않습니다.

```bash
python3 -B -m unittest discover -s deploy/monitoring -p 'test_*.py'
```
