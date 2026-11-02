# Codex SDK image provider PoC

기존 하루들 Storyboard 생성 결과, 이미지 프롬프트 렌더러, 활성 GenerationPrompt의 스타일·참조 이미지를 사용해 OAuth 기반 Codex 이미지 생성을 검증합니다. 직접 작성한 4컷 이미지 프롬프트와 프론트엔드 샘플 이미지를 사용하는 방식은 제거했습니다.

## 검증 흐름

1. 기존 SSH 연결로 운영 백엔드의 런타임 설정과 최신 GenerationPrompt를 읽습니다.
2. 실제 S3 참조 이미지를 내려받습니다.
3. 배포된 JAR과 동일한 라이브러리를 사용하는 별도 진단 JVM에서 기존 StoryboardGenerationService를 실행합니다. 테스트용 일기만 전달합니다.
4. 생성된 Storyboard로 DiaryImageGenerationRequest를 만들고 기존 GeminiDiaryImageGenerator.prepareRequest를 호출합니다. 이 단계에서 Gemini 이미지 생성은 호출하지 않습니다.
5. 실제 요청의 systemInstruction, 참조 지시문·바이트, DiaryImagePromptRenderer의 Final Task를 캡처합니다.
6. 현재 브랜치의 Java 렌더러로 같은 Storyboard를 다시 렌더링해 운영 요청과 SHA-256을 비교합니다.
7. Codex SDK가 준비된 이미지 프롬프트 파일의 내용을 내장 이미지 도구에 그대로 전달하고, 실제 참조 이미지로 PNG를 생성합니다.
8. 호출 기록·입력 원문·이미지 SHA-256을 확인하고 파일을 회수합니다.

## 준비

- Node.js 22 이상, Java/Javac 21
- `npm ci --ignore-scripts`로 lockfile의 공식 SDK/CLI 설치
- `npx codex login`으로 ChatGPT 로그인; 서버에서는 `--device-auth` 사용 가능
- 현재 서비스에 접근 가능한 기존 SSH Host 설정

## 재실행

원격 입력 준비는 운영 백엔드를 재시작하거나 Spring 애플리케이션을 기동하지 않습니다. 기존 컨테이너는 읽기만 하고, 별도 임시 진단 컨테이너를 실행·정리합니다. DB SELECT와 S3 GET만 사용합니다. 테스트 생성물은 서비스 DB나 S3에 저장하지 않습니다.

~~~powershell
./prepare-service-input.ps1 -SshHost <configured-host>
~~~

필요하면 `-SshConfig <path>`를 지정합니다. 현재 원격 준비 도구는 `harudle-backend-1` 컨테이너와 Gemini 기본·Qwen/none 보조 Provider 구성을 지원하고, 다른 구성에서는 명시적으로 실패합니다. DB와 Gemini 인증은 원격 환경에 남겨 두며 로컬로 내보내지 않습니다.
진단 JVM의 실패 출력을 반환하기 전에 서버 설정의 KEY, TOKEN, PASSWORD, SECRET,
CREDENTIAL, AUTHORIZATION 값을 가립니다. URL에 들어가도록 인코딩된 값도 가립니다.
가리기 처리를 먼저 끝낸 뒤 마지막 6000자를 반환합니다.
설정에 없는 비밀값까지 찾아내는 기능은 아니므로 불필요한 인증 정보나 원문을 로그에 추가하지 않습니다.

위 명령이 출력한 입력 경로를 사용합니다.

~~~sh
node verify-renderer.mjs runs/<service-input-snapshot>/service-input.json
npm run verify -- --service-input runs/<service-input-snapshot>/service-input.json --timeout-ms 360000
~~~

새 Storyboard 생성과 새 이미지 생성은 각각 사용량을 소비합니다. 이미 완료한 이미지 실행 기록만 재검사하려면:

~~~sh
npm run verify -- --inspect-run runs/<image-run-timestamp>
~~~

