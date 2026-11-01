@echo off
REM POD no Windows: instala (1a vez) e abre o app. Requer Python 3.10+ (python.org). Opcional: Node.js (nodejs.org) para o YouTube.
cd /d "%~dp0"
if not exist .venv-desktop (
  python -m venv .venv-desktop
)
.venv-desktop\Scripts\pip install -q -U -r desktop\requirements.txt
.venv-desktop\Scripts\python desktop\main.py %*

