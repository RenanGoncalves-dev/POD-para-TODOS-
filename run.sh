#!/usr/bin/env bash
# Instala (1ª vez) e inicia o servidor em 0.0.0.0:8000
set -e
cd "$(dirname "$0")/server"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -U -r requirements.txt   # -U mantém o yt-dlp atualizado (o YouTube muda sempre)
echo "Abra no celular: http://$(hostname -I | awk '{print $1}'):8000"
exec .venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000

