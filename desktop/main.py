"""POD Desktop Nativo (Linux .AppImage e Windows .exe) — Interface estilo Spotify em PyQt6.
Funciona 100% offline, toca áudios nativamente com QMediaPlayer, salva thumbnails locais,
organiza em Categorias e Playlists, permite escolher qualquer pasta do PC/Cartão SD
e mantém o endpoint local de Overlay para o OBS Studio."""
import argparse
import os
import socket
import sys
import threading
from pathlib import Path

from PyQt6.QtCore import Qt, QTimer, QUrl, pyqtSignal, QObject
from PyQt6.QtGui import QColor, QFont, QIcon, QPainter, QPixmap
from PyQt6.QtMultimedia import QAudioOutput, QMediaPlayer
from PyQt6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSlider,
    QVBoxLayout,
    QWidget,
)


def default_data_dir() -> Path:
    if os.environ.get("POD_DATA"):
        return Path(os.environ["POD_DATA"])
    local_server_data = Path(__file__).resolve().parent.parent / "server" / "data"
    if not getattr(sys, "frozen", False) and local_server_data.exists():
        return local_server_data
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", Path.home())) / "POD"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "POD"
    try:
        base.mkdir(parents=True, exist_ok=True)
        return base
    except OSError:
        return local_server_data


os.environ.setdefault("POD_DATA", str(default_data_dir()))
Path(os.environ["POD_DATA"]).mkdir(parents=True, exist_ok=True)
if not getattr(sys, "frozen", False):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

import app as backend  # noqa: E402


def free_port(preferred: int, host: str) -> int:
    for port in (preferred, 0):
        with socket.socket() as s:
            try:
                s.bind((host, port))
                return s.getsockname()[1]
            except OSError:
                continue
    return preferred


def start_obs_server(host: str, port: int):
    """Sobe o servidor HTTP silencioso em background para alimentar o Overlay do OBS (localhost:PORT/overlay.html)."""
    import uvicorn

    cfg = uvicorn.Config(backend.app, host=host, port=port, log_level="error")
    srv = uvicorn.Server(cfg)
    threading.Thread(target=srv.run, daemon=True).start()


CATS = [
    ("podcasts", "🎙  Podcasts"),
    ("musicas", "🎵  Músicas"),
    ("djsets", "🎛  DJ Sets"),
]
CAT_LABELS = dict(CATS)


def fmt_clock(seconds: float) -> str:
    s = max(0, int(seconds))
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return f"{h}:{m:02d}:{sec:02d}" if h else f"{m}:{sec:02d}"


def fmt_size(b: int | None) -> str:
    if not b:
        return ""
    if b >= 1 << 30:
        return f"{b / (1 << 30):.1f} GB"
    return f"{b / (1 << 20):.1f} MB"


DARK_QSS = """
QMainWindow, QDialog { background: #0f0f12; color: #f1f1f4; }
QWidget { color: #f1f1f4; font-family: 'Segoe UI', 'Ubuntu', sans-serif; font-size: 13px; }
QFrame#sidebar { background: #15151a; border-right: 1px solid #262630; }
QFrame#playerbar { background: #1a1a22; border-top: 1px solid #2b2b36; }
QFrame#topbox { background: #1a1a20; border-radius: 14px; }
QFrame#epcard { background: #1a1a20; border-radius: 12px; border: 1px solid #24242e; }
QFrame#epcard_active { background: #2c191d; border-radius: 12px; border: 1px solid #ff4d4d; }
QLineEdit, QComboBox { background: #23232b; border: 1px solid #343442; border-radius: 10px; padding: 8px 12px; color: #ffffff; }
QLineEdit:focus { border: 1px solid #ff4d4d; }
QPushButton { background: #252530; border: none; border-radius: 9px; padding: 7px 12px; font-weight: 600; color: #f1f1f4; }
QPushButton:hover { background: #30303e; }
QPushButton#primary { background: #ff4d4d; color: #ffffff; font-size: 14px; padding: 9px 16px; }
QPushButton#primary:hover { background: #ff3333; }
QPushButton#nav { text-align: left; padding: 9px 12px; border-radius: 10px; background: transparent; color: #b8b8c4; }
QPushButton#nav:hover { background: #22222b; color: #ffffff; }
QPushButton#nav_active { text-align: left; padding: 9px 12px; border-radius: 10px; background: #ff4d4d; color: #ffffff; }
QScrollArea { border: none; background: transparent; }
QSlider::groove:horizontal { height: 5px; background: #32323e; border-radius: 2px; }
QSlider::sub-page:horizontal { background: #ff4d4d; border-radius: 2px; }
QSlider::handle:horizontal { background: #ffffff; width: 12px; margin: -4px 0; border-radius: 6px; }
"""

