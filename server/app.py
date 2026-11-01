"""POD - servidor pessoal: baixa só o ÁUDIO de links do YouTube com yt-dlp
e serve para o PWA do celular (que guarda os arquivos offline)."""
import json
import os
import re
import sqlite3
import threading
from pathlib import Path

import yt_dlp
from fastapi import BackgroundTasks, FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DEFAULT_AUDIO = DATA / "audio"
CONFIG = DATA / "config.json"
WEB = ROOT.parent / "web"
DATA.mkdir(parents=True, exist_ok=True)
AUDIO_EXTS = {".m4a", ".mp3", ".opus", ".ogg", ".webm", ".aac", ".wav", ".flac"}
ID_RE = re.compile(r"\[([\w-]{11})\]$")

_lock = threading.Lock()


def load_audio_dir() -> Path:
    try:
        return Path(json.loads(CONFIG.read_text())["audio_dir"])
    except Exception:  # noqa: BLE001
        return DEFAULT_AUDIO


AUDIO = load_audio_dir()


def removable_mounts() -> list[str]:
    """Pontos de montagem típicos de cartão SD / pendrive no Linux."""
    out = []
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

app = FastAPI(title="POD")

CATS = ("podcasts", "musicas", "djsets")  # nomes das subpastas


class AddReq(BaseModel):
    url: str
    category: str = "podcasts"


class CatReq(BaseModel):
    category: str


# Áudio leve (opus/m4a, ~48-128kbps) sem precisar de ffmpeg; prefere m4a (toca em iOS/Android).
YDL_OPTS = {
    "format": "bestaudio[ext=m4a]/bestaudio",
    "quiet": True,
    "noprogress": True,
    "ignoreerrors": True,
    "noplaylist": False,
    "restrictfilenames": False,
    "windowsfilenames": True,  # nomes seguros p/ cartões FAT32/exFAT
    "trim_file_name": 120,
    "retries": 5,
    "js_runtimes": {"node": {}},  # yt-dlp precisa de um runtime JS (usa o Node instalado)
}


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
            with _lock, db() as c:
                row = c.execute("SELECT file, category FROM episodes WHERE id=?", (eid,)).fetchone()
            if row and row["file"] == rel and row["category"] == cat:
                continue
            upsert(id=eid, title=title, file=rel, size=p.stat().st_size, status="done", error=None, category=cat,
                   thumb=f"https://i.ytimg.com/vi/{eid}/mqdefault.jpg" if m else None)
            found += 1
    with _lock, db() as c:
        for r in c.execute("SELECT id,file FROM episodes WHERE status='done'").fetchall():
            if r["file"] not in present:
                c.execute("DELETE FROM episodes WHERE id=?", (r["id"],))
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


def job(url: str, cat: str = "podcasts"):
    # 1) lista entradas rápido (para aparecerem como "baixando")
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "extract_flat": "in_playlist", "ignoreerrors": True, "js_runtimes": {"node": {}}}) as y:
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
    # 2) baixa um por vez
    for vid in ids:
        upsert(id=vid, status="downloading")
        try:
            (AUDIO / cat).mkdir(parents=True, exist_ok=True)
            opts = {**YDL_OPTS, "outtmpl": str(AUDIO / cat / "%(title).100s [%(id)s].%(ext)s")}
            with yt_dlp.YoutubeDL(opts) as y:
                d = y.extract_info(f"https://www.youtube.com/watch?v={vid}", download=True)
                path = Path(y.prepare_filename(d))
            thumbs = d.get("thumbnail")
            upsert(id=vid, title=d.get("title"), channel=d.get("channel") or d.get("uploader"),
                   duration=int(d.get("duration") or 0), thumb=thumbs, file=path.relative_to(AUDIO).as_posix(),
                   size=path.stat().st_size, status="done", error=None)
        except Exception as ex:  # noqa: BLE001
            upsert(id=vid, status="error", error=str(ex)[:300])


@app.post("/api/add")
def add(req: AddReq, bg: BackgroundTasks):
    if not req.url.strip():
        raise HTTPException(400, "URL vazia")
    if req.category not in CATS:
        raise HTTPException(400, "Categoria inválida")
    bg.add_task(job, req.url.strip(), req.category)
    return {"ok": True}


@app.post("/api/episodes/{eid}/category")
def set_category(eid: str, req: CatReq):
    """Move o episódio para outra categoria (move o arquivo para a subpasta)."""
    if req.category not in CATS:
        raise HTTPException(400, "Categoria inválida")
    with _lock, db() as c:
        r = c.execute("SELECT file FROM episodes WHERE id=?", (eid,)).fetchone()
        if not r or not r["file"]:
            raise HTTPException(404)
        src = AUDIO / r["file"]
        dst = AUDIO / req.category / src.name
        if src.exists() and src != dst:
            dst.parent.mkdir(parents=True, exist_ok=True)
            src.replace(dst)
        c.execute("UPDATE episodes SET category=?, file=? WHERE id=?",
                  (req.category, dst.relative_to(AUDIO).as_posix(), eid))
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
            (AUDIO / r["file"]).unlink(missing_ok=True)
        c.execute("DELETE FROM episodes WHERE id=?", (eid,))
    return {"ok": True}


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


@app.get("/audio/{name:path}")
def audio(name: str):
    p = (AUDIO / name).resolve()
    if AUDIO.resolve() not in p.parents or not p.is_file():
        raise HTTPException(404)
    return FileResponse(p)  # suporta Range (seek)


scan_audio_dir()  # ao iniciar, reconhece o que já está na pasta/cartão

app.mount("/", StaticFiles(directory=WEB, html=True), name="web")

