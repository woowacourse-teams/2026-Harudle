# dev/prod 관측 연결

이 디렉터리의 설정은 CodeDeploy 아티팩트로 EC2의 `/opt/harudle/monitoring`에 복사된다. 애플리케이션 로그는 Docker `awslogs` 드라이버가 CloudWatch Logs에 직접 보내고, CloudWatch Agent는 호스트의 `127.0.0.1:19091/actuator/prometheus`를 1분마다 수집한다. Agent 설정은 기존 호스트 메트릭 구성을 지우지 않도록 **배포 후 별도로** 적용한다. PR 병합만으로 대시보드·Discord 알람이 완성되는 것은 아니다.

실제 AWS 리소스 확인과 적용 순서는 [인프라 적용 계획](infrastructure-rollout.md)에 정리했다. S3 폴더(prefix) 분리는 다른 팀원이 담당하며, 병합 전에 그 작업의 저장·조회·삭제·기존 이미지 인수 조건을 확인한다.

## dev 배포 전에 확인

1. S3 저장 경로 분리를 담당한 팀원에게 같은 버킷 안에서 dev/prod의 `S3_GENERATED_PREFIX`가 실제로 다른지, 새 이미지의 저장·조회·폐기 삭제가 기대한 경로에서 동작하는지 인수받는다. 이 설정은 **새 업로드의 객체 키 생성에만** 적용된다. 기존 DB 이미지 키와 프롬프트의 참조 이미지 키는 그대로 조회하며, 복구가 기존 키에 다시 저장할 수 있으므로 이동·권한 변경 전에 함께 검증한다. 폴더(prefix) 분리만으로 다른 환경 객체에 대한 삭제 권한이 차단되지는 않는다. 교차 prefix 쓰기·삭제의 IAM 제한은 우테코 공유 인프라에서 적용 가능한지 확인하고, 불가능하다면 잔여 위험과 삭제 범위 검증 결과를 기록한다. 경로 분리 인수가 끝나기 전에는 dev 자동 배포를 시작하지 않는다.
2. dev 로그 그룹 `/harudle/dev/backend`, `/harudle/dev/frontend-nginx`, `/harudle/dev/prometheus-emf`를 미리 만들고 보존 기간을 정한다. dev는 14일, prod는 30일을 시작값으로 권장한다. 이미 있는 prod 로그 그룹의 보존 기간은 먼저 확인한다.
3. dev EC2의 **Docker daemon이 쓰는 인스턴스 역할**에 해당 로그 그룹의 `logs:CreateLogStream`, `logs:PutLogEvents` 권한이 있는지 확인한다. dev Compose는 그룹을 자동 생성하지 않는다. 그룹 또는 권한이 없으면 awslogs 초기화 실패로 컨테이너 배포가 실패한다.
4. 호스트의 `19091` 포트가 비어 있는지, Docker 버전과 보안 그룹·방화벽에서 외부 접근이 차단되는지 확인한다. Compose와 Agent 수집 대상이 같은 고정 포트를 사용한다.
5. `/opt/harudle/.env`에 `DEPLOY_ENV=dev`가 명시됐는지 확인한다. prod의 `.env`에도 `DEPLOY_ENV=prod`를 명시해야 한다. 값이 없으면 배포를 중단한다.

## 배포 후 확인

```bash
curl --fail --silent --show-error http://127.0.0.1:19091/actuator/prometheus | grep '^# HELP jvm_memory_used_bytes'
docker compose --project-name harudle --env-file /opt/harudle/.env \
  --file /opt/harudle/compose.prod.yaml --file /opt/harudle/compose.dev.yaml ps
```

dev에서는 `compose.dev.yaml`을 추가하고 prod에서는 추가하지 않는다. 애플리케이션 API 포트의 `/actuator/prometheus`에는 응답 본문이 없어야 하고, 인터넷에서 호스트의 19091 포트에 접속할 수 없어야 한다. Docker 28 미만은 루프백 publish의 같은 L2 세그먼트 접근 가능성을 별도로 확인한다.