LIGHT_QSS = """
QMainWindow, QDialog { background: #f4f5f8; color: #16161a; }
QWidget { color: #16161a; font-family: 'Segoe UI', 'Ubuntu', sans-serif; font-size: 13px; }
QFrame#sidebar { background: #ffffff; border-right: 1px solid #dfe1ea; }
QFrame#playerbar { background: #ffffff; border-top: 1px solid #dfe1ea; }
QFrame#topbox { background: #ffffff; border-radius: 14px; border: 1px solid #e2e4ec; }
QFrame#epcard { background: #ffffff; border-radius: 12px; border: 1px solid #e2e4ec; }
QFrame#epcard_active { background: #ffe8e8; border-radius: 12px; border: 1px solid #e53935; }
QLineEdit, QComboBox { background: #f0f2f7; border: 1px solid #cfd2de; border-radius: 10px; padding: 8px 12px; color: #16161a; }
QLineEdit:focus { border: 1px solid #e53935; }
QPushButton { background: #eaecf2; border: none; border-radius: 9px; padding: 7px 12px; font-weight: 600; color: #16161a; }
QPushButton:hover { background: #dcdff0; }
QPushButton#primary { background: #e53935; color: #ffffff; font-size: 14px; padding: 9px 16px; }
QPushButton#primary:hover { background: #d32f2f; }
QPushButton#nav { text-align: left; padding: 9px 12px; border-radius: 10px; background: transparent; color: #555868; }
QPushButton#nav:hover { background: #eaecf2; color: #16161a; }
QPushButton#nav_active { text-align: left; padding: 9px 12px; border-radius: 10px; background: #e53935; color: #ffffff; }
QScrollArea { border: none; background: transparent; }
QSlider::groove:horizontal { height: 5px; background: #cfd2de; border-radius: 2px; }
QSlider::sub-page:horizontal { background: #e53935; border-radius: 2px; }
QSlider::handle:horizontal { background: #e53935; width: 12px; margin: -4px 0; border-radius: 6px; }
"""


class WorkerSignals(QObject):
    refresh = pyqtSignal()


def load_State() -> dict:
    p = backend.DATA / "desktop_state.json"
    try:
        import json
        return json.loads(p.read_text())
    except Exception:  # noqa: BLE001
        return {}


def save_state(st: dict):
    p = backend.DATA / "desktop_state.json"
    try:
        import json
        p.write_text(json.dumps(st))
    except Exception:  # noqa: BLE001
        pass


def rounded_pixmap(path: Path | None, size: int = 58) -> QPixmap:
    pix = QPixmap(size, size)
    pix.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pix)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    painter.setBrush(QColor("#2a2a35"))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.drawRoundedRect(0, 0, size, size, 10, 10)
    if path and path.is_file():
        src = QPixmap(str(path))
        if not src.isNull():
            scaled = src.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation)
            x = (scaled.width() - size) // 2
            y = (scaled.height() - size) // 2
            cropped = scaled.copy(x, y, size, size)
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceIn)
            painter.drawPixmap(0, 0, cropped)
    painter.end()
    return pix


