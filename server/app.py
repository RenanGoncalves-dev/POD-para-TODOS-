"""POD - servidor e motor de mídia: baixa ÁUDIO do YouTube (yt-dlp), links diretos de podcasts
(.mp3/.m4a, ex: Jovem Nerd), feeds RSS e páginas web, extrai capas ID3 para uso 100% offline
e organiza em categorias e playlists."""
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

import yt_dlp
from fastapi import BackgroundTasks, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

ROOT = Path(__file__).resolve().parent
# POD_DATA permite o app desktop guardar os dados na pasta do usuário
DATA = Path(os.environ.get("POD_DATA") or ROOT / "data")
DEFAULT_AUDIO = DATA / "audio"
CONFIG = DATA / "config.json"
# empacotado (PyInstaller) os arquivos web ficam dentro de _MEIPASS
WEB = (Path(sys._MEIPASS) if getattr(sys, "frozen", False) else ROOT.parent) / "web"
DATA.mkdir(parents=True, exist_ok=True)
AUDIO_EXTS = {".m4a", ".mp3", ".opus", ".ogg", ".webm", ".aac", ".wav", ".flac"}
THUMB_EXTS = (".jpg", ".jpeg", ".webp", ".png")
ID_RE = re.compile(r"\[([\w-]{11})\]$")
SAFE_CHARS_RE = re.compile(r'[<>:"/\\|?*\x00-\x1f]+')
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

_lock = threading.Lock()


def load_audio_dir() -> Path:
    try:
        return Path(json.loads(CONFIG.read_text())["audio_dir"])
    except Exception:  # noqa: BLE001
        return DEFAULT_AUDIO


AUDIO = load_audio_dir()


def safe_filename(name: str, max_len: int = 100) -> str:
    cleaned = SAFE_CHARS_RE.sub(" ", name).strip().strip(".")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return (cleaned[:max_len].strip() or "episodio")


def short_id(text: str) -> str:
    """Gera um ID de 11 caracteres compatível com o padrão [id] para links externos."""
    return hashlib.sha1(text.encode("utf-8", errors="ignore")).hexdigest()[:11]


def unwrap_download_url(raw_url: str) -> str:
    """Desembrulha links redirecionadores como https://jovemnerd.com.br/api/download?file=https://...mp3"""
    try:
        parsed = urllib.parse.urlsplit(raw_url)
        qs = urllib.parse.parse_qs(parsed.query)
        for key in ("file", "url", "audio", "mp3", "src", "download", "media"):
            for val in qs.get(key, []):
                if val.startswith(("http://", "https://")):
                    return val
    except Exception:  # noqa: BLE001
        pass
    return raw_url


def extract_embedded_metadata(audio_path: Path) -> dict:
    """Lê tags ID3/MP4 (título, canal/podcast, duração e capa embutida APIC/covr) de arquivos MP3/M4A/OGG."""
    info: dict = {"title": None, "channel": None, "duration": 0, "thumb_saved": False}
    try:
        import mutagen
        from mutagen.id3 import ID3
        from mutagen.mp4 import MP4

        f = mutagen.File(str(audio_path))
        if f is None:
            return info
        if getattr(f, "info", None) and getattr(f.info, "length", None):
            info["duration"] = int(f.info.length)

        thumb_dest = audio_path.with_suffix(".jpg")
        # MP3 / ID3
        if isinstance(f.tags, ID3) or audio_path.suffix.lower() == ".mp3":
            tags = f.tags if isinstance(f.tags, ID3) else ID3(str(audio_path))
            if "TIT2" in tags and str(tags["TIT2"]).strip():
                info["title"] = str(tags["TIT2"]).strip()
            album = str(tags.get("TALB", "")).strip()
            artist = str(tags.get("TPE1", "")).strip()
            info["channel"] = album or artist or None
            for k in tags.keys():
                if k.startswith("APIC") and getattr(tags[k], "data", None):
                    thumb_dest.write_bytes(tags[k].data)
                    info["thumb_saved"] = True
                    break
        # M4A / MP4
        elif isinstance(f, MP4):
            tags = f.tags or {}
            if "©nam" in tags and tags["©nam"]:
                info["title"] = str(tags["©nam"][0]).strip()
            album = str(tags.get("©alb", [""])[0]).strip()
            artist = str(tags.get("©ART", [""])[0]).strip()
            info["channel"] = album or artist or None
            covr = tags.get("covr")
            if covr:
                thumb_dest.write_bytes(bytes(covr[0]))
                info["thumb_saved"] = True
        # Outros formatos (Ogg/Opus/FLAC)
        elif f.tags:
            t = f.tags.get("title") or f.tags.get("TITLE")
            a = f.tags.get("album") or f.tags.get("artist") or f.tags.get("ARTIST")
            if t:
                info["title"] = str(t[0] if isinstance(t, list) else t).strip()
            if a:
                info["channel"] = str(a[0] if isinstance(a, list) else a).strip()
            pics = getattr(f, "pictures", None)
            if pics:
                thumb_dest.write_bytes(pics[0].data)
                info["thumb_saved"] = True
    except Exception:  # noqa: BLE001
        pass
    return info