각 환경의 백엔드·프론트 로그 그룹에 새 로그 한 건이 들어오는지 확인한다. 백엔드 로그는 한 줄 JSON이어야 하며 `traceId`, `generationId`, 고정 `event` 필드가 있는 실패 로그를 검색할 수 있어야 한다. 원문 일기·프롬프트·S3 키·서명 URL이 출력되지 않는지도 확인한다.

사용되지 않은 이미지의 S3 삭제 요청이 성공하면 `event=discarded_image_deleted`와 코드상 사유를 남긴다. S3 삭제 요청 성공은 기존 객체가 실제로 있었다는 증거는 아니다. 누락 사고를 조사할 때 이 이벤트가 없는 삭제는 애플리케이션 외부 삭제 가능성으로 분리해 조사한다. **S3 객체 수준 CloudTrail `DeleteObject` 데이터 이벤트는 기본으로 수집되지 않는다.** 삭제 주체 추적이 필요하면 환경별 버킷에 데이터 이벤트를 별도로 켜고 비용·보존 기간을 정한다.

## CloudWatch Agent

호스트에 Agent가 이미 설치되어 있다면 기존 활성 설정과 파일 이름을 먼저 기록한다. 이 파일은 추가 조각이므로 `fetch-config`로 기존 구성을 대체하지 않는다. 현재 환경에 해당하는 파일 **하나만** `append-config`로 적용한다.

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a append-config -m ec2 -s \
  -c file:/opt/harudle/monitoring/cloudwatch-agent.dev.json