class PodWindow(QMainWindow):
    def __init__(self, obs_url: str):
        super().__init__()
        self.obs_url = obs_url
        self.state = load_State()
        self.light_mode = bool(self.state.get("light", False))
        self.current_cat = self.state.get("cat", "podcasts")
        self.current_pl_id: int | None = None
        self.episodes: list[dict] = []
        self.playlists: list[dict] = []
        self.queue: list[dict] = []
        self.current_ep: dict | None = None
        self.shuffle = False
        self.sleep_minutes_left = 0
        self.slider_dragging = False

        self.signals = WorkerSignals()
        self.signals.refresh.connect(self.reload_data)

        self.setWindowTitle("POD — Podcasts, Músicas e DJ Sets Offline")
        self.resize(1120, 740)
        self.setMinimumSize(820, 560)

        # Player Nativo Qt6
        self.audio_out = QAudioOutput()
        self.audio_out.setVolume(0.9)
        self.player = QMediaPlayer()
        self.player.setAudioOutput(self.audio_out)
        self.player.positionChanged.connect(self.on_position_changed)
        self.player.durationChanged.connect(self.on_duration_changed)
        self.player.playbackStateChanged.connect(self.on_state_changed)
        self.player.mediaStatusChanged.connect(self.on_media_status)

        self.build_ui()
        self.apply_theme()
        self.reload_data()

        # Timer p/ atualizar fila de downloads e sleep timer
        self.poll_timer = QTimer(self)
        self.poll_timer.timeout.connect(self.on_tick)
        self.poll_timer.start(2500)

        self.sleep_timer = QTimer(self)
        self.sleep_timer.timeout.connect(self.on_sleep_tick)
        self.sleep_timer.start(60_000)

    def build_ui(self):
        root = QWidget()
        self.setCentralWidget(root)
        main_v = QVBoxLayout(root)
        main_v.setContentsMargins(0, 0, 0, 0)
        main_v.setSpacing(0)

        body_h = QHBoxLayout()
        body_h.setContentsMargins(0, 0, 0, 0)
        body_h.setSpacing(0)
        main_v.addLayout(body_h, 1)

        # ---- SIDEBAR (estilo Spotify) ----
        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(265)
        sv = QVBoxLayout(sidebar)
        sv.setContentsMargins(16, 18, 16, 14)
        sv.setSpacing(8)

        top_row = QHBoxLayout()
        logo = QLabel("🎧 POD")
        logo.setFont(QFont("Segoe UI", 18, QFont.Weight.Bold))
        top_row.addWidget(logo)
        top_row.addStretch()
        self.theme_btn = QPushButton("☀️ Claro")
        self.theme_btn.clicked.connect(self.toggle_theme)
        top_row.addWidget(self.theme_btn)
        sv.addLayout(top_row)

        lbl_cat = QLabel("BIBLIOTECA")
        lbl_cat.setStyleSheet("color: #8b8b98; font-size: 11px; font-weight: 700; margin-top: 8px;")
        sv.addWidget(lbl_cat)

        self.cat_btns: dict[str, QPushButton] = {}
        for cid, cname in CATS:
            b = QPushButton(cname)
            b.clicked.connect(lambda _, c=cid: self.select_category(c))
            sv.addWidget(b)
            self.cat_btns[cid] = b

        pl_hdr = QHBoxLayout()
        lbl_pl = QLabel("PLAYLISTS")
        lbl_pl.setStyleSheet("color: #8b8b98; font-size: 11px; font-weight: 700; margin-top: 10px;")
        pl_hdr.addWidget(lbl_pl)
        pl_hdr.addStretch()
        btn_new_pl = QPushButton("+ Nova")
        btn_new_pl.setFixedHeight(26)
        btn_new_pl.clicked.connect(self.create_playlist_dialog)
        pl_hdr.addWidget(btn_new_pl)
        sv.addLayout(pl_hdr)

        self.pl_scroll = QScrollArea()
        self.pl_scroll.setWidgetResizable(True)
        self.pl_container = QWidget()
        self.pl_container.setStyleSheet("background: transparent;")
        self.pl_layout = QVBoxLayout(self.pl_container)
        self.pl_layout.setContentsMargins(0, 0, 0, 0)
        self.pl_layout.setSpacing(4)
        self.pl_scroll.setWidget(self.pl_container)
        sv.addWidget(self.pl_scroll, 1)

        # Seção Armazenamento / Pasta local
        st_lbl = QLabel("ONDE SALVAR OS ÁUDIOS")
        st_lbl.setStyleSheet("color: #8b8b98; font-size: 11px; font-weight: 700; margin-top: 6px;")
        sv.addWidget(st_lbl)
        self.dir_label = QLabel("")
        self.dir_label.setWordWrap(True)
        self.dir_label.setStyleSheet("color: #8b8b98; font-size: 11px;")
        sv.addWidget(self.dir_label)

        st_row1 = QHBoxLayout()
        btn_pick = QPushButton("📁 Escolher Pasta")
        btn_pick.clicked.connect(self.pick_folder_dialog)
        btn_open = QPushButton("📂 Abrir")
        btn_open.clicked.connect(lambda: backend.open_folder_native(backend.AUDIO))
        st_row1.addWidget(btn_pick, 1)
        st_row1.addWidget(btn_open)
        sv.addLayout(st_row1)

        btn_scan = QPushButton("🔄 Reconhecer Arquivos da Pasta")
        btn_scan.clicked.connect(self.scan_now)
        sv.addWidget(btn_scan)

        # OBS Overlay copy
        btn_obs = QPushButton("🎥 Copiar URL Overlay OBS")
        btn_obs.setToolTip(self.obs_url)
        btn_obs.clicked.connect(self.copy_obs_url)
        sv.addWidget(btn_obs)

        body_h.addWidget(sidebar)

        # ---- PAINEL CENTRAL ----
        center = QWidget()
        cv = QVBoxLayout(center)
        cv.setContentsMargins(20, 18, 20, 12)
        cv.setSpacing(12)

        # Caixa de Download / Busca no YouTube
        dl_box = QFrame()
        dl_box.setObjectName("topbox")
        dl_layout = QHBoxLayout(dl_box)
        dl_layout.setContentsMargins(14, 12, 14, 12)
        dl_layout.setSpacing(10)
        self.url_input = QLineEdit()
        self.url_input.setPlaceholderText("Cole o link do YouTube (vídeo, playlist, canal) ou digite o nome para buscar e baixar…")
        self.url_input.returnPressed.connect(self.start_download)
        dl_layout.addWidget(self.url_input, 1)
        self.dl_btn = QPushButton("⬇ Baixar Áudio")
        self.dl_btn.setObjectName("primary")
        self.dl_btn.clicked.connect(self.start_download)
        dl_layout.addWidget(self.dl_btn)
        cv.addWidget(dl_box)

        # Barra de título da visão atual + filtro de busca + ordenação + ações da playlist
        bar_h = QHBoxLayout()
        self.section_title = QLabel("Podcasts")
        self.section_title.setFont(QFont("Segoe UI", 16, QFont.Weight.Bold))
        bar_h.addWidget(self.section_title)

        self.play_all_btn = QPushButton("▶ Tocar Tudo")
        self.play_all_btn.clicked.connect(self.play_all_visible)
        bar_h.addWidget(self.play_all_btn)

        self.del_pl_btn = QPushButton("🗑 Excluir Playlist")
        self.del_pl_btn.clicked.connect(self.delete_current_playlist)
        bar_h.addWidget(self.del_pl_btn)

        bar_h.addStretch()
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("🔍 Filtrar na biblioteca…")
        self.search_input.setFixedWidth(240)
        self.search_input.textChanged.connect(self.render_episodes)
        bar_h.addWidget(self.search_input)

        self.sort_combo = QComboBox()
        self.sort_combo.addItems(["Mais recentes", "Nome (A–Z)", "Tamanho"])
        self.sort_combo.currentIndexChanged.connect(self.render_episodes)
        bar_h.addWidget(self.sort_combo)
        cv.addLayout(bar_h)

        # Lista rolável de episódios
        self.ep_scroll = QScrollArea()
        self.ep_scroll.setWidgetResizable(True)
        self.ep_container = QWidget()
        self.ep_container.setStyleSheet("background: transparent;")
        self.ep_layout = QVBoxLayout(self.ep_container)
        self.ep_layout.setContentsMargins(0, 0, 4, 0)
        self.ep_layout.setSpacing(8)
        self.ep_scroll.setWidget(self.ep_container)
        cv.addWidget(self.ep_scroll, 1)

        body_h.addWidget(center, 1)

        # ---- PLAYER BAR INFERIOR (estilo Spotify) ----
        pbar = QFrame()
        pbar.setObjectName("playerbar")
        pbar.setFixedHeight(96)
        ph = QHBoxLayout(pbar)
        ph.setContentsMargins(18, 10, 18, 10)
        ph.setSpacing(16)

        # Capa + título à esquerda
        self.now_cover = QLabel()
        self.now_cover.setFixedSize(56, 56)
        self.now_cover.setPixmap(rounded_pixmap(None, 56))
        ph.addWidget(self.now_cover)

        info_v = QVBoxLayout()
        info_v.setSpacing(2)
        self.now_title = QLabel("Selecione um áudio para ouvir")
        self.now_title.setFont(QFont("Segoe UI", 11, QFont.Weight.Bold))
        self.now_title.setFixedWidth(250)
        self.now_sub = QLabel("100% Offline")
        self.now_sub.setStyleSheet("color: #8b8b98; font-size: 11px;")
        info_v.addWidget(self.now_title)
        info_v.addWidget(self.now_sub)
        ph.addLayout(info_v)

        # Controles centrais + barra de progresso
        ctrl_v = QVBoxLayout()
        ctrl_v.setSpacing(4)
        btns_h = QHBoxLayout()
        btns_h.addStretch()

        self.shuf_btn = QPushButton("🔀")
        self.shuf_btn.setToolTip("Ordem Aleatória")
        self.shuf_btn.clicked.connect(self.toggle_shuffle)
        btns_h.addWidget(self.shuf_btn)

        btn_prev = QPushButton("⏮")
        btn_prev.clicked.connect(lambda: self.step_queue(-1))
        btns_h.addWidget(btn_prev)

        btn_b15 = QPushButton("-15s")
        btn_b15.clicked.connect(lambda: self.seek_relative(-15_000))
        btns_h.addWidget(btn_b15)

        self.play_btn = QPushButton("▶")
        self.play_btn.setObjectName("primary")
        self.play_btn.setFixedWidth(48)
        self.play_btn.clicked.connect(self.toggle_play)
        btns_h.addWidget(self.play_btn)

        btn_f30 = QPushButton("+30s")
        btn_f30.clicked.connect(lambda: self.seek_relative(30_000))
        btns_h.addWidget(btn_f30)

        btn_next = QPushButton("⏭")
        btn_next.clicked.connect(lambda: self.step_queue(1))
        btns_h.addWidget(btn_next)

        btns_h.addStretch()
        ctrl_v.addLayout(btns_h)

        prog_h = QHBoxLayout()
        self.lbl_pos = QLabel("0:00")
        self.lbl_pos.setStyleSheet("color: #8b8b98; font-size: 11px;")
        self.slider = QSlider(Qt.Orientation.Horizontal)
        self.slider.setRange(0, 1000)
        self.slider.sliderPressed.connect(lambda: setattr(self, "slider_dragging", True))
        self.slider.sliderReleased.connect(self.on_slider_released)
        self.lbl_dur = QLabel("0:00")
        self.lbl_dur.setStyleSheet("color: #8b8b98; font-size: 11px;")
        prog_h.addWidget(self.lbl_pos)
        prog_h.addWidget(self.slider, 1)
        prog_h.addWidget(self.lbl_dur)
        ctrl_v.addLayout(prog_h)
        ph.addLayout(ctrl_v, 1)

        # Velocidade, Sleep Timer e Volume à direita
        right_h = QHBoxLayout()
        self.speed_btn = QPushButton("1.0x")
        self.speed_btn.setToolTip("Velocidade de reprodução")
        self.speed_btn.clicked.connect(self.cycle_speed)
        right_h.addWidget(self.speed_btn)

        self.sleep_btn = QPushButton("⏲️ Off")
        self.sleep_btn.setToolTip("Sleep Timer (Desligar automático)")
        self.sleep_btn.clicked.connect(self.cycle_sleep)
        right_h.addWidget(self.sleep_btn)

        vol_lbl = QLabel("🔊")
        right_h.addWidget(vol_lbl)
        self.vol_slider = QSlider(Qt.Orientation.Horizontal)
        self.vol_slider.setRange(0, 100)
        self.vol_slider.setValue(90)
        self.vol_slider.setFixedWidth(85)
        self.vol_slider.valueChanged.connect(lambda v: self.audio_out.setVolume(v / 100.0))
        right_h.addWidget(self.vol_slider)

        ph.addLayout(right_h)
        main_v.addWidget(pbar)

    # ---- Tema ----
    def apply_theme(self):
        self.setStyleSheet(LIGHT_QSS if self.light_mode else DARK_QSS)
        self.theme_btn.setText("🌙 Escuro" if self.light_mode else "☀️ Claro")

    def toggle_theme(self):
        self.light_mode = not self.light_mode
        self.state["light"] = self.light_mode
        save_state(self.state)
        self.apply_theme()

    # ---- Dados e Navegação ----
    def reload_data(self):
        self.episodes = backend.episodes()
        self.playlists = backend.playlists_list()
        st = backend.storage_info()
        free_txt = f" ({fmt_size(st['free'])} livres)" if st.get("free") else ""
        self.dir_label.setText(f"{st['current']}{free_txt}")
        self.render_sidebar()
        self.render_episodes()

    def select_category(self, cid: str):
        self.current_cat = cid
        self.current_pl_id = None
        self.state["cat"] = cid
        save_state(self.state)
        self.render_sidebar()
        self.render_episodes()

    def select_playlist(self, pid: int | None):
        self.current_pl_id = pid
        self.render_sidebar()
        self.render_episodes()

    def render_sidebar(self):
        for cid, btn in self.cat_btns.items():
            count = sum(1 for e in self.episodes if (e.get("category") or "podcasts") == cid)
            btn.setText(f"{CAT_LABELS[cid]} ({count})")
            btn.setObjectName("nav_active" if cid == self.current_cat and self.current_pl_id is None else "nav")
            btn.style().unpolish(btn)
            btn.style().polish(btn)

        while self.pl_layout.count():
            item = self.pl_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        cat_pls = [p for p in self.playlists if p.get("category") == self.current_cat]
        btn_all = QPushButton("🎵  Todas da categoria")
        btn_all.setObjectName("nav_active" if self.current_pl_id is None else "nav")
        btn_all.clicked.connect(lambda: self.select_playlist(None))
        self.pl_layout.addWidget(btn_all)

        for p in cat_pls:
            b = QPushButton(f"🎶  {p['name']} ({len(p['items'])})")
            b.setObjectName("nav_active" if self.current_pl_id == p["id"] else "nav")
            b.clicked.connect(lambda _, pid=p["id"]: self.select_playlist(pid))
            self.pl_layout.addWidget(b)
        self.pl_layout.addStretch()

        active_pl = next((p for p in cat_pls if p["id"] == self.current_pl_id), None)
        dest_name = CAT_LABELS[self.current_cat].split("  ")[-1] + (f" → {active_pl['name']}" if active_pl else "")
        self.dl_btn.setText(f"⬇ Baixar em {dest_name}")
        self.del_pl_btn.setVisible(active_pl is not None)

    def visible_episodes(self) -> list[dict]:
        cat_pls = [p for p in self.playlists if p.get("category") == self.current_cat]
        active_pl = next((p for p in cat_pls if p["id"] == self.current_pl_id), None)
        items = [e for e in self.episodes if (e.get("category") or "podcasts") == self.current_cat]
        if active_pl:
            allowed = set(active_pl["items"])
            items = [e for e in items if e["id"] in allowed]
        q = self.search_input.text().strip().lower()
        if q:
            items = [e for e in items if q in (e.get("title") or "").lower() or q in (e.get("channel") or "").lower()]
        sort_idx = self.sort_combo.currentIndex()
        if sort_idx == 1:
            items.sort(key=lambda x: (x.get("title") or "").lower())
        elif sort_idx == 2:
            items.sort(key=lambda x: x.get("size") or 0, reverse=True)
        return items

    def thumb_path_for(self, ep: dict) -> Path | None:
        if ep.get("file"):
            audio_p = backend.AUDIO / ep["file"]
            thumbs = backend.companion_thumbs(audio_p)
            if thumbs:
                return thumbs[0]
        return None

    def render_episodes(self):
        while self.ep_layout.count():
            item = self.ep_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        vis = self.visible_episodes()
        cat_pls = [p for p in self.playlists if p.get("category") == self.current_cat]
        active_pl = next((p for p in cat_pls if p["id"] == self.current_pl_id), None)
        title_base = f"🎶 {active_pl['name']}" if active_pl else CAT_LABELS[self.current_cat]
        self.section_title.setText(f"{title_base} ({len(vis)})")

        if not vis:
            empty = QLabel("Nenhum áudio aqui ainda.\nCole um link do YouTube ou digite o nome acima para baixar, ou escolha uma pasta que já tenha seus áudios.")
            empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
            empty.setStyleSheet("color: #8b8b98; padding: 40px;")
            self.ep_layout.addWidget(empty)
            self.ep_layout.addStretch()
            return

        positions = self.state.get("positions", {})
        for ep in vis:
            card = QFrame()
            is_cur = self.current_ep and self.current_ep["id"] == ep["id"]
            card.setObjectName("epcard_active" if is_cur else "epcard")
            ch = QHBoxLayout(card)
            ch.setContentsMargins(10, 8, 10, 8)
            ch.setSpacing(12)

            cov = QLabel()
            cov.setFixedSize(58, 58)
            cov.setPixmap(rounded_pixmap(self.thumb_path_for(ep), 58))
            ch.addWidget(cov)

            iv = QVBoxLayout()
            iv.setSpacing(3)
            t_lbl = QLabel(ep.get("title") or ep["id"])
            t_lbl.setFont(QFont("Segoe UI", 11, QFont.Weight.DemiBold))
            t_lbl.setWordWrap(True)
            iv.addWidget(t_lbl)

            status = ep.get("status") or "done"
            dur_str = f"{int(ep['duration']) // 60} min" if ep.get("duration") else ""
            size_str = fmt_size(ep.get("size")) if status == "done" else f"⏳ {status}"
            ep_pls = [p["name"] for p in self.playlists if ep["id"] in p["items"]]
            pl_str = (" · 🎶 " + ", ".join(ep_pls)) if ep_pls else ""
            saved_pos = positions.get(ep["id"], 0)
            resume_str = f" · ⏱ Retomar {fmt_clock(saved_pos)}" if saved_pos > 10 else ""
            meta = " · ".join(x for x in [ep.get("channel") or "", dur_str, size_str] if x) + resume_str + pl_str
            s_lbl = QLabel(meta)
            s_lbl.setStyleSheet("color: #8b8b98; font-size: 11px;")
            iv.addWidget(s_lbl)
            ch.addLayout(iv, 1)

            if status == "done":
                b_play = QPushButton("⏸" if (is_cur and self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState) else "▶")
                b_play.setObjectName("primary")
                b_play.setFixedWidth(40)
                b_play.clicked.connect(lambda _, e=ep, q=vis: self.play_episode(e, q))
                ch.addWidget(b_play)

                b_pl = QPushButton("➕ Playlist")
                b_pl.clicked.connect(lambda _, e=ep: self.manage_episode_playlists(e))
                ch.addWidget(b_pl)

                b_mv = QPushButton("📂 Mover")
                b_mv.clicked.connect(lambda _, e=ep: self.move_episode_category(e))
                ch.addWidget(b_mv)

            b_del = QPushButton("🗑")
            b_del.setFixedWidth(36)
            b_del.clicked.connect(lambda _, e=ep: self.delete_episode(e))
            ch.addWidget(b_del)

            self.ep_layout.addWidget(card)
        self.ep_layout.addStretch()

    # ---- Downloads ----
    def start_download(self):
        raw = self.url_input.text().strip()
        if not raw:
            return
        self.url_input.clear()
        cat = self.current_cat
        pl_id = self.current_pl_id

        def bg():
            backend.job(raw, cat, pl_id)
            self.signals.refresh.emit()

        threading.Thread(target=bg, daemon=True).start()
        QTimer.singleShot(600, self.reload_data)

    def on_tick(self):
        eps = backend.episodes()
        if len(eps) != len(self.episodes) or any(e.get("status") in ("queued", "downloading") for e in self.episodes) or any(e.get("status") in ("queued", "downloading") for e in eps):
            self.reload_data()

    # ---- Pastas / Armazenamento ----
    def pick_folder_dialog(self):
        chosen = QFileDialog.getExistingDirectory(self, "Escolher pasta onde os áudios estão ou serão salvos", str(backend.AUDIO))
        if not chosen:
            return
        res = backend.apply_storage_dir(Path(chosen))
        QMessageBox.information(self, "Pasta atualizada", f"Pasta ativa:\n{res['current']}\n\n{res['recognized']} arquivo(s) reconhecido(s).")
        self.reload_data()

    def scan_now(self):
        n = backend.scan_audio_dir()
        self.reload_data()
        QMessageBox.information(self, "Biblioteca atualizada", f"{n} arquivo(s) novo(s) reconhecido(s) em:\n{backend.AUDIO}")

    def copy_obs_url(self):
        QApplication.clipboard().setText(self.obs_url)
        QMessageBox.information(
            self,
            "Overlay para OBS Studio",
            f"URL copiada para a área de transferência:\n{self.obs_url}\n\n"
            "No OBS: Fontes → + → Navegador (Largura 600, Altura 160).",
        )

    # ---- Playlists e Categorias ----
    def create_playlist_dialog(self):
        name, ok = QInputDialog.getText(self, "Nova Playlist", f"Nome da nova playlist em {CAT_LABELS[self.current_cat]}:")
        if ok and name.strip():
            res = backend.playlists_create(backend.PlaylistCreateReq(name=name.strip(), category=self.current_cat))
            self.current_pl_id = res["id"]
            self.reload_data()

    def delete_current_playlist(self):
        if self.current_pl_id is None:
            return
        ans = QMessageBox.question(self, "Excluir Playlist", "Excluir esta playlist? (Os arquivos de áudio continuam salvos)")
        if ans == QMessageBox.StandardButton.Yes:
            backend.playlists_delete(self.current_pl_id)
            self.current_pl_id = None
            self.reload_data()

    def manage_episode_playlists(self, ep: dict):
        dlg = QDialog(self)
        dlg.setWindowTitle("Organizar em Playlists")
        dlg.setMinimumWidth(340)
        v = QVBoxLayout(dlg)
        lbl = QLabel(ep.get("title") or ep["id"])
        lbl.setWordWrap(True)
        lbl.setStyleSheet("color: #8b8b98;")
        v.addWidget(lbl)

        cat = ep.get("category") or "podcasts"
        cat_pls = [p for p in self.playlists if p.get("category") == cat]
        for p in cat_pls:
            cb = QCheckBox(f"🎶  {p['name']}")
            cb.setChecked(ep["id"] in p["items"])

            def on_toggle(checked: bool, pid=p["id"]):
                if checked:
                    backend.add_to_playlist_db(pid, ep["id"])
                else:
                    backend.playlists_remove_item(pid, ep["id"])
                self.reload_data()

            cb.toggled.connect(on_toggle)
            v.addWidget(cb)

        btn_new = QPushButton("+ Criar nova playlist com este áudio")
        btn_new.setObjectName("primary")

        def make_new():
            name, ok = QInputDialog.getText(dlg, "Nova Playlist", "Nome da playlist:")
            if ok and name.strip():
                created = backend.playlists_create(backend.PlaylistCreateReq(name=name.strip(), category=cat))
                backend.add_to_playlist_db(created["id"], ep["id"])
                self.reload_data()
                dlg.accept()

        btn_new.clicked.connect(make_new)
        v.addWidget(btn_new)

        btn_close = QPushButton("Concluído")
        btn_close.clicked.connect(dlg.accept)
        v.addWidget(btn_close)
        dlg.exec()

    def move_episode_category(self, ep: dict):
        cur_cat = ep.get("category") or "podcasts"
        choices = [f"{cid}: {label}" for cid, label in CATS if cid != cur_cat]
        item, ok = QInputDialog.getItem(self, "Mover Categoria", "Mover arquivo e capa para:", choices, 0, False)
        if ok and item:
            target_cat = item.split(":")[0]
            backend.set_category(ep["id"], backend.CatReq(category=target_cat))
            self.reload_data()

    def delete_episode(self, ep: dict):
        ans = QMessageBox.question(self, "Apagar Áudio", f"Apagar do computador:\n{ep.get('title')}?")
        if ans == QMessageBox.StandardButton.Yes:
            if self.current_ep and self.current_ep["id"] == ep["id"]:
                self.player.stop()
                self.current_ep = None
            backend.delete(ep["id"])
            self.reload_data()

    # ---- Player Nativo ----
    def play_all_visible(self):
        vis = [e for e in self.visible_episodes() if e.get("status") == "done"]
        if vis:
            self.play_episode(vis[0], vis)

    def play_episode(self, ep: dict, queue: list[dict] | None = None):
        if self.current_ep and self.current_ep["id"] == ep["id"]:
            self.toggle_play()
            return
        if not ep.get("file"):
            return
        audio_path = backend.AUDIO / ep["file"]
        if not audio_path.is_file():
            QMessageBox.warning(self, "Arquivo não encontrado", f"O arquivo não está em:\n{audio_path}")
            return
        self.current_ep = ep
        if queue is not None:
            self.queue = [x for x in queue if x.get("status") == "done"]
        self.now_title.setText(ep.get("title") or ep["id"])
        self.now_sub.setText(ep.get("channel") or CAT_LABELS.get(ep.get("category") or "podcasts", ""))
        self.now_cover.setPixmap(rounded_pixmap(self.thumb_path_for(ep), 56))

        self.player.setSource(QUrl.fromLocalFile(str(audio_path)))
        saved_sec = self.state.get("positions", {}).get(ep["id"], 0)
        self.player.play()
        if saved_sec > 2:
            QTimer.singleShot(250, lambda s=saved_sec: self.player.setPosition(int(s * 1000)))
        self.render_episodes()
        self.report_obs()

    def toggle_play(self):
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.player.pause()
        else:
            self.player.play()
        self.report_obs()

    def seek_relative(self, delta_ms: int):
        self.player.setPosition(max(0, self.player.position() + delta_ms))
        self.report_obs()

    def on_slider_released(self):
        self.slider_dragging = False
        dur = self.player.duration()
        if dur > 0:
            self.player.setPosition(int(self.slider.value() / 1000.0 * dur))
        self.report_obs()

    def on_position_changed(self, pos_ms: int):
        dur_ms = self.player.duration()
        self.lbl_pos.setText(fmt_clock(pos_ms / 1000.0))
        if not self.slider_dragging and dur_ms > 0:
            self.slider.setValue(int(pos_ms * 1000 / dur_ms))
        if self.current_ep and pos_ms > 3000:
            positions = self.state.setdefault("positions", {})
            positions[self.current_ep["id"]] = int(pos_ms / 1000)
            if int(pos_ms / 1000) % 5 == 0:
                save_state(self.state)
                self.report_obs()

    def on_duration_changed(self, dur_ms: int):
        self.lbl_dur.setText(fmt_clock(dur_ms / 1000.0))

    def on_state_changed(self, state: QMediaPlayer.PlaybackState):
        playing = state == QMediaPlayer.PlaybackState.PlayingState
        self.play_btn.setText("⏸" if playing else "▶")
        self.report_obs()

    def on_media_status(self, status: QMediaPlayer.MediaStatus):
        if status == QMediaPlayer.MediaStatus.EndOfMedia and self.current_ep:
            self.state.setdefault("positions", {}).pop(self.current_ep["id"], None)
            save_state(self.state)
            self.step_queue(1)

    def step_queue(self, direction: int):
        if not self.queue or not self.current_ep:
            return
        if self.shuffle and len(self.queue) > 1:
            import random
            others = [x for x in self.queue if x["id"] != self.current_ep["id"]]
            self.play_episode(random.choice(others), self.queue)
            return
        idx = next((i for i, x in enumerate(self.queue) if x["id"] == self.current_ep["id"]), -1)
        nxt = idx + direction
        if 0 <= nxt < len(self.queue):
            self.play_episode(self.queue[nxt], self.queue)

    def toggle_shuffle(self):
        self.shuffle = not self.shuffle
        self.shuf_btn.setObjectName("primary" if self.shuffle else "")
        self.shuf_btn.style().unpolish(self.shuf_btn)
        self.shuf_btn.style().polish(self.shuf_btn)

    def cycle_speed(self):
        steps = [1.0, 1.25, 1.5, 1.75, 2.0, 0.75]
        cur = round(self.player.playbackRate(), 2)
        idx = steps.index(cur) if cur in steps else 0
        nxt = steps[(idx + 1) % len(steps)]
        self.player.setPlaybackRate(nxt)
        self.speed_btn.setText(f"{nxt}x")

    def cycle_sleep(self):
        steps = [0, 15, 30, 45, 60]
        cur = self.sleep_minutes_left
        nxt = 15 if cur <= 0 else (30 if cur <= 15 else (45 if cur <= 30 else (60 if cur <= 45 else 0)))
        self.sleep_minutes_left = nxt
        self.sleep_btn.setText(f"⏲️ {nxt}m" if nxt else "⏲️ Off")

    def on_sleep_tick(self):
        if self.sleep_minutes_left <= 0:
            return
        self.sleep_minutes_left -= 1
        if self.sleep_minutes_left <= 0:
            self.player.pause()
            self.sleep_btn.setText("⏲️ Off")
        else:
            self.sleep_btn.setText(f"⏲️ {self.sleep_minutes_left}m")

    def report_obs(self):
        if not self.current_ep:
            return
        playing = self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState
        backend.nowplaying_set(
            backend.NowReq(
                title=self.current_ep.get("title") or "",
                channel=self.current_ep.get("channel") or "",
                thumb=self.current_ep.get("thumb") or "",
                category=self.current_ep.get("category") or "podcasts",
                playing=playing,
                pos=self.player.position() / 1000.0,
                dur=max(0.0, self.player.duration() / 1000.0),
            )
        )


def main():
    ap = argparse.ArgumentParser(prog="POD")
    ap.add_argument("--port", type=int, default=8000, help="porta para o Overlay do OBS")
    ap.add_argument("--lan", action="store_true", help="permite acesso pela rede local")
    args = ap.parse_args()

    host = "0.0.0.0" if args.lan else "127.0.0.1"
    port = free_port(args.port, host)
    start_obs_server(host, port)
    obs_url = f"http://127.0.0.1:{port}/overlay.html"

    qt_app = QApplication(sys.argv)
    qt_app.setApplicationName("POD")
    win = PodWindow(obs_url=obs_url)
    win.show()
    sys.exit(qt_app.exec())


if __name__ == "__main__":
    main()