def js_runtimes() -> dict:
    """yt-dlp precisa de um runtime JS para o YouTube: usa deno ou node, se instalados."""
    return {name: {} for name in ("deno", "node") if shutil.which(name)}


def removable_mounts() -> list[str]:
    """Cartão SD / pendrive: pontos de montagem no Linux, letras de unidade removível no Windows."""
    out = []
    if os.name == "nt":
        import ctypes
        import string
        for letter in string.ascii_uppercase:
            drv = f"{letter}:\\"
            if ctypes.windll.kernel32.GetDriveTypeW(drv) == 2 and os.path.exists(drv):  # 2 = removível
                out.append(drv)
        return out
    try:
        for line in Path("/proc/mounts").read_text().splitlines():
            mp = line.split()[1].replace("\\040", " ")
            if mp.startswith(("/media/", "/run/media/")) and os.access(mp, os.W_OK):
                out.append(mp)
    except OSError:
        pass
    return out


def pick_folder_native(initial: str) -> str | None:
    """Abre a janela nativa do Windows ou Linux para escolher qualquer pasta do PC."""
    if os.name == "nt":
        try:
            import tkinter as tk
            from tkinter import filedialog
            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            chosen = filedialog.askdirectory(initialdir=initial, title="Escolher pasta para salvar os áudios do POD")
            root.destroy()
            return chosen or None
        except Exception:  # noqa: BLE001
            ps = (
                "Add-Type -AssemblyName System.Windows.Forms;"
                "$f=New-Object System.Windows.Forms.FolderBrowserDialog;"
                "if($f.ShowDialog() -eq 'OK'){$f.SelectedPath}"
            )
            r = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, check=False)
            return r.stdout.strip() or None

    if shutil.which("zenity"):
        r = subprocess.run(
            ["zenity", "--file-selection", "--directory", "--title=Escolher pasta do POD", f"--filename={initial}/"],
            capture_output=True, text=True, check=False,
        )
        if r.returncode == 0 and r.stdout.strip():
            return r.stdout.strip()
        if r.returncode != 0:
            return None
    if shutil.which("kdialog"):
        r = subprocess.run(
            ["kdialog", "--getexistingdirectory", initial, "--title", "Escolher pasta do POD"],
            capture_output=True, text=True, check=False,
        )
        if r.returncode == 0 and r.stdout.strip():
            return r.stdout.strip()
        if r.returncode != 0:
            return None
    try:
        import tkinter as tk
        from tkinter import filedialog
        root = tk.Tk()
        root.withdraw()
        chosen = filedialog.askdirectory(initialdir=initial, title="Escolher pasta do POD")
        root.destroy()
        return chosen or None
    except Exception:  # noqa: BLE001
        return None


def open_folder_native(folder: Path):
    """Abre a pasta atual no Windows Explorer ou gerenciador de arquivos do Linux."""
    folder.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        os.startfile(str(folder))  # type: ignore[attr-defined]
    elif shutil.which("xdg-open"):
        subprocess.Popen(["xdg-open", str(folder)])


def db():
    c = sqlite3.connect(DATA / "pod.db", check_same_thread=False)
    c.row_factory = sqlite3.Row
    return c


