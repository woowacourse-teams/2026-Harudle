# 하루들

## 생성 프롬프트 초기화

운영에서 승인한 참조 이미지를 비공개 이미지 저장소에 먼저 업로드한 뒤 다음 환경 변수를 주입한다.

- `HARUDLE_GENERATION_PROMPT_BOOTSTRAP_ENABLED=true`
- `HARUDLE_GENERATION_PROMPT_BOOTSTRAP_STORYBOARD_PROMPT_TEXT`
- `HARUDLE_GENERATION_PROMPT_BOOTSTRAP_IMAGE_STYLE_PROMPT_TEXT`
- `HARUDLE_GENERATION_PROMPT_BOOTSTRAP_IMAGE_ASSET_OBJECT_KEY`

초기화는 `generation_prompts` 테이블이 비어 있을 때만 실행된다. 참조 이미지를 읽을 수 없거나 설정이
불완전하면 애플리케이션 시작을 중단하며, 여러 인스턴스가 동시에 시작되어도 한 행만 등록한다. 실제
프롬프트 본문과 이미지 키는 저장소에 커밋하지 않는다.

프롬프트를 변경하거나 롤백할 때 기존 행을 수정하지 않고 새 행을 추가한다. 가장 큰 ID의 프롬프트가
새 생성 요청에 사용되고, 이전 생성 기록은 당시 프롬프트를 계속 참조한다.