```

prod에서는 위 명령의 파일명을 `cloudwatch-agent.prod.json`으로 바꾼다. 활성 Agent 조각에 같은 이름의 파일이 없는지 확인하고, 기존 호스트 CPU·메모리·디스크 지표가 계속 들어오는지 검사한다. `/harudle/{env}/prometheus-emf`에 새 이벤트가 생기고 `Harudle/Dev` 또는 `Harudle/Prod` 네임스페이스에 아래 지표가 나타나야 한다. Agent 수집·EMF 로그는 CloudWatch 비용이 발생하므로 필요한 계열만 선택했다.

| 지표 | 확인 목적 |
|---|---|
| `harudle_generation_finalizations_total` | DB에 확정된 생성 결과(`status=SUCCEEDED\|FAILED`, `errorCode`) |
| `harudle_generation_unexpected_failures_total` | 예상하지 못한 생성 내부 오류의 단계(`phase`) |
| `harudle_gemini_stage_calls_total` | 스토리보드/이미지 단계별 성공·실패 종류 |
| `harudle_s3_operation_calls_total` | 작업별 저장·참조 이미지 조회 실패와 인증·권한·설정 오류(`operation`, `result`, `failureType`). `put_object`는 `store()` 호출 1회를 세며 이미지 최적화의 물리적 PUT 횟수는 아님. 복구는 `restore_object`, `restore_optimized`, `restore_thumbnail`으로 구분 |
| `harudle_s3_url_signs_total` | 이미지 접근 URL 발급 실패(`result`, `failureType`). 서명 성공이 객체 존재를 증명하지는 않음 |
| `harudle_image_load_failures_total` | 로그인 사용자 타임라인·상세 화면의 이미지 실패 신고를 받은 횟수. 프론트 팀원의 신고 코드가 별도 배포되기 전에는 0으로 유지됨 |
| `http_server_requests_seconds_count`, `hikaricp_connections_pending` | API 전체 요청·5xx 수·오류 비율(`job`, `outcome`, `status` 차원)과 DB 연결 대기 |

`/actuator/prometheus`의 `*_total`은 프로세스 시작부터 누적된 값이다. 다만 CloudWatch Agent는 Prometheus 카운터를 이전 스크레이프 대비 **증가분**으로 변환해 EMF/CloudWatch에 보낸다. 첫 스크레이프에는 이전 값이 없어 증가분을 내보내지 않는다. 따라서 Agent가 게시한 CloudWatch 카운터의 알람은 실제 dev 샘플을 확인한 뒤 5분 또는 10분 기간의 `Sum`으로 평가한다. 누적 원본에 쓰는 `DIFF`나 `RATE`를 CloudWatch 값에 다시 적용하지 않는다. [AWS 카운터 변환 설명](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-metrics-conversion.html)을 참고한다. `harudle_generation_executions_total{result="returned"}`는 실행기가 값을 반환했다는 뜻이며 DB 최종 성공이나 브라우저 표시 성공을 뜻하지 않는다. `GENERATION_INTERRUPTED`는 DB 상태가 아니라 실패 `errorCode`다. S3 `get_object`는 서버가 참조 이미지를 읽는 작업이지 브라우저의 완성 이미지 조회가 아니다. `head_object{result="missing"}`은 점검이나 복구 중 예상된 결과일 수 있으므로 그 값만으로 알리지 않는다.

생성 최종 상태, 예상 밖 오류 단계, 로그인 사용자 이미지 표시 실패(`timeline`·`detail`) 카운터는 앱 시작 시 가능한 태그 조합을 0으로 등록한다. Agent가 오류 발생 **전**에 그 시계열을 한 번 수집하면 다음 수집에서 첫 오류의 증가분을 계산할 수 있다. 앱 시작 또는 Agent 재시작 뒤 첫 수집보다 먼저 발생한 오류는 여전히 CloudWatch 증가분에서 빠질 수 있으므로, 생성 오류는 같은 시점의 `generation_finalized`·`generation_unexpected_failure` 구조화 로그도 확인한다.

이미지 실패 신고 API는 `POST /api/v1/telemetry/image-load-failures/{timeline|detail}`이며 인증·CSRF가 필요한 본문 없는 요청에 204로 응답한다. 이번 PR에는 프론트 신고 코드가 포함되지 않는다. 담당 팀원의 프론트 작업이 dev에 합류하고 두 화면의 실제 실패 신고가 API·지표에 도착하는지 확인한 뒤에만 `image-load-failure` 알람을 활성화한다. 그전의 지표 0은 이미지 표시 성공을 뜻하지 않는다.

Gemini 단계·S3 작업/URL 서명·HTTP 상태별 카운터는 새 태그 조합이 첫 이벤트에서 생성될 수 있어 같은 첫 수집 누락 위험이 있다. 첫 **한 건**부터 감지해야 하는 S3/Gemini 오류는 `event=external_api_failure`와 `provider`·`operation`·`failureType`을 사용하는 CloudWatch Logs 지표 필터로 세고, Agent 카운터는 추세 관찰과 대조에 쓴다. 필터가 원본 실패 로그 한 건을 정확히 한 번 세는지 dev에서 확인한다. 이 로그 지표 필터와 경보는 아직 AWS에 생성되지 않았으므로 적용·검증 전에는 첫 건 경보를 보장할 수 없다. 이미지 표시 실패에는 대응하는 구조화 실패 로그가 없어, 서버 또는 Agent 시작 직후 첫 수집 전의 보고는 증가분에서 빠질 수 있다.

dev에서 Agent 버전과 활성 설정을 기록하고, 안전한 테스트 요청 전후의 `/actuator/prometheus` 누적값·`/harudle/dev/prometheus-emf`의 증가분·CloudWatch 5분 `Sum`을 대조한다. 백엔드 재시작 뒤에도 큰 오탐 증가분이나 음수가 나오지 않는지 확인한 다음 알람을 활성화한다. 오류가 발생했을 때만 생성되는 시계열의 데이터 없음은 `notBreaching`으로 취급한다. 반대로 `hikaricp_connections_pending`처럼 매 수집 주기에 나오는 게이지가 dev에서 실제로 연속 게시되는지 확인한 뒤, 그 시계열의 무데이터를 `breaching`으로 보는 **별도 수집 중단 알람**을 둔다. Hikari가 연속 게시되지 않으면 Agent 상태를 나타내는 다른 지속 신호를 먼저 정한다. [CloudWatch 결측값 처리](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/alarms-and-missing-data.html)를 참고한다.

dev에서 단일 오류가 한 건으로 전달되는지 별도로 확인한다.

1. 백엔드와 Agent가 정상 수집 중이고 다른 오류가 없는 상태에서 Agent의 정기 수집을 먼저 기다린다. `/actuator/prometheus`에 대상 `harudle_generation_unexpected_failures_total{phase="..."}` 시계열이 0으로 존재하는지 확인한다.
2. 안전한 dev 테스트로 해당 단계의 예상 밖 오류를 **한 건만** 발생시키고 구조화 로그의 `generation_unexpected_failure` 한 건과 Prometheus 누적값 `0 → 1`을 확인한다.
3. 다음 Agent 수집 뒤 `/harudle/dev/prometheus-emf`에서 같은 `phase`의 증가분 1건을 찾고, 다른 오류가 없는 CloudWatch 5분 평가 창에서 해당 시계열의 `Sum=1`을 확인한다. 이후 수집에서 같은 오류가 반복 집계되지 않는지도 확인한다.

## 후속: 이미지 전체 점검과 삭제 감사

DB의 성공 이미지와 S3 객체를 주기적으로 대조하는 전체 점검은 이번 배포의 계측·알람 범위에서 제외한다. 이 기능을 다시 설계할 때 존재하는 키와 없는 키의 HEAD가 각각 200·404인지 dev/prod에서 확인하고, 403을 누락으로 단정하지 않아야 한다. 마지막 전체 순회의 누락 수는 그 시점의 표본 결과이지 현재 전체 누락 수가 아니다.

전체 점검이 없는 동안 **아무도 열어보지 않은 기존 이미지가 삭제되면 자동으로 발견할 수 없다.** 로그인 사용자 화면의 이미지 로드 실패 이벤트는 실제 표시 실패를 알려 주지만, 게스트·공유 화면과 열어보지 않은 이미지는 관측하지 않는다. 이번에는 같은 버킷의 환경별 새 이미지 prefix 분리를 인수받는다. 저장소 어댑터의 조회·삭제는 전달받은 전체 객체 키를 사용하고 prefix 소속을 검증하지 않으므로, 경로 분리만으로 교차 삭제가 불가능해지는 것은 아니다. AWS는 객체 prefix별 IAM 정책을 지원하지만, 이 공유 인프라에서 역할·정책을 변경할 수 있는지는 확인되지 않았다. 적용할 수 없다면 이 제한을 잔여 위험으로 남기고 삭제 경로의 범위를 검증한다. CloudTrail S3 객체 데이터 이벤트와 버전 관리의 도입 여부도 따로 결정한다.

## API 오류 비율과 지연 시간

CloudWatch Agent는 HTTP 완료 횟수를 전체(`job`), 결과군(`job`, `outcome`), 상태 코드(`job`, `status`) 차원으로 보낸다. Spring의 `outcome=SERVER_ERROR`는 5xx 응답이다. 5분 `Sum`으로 전체 요청 수 `T`와 서버 오류 수 `E`를 구하고 `IF(T >= 20, 100 * E / T, 0)`을 오류 비율(%)로 표시한다. 초기 경보는 **오류 비율 5% 초과가 5분 창 2회 연속**일 때다. 요청이 적어 비율이 불안정한 시간은 기존 **5xx 3건/5분** 경보가 보완한다. `outcome`과 전체 차원에서 같은 요청이 각각 한 번만 집계되는지 dev에서 먼저 확인하고, 재시작 직후 첫 수집 누락도 로그와 대조한다. [Spring HTTP 결과 태그](https://docs.spring.io/spring-boot/reference/actuator/metrics.html), [CloudWatch 지표 수식](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/using-metric-math.html)을 참고한다.

부하 시험에서는 AI 작업 완료까지의 비동기 시간과 API 응답 시간을 별도로 측정한다. 우선 부하 시험 도구가 측정한 **요청별 P95·P99, 5xx 비율, 동시 사용자 수, 초당 요청 수**를 시나리오와 함께 보존한다. 대시보드에는 HTTP `count`와 `sum`으로 계산한 평균, 5xx 비율, Hikari pending, EC2/RDS 자원 지표를 같은 시간축에 놓아 지연 원인을 찾는다. 평균이나 `count`만으로 P95·P99를 계산하지 않는다.

현재 CloudWatch Agent의 Prometheus 수집기는 **histogram을 버리므로**, 서버에 Micrometer histogram bucket만 켜고 CloudWatch P95·P99 알람을 만들 수 없다. 부하 시험 뒤 API 유형별 지연 목표와 최소 표본 수를 정한다. 운영 지연 경보가 필요하면 두 방식 중 하나를 선택해 dev에서 값과 비용을 검증한다: (1) Micrometer가 계산한 P95·P99 summary quantile을 Agent로 보내되, 이는 인스턴스·URI별 값이라 여러 시계열을 합쳐 전체 백분위를 만들 수 없다. (2) 장기 보존과 집계가 필요하면 Prometheus/AMP가 histogram bucket을 수집하도록 별도 경로를 둔다. 이후 `api-p95`·`api-p99`를 **5분 표본 20건 이상, 합의한 응답 시간 목표 초과가 2개 평가 창 연속**일 때만 활성화한다. [Agent의 지원 유형](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-metrics-conversion.html), [Micrometer 백분위의 집계 제한](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html)을 참고한다.

## EC2·RDS 자원 경보

각 환경의 **실제 EC2 InstanceId와 RDS DBInstanceIdentifier**로 경보를 분리한다. EC2 `CPUUtilization`·`StatusCheckFailed`는 `AWS/EC2`, RDS `CPUUtilization`·`FreeableMemory`·`FreeStorageSpace`·`DatabaseConnections`는 `AWS/RDS`에서 확인한다. EC2 `mem_used_percent`·`disk_used_percent`는 CloudWatch Agent의 `CWAgent` 지표로, 기존 호스트 설정에 없다면 별도 조각을 추가하고 수집을 검증한다. 디스크는 컨테이너·로그가 실제로 저장되는 파일시스템의 `path`·`device` 차원만 고른다. Agent 메모리·디스크 수집이 없는데 무데이터를 정상으로 처리하는 경보를 만들어 두지 않는다. [EC2 기본 지표](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/viewing_metrics_with_cloudwatch.html), [Agent 호스트 지표](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/metrics-collected-by-CloudWatch-agent.html), [RDS 지표](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-metrics.html)를 참고한다.

| 경보 | 초기 조건 | 확인 목적 |
|---|---|---|
| EC2 상태 검사 | `StatusCheckFailed >= 1` 1분 | 인스턴스·기반 호스트 장애 |
| EC2 CPU·메모리 | CPU 평균 > 85% 또는 `mem_used_percent` 평균 > 90%, 각각 5분 3회 | API 지연·OOM 전 자원 압박 |
| EC2 디스크 | 서비스 파일시스템 `disk_used_percent` > 85%, 5분 2회 | 로그·이미지 임시 파일로 인한 쓰기 실패 예방 |
| RDS CPU | `CPUUtilization` 평균 > 85%, 5분 3회 | 쿼리 처리 포화 |
| RDS 메모리·저장 공간 | `FreeableMemory`가 실제 인스턴스 메모리의 15% 미만 또는 `FreeStorageSpace`가 할당량의 20% 미만, 각각 5분 3회 | DB 메모리·저장 공간 고갈 조기 발견 |
| RDS 연결 수 | `DatabaseConnections`가 확인한 `max_connections`의 80% 초과, 5분 3회 | DB 연결 한도 접근. Hikari pending과 함께 분석 |

위 수치는 시작값이다. RDS 메모리·저장 공간·연결 수는 **실제 인스턴스 크기, 할당량, DB 한도 확인 후 바이트·연결 수 임계값으로 변환**한다. dev와 prod의 현재 기준선 및 부하 시험 결과로 임계값을 조정한다. 기본 지표의 주기와 Agent 게시 주기를 먼저 확인하고 평가 창을 맞춘다. 상태 검사 무데이터는 정상으로 간주하지 않고 인스턴스 정지·수집 장애를 별도로 조사한다.

## 알람 설계

CloudWatch 알람은 dev/prod를 별도로 만들고, 환경별 SNS 주제를 통해 Discord 전달 Lambda에 연결한다. Webhook은 Secrets Manager에 보관한다. 알람 상태 전환과 Lambda 전달 실패도 모니터링한다. 아래는 초기값이며 실제 트래픽을 1주일 관찰한 뒤 조정한다.

| 우선순위 | 조건 | 평가 창 | 이유 |
|---|---|---|---|
| 즉시 | `harudle_generation_unexpected_failures_total` 증가 1건 | 5분 | 내부 예외의 발생 단계와 원인 확인 |
| 즉시 | `harudle_generation_finalizations_total{status="FAILED",errorCode="GENERATION_INTERRUPTED"}` 증가 1건 | 5분 | 생성 작업이 중단되어 만료 처리됨 |
| 즉시 | 로그 지표 필터 `provider=s3`, `operation=put_object` 1건 | 5분 | 생성 이미지 저장 실패 |
| 즉시 | 로그 지표 필터 `provider=s3`, `failureType=AUTHENTICATION_ERROR\|AUTHORIZATION_ERROR\|CONFIGURATION_ERROR` 1건 | 5분 | 자격 증명·권한·버킷 설정 장애 |
| 주의 | 로그 지표 필터 `provider=s3`, `operation=get_object\|presign_get_object` 1건 | 5분 | 참조 이미지 조회 또는 접근 URL 발급 실패 |
| 주의 | 로그인 사용자 이미지 표시 실패 증가 3건 | 5분 | 프론트 신고 연동 검증 후 활성화. 오프라인·URL 만료도 가능하므로 S3 누락으로 단정하지 않음 |
| 즉시 | API 5xx 증가 3건 | 5분 | 사용자 요청 실패 증가 |
| 주의 | API 5xx 비율 > 5%, 단 전체 요청 20건 이상 | 5분 창 2회 연속 | 요청량이 늘 때 지속되는 장애 감지 |
| 주의 | 로그 지표 필터 `provider=gemini`, `operation=storyboard_generation\|image_generation`의 429/5xx/timeout 3건, 출력 한도/파싱 오류 2건 | 10분 | 공급자와 단계별 실패 구분 |
| 주의 | Hikari pending 연결 지속(`Max` 또는 `Average` 게이지) | 부하 시험 기준 확정 후 | 다음 스프린트 병목 관찰 |
| 즉시/주의 | EC2 상태 검사·CPU·메모리·디스크, RDS CPU·메모리·저장 공간·연결 수 | 위 자원 경보 표의 각 평가 창 | 앱 실패 전에 서버·DB 고갈 감지 |

CloudWatch Agent가 게시한 카운터는 증가분이고 로그 지표 필터는 로그 발생 건수이므로 각 알람의 5분·10분 `Sum`을 사용한다. 여러 태그 조합을 하나의 조건으로 묶을 때만 metric math로 각 시계열을 합산한다. `DIFF`로 다시 증가량을 계산하지 않는다. S3/Gemini 로그 필터에는 `event=external_api_failure`를 필수로 지정하고, S3 인증·권한·설정 오류에는 저장·참조 조회·URL 발급을 모두 포함한다. `head_object{result="missing"}`은 경보 대상에서 제외한다. 하나의 Gemini 장애가 생성 실패와 API 502까지 전파될 수 있으므로 Discord 중복 알람을 묶어 대응한다. 소비자 차감은 별도 팀원 작업이므로 공급자 토큰·생성 실행 수를 차감 수로 해석하지 않는다.

P95·P99 알람은 위의 수집 방식·목표·표본 수가 dev에서 검증되기 전에는 만들지 않는다. API 오류 비율과 EC2·RDS 자원 경보는 검증된 지표가 들어오는 즉시 dev에서 먼저 적용한다.

Discord 전달 함수 `discord_forwarder.py`는 아래 이름만 허용한다. 각 환경에서 `harudle-{env}-{suffix}`로 경보를 만들고 환경별 SNS 주제 ARN, Secrets Manager Webhook ARN을 Lambda 환경 변수 `ALARM_TOPIC_ARN`, `WEBHOOK_SECRET_ARN`에 지정한다. Lambda의 `DEPLOY_ENV`도 해당 환경으로 설정한다. 함수는 CloudWatch의 자유 형식 오류 이유를 전달하지 않고, 환경·알람명·상태·고정 원인 문구만 Discord로 보낸다. `allowed_mentions`는 비활성화한다. 현재 Lambda·SNS·IAM·Webhook 비밀·알람은 저장소 배포에서 자동으로 생성하지 않는다.

연결할 때 SNS 주제 정책은 같은 계정의 해당 환경 `harudle-{env}-*` CloudWatch 알람만 발행할 수 있게 제한한다. Lambda에는 지정한 비밀 하나의 `secretsmanager:GetSecretValue`와 자기 로그 그룹 쓰기만 허용하고, SNS 주제 하나에만 호출 권한을 준다. 비밀이 고객 관리 KMS 키로 암호화됐다면 그 키의 복호화 권한도 별도로 확인한다. Lambda 코드 ZIP의 루트에 `discord_forwarder.py`를 넣고, 배포 시 버전이 바뀌는 아티팩트 키를 사용한다. Lambda 실패 경보를 같은 SNS→Lambda 경로에 연결하면 전달 장애를 알 수 없으므로 독립된 연락 경로로 보낸다. SNS 재전달로 Discord 알림이 중복될 수 있다.

| 경보 이름 suffix | 연결할 조건 |
|---|---|
| `generation-unexpected-failure` | 예상하지 못한 생성 내부 오류 1건 |
| `generation-interrupted` | 만료 처리로 실패 확정된 생성 1건 |
| `s3-put-failure` | S3 생성 이미지 저장 실패 1건 |
| `s3-reference-get-failure` | S3 참조 이미지 조회 실패 1건 |
| `s3-url-sign-failure` | 이미지 접근 URL 발급 실패 1건 |
| `s3-authentication`, `s3-authorization`, `s3-configuration` | 각 S3 실패 유형 1건 |
| `image-load-failure` | 로그인 사용자 타임라인·상세 화면 이미지 표시 실패 5분간 3건. 프론트 팀원 코드의 신고 연동 검증 전에는 만들거나 활성화하지 않음 |
| `api-5xx` | API 5xx 증가 |
| `api-error-rate` | 표본 조건을 만족한 API 5xx 비율 증가 |
| `gemini-storyboard-errors` | 스토리보드 단계 공급자·형식 오류 |
| `gemini-image-errors` | 이미지 단계 공급자·형식 오류 |
| `hikari-pending` | DB 연결 대기 증가 |
| `telemetry-stale` | 검증된 지속 게이지의 무데이터로 서버 지표 수집 중단 감지 |
| `api-p95`, `api-p99` | 지연 수집 방식·목표·표본 수 검증 후 활성화 |
| `ec2-status-check`, `ec2-cpu-high`, `ec2-memory-high`, `ec2-disk-high` | EC2 상태·CPU·메모리·디스크 경보 |
| `rds-cpu-high`, `rds-memory-low`, `rds-storage-low`, `rds-connections-high` | RDS CPU·사용 가능 메모리·저장 공간·연결 수 경보 |

Discord 연결 검증은 dev 테스트 알람을 ALARM으로 전환한 뒤 수신 시각·환경·알람 이름·원인·상태 복귀를 확인한다. prod에는 dev 검증 후 동일 구성을 적용한다. 실제 AWS 리소스는 현재 저장소 밖에서 관리되므로 적용한 알람·SNS·Lambda·대시보드의 식별자와 검증 결과를 운영 기록에 남긴다.
