# 2026-09-30 스토리보드 MAX_TOKENS 후보값과 검증 기준

- 상태: 8,192 적용 결정, dev 검증 필요
- 작성일: 2026-09-30
- 최종 수정일: 2026-10-01
- 작성자: 이산
- 결정 대상: 스토리보드 생성의 `GEMINI_MAX_OUTPUT_TOKENS`

## 배경

이전 요약에는 스토리보드 실패 14건, 생각 토큰 평균 3,829개·최댓값 3,932개, 후보 토큰 평균 251개가 기록되어 있었다. 2026-09-30 재조회에서는 최근 1주간 `MAX_TOKENS` 실패 28건이 확인됐다. 이전 집계의 원본 목록과 조회 조건은 남아 있지 않아 두 결과의 차이를 정확히 대조할 수 없다. 당시 기본 한도는 4,096이었다.

이후 대표 입력을 요청해 API 응답의 토큰 사용량을 확인했다. 일반적인 입력에서는 4,921개, 일기 입력을 맥락이 없는 스토리로 300자로 채운 경우에는 최대 5,177개가 관측됐다. 따라서 5,120은 실제 관측치보다 낮아 최대 입력을 처리하기에 부족할 수 있음이 확인됐다.

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

## 결정

팀 논의 결과 추론 수준 `high`를 유지하고, 스토리보드 출력 한도를 `8,192`로 설정한다. `5,177` 관측치보다 3,015개(약 58%)의 여유를 두는 값이다. 이는 관측치에 근거한 운영 설정이며, 모든 입력에서 잘림이 없음을 수학적으로 보장하지는 않는다.

`application.yml`의 기본값은 `8,192`로 두며, `GEMINI_MAX_OUTPUT_TOKENS` 환경 변수가 설정되면 환경 변수 값이 우선한다. 적용 후 dev에서 대표 입력의 종료 사유, 토큰 수, JSON 파싱·검증 결과를 확인한다. `MAX_TOKENS`가 다시 발생하면 그 결과를 근거로 한도를 재검토한다.

## 이유

Google은 `maxOutputTokens`를 생각 토큰과 응답 토큰의 합산 하드 한도로 정의하며, 한도에 도달하면 `MAX_TOKENS`로 응답이 잘릴 수 있다고 설명한다. `gemini-3.5-flash`의 모델 상한은 65,536토큰이지만, 이는 우리 서비스의 권장값이 아니다. [Thinking 문서](https://ai.google.dev/gemini-api/docs/generate-content/thinking), [모델 정보](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash)

## 결과 및 후속 검증

- `8,192`는 팀이 결정한 적용값이다. 8연속 에러가 뜨는 실패 이력을 보고 넉넉하게 바로 적용해본 값이다. 300자 입력에서 관측한 `5,177`보다 여유가 있지만, 충분성이 검증 완료된 값은 아니므로 dev 결과를 확인한다.
- `STOP`인데 JSON 파싱이 실패하면 토큰 한도를 바로 올리지 않고 응답 형식과 파싱 경로를 조사한다. 429 등 제공자 호출 실패도 이 한도로 해결된다고 가정하지 않는다.
- 검증 시 종료 사유와 토큰 수, 파싱·검증 결과, 생성 시간을 기록하고 일기 원문은 기록하지 않는다.
