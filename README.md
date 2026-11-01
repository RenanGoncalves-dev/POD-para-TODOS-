# POD-para-TODOS-

Baixa somente o **áudio** (`.m4a`/`.opus`) e a **capa (thumbnail)** de vídeos, playlists e canais do YouTube para ouvir **100% offline**, com suporte a cartão de memória, categorias (**Podcasts**, **Músicas** e **DJ Sets**), **Playlists personalizadas** e **Overlay para OBS**.

## Downloads (Releases)
Na aba **Releases** do GitHub você encontra os arquivos prontos para cada plataforma:
- **`POD.apk`** — App nativo para Android (baixa direto no celular, salva no cartão SD, toca em segundo plano e com a tela bloqueada).
- **`POD-Linux`** — Executável para Linux.
- **`POD-Windows.exe`** — Executável para Windows.

## Rodando pelo código-fonte
- **Android (gerar APK):** `./build-apk.sh` → gera `dist/POD.apk`.
- **Linux (desktop):** `./run-desktop.sh`
- **Windows (desktop):** `run-desktop.bat` (requer Python 3.10+)
- **Servidor Web / PWA na rede local:** `./run.sh` (ou passe `--lan` no desktop para acessar pelo celular).

## Overlay para OBS ("Tocando agora")
1. Abra o app no PC e dê play em qualquer faixa ou playlist.
2. No OBS: **Fontes → + → Navegador**, URL `http://localhost:8000/overlay.html`, largura `600`, altura `160`.
3. Parâmetros opcionais na URL: `?hide=0` (mantém visível quando pausado), `&accent=ff4d4d` (cor), `&bg=transparent`, `&progress=0`.
4. Alternativa em texto: fonte *Texto (ler de arquivo)* apontando para `nowplaying.txt` na pasta de dados (`~/.local/share/POD` no Linux ou `%APPDATA%\POD` no Windows).
