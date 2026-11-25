#!/usr/bin/env bash
#
# Build the backend image locally and ship it to the cloud VM.
#
# The reference VM is a 1 GB VM.Standard.E2.1.Micro. Building there works but takes about
# forty minutes whenever Cargo.toml changes, because that invalidates the Docker
# dependency layer and every crate recompiles. Building on a developer machine
# and shipping the image takes a couple of minutes instead.
#
# This only works while the VM is x86_64. If it is ever moved to an Ampere A1
# shape the image would need to be built for linux/arm64, or built on the VM.
#
# Usage:
#   scripts/deploy_cloud_backend.sh            # build, ship, restart, verify
#   SKIP_BUILD=1 scripts/deploy_cloud_backend.sh   # reuse the local image
#   WITH_PAPAGEI=1 scripts/deploy_cloud_backend.sh # also rebuild and ship the PaPaGei worker
#                                                  # (its prediction heads are baked into its image)
#   SYNC_COMPOSE=1 scripts/deploy_cloud_backend.sh # also copy docker-compose.yml to the VM
#
# Full procedure, backup and rollback: docs/DEPLOY_RUNBOOK.md. Run it from a clean checkout
# (or `git worktree add --detach <dir> <commit>`) so a half-edited working tree is never shipped.
#
# Where to deploy comes from deployment/cloud/deploy.env (gitignored; copy
# deploy.env.example), or from the environment:
#   VM_HOST     ssh target, e.g. ubuntu@203.0.113.10
#   VM_KEY      path to the ssh private key
#   VM_APP_DIR  checkout on the server (default /home/ubuntu/wearable-app)
#   IMAGE, PLATFORM

set -euo pipefail

DEPLOY_ENV="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deployment/cloud/deploy.env"
if [ -f "$DEPLOY_ENV" ]; then
  # shellcheck disable=SC1090
  . "$DEPLOY_ENV"
fi
VM_HOST="${VM_HOST:?set VM_HOST in deployment/cloud/deploy.env (see deploy.env.example)}"
VM_KEY="${VM_KEY:?set VM_KEY in deployment/cloud/deploy.env (see deploy.env.example)}"
VM_APP_DIR="${VM_APP_DIR:-/home/ubuntu/wearable-app}"
IMAGE="${IMAGE:-cloud-backend:latest}"
PLATFORM="${PLATFORM:-linux/amd64}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PAPAGEI_IMAGE="${PAPAGEI_IMAGE:-cloud-papagei:latest}"
WITH_PAPAGEI="${WITH_PAPAGEI:-0}"
SYNC_COMPOSE="${SYNC_COMPOSE:-0}"

TARBALL="${TMPDIR:-/tmp}/cloud-backend-image.tar.gz"
REMOTE_TARBALL="/tmp/cloud-backend-image.tar.gz"

step() { printf '\n== %s ==\n' "$1"; }

if [ ! -f "$VM_KEY" ]; then
  echo "SSH key not found at $VM_KEY" >&2
  exit 1
fi

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  step "Building $IMAGE for $PLATFORM"
  # Context is the repository root: the Dockerfile copies backend/ from there.
  docker build \
    --platform "$PLATFORM" \
    --build-arg GIT_SHA="$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- backend || echo -dirty)" \
    -f deployment/cloud/Dockerfile \
    -t "$IMAGE" \
    .
  if [ "$WITH_PAPAGEI" = "1" ]; then
    step "Building $PAPAGEI_IMAGE for $PLATFORM"
    docker build --platform "$PLATFORM" -f deployment/cloud/papagei.Dockerfile -t "$PAPAGEI_IMAGE" .
  fi
else
  step "Reusing local image $IMAGE"
fi

step "Checking the built binary's architecture"
BUILT_ARCH="$(docker image inspect "$IMAGE" --format '{{.Architecture}}')"
echo "image architecture: $BUILT_ARCH"
REMOTE_ARCH="$(ssh -i "$VM_KEY" -o BatchMode=yes "$VM_HOST" 'dpkg --print-architecture')"
echo "VM architecture:    $REMOTE_ARCH"
if [ "$BUILT_ARCH" != "$REMOTE_ARCH" ]; then
  echo "Architecture mismatch: the image will not run on the VM." >&2
  echo "Set PLATFORM=linux/arm64 if the VM has moved to an Ampere A1 shape." >&2
  exit 1
fi

step "Saving and compressing the image"
if [ "$WITH_PAPAGEI" = "1" ]; then
  docker save "$IMAGE" "$PAPAGEI_IMAGE" | gzip -1 > "$TARBALL"
else
  docker save "$IMAGE" | gzip -1 > "$TARBALL"
fi
ls -lh "$TARBALL"

step "Copying to the VM"
scp -i "$VM_KEY" "$TARBALL" "$VM_HOST:$REMOTE_TARBALL"

if [ "$SYNC_COMPOSE" = "1" ]; then
  step "Copying docker-compose.yml (previous one kept as docker-compose.yml.prev)"
  ssh -i "$VM_KEY" -o BatchMode=yes "$VM_HOST" "cp -p $VM_APP_DIR/deployment/cloud/docker-compose.yml $VM_APP_DIR/deployment/cloud/docker-compose.yml.prev"
  scp -i "$VM_KEY" deployment/cloud/docker-compose.yml "$VM_HOST:$VM_APP_DIR/deployment/cloud/docker-compose.yml"
fi

# Keep the images that are running under a :previous tag so a rollback is one retag away.
step "Loading on the VM and restarting"
SERVICES="backend"
if [ "$WITH_PAPAGEI" = "1" ]; then SERVICES="papagei backend"; fi
TAG_PREVIOUS="docker tag ${IMAGE} ${IMAGE%%:*}:previous || true"
if [ "$WITH_PAPAGEI" = "1" ]; then TAG_PREVIOUS="$TAG_PREVIOUS; docker tag ${PAPAGEI_IMAGE} ${PAPAGEI_IMAGE%%:*}:previous || true"; fi
ssh -i "$VM_KEY" -o BatchMode=yes "$VM_HOST" \
  "$TAG_PREVIOUS; gunzip -c $REMOTE_TARBALL | docker load && rm -f $REMOTE_TARBALL && cd $VM_APP_DIR/deployment/cloud && docker compose up -d --no-build $SERVICES"

step "Verifying"
# /health sits behind the bearer check, so read the token from the VM's own
# .env rather than passing a secret through this script.
#
# Poll rather than sampling once: a cold start on the small VM takes a few
# seconds, and an immediate curl reports 000, which looks like a failure and
# hides a real one. The final test makes a genuinely unhealthy deploy exit
# non-zero instead of printing "Done".
ssh -i "$VM_KEY" -o BatchMode=yes "$VM_HOST" \
  "cd $VM_APP_DIR/deployment/cloud && set -a && . ./.env && set +a && \
   code=000; \
   for _ in \$(seq 1 30); do \
     code=\$(curl -s -o /dev/null -w '%{http_code}' -H \"Authorization: Bearer \$API_TOKEN\" http://127.0.0.1:8080/health || true); \
     case \"\$code\" in 200|401) break;; esac; \
     sleep 2; \
   done; \
   printf 'health: %s\n' \"\$code\"; \
   docker compose ps backend; \
   case \"\$code\" in 200) ;; 401) echo 'backend up (shared token disabled, so /health answers 401)';; *) exit 1;; esac"

rm -f "$TARBALL"
printf '\nDone. Deployed %s to %s\n' "$IMAGE" "$VM_HOST"
