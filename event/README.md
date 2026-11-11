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
   실패 시 한 번 재시도한다.
2. **이미지**: `image-style.txt`, 그림체 참조 `reference.png`, 프레임 이미지, 최종 지시문을 `gemini-nano-banana-2.1`에 보낸다.
   최종 지시문의 스토리 본문은 백엔드 `DiaryImagePromptRenderer`와 같은 문구이고, 캔버스·푸터 규칙만 프레임용으로 바뀐다.
3. **합성**: 원본 프레임에서 네 칸의 흰 안쪽과 제목 글씨만 생성본으로 바꾼다. 배경·상단 문구·캐릭터·핸들·QR은
   원본 픽셀을 그대로 쓴다. 생성본의 칸 위치가 40px 넘게 어긋나거나 제목을 못 찾으면 이미지 단계만 한 번 다시 만든다.

백엔드 렌더러·스키마가 바뀌면 `src/storyboard.mjs`, `src/prompt.mjs`를 함께 맞춘다.

## 제한과 개인정보

| 항목 | 기본값 | 설정 |
| --- | --- | --- |
| 동시 생성 | 1 | `EVENT_CONCURRENCY` |
| 대기열 상한(초과 시 503) | 20 | `EVENT_MAX_QUEUE` |
| IP당 시간당 접수 | 3 | `EVENT_RATE_LIMIT_PER_HOUR` |
| 하루 전체 접수(한국 시간 자정 초기화) | 300 | `EVENT_DAILY_LIMIT` |
| 접속 코드(QR 주소 `?code=`) | 없음 | `EVENT_ACCESS_CODE` |
| 결과 보관 | 120분 | `EVENT_RESULT_TTL_MINUTES` |

- 거절된 요청은 횟수를 차감하지 않는다. 접수 후 실패는 차감한다.
- 일기 원문은 메모리에만 두고 생성이 끝나면 버린다. 로그에는 일기와 프롬프트를 남기지 않는다.
- 부스 기기에 띄워 두는 화면이라 동의 체크박스는 두지 않고, 생성 버튼 아래에 Google 전송과 보관 기간을 안내한다.
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

## 알려진 한계

- 프레임 원본 1024×1536은 4×6 인쇄 기준 약 256dpi다. 300dpi가 필요하면 1200×1800 이상 프레임을 받는다.
  이 경우 `src/composite.mjs`의 `LAYOUT`(제목 높이 등 픽셀 값)도 비율에 맞춰 확인한다.
- AI가 지시하지 않은 짧은 효과음 글자를 칸 안에 넣는 경우가 있다(실험 3장 중 1장). 자동 검출은 하지 않는다.
- 사용자 입력 금칙어 필터는 없다. 행사장 출력 전에 사람이 확인한다.
