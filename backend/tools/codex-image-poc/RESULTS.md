# 기존 서비스 입력을 사용한 Codex 이미지 검증

검증일: 2026-10-15

## 결과: 성공

운영 GenerationPrompt 5번의 실제 입력을 사용했습니다. 새로운 테스트 일기는 기존 StoryboardGenerationService → GeminiStoryboardGenerator를 통해 Storyboard로 생성했습니다. 이어 DiaryImageGenerationRequest → GeminiDiaryImageGenerator.prepareRequest → DiaryImagePromptRenderer를 거쳐 실제 이미지 요청 내용을 캡처했습니다.

현재 브랜치의 Java 렌더러로 같은 Storyboard를 다시 렌더링한 결과도 캡처한 Final Task와 SHA-256이 일치했습니다. Codex에는 이 입력과 S3에서 읽은 실제 참조 이미지를 전달했습니다.

| 항목 | 확인 값 |
| --- | --- |
| 활성 입력 | prod GenerationPrompt 5 |
| Storyboard 설정 | 기본 Gemini, 보조 Qwen; 이번 호출은 Gemini 성공 |
| Storyboard 모델 | gemini-3.5-flash |
| Storyboard 생성 | 43.896초 |
| Storyboard 제목 | 바람 부는 날의 출근길 |
| 이미지 스타일 지시문 | 실제 요청에서 캡처한 15,823자 |
| 렌더링된 Final Task | 9,091자 |
| 합친 이미지 프롬프트 | 25,238자 |
| 이미지 입력 참조 | 활성 GenerationPrompt가 가리키는 실제 S3 이미지 |
| Codex SDK / CLI | 0.162.1 / 0.162.1 |
| Codex 에이전트 모델 | gpt-6.1-sol |
| 인증 | 기존 ChatGPT OAuth; OpenAI API 키 미전달 |
| SDK 실행·이미지 회수 | 127.185초 |
| Storyboard + 이미지 실행 합계 | 약 171.1초; 준비·전송 시간 제외 |
| 결과 파일 | PNG, 1254×1254, 1,686,753 bytes |

입력 스냅샷: `runs/service-input-2026-10-15/`

이미지 실행: `runs/2026-10-15T04-17-36-717Z/`

## 입력 동일성 증거

| 대상 | SHA-256 |
| --- | --- |
| 운영 Storyboard 지시문 | d5449702491beff42bf467ef9ae8f3b1fd545623d465324e6d3339236e5b56b1 |
| 실제 요청의 스타일 지시문 | 0ab41ced36d587435a7c32350386825e6f344de378ffccb77a16b5a39c6801cb |
| 실제 S3 참조 이미지 | 3e2343a46a7ff9c3c93b62be06125e039d6f24a654eef2b772227714b6c722ed |
| 운영 요청의 Final Task | e6572a1240e008cac202fd600450a77cd0ee3a7bb2f51552d17b42a3aacc8829 |
| 현재 브랜치 렌더러의 Final Task | e6572a1240e008cac202fd600450a77cd0ee3a7bb2f51552d17b42a3aacc8829 |
| 도구 반환 이미지 / 회수 PNG | 1107a438b622039853efdfe833d2c5c7a36b9f71ad777a5b0f62eb8457f75401 |

스타일 문자열은 실제 DiaryImageGenerationRequest에서 normalizeRequired로 정규화한 값을 캡처했습니다. DB 원문 끝의 공백까지 포함한 해시와는 다를 수 있으며, Codex에 넘긴 값은 기존 이미지 Provider가 실제 사용하는 값입니다.

Codex의 도구 호출 코드에서 준비 파일을 읽은 stdout을 image_gen의 prompt에 직접 전달하는 것을 확인했습니다. CommandExecution의 전체 stdout과 준비 파일은 마지막 출력 개행 외에 일치했습니다. 화면에 표시된 도구 출력은 길이 제한으로 일부가 생략되어, 검증에는 전체 세션 기록을 사용했습니다.

입력에 마지막 콘솔 개행 2자가 더해졌지만, 스타일·장면·캡션·풋터 내용을 요약하거나 다시 작성하지 않았습니다.

## 이미지 확인

실제 파일을 이미지 뷰어로 열어 다음을 확인했습니다.

- 동일한 주인공의 검은 마커 스타일
- 좌상단에서 우하단으로 진행하는 2×2 패널
- 접힌 우산 출발 → 비에 우산 펼침 → 바람에 뒤집힘 → 버스 안 휴식
- Storyboard의 캡션 4개가 그대로 표시됨
- 하단 왼쪽 제목과 오른쪽 `@harudle.official` 표시
- Storyboard가 지정한 우산 천의 파란색 적용

캡션:

1. 우산 들고 출발!
2. 비 오니까 활짝 펴고
3. 바람 불어 활짝 뒤집히고
4. 버스 안에서 무사히 한숨

## 확인한 범위와 남은 범위

