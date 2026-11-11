#!/usr/bin/env bash
# Dev 앱 EC2에서 실행한다. Dev DB의 generation_prompts 한 건과 S3 참조 이미지를 assets/에 받는다.
# DB는 SELECT, S3는 GET만 수행한다. 행사 전에 한 번 받아 두고, 행사 중에는 다시 받지 않는다.
#
# 사용: sudo bash scripts/fetch-assets.sh [PROMPT_ID] [REFERENCE_OBJECT_KEY]
#   PROMPT_ID 생략 시 가장 최근 id
#   REFERENCE_OBJECT_KEY 생략 시 해당 프롬프트의 image_asset_object_key
#   테마 참조 이미지 대신 기본 그림체를 쓰려면 예: harudle/references/generation/dev/07-reference-style-asset.png
set -euo pipefail
cd "$(dirname "$0")/.."
ASSETS="$PWD/assets"
mkdir -p "$ASSETS"
ENV_FILE=/opt/harudle/.env
envv() { sed -n "s/^$1=//p" "$ENV_FILE" | tr -d '\r' | sed -e 's/^"//' -e 's/"$//'; }
DBH=$(envv DB_HOST); DBP=$(envv DB_PORT); DBN=$(envv DB_NAME); DBU=$(envv DB_USERNAME); DBPW=$(envv DB_PASSWORD)
BUCKET=$(envv S3_BUCKET)
psqlq() {
  docker run --rm --network host -e PGPASSWORD="$DBPW" postgres:18-alpine \
    psql -h "$DBH" -p "$DBP" -U "$DBU" -d "$DBN" -v ON_ERROR_STOP=1 -X -At -c "$1"
}
ID=${1:-$(psqlq "select max(id) from generation_prompts")}
[[ "$ID" =~ ^[0-9]+$ ]] || { echo "잘못된 PROMPT_ID: $ID" >&2; exit 1; }
KEY=${2:-$(psqlq "select image_asset_object_key from generation_prompts where id = $ID")}

psqlq "select storyboard_prompt_text from generation_prompts where id = $ID" > "$ASSETS/storyboard-prompt.txt"
psqlq "select image_style_prompt_text from generation_prompts where id = $ID" > "$ASSETS/image-style.raw.txt"
# 시즌 테마 프레임 구간은 이벤트 프레임과 충돌하므로 잘라낸다
sed '/^SEASONAL FRAME/,$d' "$ASSETS/image-style.raw.txt" > "$ASSETS/image-style.txt"
aws s3 cp --only-show-errors "s3://$BUCKET/$KEY" "$ASSETS/reference.png"
chmod 640 "$ASSETS"/*.txt "$ASSETS/reference.png"
chgrp 10001 "$ASSETS"/*.txt "$ASSETS/reference.png"

echo "prompt id=$ID reference=$KEY"
echo "남은 테마 문구(있으면 image-style.txt에서 직접 지운다):"
grep -n -i -E "pepero|puppy|seasonal|square" "$ASSETS/image-style.txt" | cut -c1-200 || echo "  없음"