with db() as c:
    c.execute(
        """CREATE TABLE IF NOT EXISTS episodes(
        id TEXT PRIMARY KEY, title TEXT, channel TEXT, duration INTEGER,
        thumb TEXT, file TEXT, size INTEGER, status TEXT, error TEXT,
        added TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"""
    )
    try:
        c.execute("ALTER TABLE episodes ADD COLUMN category TEXT DEFAULT 'podcasts'")
    except sqlite3.OperationalError:
        pass
    c.execute(
        """CREATE TABLE IF NOT EXISTS playlists(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        category TEXT DEFAULT 'podcasts',
        created TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"""
    )
    c.execute(
        """CREATE TABLE IF NOT EXISTS playlist_items(
        playlist_id INTEGER NOT NULL,
        episode_id TEXT NOT NULL,
        pos INTEGER DEFAULT 0,
        PRIMARY KEY (playlist_id, episode_id))"""
    )

app = FastAPI(title="POD")

CATS = ("podcasts", "musicas", "djsets")


class AddReq(BaseModel):
    url: str
    category: str = "podcasts"
    playlist_id: int | None = None


class CatReq(BaseModel):
    category: str


class PlaylistCreateReq(BaseModel):
    name: str
    category: str = "podcasts"


class PlaylistItemReq(BaseModel):
    episode_id: str


YDL_OPTS = {
    "format": "bestaudio[ext=m4a]/bestaudio",
    "writethumbnail": True,
    "quiet": True,
    "noprogress": True,
    "ignoreerrors": True,
    "noplaylist": False,
    "restrictfilenames": False,
    "windowsfilenames": True,
    "trim_file_name": 120,
    "retries": 5,
    "js_runtimes": js_runtimes(),
}


def find_or_fetch_thumb(audio_path: Path, vid: str | None = None, fallback_url: str | None = None) -> str | None:
    """Procura thumbnail salva ao lado do arquivo de áudio; se não existir, extrai das tags ID3 ou baixa da URL."""
    for ext in THUMB_EXTS:
        cand = audio_path.with_suffix(ext)
        if cand.is_file():
            return "/audio/" + cand.relative_to(AUDIO).as_posix()
    meta = extract_embedded_metadata(audio_path)
    if meta.get("thumb_saved"):
        cand = audio_path.with_suffix(".jpg")
        if cand.is_file():
            return "/audio/" + cand.relative_to(AUDIO).as_posix()
    is_yt_id = bool(vid and len(vid) == 11 and re.match(r"^[\w-]{11}$", vid) and not vid.startswith("ext_"))
    url = fallback_url or (f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg" if is_yt_id else None)
    if url and url.startswith("http"):
        dest = audio_path.with_suffix(".jpg")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=12) as resp:
                dest.write_bytes(resp.read())
            if dest.is_file() and dest.stat().st_size > 0:
                return "/audio/" + dest.relative_to(AUDIO).as_posix()
        except Exception:  # noqa: BLE001
            pass
    return None


def companion_thumbs(audio_path: Path) -> list[Path]:
    return [audio_path.with_suffix(ext) for ext in THUMB_EXTS if audio_path.with_suffix(ext).is_file()]


def scan_audio_dir() -> int:
    """Reconhece arquivos já existentes na pasta (ex.: cartão SD ou pasta do PC) e extrai metadados/capas."""
    AUDIO.mkdir(parents=True, exist_ok=True)
    found = 0
    present = set()
    for c_ in CATS:
        (AUDIO / c_).mkdir(exist_ok=True)
    sources = [(AUDIO, "podcasts")] + [(AUDIO / c_, c_) for c_ in CATS]
    for folder, cat in sources:
        for p in folder.iterdir():
            if not p.is_file() or p.suffix.lower() not in AUDIO_EXTS or p.name.startswith("."):
                continue
            rel = p.relative_to(AUDIO).as_posix()
            present.add(rel)
            m = ID_RE.search(p.stem)
            eid = m.group(1) if m else p.stem
            raw_title = ID_RE.sub("", p.stem).strip() if m else p.stem
            thumb = find_or_fetch_thumb(p, m.group(1) if m else None)
            with _lock, db() as c:
                row = c.execute("SELECT file, category, thumb, title, channel, duration FROM episodes WHERE id=?", (eid,)).fetchone()
            if row and row["file"] == rel and row["category"] == cat and row["thumb"] == thumb:
                continue
            meta = extract_embedded_metadata(p)
            title = (row["title"] if row and row["title"] else None) or meta.get("title") or raw_title
            channel = (row["channel"] if row and row["channel"] else None) or meta.get("channel")
            duration = (row["duration"] if row and row["duration"] else 0) or meta.get("duration") or 0
            upsert(
                id=eid, title=title, channel=channel, duration=duration, file=rel,
                size=p.stat().st_size, status="done", error=None, category=cat, thumb=thumb,
            )
            found += 1
    with _lock, db() as c:
        for r in c.execute("SELECT id,file FROM episodes WHERE status='done'").fetchall():
            if r["file"] not in present:
                c.execute("DELETE FROM episodes WHERE id=?", (r["id"],))
                c.execute("DELETE FROM playlist_items WHERE episode_id=?", (r["id"],))
    return found


