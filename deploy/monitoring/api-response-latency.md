# API 응답 시간 P95·P99

## 범위와 모집단

`harudle.api.response`는 Spring MVC의 HTTP 서버 Observation 시작부터 종료까지를 기록한다.
대상은 context path를 제외한 `/api`·`/api/**` 요청이다. 성공·실패 응답을 함께 측정하며
상태 코드·예외 종류를 차원으로 나누지 않는다. 비동기 HTTP는 최종 dispatch 완료 때 한 번 기록한다.
생성 요청 API의 HTTP 응답은 포함하지만, 응답 이후의 비동기 이미지 생성 작업은 포함하지 않는다.
Actuator·OAuth·정적 파일과 loopback의 `X-Harudle-Healthcheck: true` 내부 점검은 제외한다.
외부 클라이언트가 같은 헤더를 보내도 제외하지 않는다.

모든 완료 요청의 원래 소요 시간을 아래 두 Timer에 각각 기록한다.

| 모집단 | `method` | `uri` |
|---|---|---|
| 현재 백엔드 인스턴스의 전체 API | `ALL` | `ALL` |
| 엔드포인트별 API | HTTP 메서드(비표준은 `OTHER`) | Spring 라우트 템플릿, 미매칭은 `UNMATCHED` |

동일 모집단의 이름과 태그가 `node`·`method`·`uri`로 고정된다. 원본 URL, 쿼리 문자열, 사용자·일기 ID,
상태 코드, trace ID, 이미지 URL은 태그에 넣지 않는다. `ALL`과 개별 엔드포인트를 합산하면 요청이 중복된다.
여러 경로나 인스턴스의 summary 값을 평균 내거나 합산해 전체 P95·P99를 만들지 않는다.
전체 API 백분위도 원래 요청들을 직접 기록해서 계산한 값이다.

## 시간 창과 CloudWatch 통계

Timer는 P95·P99 client-side summary만 게시한다. expiry는 5분, buffer length는 1이다.
표본은 최대 5분 보관되며 애플리케이션의 감쇠 버퍼 회전 시 초기화된다. 이는 CloudWatch의 벽시계
5분 평가 창과 정렬된 표본 집합 또는 정확한 직전 5분 sliding window가 아니다. 새 버퍼 시작 직후
백분위는 아직 적은 표본으로 계산될 수 있다. 무호출 창이 지난 뒤 0으로 돌아간 백분위는 그래프에서 숨긴다.

Agent가 1분마다 summary quantile을 그대로 수집하며 다음 **3개 지표명**을 추가한다.

| 지표 | 단위 | CloudWatch 차원 | 통계 |
|---|---|---|---|
| `harudle_api_response_seconds` | Seconds | job,node,method,uri,quantile | 1분 `Maximum` 게시값 |
| `harudle_api_response_seconds_sum` | Seconds | job,node,method,uri | 5분 `Sum` |
| `harudle_api_response_seconds_count` | Count | job,node,method,uri | 5분 `Sum` |

Agent는 sum/count를 이전 수집 대비 증가분으로 변환한다. 첫 수집에는 증가분을 내보내지 않는다.
전체 API Timer는 시작 때 0으로 등록하지만, 첫 수집 전 요청은 증가분에서 빠질 수 있다.
엔드포인트 Timer는 첫 완료 때 생성되므로 해당 경로의 첫 수집 구간도 빠질 수 있다.
CloudWatch 평균은 동일 차원의 `IF(c > 0, s / c)`다. `DIFF`·`RATE`를 다시 적용하지 않는다.
이미 계산된 백분위에 CloudWatch 통계 `p95`·`p99`를 다시 적용하지 않는다.
5분 `Maximum`은 그 기간에 수집된 백분위 중 최댓값이며 5분 모든 요청의 백분위가 아니다.

각 모집단은 백분위 2개, sum/count 2개로 **4개 CloudWatch 시계열**을 만든다.
활성 메서드·경로 조합이 R개이면 인스턴스당 추가 시계열은 약 `4 × (R + 1)`개다.
histogram bucket·max·created는 수집하지 않는다. 기존 HTTP count의 오류율 지표는 유지한다.

## 인스턴스 식별

Compose는 `HARUDLE_API_METRICS_NODE`의 기본값을 `harudle-${DEPLOY_ENV}-backend`로 지정한다.
현재 환경별 단일 백엔드를 위한 값이다. 여러 인스턴스를 운영하면 각 호스트 `.env`에
`HARUDLE_API_METRICS_NODE=harudle-prod-backend-1`처럼 서로 다르고 안정적인 값을 명시한다.
컨테이너 재생성마다 바뀌는 ID나 사용자 식별자를 쓰지 않는다. 대시보드·알람의 `--node`는 실제 태그와 같아야 한다.
여러 인스턴스 전체의 백분위가 필요하면 별도 Prometheus/AMP histogram 경로로 전환한다.

## 정의 생성

`api_latency_resources.py`는 AWS 호출 없이 별도 대시보드와 전체 API 알람 두 개의 요청 본문을 만든다.
P95/P99 지연 목표는 필수 인자로 받으며 임의의 운영 목표를 고정하지 않는다.
아래 1초·2초는 생성 예시다. 부하 시험과 dev 관측으로 실제 목표를 정한다.

