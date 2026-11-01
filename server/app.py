"""POD - servidor pessoal: baixa só o ÁUDIO de links do YouTube com yt-dlp,
salva a thumbnail local para uso offline e permite organizar em categorias e playlists."""
import json
import os
import re
import shutil
import sqlite3
import sys
import threading
import urllib.request
from pathlib import Path

import yt_dlp
from fastapi import BackgroundTasks, FastAPI, HTTPException
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

_lock = threading.Lock()


def load_audio_dir() -> Path:
    try:
        return Path(json.loads(CONFIG.read_text())["audio_dir"])
    except Exception:  # noqa: BLE001
        return DEFAULT_AUDIO


AUDIO = load_audio_dir()


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
        pass  # coluna já existe
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

CATS = ("podcasts", "musicas", "djsets")  # nomes das subpastas


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


# Somente ÁUDIO (sem vídeo): m4a/opus leve + baixa a thumbnail junto para uso offline.
YDL_OPTS = {
    "format": "bestaudio[ext=m4a]/bestaudio",
    "writethumbnail": True,
    "quiet": True,
    "noprogress": True,
    "ignoreerrors": True,
    "noplaylist": False,
    "restrictfilenames": False,
    "windowsfilenames": True,  # nomes seguros p/ cartões FAT32/exFAT
    "trim_file_name": 120,
    "retries": 5,
    "js_runtimes": js_runtimes(),  # yt-dlp precisa de um runtime JS (deno/node)
}


def find_or_fetch_thumb(audio_path: Path, vid: str | None = None, fallback_url: str | None = None) -> str | None:
    """Procura thumbnail salva ao lado do arquivo de áudio; se não existir, tenta baixar uma vez."""
    for ext in THUMB_EXTS:
        cand = audio_path.with_suffix(ext)
        if cand.is_file():
            return "/audio/" + cand.relative_to(AUDIO).as_posix()
    url = fallback_url or (f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg" if vid else None)
    if url and url.startswith("http"):
        dest = audio_path.with_suffix(".jpg")
        try:
            urllib.request.urlretrieve(url, dest)
            if dest.is_file() and dest.stat().st_size > 0:
                return "/audio/" + dest.relative_to(AUDIO).as_posix()
        except Exception:  # noqa: BLE001
            pass
    return url


def companion_thumbs(audio_path: Path) -> list[Path]:
    return [audio_path.with_suffix(ext) for ext in THUMB_EXTS if audio_path.with_suffix(ext).is_file()]


def scan_audio_dir() -> int:
    """Reconhece arquivos já existentes na pasta (ex.: cartão SD com episódios) e remove do banco os que sumiram."""
    AUDIO.mkdir(parents=True, exist_ok=True)
    found = 0
    present = set()
    for c_ in CATS:
        (AUDIO / c_).mkdir(exist_ok=True)
    # arquivos soltos na raiz contam como podcasts; as subpastas definem a categoria
    sources = [(AUDIO, "podcasts")] + [(AUDIO / c_, c_) for c_ in CATS]
    for folder, cat in sources:
        for p in folder.iterdir():
            if not p.is_file() or p.suffix.lower() not in AUDIO_EXTS or p.name.startswith("."):
                continue
            rel = p.relative_to(AUDIO).as_posix()
            present.add(rel)
            m = ID_RE.search(p.stem)
            eid = m.group(1) if m else p.stem
            title = ID_RE.sub("", p.stem).strip() if m else p.stem
            thumb = find_or_fetch_thumb(p, m.group(1) if m else None)
            with _lock, db() as c:
                row = c.execute("SELECT file, category, thumb FROM episodes WHERE id=?", (eid,)).fetchone()
            if row and row["file"] == rel and row["category"] == cat and row["thumb"] == thumb:
                continue
            upsert(id=eid, title=title, file=rel, size=p.stat().st_size, status="done", error=None, category=cat,
                   thumb=thumb)
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


def job(url: str, cat: str = "podcasts", playlist_id: int | None = None):
    # 1) lista entradas rápido (para aparecerem como "baixando")
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "extract_flat": "in_playlist", "ignoreerrors": True, "js_runtimes": js_runtimes()}) as y:
            info = y.extract_info(url, download=False)
    except Exception as e:  # noqa: BLE001
        upsert(id=url, title=url, status="error", error=str(e)[:300])
        return
    entries = info.get("entries") if info else None
    items = [e for e in (entries or [info]) if e]
    ids = []
    for e in items:
        vid = e.get("id")
        if not vid:
            continue
        ids.append(vid)
        upsert(id=vid, title=e.get("title") or vid, channel=e.get("channel") or e.get("uploader") or info.get("title"),
               duration=int(e.get("duration") or 0), status="queued", category=cat)
        if playlist_id:
            add_to_playlist_db(playlist_id, vid)
    # 2) baixa um por vez (somente áudio + thumbnail offline)
    for vid in ids:
        upsert(id=vid, status="downloading")
        try:
            (AUDIO / cat).mkdir(parents=True, exist_ok=True)
            opts = {**YDL_OPTS, "outtmpl": str(AUDIO / cat / "%(title).100s [%(id)s].%(ext)s")}
            with yt_dlp.YoutubeDL(opts) as y:
                d = y.extract_info(f"https://www.youtube.com/watch?v={vid}", download=True)
                path = Path(y.prepare_filename(d))
            thumb_local = find_or_fetch_thumb(path, vid, d.get("thumbnail"))
            upsert(id=vid, title=d.get("title"), channel=d.get("channel") or d.get("uploader"),
                   duration=int(d.get("duration") or 0), thumb=thumb_local, file=path.relative_to(AUDIO).as_posix(),
                   size=path.stat().st_size, status="done", error=None)
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


# ---------- Armazenamento ----------
class StorageReq(BaseModel):
    path: str  # pasta raiz do cartão (ou "" para voltar ao padrão)


@app.get("/api/storage")
def storage_info():
    return {"current": str(AUDIO), "default": str(DEFAULT_AUDIO), "cards": removable_mounts(),
            "available": AUDIO.exists()}


@app.post("/api/storage")
def storage_set(req: StorageReq):
    """Escolhe onde salvar. No cartão cria a pasta POD e reconhece o que já houver nela."""
    global AUDIO
    if req.path.strip():
        target = Path(req.path.strip()) / "POD"
        try:
            target.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            raise HTTPException(400, f"Não consegui escrever em {req.path}: {e}")
        if not os.access(target, os.W_OK):
            raise HTTPException(400, "Sem permissão de escrita nesse local")
    else:
        target = DEFAULT_AUDIO
    AUDIO = target
    CONFIG.write_text(json.dumps({"audio_dir": str(target)}))
    return {"current": str(AUDIO), "recognized": scan_audio_dir()}


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
    # arquivo de texto p/ a fonte "Texto (ler de arquivo)" do OBS
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
    return FileResponse(p)  # suporta Range (seek) e também serve as thumbnails (.jpg/.webp)


scan_audio_dir()  # ao iniciar, reconhece o que já está na pasta/cartão e garante as thumbnails locais

app.mount("/", StaticFiles(directory=WEB, html=True), name="web")
