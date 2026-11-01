"""Gera o aplicativo Desktop Nativo (estilo Spotify em PyQt6):
- No Linux: gera dist-desktop/POD-Linux-x86_64.AppImage (e dist-desktop/POD)
- No Windows: gera dist-desktop/POD-Windows.exe"""
import os
import shutil
import stat
import subprocess
import sys
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
dist_dir = root / "dist-desktop"
build_dir = root / "build-desktop"
dist_dir.mkdir(parents=True, exist_ok=True)
build_dir.mkdir(parents=True, exist_ok=True)

exe_name = "POD-Windows" if os.name == "nt" else "POD"

cmd = [
    sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile",
    "--windowed",
    "--name", exe_name,
    "--distpath", str(dist_dir),
    "--workpath", str(build_dir),
    "--specpath", str(build_dir),
    "--paths", str(root / "server"),
    "--add-data", f"{root / 'web'}{os.pathsep}web",
    "--collect-all", "yt_dlp",
    "--collect-submodules", "uvicorn",
    "--hidden-import", "app",
    "--hidden-import", "PyQt6.QtMultimedia",
]
try:
    import yt_dlp_ejs  # noqa: F401
    cmd += ["--collect-all", "yt_dlp_ejs"]
except ImportError:
    pass

cmd.append(str(root / "desktop" / "main.py"))
subprocess.check_call(cmd)


def build_appimage():
    """Empacota o binário Linux em um .AppImage autocontido."""
    appdir = build_dir / "POD.AppDir"
    if appdir.exists():
        shutil.rmtree(appdir)
    (appdir / "usr" / "bin").mkdir(parents=True, exist_ok=True)

    bin_src = dist_dir / "POD"
    bin_dst = appdir / "usr" / "bin" / "POD"
    shutil.copy2(bin_src, bin_dst)
    bin_dst.chmod(bin_dst.stat().st_mode | stat.S_IEXEC)

    apprun = appdir / "AppRun"
    apprun.write_text('#!/bin/sh\nHERE="$(dirname "$(readlink -f "$0")")"\nexec "$HERE/usr/bin/POD" "$@"\n')
    apprun.chmod(0o755)

    desktop_entry = appdir / "POD.desktop"
    desktop_entry.write_text(
        "[Desktop Entry]\n"
        "Type=Application\n"
        "Name=POD\n"
        "Comment=Podcasts, Musicas e DJ Sets Offline\n"
        "Exec=POD\n"
        "Icon=pod\n"
        "Categories=AudioVideo;Audio;Player;\n"
        "Terminal=false\n"
    )

    icon_src = root / "web" / "icons" / "icon.svg"
    if icon_src.is_file():
        shutil.copy2(icon_src, appdir / "pod.svg")
        shutil.copy2(icon_src, appdir / ".DirIcon")

    tool = build_dir / "appimagetool"
    if not tool.is_file():
        url = "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
        urllib.request.urlretrieve(url, tool)
        tool.chmod(0o755)

    out_appimage = dist_dir / "POD-Linux-x86_64.AppImage"
    env = {**os.environ, "ARCH": "x86_64", "APPIMAGE_EXTRACT_AND_RUN": "1"}
    subprocess.check_call([str(tool), str(appdir), str(out_appimage)], env=env)
    out_appimage.chmod(0o755)
    print("AppImage gerado com sucesso:", out_appimage)


if os.name != "nt":
    try:
        build_appimage()
    except Exception as e:  # noqa: BLE001
        print(f"Aviso ao gerar AppImage: {e}")

print("Pronto:", dist_dir)