```bash
python3 deploy/monitoring/api_latency_resources.py \
  --environment dev --node harudle-dev-backend \
  --topic-arn arn:aws:sns:ap-northeast-2:843255971531:harudle-dev-alerts \
  --p95-seconds 1 --p99-seconds 2 \
  --min-p95-requests 100 --min-p99-requests 1000 \
  --endpoint 'GET:/api/v1/diaries/{diaryId}' \
  --output-dir /tmp/harudle-dev-api-latency
```

출력은 `dashboard.json`, `api-p95.json`, `api-p99.json`이다. 엔드포인트 패널은 `--endpoint`로
최대 20개 선택한다. 알람은 선택한 node의 `method=ALL,uri=ALL` 모집단만 평가한다.
지표는 모든 매칭 API의 전체·엔드포인트별로 수집하며 패널 선택은 수집 범위를 바꾸지 않는다.

알람 수식은 `IF((c >= 최소요청수) AND (q > 0), q)`다. `c`는 5분 API 완료 증가분,
`q`는 같은 모집단의 5분 `Maximum` 백분위 게시값이다. 2/2 평가 창 연속 목표 초과로 ALARM이 된다.
5분 완료 건수는 트래픽 활동 조건이지 summary 내부 표본 수 보장이 아니다. P99는 낮은 트래픽과
감쇠 창 초기화에서 불안정하므로 dev 분포·최소요청수와 창 회전의 영향을 검증한 뒤 활성화한다.
100·1000건은 조정 가능한 출발값이다. 표본 부족·0·누락은 수식에서 제거하고 `TreatMissingData=missing`으로
평가 불가 상태를 구분한다. 무호출을 정상 지연 0으로 채우거나 수집 중단을 지연 복구로 해석하지 않는다.
관련 수집 중단은 기존 `telemetry-stale` 경보로 확인한다. InsufficientDataActions는 비어 있다.

## dev 적용·검증 후 prod

1. 백엔드 새 코드와 Compose를 dev에 배포한다. `/actuator/prometheus`에서 summary의
   `quantile=0.95|0.99`, node, ALL/경로 템플릿 및 Seconds 단위를 확인한다.
2. 기존 Agent 조각을 백업하고 `cloudwatch-agent.dev.json`을 기존 README의 `append-config`로 적용한다.
   기존 호스트 CPU·메모리·디스크 수집이 유지되는지 확인한다. CodeDeploy 파일 복사만으로 Agent는 갱신되지 않는다.
3. 정상·오류·비동기 HTTP를 호출하고 Actuator 누적 count/sum → EMF 증가분 → CloudWatch `Sum`을 대조한다.
   내부 healthcheck, Actuator 수집, 응답 이후 생성 작업은 추가 샘플이 되지 않아야 한다.
   재시작·무호출·버퍼 회전·첫 경로 수집의 영향과 새 지표 시계열 수·비용도 확인한다.
4. 검증한 목표로 정의를 생성하고 아래처럼 **별도 대시보드**와 알람을 적용한다. 기존 대시보드를 덮어쓰지 않는다.

```bash
aws cloudwatch put-dashboard --region ap-northeast-2 \
  --dashboard-name DASHBOARD-harudle-dev-api-latency \
  --dashboard-body file:///tmp/harudle-dev-api-latency/dashboard.json
aws cloudwatch put-metric-alarm --region ap-northeast-2 \
  --cli-input-json file:///tmp/harudle-dev-api-latency/api-p95.json
aws cloudwatch put-metric-alarm --region ap-northeast-2 \
  --cli-input-json file:///tmp/harudle-dev-api-latency/api-p99.json
```

5. 2회 연속 초과·충분한 트래픽에서 정상 복귀·표본 부족·지표 누락을 실제 AWS 평가로 확인한다.
   생성된 알람의 `ActionsEnabled`는 false이며 Discord 전달기는 기존 `api-p95`·`api-p99` 이름을 지원한다.
   목표·수집·SNS/Discord 검증이 끝나면 아래 명령으로 전달을 활성화한다.

```bash
aws cloudwatch enable-alarm-actions --region ap-northeast-2 \
  --alarm-names harudle-dev-api-p95 harudle-dev-api-p99
```

6. dev 결과를 기록한 뒤 prod 코드·Agent를 적용한다. prod namespace·node·SNS로 정의를 다시 생성하고
   별도 prod 대시보드·알람을 검증한 뒤 활성화한다. 실제 적용 전 같은 알람 이름이 이미 있는지 확인하고 백업한다.
7. 수집 롤백은 이전 앱 Agent 조각·scrape 파일을 복원하고 append-config를 재적용한다.
   알람 전달을 먼저 비활성화하고 기존 자원·오류율 지표가 계속 수집되는지 확인한다.

저장소 변경과 정의 생성은 AWS 적용 완료를 뜻하지 않는다. 적용 시각, 앱 SHA, Agent 조각 해시,
대시보드/알람 ARN, node, 목표, 최소요청수, 실제 평가 및 Discord 수신 결과를 운영 기록에 남긴다.

## 검증 명령과 근거

```bash
(cd backend && ./gradlew --no-daemon test --tests 'com.harudle.common.monitoring.*')
python3 -m unittest discover -s deploy/monitoring -p 'test_*.py'
```

- [Spring Boot ObservationHandler 자동 등록](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [Micrometer percentile의 태그 간 집계 제한](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html)
- [Timer 시간 창](https://docs.micrometer.io/micrometer/reference/concepts/timers.html)
- [Agent의 summary/counter 변환과 histogram 제외](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-metrics-conversion.html)
- [CloudWatch IF와 결측값](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/using-metric-math.html)
