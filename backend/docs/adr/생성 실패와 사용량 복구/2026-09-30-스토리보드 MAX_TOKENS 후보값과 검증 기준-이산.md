# 2026-09-30 스토리보드 MAX_TOKENS 후보값과 검증 기준

- 상태: 8,192 적용, 관측 지속
- 작성일: 2026-09-30
- 최종 수정일: 2026-10-01
- 작성자: 이산
- 결정 대상: 스토리보드 생성의 `GEMINI_MAX_OUTPUT_TOKENS`

## 배경

이전 요약에는 스토리보드 실패 14건, 생각 토큰 평균 3,829개·최댓값 3,932개, 후보 토큰 평균 251개가 기록되어 있었다. 2026-09-30 재조회에서는 최근 1주간 `MAX_TOKENS` 실패 28건이 확인됐다. 이전 집계의 원본 목록과 조회 조건은 남아 있지 않아 두 결과의 차이를 정확히 대조할 수 없다. 당시 기본 한도는 4,096이었다.

이후 대표 입력의 토큰 수를 출력 한도와 비교했으나, 비교에 사용한 지표를 잘못 해석했다. 아래에서 이를 정정한다.

## 로그 집계 근거

재조회 및 집계 확인 시각은 **2026-09-30 01:05 KST**다. AWS CloudWatch `ap-northeast-2`의 `/harudle/prod/backend` 로그를 최근 1주(UTC) 범위에서 읽기 전용 조회했다. 아래 조건에 맞는 로그를 `traceId`별로 묶었고, 28개 traceId 모두 로그가 1건씩이었다. 각 로그에 `candidateTokenCount`와 `thoughtTokenCount`가 함께 있어 요청별 합계도 계산할 수 있었다.

```text
SOURCE "/harudle/prod/backend" START=-1w END=0s
| filter @message like /event=external_api_failure/
    and @message like /operation=storyboard_generation/
    and @message like /failureType=OUTPUT_TRUNCATED/
    and @message like /finishReason=MAX_TOKENS/
| parse @message /traceId=(?<trace>[0-9a-f]+).*candidateTokenCount=(?<cTok>\d+) thoughtTokenCount=(?<tTok>\d+)/
| stats count(*) as logRows,
        max(toNumber(cTok)) as candidateTokens,
        max(toNumber(tTok)) as thoughtTokens by trace
```

| 항목 | 계산 | 결과 |
| --- | --- | ---: |
| 생각 토큰 평균 | 107,099 ÷ 28 | 3,824.96 (약 3,825) |
| 생각 토큰 최댓값 | 28건 중 최댓값 | 3,934 |
| 후보 토큰 평균 | 7,143 ÷ 28 | 255.11 (약 255) |
| 두 토큰 합계 평균 | (107,099 + 7,143) ÷ 28 | 4,080.07 |
| 요청별 두 토큰 합계 최댓값 | 각 요청의 생각 토큰 + 후보 토큰 중 최댓값 | 4,082 |

따라서 이전 요약의 `3,829 / 3,932 / 251`은 이번 표본에서 재현되지 않는다. 이후 한도 판단에는 조회 시점과 표본 수를 함께 기록하며, 이 집계는 해당 기간의 실패 요청만을 대상으로 한 스냅샷으로 취급한다.

## 토큰 지표에 대한 정정

처음에는 대표 입력의 `promptTokenCount` 4,921개와 5,177개를 스토리보드 출력 토큰 수로 오해해 `GEMINI_MAX_OUTPUT_TOKENS`와 비교했다. 따라서 `5,120`이 실제 출력 사용량보다 낮다는 당시 판단은 근거가 되지 않는다. `promptTokenCount`는 **입력** 토큰 수다. 출력 한도와 비교할 사용량은 `thoughtTokenCount + candidateTokenCount`다. `totalTokenCount`는 입력까지 포함하므로 `8,192`를 넘어도 그 자체로 출력 한도 초과가 아니다.

## 결정

팀 논의 결과 추론 수준 `high`를 유지하고, 스토리보드 출력 한도를 `8,192`로 설정한다. 처음 이 값을 검토할 때 토큰 지표를 잘못 해석한 부분이 있었지만, 적용 후 관측한 출력 사용량으로 `5,120`보다 여유가 있는 선택이었음을 확인했다. 모든 입력에서 잘림이 없음을 보장하지는 않는다.

`application.yml`의 기본값은 `8,192`로 두며, `GEMINI_MAX_OUTPUT_TOKENS` 환경 변수가 설정되면 환경 변수 값이 우선한다. `MAX_TOKENS`가 다시 발생하면 그 결과를 근거로 한도를 재검토한다.

## 이유

Google은 `maxOutputTokens`를 생각 토큰과 응답 토큰의 합산 상한으로 정의하며, 한도에 도달하면 `MAX_TOKENS`로 응답이 잘릴 수 있다고 설명한다. [Thinking 문서](https://ai.google.dev/gemini-api/docs/generate-content/thinking)

상향 후 추가로 확인한 5건의 출력 사용량은 `4,370`, `4,802`, `4,387`, `3,929`, `3,437`토큰이었다. 최대값 `4,802`는 처음 검토한 `5,120`보다 불과 318토큰 낮다. 이번 표본만으로 `5,120`에서 실패했을 것이라고 단정할 수는 없지만, 출력 길이가 달라지는 다른 요청까지 고려하면 여유가 작다. 확인한 범위에서 `8,192` 적용 후 `MAX_TOKENS` 오류는 다시 관측되지 않았다. 출력 사용량을 계속 확인해 한도를 낮출 근거가 쌓이면 재검토한다.

## 결과 및 후속 검증

- `STOP`인데 JSON 파싱이 실패하면 토큰 한도를 바로 올리지 않고 응답 형식과 파싱 경로를 조사한다. 429 등 제공자 호출 실패도 이 한도로 해결된다고 가정하지 않는다.
- 검증 시 종료 사유와 토큰 수, 파싱·검증 결과, 생성 시간을 기록하고 일기 원문은 기록하지 않는다.
