#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose plugin is not available."
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example. Please change POSTGRES_PASSWORD before production use."
fi

docker compose up -d --build
docker compose ps

echo
echo "EDSP Demo is starting."
echo "Open: http://<your-server-ip>:${FRONTEND_PORT:-18080}"
echo "If the page is not ready yet, wait 30-60 seconds and refresh."
