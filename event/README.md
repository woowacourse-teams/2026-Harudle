# 데모데이 엽서 이벤트 서버 (프로토타입)

일기를 받아 네 컷 만화를 그리고, 데모데이 엽서 프레임(2:3)에 담아 돌려주는 단독 Node 서버다.
하루들 백엔드와 코드·배포를 공유하지 않는다. Dev 앱 EC2에서 별도 Compose 프로젝트로 띄운다.

```text
event.harudle.com → Host Nginx(443) → 127.0.0.1:3100 → harudle-event 컨테이너
  POST /api/jobs           { diary, code? } → 202 { id, status }
  GET  /api/jobs/:id       { status: queued|running|done|failed, stage, position, title, error }
  GET  /api/jobs/:id/image PNG (?download=1 이면 첨부 파일)
  GET  /health             { status, waiting, running, today }
```

## 생성 흐름

1. **스토리보드**: `storyboard-prompt.txt`를 시스템 프롬프트로 Gemini(`gemini-3.5-flash`)에 일기를 보내 JSON을 받는다.
   요청 문구·스키마·검증은 백엔드 `StoryboardRequestRenderer`, `StoryboardResponseSchema`, `StoryboardResponseMapper`와 같다.
   실패 시 한 번 재시도한다. `EVENT_STORYBOARD_FALLBACK=openai`이면 두 번째 시도를 OpenAI(`gpt-6-luna`, 추론 `low`)로 한다.
   Responses API에 같은 시스템 프롬프트·요청문을 보내고, 같은 스키마를 Structured Outputs strict 모드로 받는다
   (Gemini 전용 `propertyOrdering`은 빼고 `additionalProperties: false`를 붙인다). 429·시간 초과·JSON 검증 실패는
   넘기고 응답 거절은 넘기지 않는다. 실제 프롬프트로 시험한 한 번은 약 13초 걸렸다.
   시도별 실패는 `storyboard_attempt_failed`, 성공한 제공자는 `job_done`의 `storyboardProvider`로 남는다.
2. **이미지**: `image-style.txt`, 그림체 참조 `reference.png`, 프레임 이미지, 최종 지시문을 `gemini-3.1-flash-image`에
   `imageSize: 2K`로 보낸다. 결과는 1696×2528이며 Dev 시험에서 한 장에 약 15초 걸렸다.
   최종 지시문은 백엔드 `DiaryImagePromptRenderer`의 항목을 따르되 프레임용으로 줄였다. AI가 프레임을 무시하고 칸을
   새로 그리는 실패가 잦아 프레임 고정 지시와 칸 위치 비율을 맨 앞에, 겹쳐 보기 점검을 맨 끝에 둔다.
3. **합성**: 원본 프레임에서 네 칸의 흰 안쪽과 제목 글씨만 생성본으로 바꾼다. 배경·상단 문구·캐릭터·핸들·QR은
   원본 픽셀을 그대로 쓴다. 생성본의 칸 위치가 60px(가로 1024px 기준) 넘게 어긋나거나 제목을 못 찾으면 이미지 단계만 한 번 다시 만든다.
4. **대체 생성(선택)**: `EVENT_IMAGE_FALLBACK=openai`이면 Gemini가 칸 위치 거절·429·시간 초과 같은 재시도 가능한 실패를
   냈을 때 다시 만드는 시도를 OpenAI(`gpt-image-2.5-flare`, `/v1/images/edits`, 1024×1536, medium)로 한다.
   전체 시도 수는 `EVENT_IMAGE_ATTEMPTS`(기본 2) 그대로라 Gemini 1번 + OpenAI 1번이다. 안전 정책 차단은 넘기지 않는다.
   그림체 지시·참조 이미지·프레임·최종 지시문을 함께 보내며 결과는 프레임 크기로 늘려 합성한다.
   시도별 실패는 `image_attempt_failed`, 성공한 제공자는 `job_done`의 `imageProvider`로 남는다.
   `OPENAI_API_KEY`는 서버 `.env.event`에만 둔다.

