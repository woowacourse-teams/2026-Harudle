# Codex 이미지 fallback worker

백엔드가 Gemini 이미지 API의 **429 또는 timeout**을 받으면 같은 이미지 입력을 이 내부 HTTP worker로 보낸다.
Worker는 @openai/codex-sdk 0.162.1과 CLI의 ChatGPT OAuth 로그인을 사용한다.
사용자 브라우저의 Kakao 로그인이나 OpenAI API 키를 전달하지 않는다.

## 연결

백엔드 설정:

    IMAGE_FALLBACK_PROVIDER=codex
    CODEX_IMAGE_WORKER_URL=http://codex-image-worker:8090
    CODEX_IMAGE_WORKER_TOKEN=<32자 이상의 임의 ASCII 비밀값>
    CODEX_IMAGE_TIMEOUT=310s

기본 fallback은 none이다. codex를 켜면 Gemini 이미지 SDK의 재시도를 끄고 1회 호출한다.
429와 timeout에서 Codex로 전환한다. HTTP 408/504 및 SDK 요청 시간 초과도 포함한다.
401/403·그 밖의 5xx·응답 처리 오류와 취소된 요청은 그대로 실패한다.
Codex도 HTTP 재시도 없이 1회 호출한다. 실패하면 상위 생성/복구 서비스의 기존 실패 처리로 간다.
스토리보드를 다시 생성하지 않으며 기존 이미지 저장과 S3 업로드를 그대로 사용한다.

Worker 제한 시간 기본값은 300초다. 백엔드 HTTP 제한 시간을 이보다 조금 길게 두면 worker의 504를 받을 수 있다.
진행 중인 요청이 있으면 worker는 429를 반환한다. 요청을 대기열에 쌓지 않는다.

## 로컬 실행

Node 22 이상을 사용한다. 다음 명령은 이 디렉터리에서 실행한다.

    npm ci
    npm test

Worker 전용 CODEX_HOME을 지정하고 설치된 CLI로 ChatGPT 로그인한다.
실제 서비스 프로세스에서도 같은 운영체제 사용자와 CODEX_HOME을 사용해야 한다.
자격 증명 디렉터리는 Git에 추가하지 않는다. Worker 토큰은 별도로 환경 변수로 설정한다.

    node --input-type=module -e "import {bundledCli} from './generator.mjs'; console.log(bundledCli())"

위에서 확인한 CLI로 codex login을 실행한 다음 npm start로 시작한다.
PowerShell에서는 $env:CODEX_HOME, $env:CODEX_IMAGE_WORKER_TOKEN을 설정한다.
기본 listen 주소는 127.0.0.1:8090이다. 컨테이너 밖에서 접근해야 하는 경우에만 HOST를 지정한다.

## 선택적 Docker 실행

compose.yaml은 이미 실행 중인 백엔드의 Docker 네트워크에 worker만 붙인다.
백엔드 이미지에는 Node를 추가하지 않는다. 포트를 호스트에 공개하지 않는다.
HARUDLE_DOCKER_NETWORK의 기본값은 harudle_app이다.

    docker compose build
    docker compose run --rm --entrypoint sh codex-image-worker
    # 컨테이너 안에서 CLI 경로를 확인하고 해당 CLI로 login --device-auth 실행
    # OAuth 로그인 후 exit
    docker compose up -d

토큰은 worker와 백엔드에 동일하게 주입한다. 로그인은 비밀값을 CLI 밖으로 추출하지 않고 진행한다.
codex-auth 볼륨은 OAuth 갱신을 위해 쓰기 가능해야 한다. 운영 백엔드의 AWS/GCP/DB 자격 증명은
worker에 전달하지 않는다. CODEX_HOME에는 SDK 세션 기록에 입력 프롬프트가 남으므로 접근과 보관 기간을 관리한다.
Worker 로그에는 프롬프트·참조 이미지·OAuth 토큰을 출력하지 않는다.

Windows에서 기존 서비스의 실제 Storyboard/스타일/참조 이미지를 이용한 SDK 실생성을 검증했다.
Linux 컨테이너의 native image tool 제공 여부와 OAuth 로그인은 배포 환경에서 별도 실생성 검증이 필요하다.
이 때문에 기본 배포 구성에는 worker와 fallback을 자동 활성화하지 않는다.

## 내부 계약

POST /v1/images/generations, Authorization: Bearer <internal token>, application/json:

    {
      "systemInstruction": "GenerationPrompt의 실제 스타일 지시문",
      "referenceInstruction": "Gemini와 공유하는 참조 이미지 지시문",
      "finalTask": "[Final Task]\n실제 Storyboard를 렌더링한 전체 문자열",
      "reference": {"mediaType": "image/png", "base64": "..."}
    }

성공 응답은 바이너리 image/png다. 입력/출력은 각각 20MiB 이하로 제한한다.
실제 생성 파일은 해당 Codex thread의 generated_images 디렉터리 안에 있고,
이번 요청에서 새로 만들어졌으며 참조 이미지와 다른 PNG여야 한다.
임시 입력과 해당 thread의 생성 이미지 파일은 응답 후 정리한다.
클라이언트 연결 종료·worker 제한 시간·프로세스 종료 신호는 SDK AbortSignal로 전달한다.
원격 추론 시작 후 취소하면 원격 비용이나 실행이 즉시 중단된다고 보장할 수는 없다.

검증:

    node ../tools/codex-image-poc/verify-worker.mjs <captured-service-input.json>
