# 이벤트 서버 에셋

이 폴더의 파일은 저장소에 올리지 않는다. 서버에서는 `/opt/harudle-event/assets/`에 둔다.

| 파일 | 내용 | 준비 방법 |
| --- | --- | --- |
| `frame.png` 또는 `frame.webp` | 데모데이 엽서 프레임(2:3) | 디자인 원본. 인쇄용이면 1200×1800 이상 권장 |
| `storyboard-prompt.txt` | 스토리보드 시스템 프롬프트 | `scripts/fetch-assets.sh` |
| `image-style.txt` | 이미지 스타일 시스템 프롬프트(테마 구간 제거) | `scripts/fetch-assets.sh` 후 남은 테마 문구 확인 |
| `reference.png` | 그림체 참조 이미지 | `scripts/fetch-assets.sh` |
| `sample.webp` | 첫 화면에 보여 줄 완성 엽서 예시(선택) | 미리 생성한 결과를 가로 720px 정도로 변환. 없으면 빈 프레임을 보여 준다 |
| `fake/storyboard.json`, `fake/generated.png` | `EVENT_FAKE_AI=true`용 고정 응답 | 로컬 확인용. 서버에는 필요 없음 |

프레임을 바꾸면 `src/composite.mjs`의 `LAYOUT` 값(칸 중심, 새·강아지 위치, 제목 크기)을 함께 확인한다.