백엔드 렌더러·스키마가 바뀌면 `src/storyboard.mjs`, `src/prompt.mjs`를 함께 맞춘다.

## 제한과 개인정보

| 항목 | 기본값 | 설정 |
| --- | --- | --- |
| 동시 생성 | 1 | `EVENT_CONCURRENCY` |
| 대기열 상한(초과 시 503) | 20 | `EVENT_MAX_QUEUE` |
| IP당 시간당 접수(0이면 끔) | 0 | `EVENT_RATE_LIMIT_PER_HOUR` |
| 접속 허용 IP | 부스 네트워크만 | Host Nginx `allow`/`deny` |
| 하루 전체 접수(한국 시간 자정 초기화) | 300 | `EVENT_DAILY_LIMIT` |
| 접속 코드(QR 주소 `?code=`) | 없음 | `EVENT_ACCESS_CODE` |
| 결과 보관 | 120분 | `EVENT_RESULT_TTL_MINUTES` |

- 거절된 요청은 횟수를 차감하지 않는다. 접수 후 실패는 차감한다.
- 일기 원문은 메모리에만 두고 생성이 끝나면 버린다. 로그에는 일기와 프롬프트를 남기지 않는다.
- 부스 기기에 띄워 두는 화면이라 동의 체크박스는 두지 않고, 생성 버튼 아래에 외부 전송과 보관 기간을 안내한다.
  대체 생성을 켜면 안내 문구에 OpenAI가 함께 나온다. 스토리보드 대체에는 일기 원문이, 이미지 대체에는 스토리보드 기반
  지시문이 간다. 실제 행사 전에 외부 전송 고지 기준과 대조한다.
  실제 행사 전에 `backend/docs/adr/약관과 동의/`의 기준과 대조한다.
- 여러 사람이 같은 기기를 쓰므로 새로고침하면 이전 작업을 이어 보여 주지 않는다.
- 대기열·IP 기록은 프로세스 메모리에만 있다. 컨테이너가 재시작되면 진행 중 작업과 결과 링크가 사라진다.

## 로컬 확인 (AI 호출 없음)

```bash
cd event
npm install
npm test
EVENT_FAKE_AI=true node server.mjs   # http://127.0.0.1:3100
```

가짜 모드는 `assets/fake/storyboard.json`, `assets/fake/generated.png`를 응답으로 쓴다. 에셋 목록은 `assets/README.md`를 따른다.

## Dev EC2 배포

```bash
# 1) 코드 복사: /opt/harudle-event (node_modules, assets/fake 제외)
# 2) 에셋 준비 (SELECT·S3 GET만 수행). 테마 프롬프트면 기본 그림체 참조 이미지를 지정한다.
sudo bash scripts/fetch-assets.sh 47 harudle/references/generation/dev/07-reference-style-asset.png
#    출력에 남은 테마 문구(Pepero, puppy 등)가 있으면 assets/image-style.txt에서 지운다.
#    프레임 원본을 assets/frame.png(또는 frame.webp)로 둔다.
# 3) 환경 파일
sudo cp .env.event.example .env.event && sudo chmod 600 .env.event   # GOOGLE_CLOUD_PROJECT 등 채우기
# 4) 실행
sudo docker compose -f compose.event.yaml up -d --build
curl -s 127.0.0.1:3100/health
# 5) Host Nginx + 인증서 (DNS A 레코드 event.harudle.com → Dev 앱 공인 IP 먼저)
sudo cp nginx/event.harudle.conf.example /etc/nginx/conf.d/harudle-event.conf
sudo certbot --nginx -d event.harudle.com && sudo nginx -t && sudo systemctl reload nginx
```

