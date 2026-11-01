# POD-para-TODOS-

Baixa o áudio de vídeos/podcasts do YouTube para ouvir offline. Categorias: Podcasts, Músicas e DJ Sets.

## Android
`./build-apk.sh` gera `dist/POD.apk`.

## Linux e Windows (desktop)
- **Linux:** `./run-desktop.sh`  |  **Windows:** `run-desktop.bat` (precisa de Python 3.10+)
- **Executável único:** `python desktop/build.py` gera `dist-desktop/POD` (ou `POD.exe` no Windows).
  A aba *Actions* do GitHub (Desktop) compila Linux e Windows automaticamente; baixe em *Artifacts*.
- A opção `--lan` deixa o celular acessar o PC pela rede. Para o YouTube, instale também Node.js ou Deno.

## Overlay para OBS ("Tocando agora")
1. Abra o app e toque algo.
2. No OBS: **Fontes → + → Navegador**, URL `http://localhost:8000/overlay.html`, largura 600, altura 160.
3. Opções na URL: `?hide=0` (fica visível pausado), `&accent=ff4d4d` (cor), `&bg=transparent`, `&progress=0`.
4. Alternativa: fonte *Texto (ler de arquivo)* apontando para `nowplaying.txt` na pasta de dados
   (`~/.local/share/POD` no Linux, `%APPDATA%\POD` no Windows).
