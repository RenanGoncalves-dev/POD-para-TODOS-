const $ = (s) => document.querySelector(s);
const CACHE = "pod-audio-v1";
const META = "pod-meta"; // localStorage: lista de episódios (p/ funcionar sem servidor)
const PL_META = "pod-playlists"; // localStorage: playlists offline
let eps = JSON.parse(localStorage.getItem(META) || "[]");
let playlists = JSON.parse(localStorage.getItem(PL_META) || "[]");
let offline = new Set();

const CATS = [["podcasts", "🎙 Podcasts"], ["musicas", "🎵 Músicas"], ["djsets", "🎛 DJ Sets"]];
let cat = localStorage.getItem("pod-cat") || "podcasts";
let curPl = null; // id da playlist selecionada (ou null para "Todas")
let queue = []; // fila de reprodução atual
let shuffle = false;
let sleepUntil = 0; // timestamp do sleep timer

// ---- Tema Claro / Escuro ----
function applyTheme(light) {
  document.body.classList.toggle("light", light);
  $("#themeBtn").textContent = light ? "🌙 Escuro" : "☀️ Claro";
  localStorage.setItem("pod-theme", light ? "light" : "dark");
}
applyTheme(localStorage.getItem("pod-theme") === "light");
$("#themeBtn").onclick = () => applyTheme(!document.body.classList.contains("light"));

// mesma normalização que o navegador usa na chave do cache
const pathOf = (file) => new URL("/audio/" + file, location.href).pathname;