- Compose 프로젝트 이름이 `harudle-event`라 CodeDeploy의 `--remove-orphans`(프로젝트 `harudle`)에 지워지지 않는다.
- WIF 파일 `/etc/harudle/gcp/gcp-wif.json`을 백엔드와 같이 읽기 전용으로 마운트하고, 컨테이너는 `10001:10001`로 실행한다.
- 컨테이너 메모리 한도는 `320m`이다. Dev 시험 생성 시 실제 사용량은 약 80MB였다.

## 행사 기간 운영

메모리 여유를 위해 Dev 백엔드를 멈추는 경우:

1. Dev 배포를 멈춘다. `harudle-dev` 배포가 돌면 `ApplicationStart`가 백엔드를 다시 만든다.
2. `cd /opt/harudle && sudo docker compose -p harudle stop backend frontend` (stop만 한다. down·rm 금지)
3. Dev 백엔드 지표 경보가 있으면 팀에 알리거나 잠시 끈다.
4. 행사 후 `sudo docker compose -p harudle start backend frontend`로 되돌리고 배포를 재개한다.

이벤트 서버는 Dev DB와 백엔드를 호출하지 않는다. 프롬프트는 행사 전에 받은 파일로 고정된다.

## 접속 제한

부스 기기는 모든 참여자가 같은 IP를 쓰므로 서버의 IP당 횟수 제한은 기본으로 끈다.
대신 Host Nginx의 443 블록에서 부스 네트워크의 공인 IP만 `allow`하고 나머지는 `deny`한다.
행사장 Wi-Fi나 테더링의 외부 IP는 당일 바뀔 수 있으므로 현장에서 확인해 반영하고 `nginx -t` 후 reload한다.
80 블록에는 걸지 않는다. Certbot 갱신이 HTTP로 들어온다. 하루 전체 상한(`EVENT_DAILY_LIMIT`)은 유지한다.

## 엽서 인쇄 (Canon SELPHY CP1500)

결과 화면의 "엽서 인쇄하기"는 완성 엽서만 엽서 용지(100×148mm, 여백 없음)에 꽉 채워 인쇄한다.
엽서는 2:3, 용지는 1:1.48이라 위아래가 약 1mm씩 잘린다. 프레임 가장자리 여백 안에 들어가는 범위다.

- **프레임 해상도**: 300dpi 출력에는 가로 1200px 이상(1200×1800 권장) 프레임이 필요하다.
  더 작으면 서버 시작 로그에 `frame_low_resolution`이 남는다. 합성의 픽셀 값은 프레임 너비에 비례해 자동으로 늘어난다.
  결과 PNG에는 300dpi 정보를 넣는다.
- **생성 그림 해상도**: AI 이미지를 2K(1696×2528)로 바로 받아 프레임 크기로 줄여 칸 안쪽에 쓴다. 따로 업스케일하지 않는다.
  `gemini-nano-banana-2.1`은 `imageSize`를 무시하고 848×1264만 돌려주면서 출력 토큰은 늘어나므로 이벤트에는 쓰지 않는다.
  서버 CPU 업스케일(waifu2x)은 Dev EC2에서 메모리 부족으로 서버가 멈춰 도입하지 않았다.
  그림체가 Dev 서비스 모델과 조금 다를 수 있으니 리허설에서 여러 장 확인한다.
- **프린터 설정**: 부스 기기에서 CP1500을 기본 프린터로 두고 용지 크기 "엽서(100×148mm)", 여백 없음,
  배율 100%로 맞춘다. 브라우저 인쇄 창에서도 같은 설정을 한 번 저장해 둔다.
- **인쇄 창 없이 바로 출력**: Chrome이나 Edge를 `--kiosk --kiosk-printing` 옵션으로 실행하면
  인쇄 창 없이 기본 프린터로 바로 보낸다. 리허설에서 실제 용지로 한 장 뽑아 잘림과 색을 확인한다.

## 부스 운영: 입력 기기와 인쇄 대기열

입력 기기 2대와 프린터 기기 1대를 쓰는 구성이다. `EVENT_PRINT_QUEUE=true`를 설정한다.

