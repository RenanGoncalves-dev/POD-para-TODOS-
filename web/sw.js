const SHELL = "pod-shell-v1";
const AUDIO = "pod-audio-v1";
const FILES = ["./", "index.html", "app.js", "manifest.json"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)));
  self.skipWaiting();
});
self.addEventListener("activate", (e) => e.waitUntil(clients.claim()));

// Responde pedidos Range (seek) a partir do cache — necessário para <audio> funcionar offline.
async function rangeFromCache(req) {
  const hit = await (await caches.open(AUDIO)).match(req.url);
  if (!hit) return null;
  const range = req.headers.get("range");
  const buf = await hit.arrayBuffer();
  const type = hit.headers.get("content-type") || "audio/mp4";
  if (!range) return new Response(buf, { headers: { "Content-Type": type, "Content-Length": buf.byteLength } });
  const m = /bytes=(\d*)-(\d*)/.exec(range);
  const start = m[1] ? +m[1] : 0;
  const end = m[2] ? Math.min(+m[2], buf.byteLength - 1) : buf.byteLength - 1;
  return new Response(buf.slice(start, end + 1), {
    status: 206,
    headers: {
      "Content-Type": type,
      "Content-Range": `bytes ${start}-${end}/${buf.byteLength}`,
      "Content-Length": end - start + 1,
      "Accept-Ranges": "bytes",
    },
  });
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== location.origin) return;
  if (url.pathname.startsWith("/audio/")) {
    e.respondWith((async () => (await rangeFromCache(req)) || fetch(req))());
  } else if (!url.pathname.startsWith("/api/")) {
    // app shell: rede primeiro, cache como reserva
    e.respondWith(
      fetch(req)
        .then((r) => {
          const copy = r.clone();
          caches.open(SHELL).then((c) => c.put(req, copy));
          return r;
        })
        .catch(() => caches.match(req, { ignoreSearch: true }))
    );
  }
});