이 재검사는 모델 요청을 보내지 않습니다. 원래 생성 파일과 해당 thread의 로컬 세션 기록이 남아 있어야 합니다.

## 입력과 결과

`runs/`는 Git에서 제외합니다. 입력 스냅샷에는 실제 Storyboard, 캡처한 지시문, 참조 이미지, 출처·해시가 저장됩니다. 실행 폴더에는 다음이 저장됩니다.

| 파일 | 내용 |
| --- | --- |
| `service-input.json` | 이번 실행에 사용한 서비스 입력 스냅샷 |
| `image-prompt.txt` | 실제 스타일·참조 지시문·렌더링 결과를 합친 원문 |
| `prompt.txt` | SDK 에이전트에게 전달한 실행·파일 전달 지시 |
| `reference.png` 등 | 활성 프롬프트에 연결된 실제 참조 이미지 |
| `events.jsonl` | SDK 이벤트; 큰 문자열과 토큰 필드 제외 |
| `final-response.json` | 생성 결과의 구조화된 파일 경로 |
| `report.json` | 인증·버전·소요 시간·입력 계보·해시·호출 증거 |
| `generated.png` | 회수한 PNG |

`success=true`는 turn 완료, 실제 내장 이미지 반환, 새 PNG 회수, 반환 이미지/파일 해시 일치를 모두 만족해야 합니다. 서비스 검증 모드에서는 준비 파일을 읽은 전체 stdout이 원문과 같은지, 그 stdout을 이미지 도구의 prompt로 직접 전달한 호출인지도 확인합니다. PowerShell이 stdout 끝에 덧붙인 개행만 허용합니다.

## SDK 관찰과 범위

TypeScript SDK의 ThreadItem 타입에는 이미지 생성 전용 항목이 없습니다. PoC는 해당 thread의 세션 기록에서 code-mode exec의 실제 이미지 호출과 반환 데이터를 추적합니다. 이 진단용 로컬 로그 구조를 안정적인 운영 API로 취급하지 않습니다.

SDK는 기존 ChatGPT 로그인을 사용하며 API 키 환경 변수를 제거하고 forced_login_method=chatgpt를 지정합니다. 외부 앱·플러그인·MCP·웹 검색·하위 에이전트를 비활성화하고, 셸은 준비된 프롬프트 파일을 읽는 용도로 사용합니다. 토큰 갱신은 CLI가 담당합니다.

제한 시간에는 SDK AbortSignal로 로컬 실행을 중단합니다. 이미 시작된 원격 이미지 생성이나 사용량 소비의 중단까지 보장하지는 않습니다. 보고서 usage는 에이전트 turn의 토큰 수이며 이미지 비용이나 남은 구독 한도가 아닙니다.

단독 검증 이후 Java Codex Provider와 429/timeout fallback, 내부 HTTP worker 연결을 추가했습니다. Worker를 통한 실제 입력의 이미지 생성도 확인했습니다. 실행 설정은 [worker README](../../codex-image-worker/README.md)를 참고하세요. 운영 배포와 Linux/Docker 실생성 검증은 아직 수행하지 않았습니다.

## 결과

[RESULTS.md](./RESULTS.md)에 서비스 입력 검증 결과와 이전 연결 확인 테스트의 차이를 기록했습니다.

## 공식 문서

- [Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)
- [Authentication](https://learn.chatgpt.com/docs/auth)
- [Image generation](https://learn.chatgpt.com/docs/image-generation)

Worker 연결 검증에서는 요청 종료 후 SDK 세션이 정리됩니다.
SDK 이벤트 원문까지 보관해 직접 확인하려면 이 디렉터리의 단독 verify.mjs를 사용합니다.
진단 결과는 runs 아래에만 저장하며 Git에서 제외합니다.

비밀값 가리기 테스트:

    python -m unittest discover -s . -p 'test_*.py'