```text
입력 기기 A·B  /           이야기 입력 → 생성 → "N번, 프린터에서 받아 가세요" → QR 저장 후 버튼으로 처음 화면
프린터 기기    /print      완성 엽서가 확인 대기 목록에 쌓임 → 운영자가 확인 → 인쇄하기 / 인쇄 안 함
```

- **번호표**: 접수할 때마다 그날의 순번을 붙인다(한국 시간 자정 초기화). 생성 중 화면과 결과 화면에 표시한다.
- **입력 화면**: 대기열 모드에서는 인쇄 버튼을 숨기고 번호표와 휴대폰 저장 QR을 보여 준다. QR을 찍을 시간이 필요하므로
  결과 화면은 자동으로 넘어가지 않고 **"다 받았어요, 처음으로"** 버튼을 눌러야 입력이 비워진 처음 화면으로 돌아간다.
  실패 화면만 `EVENT_RESULT_RESET_SECONDS`(기본 10초)의 3배 뒤 자동으로 돌아간다.
- **인쇄 화면**: 프린터 기기에서 Chrome이나 Edge를 `--kiosk --kiosk-printing`으로 실행하고
  `https://<도메인>/print`를 연다. `EVENT_PRINT_KEY`를 설정했다면 `/print?key=<값>`으로 한 번 열고, 키는 탭 저장소에 넣은 뒤 주소에서 지운다.
  - **기본은 확인 후 인쇄**다. 완성 엽서가 오른쪽 "확인 대기"에 미리보기로 쌓이고, 가장 먼저 온 엽서가 왼쪽에 크게 보인다.
    다른 엽서를 누르면 그 엽서를 고른다. 이상한 글자, 깨진 칸, 부적절한 내용이 없는지 보고 **인쇄하기**를 누른다.
  - **인쇄 안 함**: 뽑지 않을 엽서를 목록에서 뺀다. "최근 처리"에 건너뜀으로 남고 **되살리기**로 다시 넣을 수 있다.
  - **다시 뽑기**: 최근 인쇄 목록에서 누르면 확인 대기 맨 앞에 다시 넣는다.
  - 인쇄하기를 연달아 눌러도 기다리지 않는다. 보낸 엽서는 프린터(CP1500) 자체 인쇄 대기열에 쌓여 한 장씩(약 41초) 나온다.
  - 인쇄는 엽서 전체가 용지 안쪽 안전 여백(기본 4mm) 안에 들어가게 줄여서 보낸다. 실제 출력에서 "여백 없음, 배율 100%"로도
    위아래가 잘려서 바꾼 것이다. 여백은 주소에 `?inset=6`처럼 mm 단위로 바꾼다.
    인쇄 뒤 버튼을 잠깐 잠그고 싶으면 `?gap=`(초)를 붙인다.
  - `?auto=1`이면 확인 없이 완성 순서대로 자동 인쇄한다. 이때만 **일시정지** 버튼이 나온다.
  - 가져간 엽서에 `EVENT_PRINT_LEASE_SECONDS`(기본 120초) 안에 완료 보고가 없으면 다시 대기열로 돌린다.
    인쇄 화면을 새로고침하거나 기기가 꺼져도 엽서가 사라지지 않는다.
- 인쇄 화면과 인쇄 API(`/api/print/*`)는 기본으로 키 없이 열려 있다. **행사 전에 반드시 Host Nginx IP 화이트리스트를 걸어야 한다.**
  화이트리스트 전에는 누구나 `/print`에서 엽서를 보거나 인쇄를 건너뛸 수 있다. `EVENT_PRINT_KEY`를 설정하면 `x-print-key` 헤더도 확인한다.
- 대기열은 메모리에만 있어 서버를 재시작하면 사라진다.

## 알려진 한계

- AI가 지시하지 않은 짧은 효과음 글자를 칸 안에 넣는 경우가 있다(실험 3장 중 1장). 자동 검출은 하지 않는다.
- 사용자 입력 금칙어 필터는 없다. 행사장 출력 전에 사람이 확인한다.