실제 Storyboard Provider, 도메인 검증, 이미지 요청 준비 코드, 현재 브랜치의 렌더러, 운영 스타일·참조 이미지, OAuth 기반 SDK 생성·파일 회수를 검증했습니다. 서비스 사용자 일기는 사용하지 않았고, 서비스 DB나 S3에 테스트 생성물을 저장하지 않았습니다.

코드에 포함된 Node 파일 읽기는 이 Windows 환경의 unified exec 초기화 오류로 두 번 실패했습니다. 이후 PowerShell Get-Content로 동일 파일을 읽어 성공했습니다. 이미지 생성이 완료된 호출은 한 번입니다. 재실행 지시는 Windows에서 Get-Content를 우선 사용하도록 수정했습니다.

이후 아래 HTTP worker/Java Provider 연결 검증을 추가했습니다. Linux/Docker에서의 이미지 도구 제공,
OAuth 갱신·재시작과 실제 계정 한도 소진은 배포 환경에서 추가 검증이 필요합니다. 또한 이번 결과는 한 사례이며 다양한 일기의 품질이나 캡션 정확도를 보장하지 않습니다.

## 이전 테스트와의 구분

`runs/2026-10-15T03-50-53-186Z/`의 이전 산책·강아지 이미지는 직접 작성한 이야기와 프론트엔드 샘플 이미지로 수행한 연결 확인 테스트입니다. 기존 Storyboard나 운영 입력을 사용하지 않았으므로 서비스 입력 검증 근거로 사용하지 않습니다.

## 429 fallback Provider 연결 검증

실제 서비스에서 캡처한 같은 입력을 구현된 codex-image-worker의 HTTP 엔드포인트에 전송했습니다.
새 Storyboard를 작성하거나 Gemini Storyboard를 다시 호출하지 않았습니다.

- 실행: runs/worker-2026-10-15T04-38-26-473Z/
- thread: 01a13dda-bf94-7672-8cd2-389cf65a99cd
- 인증: CLI ChatGPT OAuth, API 키 미전달
- HTTP: 200 image/png
- 소요: 85,113ms
- 이미지: 1,684,620 bytes
- 이미지 SHA-256: 96be1c70b13ed4ca07033cb4b141775aaa97297b6e64f78ed6e94cc9a1b4f668
- 전체 준비 프롬프트 SHA-256: dac6ba32639942a8173ad00b303c2f33b29a8056b320bd997565a7f0b669befd

해당 thread의 세션에서 native image tool 1회 호출을 확인했습니다.
파일을 읽은 전체 stdout이 준비 프롬프트와 마지막 콘솔 개행 외에 일치했고,
store("preparedImagePrompt", r.output) -> prompt: load("preparedImagePrompt")로 전달됐습니다.
native tool이 반환한 이미지의 SHA-256과 HTTP 응답 PNG의 SHA-256이 일치했습니다.

이미지를 직접 열어 실제 Storyboard의 네 캡션, 제목, 계정명과 파란 우산을 확인했습니다.
Worker는 읽기가 끝난 native 이미지와 임시 입력 파일을 정리합니다. 검증용 사본은 runs 아래에만 보관했습니다.

Node offline 테스트는 HTTP 계약, 인증, 입력 검증, 동시 요청 429, timeout/연결 종료 취소,
오류 정보 비노출과 자격 증명 환경 분리를 확인합니다.
Java 계약 테스트는 Gemini 429 또는 timeout에서 같은 요청을 Codex에 전달하며,
다른 오류는 전환하지 않고 Codex도 재시도하지 않는 것을 확인합니다.

최종 회귀 결과: Java 관련 테스트 233개, Node worker 테스트 9개 통과.
Java에서는 로컬 HTTP 서버의 실제 429를 Gemini SDK에 반환해 SDK 호출 1회와 fallback 1회를 확인했습니다.
Worker HTTP 응답 헤더 이후 본문이 지연돼도 Java 제한 시간이 적용되는 것을 확인했습니다.
공유 HTTP 전송 계층으로 변경한 Gemini/Qwen 스토리보드와 기존 생성·관리자 복구 테스트도 포함했습니다.
Git diff 공백 검사를 통과했습니다. Linux Docker 실행은 이 로컬 환경에 Docker가 없어 수행하지 않았습니다.

## Timeout fallback 확장

Gemini의 429와 TIMEOUT 오류에서 같은 이미지 요청을 Codex에 한 번 전달하도록 확장했습니다.
HTTP 408/504와 실제 SDK 전송 timeout도 포함하며, 취소·인터럽트 요청은 전환하지 않습니다.
Codex가 다시 429 또는 timeout으로 실패해도 재시도하거나 Gemini로 돌아가지 않습니다.
원인을 구분할 수 있도록 fallback 로그와 metric reason을 rate_limited / timeout으로 나눴습니다.

이번 변경의 관련 Java 회귀 테스트 123개가 통과했습니다.
실제 Gemini SDK에 로컬 HTTP 서버의 408/504 응답과 지연된 응답 본문을 전달해
Gemini 호출 1회 후 fallback 호출 1회를 확인했습니다. 추가 유료 이미지 생성은 수행하지 않았습니다.