def upsert(**kw):
    with _lock, db() as c:
        keys = ",".join(kw)
        marks = ",".join("?" * len(kw))
        upd = ",".join(f"{k}=excluded.{k}" for k in kw if k != "id")
        c.execute(
            f"INSERT INTO episodes({keys}) VALUES({marks}) "
            f"ON CONFLICT(id) DO UPDATE SET {upd}",
            list(kw.values()),
        )


def add_to_playlist_db(pid: int, eid: str):
    with _lock, db() as c:
        pos = c.execute("SELECT COALESCE(MAX(pos), 0) + 1 FROM playlist_items WHERE playlist_id=?", (pid,)).fetchone()[0]
        c.execute("INSERT OR IGNORE INTO playlist_items(playlist_id, episode_id, pos) VALUES(?,?,?)", (pid, eid, pos))


def human_title_from_url(url: str) -> tuple[str, str, str]:
    """Retorna (titulo_legivel, canal_dominio, extensao) a partir de uma URL de áudio."""
    parsed = urllib.parse.urlsplit(url)
    fname = urllib.parse.unquote(Path(parsed.path).name)
    ext = Path(fname).suffix.lower()
    if ext not in AUDIO_EXTS:
        ext = ".mp3"
    stem = Path(fname).stem.replace("_", " ").replace("-", " ").strip()
    title = stem.title() if stem else "Episódio de Podcast"
    host = parsed.netloc.removeprefix("www.").split(":")[0]
    return title, host, ext


def download_direct_audio_url(
    audio_url: str,
    cat: str,
    playlist_id: int | None = None,
    hint_title: str | None = None,
    hint_channel: str | None = None,
    hint_thumb: str | None = None,
):
    """Baixa um arquivo de áudio direto (.mp3, .m4a, .ogg, etc., ex: Jovem Nerd), extrai tags ID3 e capa."""
    eid = "ext_" + short_id(audio_url)[:7]
    guess_title, guess_chan, ext = human_title_from_url(audio_url)
    title = hint_title or guess_title
    channel = hint_channel or guess_chan
    upsert(id=eid, title=title, channel=channel, duration=0, status="downloading", category=cat, error=None)
    if playlist_id:
        add_to_playlist_db(playlist_id, eid)

    (AUDIO / cat).mkdir(parents=True, exist_ok=True)
    tmp_path = AUDIO / cat / f".downloading_{eid}{ext}"
    req = urllib.request.Request(audio_url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp_path, "wb") as out:
        shutil.copyfileobj(resp, out, length=256 * 1024)

    # Lê tags ID3 / MP4 do arquivo recém-baixado (ex: título real, Jovem Nerd, capa APIC)
    meta = extract_embedded_metadata(tmp_path)
    final_title = hint_title or meta.get("title") or title
    final_channel = hint_channel or meta.get("channel") or channel
    final_dur = meta.get("duration") or 0

    final_name = f"{safe_filename(final_title)} [{eid}]{ext}"
    final_path = AUDIO / cat / final_name
    if tmp_path.with_suffix(".jpg").is_file():
        tmp_path.with_suffix(".jpg").replace(final_path.with_suffix(".jpg"))
    tmp_path.replace(final_path)

    thumb_local = find_or_fetch_thumb(final_path, eid, hint_thumb)
    upsert(
        id=eid,
        title=final_title,
        channel=final_channel,
        duration=final_dur,
        thumb=thumb_local,
        file=final_path.relative_to(AUDIO).as_posix(),
        size=final_path.stat().st_size,
        status="done",
        error=None,
        category=cat,
    )


