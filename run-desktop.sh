#!/usr/bin/env bash
# POD no Linux: instala (1ª vez) e abre o app (janela própria se disponível, senão o navegador)
set -e
cd "$(dirname "$0")"
[ -d .venv-desktop ] || python3 -m venv .venv-desktop
.venv-desktop/bin/pip install -q -U -r desktop/requirements.txt
exec .venv-desktop/bin/python desktop/main.py "$@"

