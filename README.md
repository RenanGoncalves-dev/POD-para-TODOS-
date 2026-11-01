# POD — Podcasts, Músicas e DJ Sets Offline

Aplicativo **100% nativo** para **Android (`.apk`)**, **Linux (`.AppImage`)** e **Windows (`.exe`)**, com visual estilo Spotify, que baixa somente o **áudio** (`.m4a`/`.opus`) e a **capa (thumbnail)** de vídeos, playlists e canais do YouTube para ouvir **totalmente offline**.

## Downloads (Releases)
Na aba **Releases** do GitHub você encontra os pacotes prontos:
- **`POD.apk`** — App nativo para Android (Compose + ExoPlayer).
- **`POD-Linux-x86_64.AppImage`** — App Desktop nativo para Linux (Qt6 + FFmpeg embutido, basta dar 2 cliques ou rodar `./dist-desktop/POD-Linux-x86_64.AppImage`).
- **`POD-Windows.exe`** — App Desktop nativo para Windows (janela própria Qt6, sem navegador).

## Principais Funções
- **100% Nativo e Offline:** Abre em janela própria estilo Spotify (com barra lateral de Categorias e Playlists, lista com capas e player inferior). Funciona mesmo sem internet.
- **Categorias e Playlists:** Separação em **🎙 Podcasts**, **🎵 Músicas** e **🎛 DJ Sets**, além de criação de **Playlists** personalizadas com reprodução contínua e aleatória (`🔀`).
- **Escolha de Pasta / Cartão de Memória:** Escolha qualquer pasta do PC (Windows Explorer / Linux) ou cartão SD no celular; o app reconhece automaticamente todos os áudios e capas que já estiverem lá.
- **Qualidade de Vida:** Modo **Escuro / Claro**, **Sleep Timer** (`⏲️ 15m/30m/45m/60m`), velocidades `0.75x` a `2.0x`, retomada automática de onde parou (`⏱`) e busca direta no YouTube pelo nome.
- **Overlay para OBS Studio ("Tocando agora"):** Enquanto o app está aberto, adicione no OBS uma fonte **Navegador** (`600x160`) apontando para `http://127.0.0.1:8000/overlay.html` (ou use o botão *🎥 Copiar URL Overlay OBS* na barra lateral).

## Como compilar localmente
- **Linux Desktop + AppImage:** `./run-desktop.sh` (para abrir direto) ou `.venv-desktop/bin/python desktop/build.py` (gera `dist-desktop/POD-Linux-x86_64.AppImage`).
- **Windows Desktop (.exe):** `run-desktop.bat` ou `python desktop\build.py` (gera `dist-desktop\POD-Windows.exe`).
- **Android APK:** `./build-apk.sh` (gera `dist/POD.apk`).