def parse_and_download_rss(xml_bytes: bytes, cat: str, playlist_id: int | None = None, max_items: int = 15) -> bool:
    """Se a URL for um Feed RSS de podcast, baixa os episódios listados no feed."""
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError:
        return False
    channel_el = root.find("channel")
    if channel_el is None:
        return False
    pod_title = (channel_el.findtext("title") or "Podcast").strip()
    ns = {"itunes": "http://www.itunes.com/dtds/podcast-1.0.dtd"}
    pod_img_el = channel_el.find("itunes:image", ns)
    pod_thumb = pod_img_el.attrib.get("href") if pod_img_el is not None else None

    items = channel_el.findall("item")[:max_items]
    if not items:
        return False
    for it in items:
        enc = it.find("enclosure")
        if enc is None or not enc.attrib.get("url"):
            continue
        ep_url = enc.attrib["url"]
        ep_title = (it.findtext("title") or "").strip() or None
        ep_img_el = it.find("itunes:image", ns)
        ep_thumb = (ep_img_el.attrib.get("href") if ep_img_el is not None else None) or pod_thumb
        try:
            download_direct_audio_url(ep_url, cat, playlist_id, hint_title=ep_title, hint_channel=pod_title, hint_thumb=ep_thumb)
        except Exception as e:  # noqa: BLE001
            eid = "ext_" + short_id(ep_url)[:7]
            upsert(id=eid, title=ep_title or ep_url, status="error", error=str(e)[:300], category=cat)
    return True


def try_external_podcast_or_audio(raw_url: str, cat: str, playlist_id: int | None = None) -> bool:
    """Detecta e baixa links diretos de áudio (?file=...mp3, .m4a, etc.) ou feeds RSS de podcasts."""
    unwrapped = unwrap_download_url(raw_url)
    parsed = urllib.parse.urlsplit(unwrapped)
    path_ext = Path(urllib.parse.unquote(parsed.path)).suffix.lower()

    # 1) Se já tem extensão de áudio explícita (.mp3, .m4a, .ogg, .opus, etc.)
    if path_ext in AUDIO_EXTS:
        download_direct_audio_url(unwrapped, cat, playlist_id)
        return True

    # 2) Para domínios conhecidos do yt-dlp (youtube, youtu.be, soundcloud, etc.), deixa o yt-dlp agir primeiro
    host = parsed.netloc.lower()
    if any(h in host for h in ("youtube.com", "youtu.be", "music.youtube.com", "soundcloud.com", "bandcamp.com", "mixcloud.com", "twitch.tv")):
        return False

    # 3) Faz uma requisição rápida para checar o Content-Type (pode ser audio/*, RSS XML ou página com áudio)
    try:
        req = urllib.request.Request(unwrapped, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=20) as resp:
            ctype = (resp.headers.get("Content-Type") or "").lower()
            final_url = resp.geturl()
            final_ext = Path(urllib.parse.unquote(urllib.parse.urlsplit(final_url).path)).suffix.lower()
            if ctype.startswith("audio/") or final_ext in AUDIO_EXTS:
                # Já é stream de áudio!
                resp.close()
                download_direct_audio_url(final_url, cat, playlist_id)
                return True
            if "xml" in ctype or "rss" in ctype or unwrapped.endswith((".xml", ".rss", "/feed", "/rss")):
                data = resp.read(5 * 1024 * 1024)
                if parse_and_download_rss(data, cat, playlist_id):
                    return True
    except Exception:  # noqa: BLE001
        pass
    return False


def try_scrape_audio_from_webpage(page_url: str, cat: str, playlist_id: int | None = None) -> bool:
    """Fallback: se o usuário colou a URL de uma página de podcast que tem um link .mp3/.m4a no HTML."""
    try:
        req = urllib.request.Request(page_url, headers={"User-Agent": UA})
        html = urllib.request.urlopen(req, timeout=20).read(2 * 1024 * 1024).decode("utf-8", errors="ignore")
        # Procura links diretos .mp3 / .m4a no HTML
        m_audio = re.search(r'(https?://[^\s"\'<>]+?\.(?:mp3|m4a|ogg|opus))(?:\?[^\s"\'<>]*)?', html, re.IGNORECASE)
        if not m_audio:
            return False
        audio_url = unwrap_download_url(m_audio.group(0))
        m_title = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']', html, re.IGNORECASE)
        m_img = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', html, re.IGNORECASE)
        download_direct_audio_url(
            audio_url,
            cat,
            playlist_id,
            hint_title=m_title.group(1).strip() if m_title else None,
            hint_thumb=m_img.group(1).strip() if m_img else None,
        )
        return True
    except Exception:  # noqa: BLE001
        return False


