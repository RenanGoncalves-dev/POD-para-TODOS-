"""Gera o executável (Linux ou Windows, conforme o sistema onde roda) em dist-desktop/POD[.exe]"""
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
cmd = [
    sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile",
    "--name", "POD",
    "--distpath", str(root / "dist-desktop"),
    "--workpath", str(root / "build-desktop"),
    "--specpath", str(root / "build-desktop"),
    "--paths", str(root / "server"),
    "--add-data", f"{root / 'web'}{os.pathsep}web",
    "--collect-all", "yt_dlp",
    "--collect-submodules", "uvicorn",
    "--hidden-import", "app",
]
try:
    import yt_dlp_ejs  # noqa: F401  (solver de desafios JS do YouTube)
    cmd += ["--collect-all", "yt_dlp_ejs"]
except ImportError:
    pass
if os.name == "nt":
    cmd.append("--windowed")  # sem janela de console
cmd.append(str(root / "desktop" / "main.py"))
subprocess.check_call(cmd)
print("Pronto:", root / "dist-desktop")

