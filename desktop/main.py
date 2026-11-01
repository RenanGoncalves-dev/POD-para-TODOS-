"""POD para Linux e Windows: sobe o servidor local e abre o app numa janela própria
(ou no navegador, se não houver suporte a janela). Mesmas funções da versão web/celular
+ overlay para o OBS em http://localhost:PORTA/overlay.html"""
import argparse
import os
import socket
import sys
import threading
import time
import webbrowser
from pathlib import Path


def data_dir() -> Path:
    if os.environ.get("POD_DATA"):
        return Path(os.environ["POD_DATA"])
    if os.name == "nt":
        return Path(os.environ.get("APPDATA", Path.home())) / "POD"
    return Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "POD"


def free_port(preferred: int, host: str) -> int:
    for port in (preferred, 0):
        with socket.socket() as s:
            try:
                s.bind((host, port))
                return s.getsockname()[1]
            except OSError:
                continue
    raise RuntimeError("sem porta livre")


def main():
    ap = argparse.ArgumentParser(prog="POD")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--lan", action="store_true", help="aceita conexões da rede (celular, OBS em outro PC)")
    ap.add_argument("--browser", action="store_true", help="abre no navegador em vez de janela própria")
    ap.add_argument("--no-open", action="store_true", help="só inicia o servidor")
    args = ap.parse_args()

    os.environ.setdefault("POD_DATA", str(data_dir()))
    Path(os.environ["POD_DATA"]).mkdir(parents=True, exist_ok=True)
    if not getattr(sys, "frozen", False):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

    import uvicorn
    from app import app  # servidor FastAPI

    host = "0.0.0.0" if args.lan else "127.0.0.1"
    port = free_port(args.port, host)
    server = uvicorn.Server(uvicorn.Config(app, host=host, port=port, log_level="warning"))
    threading.Thread(target=server.run, daemon=True).start()
    while not server.started:
        time.sleep(0.1)

    url = f"http://127.0.0.1:{port}"
    print(f"POD rodando em {url}")
    print(f"Overlay do OBS (Fonte de Navegador, 600x160): {url}/overlay.html")
    print(f"Dados em: {os.environ['POD_DATA']}")

    if args.no_open:
        threading.Event().wait()
        return
    if not args.browser:
        try:
            import webview  # pywebview (opcional)

            webview.create_window("POD", url, width=1100, height=820, min_size=(420, 600))
            webview.start()
            server.should_exit = True
            return
        except Exception as e:  # noqa: BLE001
            print(f"(janela própria indisponível: {e}; abrindo no navegador)")
    webbrowser.open(url)
    try:
        threading.Event().wait()  # Ctrl+C para sair
    except KeyboardInterrupt:
        server.should_exit = True


if __name__ == "__main__":
    main()