def import_local_audio_file(src_path: Path, cat: str = "podcasts", playlist_id: int | None = None) -> dict:
    """Importa um arquivo de áudio (.mp3, .m4a, etc.) já salvo no computador para dentro da biblioteca do POD."""
    if not src_path.is_file() or src_path.suffix.lower() not in AUDIO_EXTS:
        raise ValueError(f"Formato de arquivo não suportado: {src_path.name}")
    (AUDIO / cat).mkdir(parents=True, exist_ok=True)
    m = ID_RE.search(src_path.stem)
    eid = m.group(1) if m else ("loc_" + short_id(src_path.name + str(src_path.stat().st_size))[:7])
    meta = extract_embedded_metadata(src_path)
    raw_title = ID_RE.sub("", src_path.stem).strip() if m else src_path.stem.replace("_", " ")
    title = meta.get("title") or raw_title
    channel = meta.get("channel") or "Arquivo Importado"
    dur = meta.get("duration") or 0

    dst_name = f"{safe_filename(title)} [{eid}]{src_path.suffix.lower()}"
    dst_path = AUDIO / cat / dst_name
    if src_path.resolve() != dst_path.resolve():
        shutil.copy2(src_path, dst_path)
    extract_embedded_metadata(dst_path)  # garante extração da capa .jpg ao lado do arquivo de destino
    thumb = find_or_fetch_thumb(dst_path, eid)
    upsert(
        id=eid,
        title=title,
        channel=channel,
        duration=dur,
        thumb=thumb,
        file=dst_path.relative_to(AUDIO).as_posix(),
        size=dst_path.stat().st_size,
        status="done",
        error=None,
        category=cat,
    )
    if playlist_id:
        add_to_playlist_db(playlist_id, eid)
    return {"id": eid, "title": title}


def job(raw_url: str, cat: str = "podcasts", playlist_id: int | None = None):
    raw_url = raw_url.strip()
    # 1) Verifica primeiro se é um link de download direto de podcast (ex: Jovem Nerd ?file=...mp3) ou Feed RSS
    if raw_url.startswith(("http://", "https://")):
        try:
            if try_external_podcast_or_audio(raw_url, cat, playlist_id):
                return
        except Exception as e:  # noqa: BLE001
            eid = "ext_" + short_id(raw_url)[:7]
            upsert(id=eid, title=raw_url, status="error", error=str(e)[:300], category=cat)
            return

    # 2) YouTube / SoundCloud / Bandcamp / Mixcloud / Busca por nome via yt-dlp
    url = unwrap_download_url(raw_url) if raw_url.startswith(("http://", "https://")) else f"ytsearch1:{raw_url}"
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "extract_flat": "in_playlist", "ignoreerrors": True, "js_runtimes": js_runtimes()}) as y:
            info = y.extract_info(url, download=False)
    except Exception as e:  # noqa: BLE001
        info = None
        last_err = str(e)

    entries = info.get("entries") if info else None
    items = [e for e in (entries or ([info] if info else [])) if e]
    if not items:
        # 3) Fallback: tenta achar link .mp3/.m4a dentro do HTML da página
        if raw_url.startswith(("http://", "https://")) and try_scrape_audio_from_webpage(raw_url, cat, playlist_id):
            return
        upsert(id=raw_url, title=raw_url, status="error", error=locals().get("last_err", "Não foi possível extrair áudio deste link")[:300], category=cat)
        return

    ids = []
    for e in items:
        vid = e.get("id") or ("ext_" + short_id(e.get("webpage_url") or raw_url)[:7])
        ids.append((vid, e.get("webpage_url") or e.get("url") or raw_url))
        upsert(
            id=vid,
            title=e.get("title") or vid,
            channel=e.get("channel") or e.get("uploader") or info.get("title"),
            duration=int(e.get("duration") or 0),
            status="queued",
            category=cat,
        )
        if playlist_id:
            add_to_playlist_db(playlist_id, vid)

    for vid, item_url in ids:
        upsert(id=vid, status="downloading")
        try:
            (AUDIO / cat).mkdir(parents=True, exist_ok=True)
            target = item_url if item_url.startswith(("http://", "https://")) else f"https://www.youtube.com/watch?v={vid}"
            opts = {**YDL_OPTS, "outtmpl": str(AUDIO / cat / "%(title).100s [%(id)s].%(ext)s")}
            with yt_dlp.YoutubeDL(opts) as y:
                d = y.extract_info(target, download=True)
                path = Path(y.prepare_filename(d))
            meta = extract_embedded_metadata(path)
            thumb_local = find_or_fetch_thumb(path, vid, d.get("thumbnail"))
            upsert(
                id=vid,
                title=d.get("title") or meta.get("title") or vid,
                channel=d.get("channel") or d.get("uploader") or meta.get("channel"),
                duration=int(d.get("duration") or meta.get("duration") or 0),
                thumb=thumb_local,
                file=path.relative_to(AUDIO).as_posix(),
                size=path.stat().st_size,
                status="done",
                error=None,
            )
        except Exception as ex:  # noqa: BLE001
            upsert(id=vid, status="error", error=str(ex)[:300])