const fmt = (s) => (s ? `${Math.floor(s / 60)}min` : "");
const fmtClock = (s) => {
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec < 10 ? "0" : ""}${sec}`;
};
const mb = (b) => (b ? (b > 1073741824 ? (b / 1073741824).toFixed(1) + " GB" : (b / 1048576).toFixed(1) + " MB") : "");

async function refreshOffline() {
  const c = await caches.open(CACHE);
  offline = new Set((await c.keys()).map((r) => new URL(r.url).pathname));
}

async function load() {
  try {
    const [r, pr] = await Promise.all([fetch("/api/episodes"), fetch("/api/playlists")]);
    const server = await r.json();
    playlists = await pr.json();
    localStorage.setItem(PL_META, JSON.stringify(playlists));
    // mantém os salvos offline mesmo se apagados no servidor
    const byId = Object.fromEntries(eps.map((e) => [e.id, e]));
    server.forEach((e) => (byId[e.id] = e));
    eps = Object.values(byId);
    localStorage.setItem(META, JSON.stringify(eps));
    $("#net").textContent = "";
  } catch {
    $("#net").textContent = "Sem conexão com o servidor — mostrando o que está salvo no aparelho";
  }
  await refreshOffline();
  render();
}

function getVisibleEpisodes() {
  const activePl = playlists.find((p) => p.id === curPl && p.category === cat);
  const q = ($("#q")?.value || "").trim().toLowerCase();
  const sort = $("#sort")?.value || "recent";
  let list = eps.filter((e) => (e.category || "podcasts") === cat && (navigator.onLine || (e.file && offline.has(pathOf(e.file)))));
  if (activePl) {
    const set = new Set(activePl.items);
    list = list.filter((e) => set.has(e.id));
  }
  if (q) {
    list = list.filter((e) => (e.title || "").toLowerCase().includes(q) || (e.channel || "").toLowerCase().includes(q));
  }
  if (sort === "az") list = [...list].sort((a, b) => (a.title || "").localeCompare(b.title || ""));
  else if (sort === "size") list = [...list].sort((a, b) => (b.size || 0) - (a.size || 0));
  return list;
}

$("#q").oninput = render;
$("#sort").onchange = render;

function render() {
  const list = $("#list");
  list.innerHTML = "";
  renderTabs();
  renderPlaylistsBar();
  const visible = getVisibleEpisodes();
  if (!visible.length) {
    const activePl = playlists.find((p) => p.id === curPl && p.category === cat);
    list.innerHTML = activePl
      ? `<div class="empty">A playlist <b>${activePl.name}</b> está vazia.<br>Toque no botão <b>➕</b> de qualquer faixa ou cole um link acima para baixar direto nela.</div>`
      : `<div class="empty">Nada encontrado aqui.<br>Cole um link ou digite o nome acima: o áudio e a capa serão salvos em <b>${CATS.find((c) => c[0] === cat)[1]}</b>.</div>`;
  }
  for (const e of visible) {
    const path = e.file ? pathOf(e.file) : "";
    const saved = path && offline.has(path);
    const epPls = playlists.filter((p) => p.items.includes(e.id));
    const savedPos = +(localStorage.getItem("pos:" + e.id) || 0);
    const d = document.createElement("div");
    d.className = "ep" + (cur && cur.id === e.id ? " on" : "");
    d.innerHTML = `<img src="${e.thumb || ""}" loading="lazy" alt="">
      <div class="i"><div class="t"></div><div class="s">${e.channel || ""} · ${fmt(e.duration)} · ${
      e.status === "done" ? mb(e.size) : e.status + (e.error ? " ⚠" : "")}</div><div class="badges"></div></div><div class="a"></div>`;
    d.querySelector(".t").textContent = e.title;
    const badges = d.querySelector(".badges");
    if (savedPos > 10) {
      const sp = document.createElement("span");
      sp.className = "badge prog";
      sp.textContent = "⏱ Retomar " + fmtClock(savedPos);
      badges.append(sp);
    }
    epPls.forEach((p) => {
      const sp = document.createElement("span");
      sp.className = "badge";
      sp.textContent = "🎶 " + p.name;
      badges.append(sp);
    });
    const a = d.querySelector(".a");
    if (e.status === "done" || saved) {
      const row = document.createElement("div");
      row.className = "row";
      row.append(btn("▶", "ic play", () => play(e, path, visible)));
      row.append(btn("➕", "ic", () => openPlaylistModal(e)));
      if (navigator.onLine) row.append(btn("📂", "ic", () => move(e)));
      if (navigator.onLine) row.append(btn("🗑", "ic", () => del(e)));
      a.append(row);
      a.append(saved ? btn("✓ offline", "ok", () => unsave(e, path)) : btn("⬇ celular", "", () => save(e, path, a)));
    } else if (navigator.onLine) a.append(btn("🗑", "ic", () => del(e)));
    list.append(d);
  }
}

function renderTabs() {
  const t = $("#tabs");
  t.innerHTML = "";
  for (const [id, label] of CATS) {
    const n = eps.filter((e) => (e.category || "podcasts") === id).length;
    const b = btn(`${label} (${n})`, id === cat ? "tab on" : "tab", () => {
      cat = id;
      curPl = null;
      localStorage.setItem("pod-cat", id);
      render();
    });
    t.append(b);
  }
  const activePl = playlists.find((p) => p.id === curPl && p.category === cat);
  $("#dest").textContent = CATS.find((c) => c[0] === cat)[1] + (activePl ? ` → ${activePl.name}` : "");
}

function renderPlaylistsBar() {
  const bar = $("#plbar");
  const head = $("#plhead");
  bar.innerHTML = "";
  const catPls = playlists.filter((p) => p.category === cat);
  if (!catPls.some((p) => p.id === curPl)) curPl = null;

  bar.append(btn("Todas", curPl === null ? "plchip on" : "plchip", () => { curPl = null; render(); }));
  for (const p of catPls) {
    bar.append(btn(`🎶 ${p.name} (${p.items.length})`, curPl === p.id ? "plchip on" : "plchip", () => { curPl = p.id; render(); }));
  }
  bar.append(btn("+ Nova playlist", "plchip add", () => createPlaylistPrompt()));

  const activePl = catPls.find((p) => p.id === curPl);
  if (activePl) {
    head.hidden = false;
    head.innerHTML = `<div><b>🎶 ${activePl.name}</b> <span class="s">(${activePl.items.length} itens)</span></div><div style="display:flex;gap:6px"></div>`;
    const actions = head.lastChild;
    const vis = getVisibleEpisodes().filter((e) => e.status === "done" || (e.file && offline.has(pathOf(e.file))));
    if (vis.length) actions.append(btn("▶ Tocar tudo", "play", () => play(vis[0], pathOf(vis[0].file), vis)));
    if (navigator.onLine) actions.append(btn("🗑 Excluir playlist", "", () => deletePlaylist(activePl)));
  } else {
    head.hidden = true;
  }
}

function closeModal() { $("#modal").hidden = true; }
$("#modal").onclick = (ev) => { if (ev.target.id === "modal") closeModal(); };

function createPlaylistPrompt(afterCreate) {
  const box = $("#mcard");
  box.innerHTML = `<h3>Nova playlist em ${CATS.find((c) => c[0] === cat)[1]}</h3>
    <input id="plname" placeholder="Nome da playlist (ex: Treino, Favoritas)">
    <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:6px">
      <button id="mcancel">Cancelar</button>
      <button id="mok" class="play">Criar</button>
    </div>`;
  $("#modal").hidden = false;
  $("#plname").focus();
  $("#mcancel").onclick = closeModal;
  $("#mok").onclick = async () => {
    const name = $("#plname").value.trim();
    if (!name) return;
    closeModal();
    if (navigator.onLine) {
      const r = await fetch("/api/playlists", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name, category: cat }) });
      const created = await r.json();
      curPl = created.id;
      if (afterCreate) await afterCreate(created.id);
      await load();
    } else {
      const id = Date.now();
      playlists.push({ id, name, category: cat, items: [] });
      localStorage.setItem(PL_META, JSON.stringify(playlists));
      curPl = id;
      if (afterCreate) await afterCreate(id);
      render();
    }
  };
}

async function deletePlaylist(pl) {
  if (!confirm(`Excluir a playlist "${pl.name}"? (Os áudios continuam salvos)`)) return;
  await fetch("/api/playlists/" + pl.id, { method: "DELETE" });
  curPl = null;
  load();
}

function openPlaylistModal(e) {
  const box = $("#mcard");
  const catPls = playlists.filter((p) => p.category === (e.category || "podcasts"));
  box.innerHTML = `<h3>Playlists</h3><div class="s" style="margin-bottom:6px">${e.title}</div><div id="plopts" style="display:flex;flex-direction:column;gap:6px"></div>`;
  const opts = $("#plopts");
  for (const p of catPls) {
    const has = p.items.includes(e.id);
    opts.append(
      btn((has ? "✓ Na playlist: " : "+ Adicionar em: ") + p.name, has ? "ok" : "", async () => {
        if (navigator.onLine) {
          if (has) await fetch(`/api/playlists/${p.id}/items/${encodeURIComponent(e.id)}`, { method: "DELETE" });
          else await fetch(`/api/playlists/${p.id}/items`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ episode_id: e.id }) });
          closeModal();
          load();
        } else {
          p.items = has ? p.items.filter((x) => x !== e.id) : [...p.items, e.id];
          localStorage.setItem(PL_META, JSON.stringify(playlists));
          closeModal();
          render();
        }
      })
    );
  }
  opts.append(
    btn("+ Criar nova playlist com este áudio", "play", () => {
      createPlaylistPrompt(async (pid) => {
        if (navigator.onLine) {
          await fetch(`/api/playlists/${pid}/items`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ episode_id: e.id }) });
        }
      });
    })
  );
  opts.append(btn("Fechar", "", closeModal));
  $("#modal").hidden = false;
}

function move(e) {
  const box = $("#mcard");
  box.innerHTML = `<h3>Mover categoria</h3><div class="s" style="margin-bottom:6px">${e.title}</div>`;
  for (const [id, label] of CATS) {
    if (id === (e.category || "podcasts")) continue;
    box.append(
      btn(`Mover para ${label}`, "", async () => {
        closeModal();
        await fetch("/api/episodes/" + encodeURIComponent(e.id) + "/category", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ category: id }) });
        if (e.file) await unsave(e, pathOf(e.file));
        load();
      })
    );
  }
  box.append(btn("Cancelar", "", closeModal));
  $("#modal").hidden = false;
}

function btn(t, c, f) {
  const b = document.createElement("button");
  b.textContent = t;
  b.className = c;
  b.onclick = f;
  return b;
}

async function save(e, path, a) {
  a.textContent = "baixando…";
  const c = await caches.open(CACHE);
  await c.add(path); // guarda o áudio no armazenamento offline do aparelho
  if (e.thumb && e.thumb.startsWith("/audio/")) {
    try { await c.add(e.thumb); } catch {} // guarda também a thumbnail offline!
  }
  if (navigator.storage?.persist) navigator.storage.persist();
  await refreshOffline();
  render();
}
async function unsave(e, path) {
  const c = await caches.open(CACHE);
  await c.delete(path);
  if (e?.thumb && e.thumb.startsWith("/audio/")) await c.delete(e.thumb);
  await refreshOffline();
  render();
}
async function del(e) {
  if (!confirm("Apagar do servidor?")) return;
  await fetch("/api/episodes/" + encodeURIComponent(e.id), { method: "DELETE" });
  eps = eps.filter((x) => x.id !== e.id);
  if (e.file) await unsave(e, pathOf(e.file));
  load();
}

function play(e, path, list = null) {
  cur = e;
  if (list) queue = list.filter((x) => x.status === "done" || (x.file && offline.has(pathOf(x.file))));
  const au = $("#au");
  $("#player").hidden = false;
  $("#pt").textContent = e.title;
  au.src = path;
  const key = "pos:" + e.id;
  au.onloadedmetadata = () => (au.currentTime = +localStorage.getItem(key) || 0);
  au.ontimeupdate = () => localStorage.setItem(key, au.currentTime);
  au.onended = () => {
    localStorage.removeItem(key);
    playNext(1);
  };
  au.play();
  render();
  if ("mediaSession" in navigator)
    navigator.mediaSession.metadata = new MediaMetadata({ title: e.title, artist: e.channel || "", artwork: e.thumb ? [{ src: e.thumb }] : [] });
}

function playNext(dir) {
  if (!queue.length || !cur) return;
  if (shuffle && queue.length > 1) {
    const others = queue.filter((x) => x.id !== cur.id);
    const pick = others[Math.floor(Math.random() * others.length)];
    play(pick, pathOf(pick.file), queue);
    return;
  }
  const idx = queue.findIndex((x) => x.id === cur.id);
  const next = queue[idx + dir];
  if (next) play(next, pathOf(next.file), queue);
}

$("#prevBtn").onclick = () => playNext(-1);
$("#nextBtn").onclick = () => playNext(1);
$("#back15").onclick = () => ($("#au").currentTime = Math.max(0, $("#au").currentTime - 15));
$("#fwd30").onclick = () => ($("#au").currentTime = $("#au").currentTime + 30);
$("#shufBtn").onclick = () => {
  shuffle = !shuffle;
  $("#shufBtn").classList.toggle("sel", shuffle);
};

// ---- Sleep Timer (Desligamento automático) ----
const SLEEP_STEPS = [0, 15, 30, 45, 60];
let sleepIdx = 0;
$("#sleepBtn").onclick = () => {
  sleepIdx = (sleepIdx + 1) % SLEEP_STEPS.length;
  const mins = SLEEP_STEPS[sleepIdx];
  sleepUntil = mins ? Date.now() + mins * 60000 : 0;
  $("#sleepBtn").classList.toggle("sel", mins > 0);
  $("#sleepBtn").textContent = mins ? `⏲️ ${mins}m` : "⏲️ Off";
};
setInterval(() => {
  if (!sleepUntil) return;
  const rem = Math.ceil((sleepUntil - Date.now()) / 60000);
  if (rem <= 0) {
    $("#au").pause();
    sleepUntil = 0;
    sleepIdx = 0;
    $("#sleepBtn").classList.remove("sel");
    $("#sleepBtn").textContent = "⏲️ Off";
  } else {
    $("#sleepBtn").textContent = `⏲️ ${rem}m`;
  }
}, 5000);

document.querySelectorAll("[data-r]").forEach(
  (b) =>
    (b.onclick = () => {
      $("#au").playbackRate = +b.dataset.r;
      document.querySelectorAll("[data-r]").forEach((x) => x.classList.toggle("sel", x === b));
    })
);
$("#f").onsubmit = async (ev) => {
  ev.preventDefault();
  await fetch("/api/add", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: $("#u").value, category: cat, playlist_id: curPl }),
  });
  $("#u").value = "";
  load();
};

// ---- Armazenamento: escolher qualquer pasta no PC (Linux/Windows) ou cartão SD ----
async function loadStorage() {
  try {
    const s = await (await fetch("/api/storage")).json();
    const freeStr = s.free ? ` (${mb(s.free)} livres)` : "";
    $("#st").textContent = `Pasta atual: ${s.current}${freeStr}` + (s.available ? "" : " (indisponível — unidade removida?)");
    $("#customPath").value = s.current;
    const box = $("#cards");
    box.innerHTML = "";
    s.cards.forEach((p) => box.append(btn("💾 Cartão/Pendrive: " + p, "", () => setStorage(p, false))));
    if (s.current !== s.default) box.append(btn("↩ Voltar à pasta padrão", "", () => setStorage("", false)));
  } catch {}
}
async function setStorage(path, exact) {
  const r = await fetch("/api/storage", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path, exact }),
  });
  const j = await r.json();
  if (!r.ok) return alert(j.detail || "Erro ao alterar pasta");
  alert(`Pasta ativa: ${j.current}\n${j.recognized} arquivo(s) novo(s) reconhecido(s).`);
  loadStorage();
  load();
}
$("#pickDir").onclick = async () => {
  const r = await fetch("/api/storage/pick", { method: "POST" });
  const j = await r.json();
  if (!r.ok) return alert(j.detail || "Erro ao escolher pasta");
  if (j.cancelled) return;
  alert(`Pasta escolhida: ${j.current}\n${j.recognized} arquivo(s) reconhecido(s).`);
  loadStorage();
  load();
};
$("#openDir").onclick = () => fetch("/api/storage/open", { method: "POST" });
$("#setPathBtn").onclick = () => setStorage($("#customPath").value, true);
$("#scan").onclick = async () => {
  const j = await (await fetch("/api/storage/scan", { method: "POST" })).json();
  alert(`${j.recognized} arquivo(s) novo(s) reconhecido(s).`);
  load();
};
// Importa arquivos escolhidos no seletor do Android (aceita cartão SD) para o armazenamento offline do app.
$("#imp").onclick = () => $("#file").click();
$("#file").onchange = async (ev) => {
  const c = await caches.open(CACHE);
  for (const f of ev.target.files) {
    const m = /\[([\w-]{11})\]\.\w+$/.exec(f.name);
    const id = m ? m[1] : f.name;
    const path = pathOf(f.name);
    await c.put(path, new Response(f, { headers: { "Content-Type": f.type || "audio/mp4" } }));
    if (!eps.some((e) => e.id === id))
      eps.push({ id, title: f.name.replace(/\s*\[[\w-]{11}\]/, "").replace(/\.\w+$/, ""), file: f.name, size: f.size, status: "done", category: cat, thumb: m ? `https://i.ytimg.com/vi/${id}/mqdefault.jpg` : "" });
  }
  localStorage.setItem(META, JSON.stringify(eps));
  ev.target.value = "";
  await refreshOffline();
  render();
};

// ---- "Tocando agora": avisa o servidor para o overlay do OBS ----
let cur = null;
let lastReport = 0;
function report(force) {
  const now = Date.now();
  if (!cur || (!force && now - lastReport < 2000)) return;
  lastReport = now;
  const au = $("#au");
  fetch("/api/nowplaying", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      title: cur.title || "", channel: cur.channel || "", thumb: cur.thumb || "", category: cur.category || "podcasts",
      playing: !au.paused && !au.ended, pos: au.currentTime || 0, dur: isFinite(au.duration) ? au.duration : 0,
    }),
  }).catch(() => {});
}
["play", "pause", "ended", "seeked", "loadedmetadata"].forEach((ev) => $("#au").addEventListener(ev, () => report(true)));
$("#au").addEventListener("timeupdate", () => report(false));
$("#ovl").textContent = location.origin + "/overlay.html";

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js");
addEventListener("online", load);
addEventListener("offline", load);
setInterval(() => navigator.onLine && eps.some((e) => ["queued", "downloading"].includes(e.status)) && load(), 4000);
load();
loadStorage();
