const V="gymjbr-7203f3df24", F=["./", "index.html", "manifest.webmanifest", "icon-192.png", "icon-512.png"];
self.addEventListener("install",e=>{ e.waitUntil(caches.open(V).then(c=>c.addAll(F)).then(()=>self.skipWaiting())); });
self.addEventListener("activate",e=>{ e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==V).map(k=>caches.delete(k)))).then(()=>self.clients.claim())); });
self.addEventListener("fetch",e=>{ const u=new URL(e.request.url); if(e.request.method!=="GET"||u.origin!==location.origin) return;
  if(e.request.mode==="navigate"||u.pathname.endsWith("/")||u.pathname.endsWith("index.html")){ e.respondWith(fetch(e.request).then(res=>{ const c=res.clone(); caches.open(V).then(x=>x.put("index.html",c)); return res; }).catch(()=>caches.match("index.html"))); return; }
  e.respondWith(caches.match(e.request,{ignoreSearch:true}).then(r=>r||fetch(e.request).then(res=>{ const c=res.clone(); caches.open(V).then(x=>x.put(e.request,c)); return res; }).catch(()=>caches.match("index.html")))); });