@app.post("/api/add")
def add(req: AddReq, bg: BackgroundTasks):
    if not req.url.strip():
        raise HTTPException(400, "URL vazia")
    if req.category not in CATS:
        raise HTTPException(400, "Categoria inválida")
    bg.add_task(job, req.url.strip(), req.category, req.playlist_id)
    return {"ok": True}


@app.post("/api/upload")
async def upload_audio_file(
    request: Request,
    filename: str = "audio.mp3",
    category: str = "podcasts",
    playlist_id: int | None = None,
):
    """Recebe um arquivo de áudio (.mp3, .m4a, etc.) enviado pelo usuário e o adiciona na biblioteca com capa ID3."""
    cat = category if category in CATS else "podcasts"
    (AUDIO / cat).mkdir(parents=True, exist_ok=True)
    ext = Path(filename).suffix.lower()
    if ext not in AUDIO_EXTS:
        raise HTTPException(400, f"Formato não suportado: {ext}")
    tmp = AUDIO / cat / f".upload_{safe_filename(Path(filename).stem)}{ext}"
    with open(tmp, "wb") as out:
        async for chunk in request.stream():
            out.write(chunk)
    try:
        res = import_local_audio_file(tmp, cat, playlist_id)
    finally:
        tmp.unlink(missing_ok=True)
    return {"ok": True, "item": res}


@app.post("/api/episodes/{eid}/category")
def set_category(eid: str, req: CatReq):
    """Move o episódio (e sua thumbnail) para outra categoria."""
    if req.category not in CATS:
        raise HTTPException(400, "Categoria inválida")
    with _lock, db() as c:
        r = c.execute("SELECT file, thumb FROM episodes WHERE id=?", (eid,)).fetchone()
        if not r or not r["file"]:
            raise HTTPException(404)
        src = AUDIO / r["file"]
        dst = AUDIO / req.category / src.name
        dst.parent.mkdir(parents=True, exist_ok=True)
        for t in companion_thumbs(src):
            t.replace(dst.with_suffix(t.suffix))
        if src.exists() and src != dst:
            src.replace(dst)
        new_thumb = find_or_fetch_thumb(dst, eid, r["thumb"])
        c.execute("UPDATE episodes SET category=?, file=?, thumb=? WHERE id=?",
                  (req.category, dst.relative_to(AUDIO).as_posix(), new_thumb, eid))
    return {"ok": True}


@app.get("/api/episodes")
def episodes():
    with db() as c:
        return [dict(r) for r in c.execute("SELECT * FROM episodes ORDER BY added DESC, rowid DESC")]


@app.delete("/api/episodes/{eid}")
def delete(eid: str):
    with _lock, db() as c:
        r = c.execute("SELECT file FROM episodes WHERE id=?", (eid,)).fetchone()
        if r and r["file"]:
            p = AUDIO / r["file"]
            for t in companion_thumbs(p):
                t.unlink(missing_ok=True)
            p.unlink(missing_ok=True)
        c.execute("DELETE FROM episodes WHERE id=?", (eid,))
        c.execute("DELETE FROM playlist_items WHERE episode_id=?", (eid,))
    return {"ok": True}


# ---------- Playlists ----------
@app.get("/api/playlists")
def playlists_list():
    with db() as c:
        pls = [dict(r) for r in c.execute("SELECT * FROM playlists ORDER BY id ASC").fetchall()]
        for pl in pls:
            rows = c.execute(
                "SELECT episode_id FROM playlist_items WHERE playlist_id=? ORDER BY pos ASC, rowid ASC",
                (pl["id"],),
            ).fetchall()
            pl["items"] = [r["episode_id"] for r in rows]
        return pls


