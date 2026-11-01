const $ = (s) => document.querySelector(s);
const CACHE = "pod-audio-v1";
const META = "pod-meta"; // localStorage: lista de episódios (p/ funcionar sem servidor)
let eps = JSON.parse(localStorage.getItem(META) || "[]");
let offline = new Set();

const CATS = [["podcasts", "🎙 Podcasts"], ["musicas", "🎵 Músicas"], ["djsets", "🎛 DJ Sets"]];
let cat = localStorage.getItem("pod-cat") || "podcasts";
// mesma normalização que o navegador usa na chave do cache
const pathOf = (file) => new URL("/audio/" + file, location.href).pathname;

const fmt = (s) => (s ? `${Math.floor(s / 60)}min` : "");
const mb = (b) => (b ? (b / 1048576).toFixed(1) + " MB" : "");

async function refreshOffline() {
  const c = await caches.open(CACHE);
  offline = new Set((await c.keys()).map((r) => new URL(r.url).pathname));
}

async function load() {
  try {
    const r = await fetch("/api/episodes");
    const server = await r.json();
    // mantém os salvos offline mesmo se apagados no servidor
    const byId = Object.fromEntries(eps.map((e) => [e.id, e]));
    server.forEach((e) => (byId[e.id] = e));
    eps = Object.values(byId);
    localStorage.setItem(META, JSON.stringify(eps));
    $("#net").textContent = "";
  } catch {
    $("#net").textContent = "Sem conexão com o servidor — mostrando só o salvo no celular";
  }
  await refreshOffline();
  render();
}

function render() {
  const list = $("#list");
  list.innerHTML = "";
  renderTabs();
  const visible = eps.filter((e) => (e.category || "podcasts") === cat && (navigator.onLine || offline.has(pathOf(e.file))));
  if (!visible.length) list.innerHTML = '<div class="empty">Nada aqui ainda.<br>Cole um link acima: ele será salvo em <b>' + CATS.find((c) => c[0] === cat)[1] + '</b>.</div>';
  for (const e of visible) {
    const path = pathOf(e.file);
    const saved = offline.has(path);
    const d = document.createElement("div");
    d.className = "ep";
    d.innerHTML = `<img src="${e.thumb || ""}" loading="lazy" alt="">
      <div class="i"><div class="t"></div><div class="s">${e.channel || ""} · ${fmt(e.duration)} · ${
      e.status === "done" ? mb(e.size) : e.status + (e.error ? " ⚠" : "")}</div></div><div class="a"></div>`;
    d.querySelector(".t").textContent = e.title;
    const a = d.querySelector(".a");
    if (e.status === "done" || saved) {
      const row = document.createElement("div");
      row.className = "row";
      row.append(btn("▶", "ic play", () => play(e, path)));
      if (navigator.onLine) row.append(btn("📂", "ic", () => move(e)));
      if (navigator.onLine) row.append(btn("🗑", "ic", () => del(e)));
      a.append(row);
      a.append(saved ? btn("✓ offline", "ok", () => unsave(path)) : btn("⬇ celular", "", () => save(e, path, a)));
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
      localStorage.setItem("pod-cat", id);
      render();
    });
    t.append(b);
  }
  $("#dest").textContent = CATS.find((c) => c[0] === cat)[1];
}

async function move(e) {
  const opts = CATS.map((c, i) => `${i + 1} = ${c[1]}`).join("\n");
  const n = parseInt(prompt("Mover para qual categoria?\n" + opts, ""), 10);
  if (!CATS[n - 1]) return;
  await fetch("/api/episodes/" + encodeURIComponent(e.id) + "/category", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ category: CATS[n - 1][0] }) });
  const oldPath = pathOf(e.file);
  await unsave(oldPath);
  load();
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
  await c.add(path); // guarda o arquivo no armazenamento do navegador
  if (navigator.storage?.persist) navigator.storage.persist();
  await refreshOffline();
  render();
}
async function unsave(path) {
  await (await caches.open(CACHE)).delete(path);
  await refreshOffline();
  render();
}
async function del(e) {
  if (!confirm("Apagar do servidor?")) return;
  await fetch("/api/episodes/" + e.id, { method: "DELETE" });
  eps = eps.filter((x) => x.id !== e.id);
  if (e.file) await unsave(pathOf(e.file));
  load();
}

function play(e, path) {
  cur = e;
  const au = $("#au");
  $("#player").hidden = false;
  $("#pt").textContent = e.title;
  au.src = path;
  const key = "pos:" + e.id;
  au.onloadedmetadata = () => (au.currentTime = +localStorage.getItem(key) || 0);
  au.ontimeupdate = () => localStorage.setItem(key, au.currentTime);
  au.play();
  if ("mediaSession" in navigator)
    navigator.mediaSession.metadata = new MediaMetadata({ title: e.title, artist: e.channel || "", artwork: e.thumb ? [{ src: e.thumb }] : [] });
}

document.querySelectorAll("[data-r]").forEach(
  (b) =>
    (b.onclick = () => {
      $("#au").playbackRate = +b.dataset.r;
      document.querySelectorAll("[data-r]").forEach((x) => x.classList.toggle("sel", x === b));
    })
);
$("#f").onsubmit = async (ev) => {
  ev.preventDefault();
  await fetch("/api/add", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url: $("#u").value, category: cat }) });
  $("#u").value = "";
  load();
};

// ---- Armazenamento: cartão no PC (servidor) e importação do cartão do celular ----
async function loadStorage() {
  try {
    const s = await (await fetch("/api/storage")).json();
    $("#st").textContent = `Salvando em: ${s.current}` + (s.available ? "" : " (indisponível — cartão removido?)");
    const box = $("#cards");
    box.innerHTML = "";
    s.cards.forEach((p) => box.append(btn("Usar cartão: " + p, "g", () => setStorage(p))));
    if (s.current !== s.default) box.append(btn("Voltar ao disco padrão", "g", () => setStorage("")));
  } catch {}
}
async function setStorage(path) {
  const r = await fetch("/api/storage", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ path }) });
  const j = await r.json();
  if (!r.ok) return alert(j.detail || "Erro");
  alert(`Pronto! ${j.recognized} arquivo(s) reconhecido(s) no local.`);
  loadStorage();
  load();
}
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
      eps.push({ id, title: f.name.replace(/\s*\[[\w-]{11}\]/, "").replace(/\.\w+$/, ""), file: f.name, size: f.size, status: "done", thumb: m ? `https://i.ytimg.com/vi/${id}/mqdefault.jpg` : "" });
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

