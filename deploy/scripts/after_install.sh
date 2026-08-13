#!/usr/bin/env bash

set -Eeuo pipefail

readonly APP_DIR="/opt/harudle"
readonly IMAGE_DIR="${APP_DIR}/images"

cd "${IMAGE_DIR}"
sha256sum --check image-checksums.sha256

gzip -dc backend-image.tar.gz | docker load
gzip -dc frontend-image.tar.gz | docker load

docker image inspect harudle-backend:local >/dev/null
docker image inspect harudle-frontend:local >/dev/null

# CodeDeploy keeps the revision bundle for rollback, so target copies can be
# removed after loading to conserve the EC2 volume.
rm -f backend-image.tar.gz frontend-image.tar.gz image-checksums.sha256