@app.post("/api/playlists")
def playlists_create(req: PlaylistCreateReq):
    name = req.name.strip()
    if not name:
        raise HTTPException(400, "Nome vazio")
    cat = req.category if req.category in CATS else "podcasts"
    with _lock, db() as c:
        cur = c.execute("INSERT INTO playlists(name, category) VALUES(?,?)", (name, cat))
        return {"id": cur.lastrowid, "name": name, "category": cat, "items": []}


@app.delete("/api/playlists/{pid}")
def playlists_delete(pid: int):
    with _lock, db() as c:
        c.execute("DELETE FROM playlist_items WHERE playlist_id=?", (pid,))
        c.execute("DELETE FROM playlists WHERE id=?", (pid,))
    return {"ok": True}


@app.post("/api/playlists/{pid}/items")
def playlists_add_item(pid: int, req: PlaylistItemReq):
    add_to_playlist_db(pid, req.episode_id)
    return {"ok": True}


@app.delete("/api/playlists/{pid}/items/{eid}")
def playlists_remove_item(pid: int, eid: str):
    with _lock, db() as c:
        c.execute("DELETE FROM playlist_items WHERE playlist_id=? AND episode_id=?", (pid, eid))
    return {"ok": True}


# ---------- Armazenamento (PC Linux/Windows e Cartão SD) ----------
class StorageReq(BaseModel):
    path: str
    exact: bool = False


def apply_storage_dir(target: Path) -> dict:
    global AUDIO
    try:
        target.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        raise HTTPException(400, f"Não consegui criar/abrir a pasta {target}: {e}")
    if not os.access(target, os.W_OK):
        raise HTTPException(400, "Sem permissão de escrita nessa pasta")
    AUDIO = target
    CONFIG.write_text(json.dumps({"audio_dir": str(target)}))
    return {"current": str(AUDIO), "recognized": scan_audio_dir()}


@app.get("/api/storage")
def storage_info():
    free = 0
    try:
        if AUDIO.exists():
            free = shutil.disk_usage(AUDIO).free
    except OSError:
        pass
    return {
        "current": str(AUDIO),
        "default": str(DEFAULT_AUDIO),
        "cards": removable_mounts(),
        "available": AUDIO.exists(),
        "free": free,
    }


@app.post("/api/storage")
def storage_set(req: StorageReq):
    raw = req.path.strip()
    if not raw:
        return apply_storage_dir(DEFAULT_AUDIO)
    p = Path(raw).expanduser()
    target = p if (req.exact or p.name.upper() == "POD") else (p / "POD")
    return apply_storage_dir(target)


@app.post("/api/storage/pick")
def storage_pick():
    chosen = pick_folder_native(str(AUDIO if AUDIO.exists() else Path.home()))
    if not chosen:
        return {"cancelled": True, "current": str(AUDIO)}
    return apply_storage_dir(Path(chosen))


@app.post("/api/storage/open")
def storage_open():
    open_folder_native(AUDIO)
    return {"ok": True}


@app.post("/api/storage/scan")
def storage_scan():
    return {"recognized": scan_audio_dir()}


# ---------- "Tocando agora" (overlay do OBS) ----------
NOW = {"title": "", "channel": "", "thumb": "", "category": "", "playing": False, "pos": 0, "dur": 0}


class NowReq(BaseModel):
    title: str = ""
    channel: str = ""
    thumb: str = ""
    category: str = ""
    playing: bool = False
    pos: float = 0
    dur: float = 0


@app.post("/api/nowplaying")
def nowplaying_set(req: NowReq):
    NOW.update(req.model_dump())
    try:
        line = f"{req.title} - {req.channel}" if req.title and req.playing else ""
        (DATA / "nowplaying.txt").write_text(line, encoding="utf-8")
    except OSError:
        pass
    return {"ok": True}


@app.get("/api/nowplaying")
def nowplaying_get():
    return NOW


@app.get("/audio/{name:path}")
def audio(name: str):
    p = (AUDIO / name).resolve()
    if AUDIO.resolve() not in p.parents or not p.is_file():
        raise HTTPException(404)
    return FileResponse(p)


scan_audio_dir()

app.mount("/", StaticFiles(directory=WEB, html=True), name="web")
