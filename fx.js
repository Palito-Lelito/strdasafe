/* Rotta — effetti: cielo stellato, pioggia/neve, intro cinematica */
window.FX = (() => {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  let canvas, ctx, W = 0, H = 0, dpr = 1;
  let skyFactor = 0;        // 0..1: quanto cielo è visibile (dipende dall'inclinazione)
  let starsEnabled = true;
  let weather = null;       // null | 'rain' | 'snow'
  let stars = [], drops = [];
  let running = false, last = 0;

  function init(el) {
    canvas = el; ctx = canvas.getContext('2d');
    resize();
    window.addEventListener('resize', resize);
    stars = Array.from({ length: 220 }, () => ({
      x: Math.random(), y: Math.random() * 0.9, r: 0.4 + Math.random() * 1.3,
      a: 0.3 + Math.random() * 0.7, tw: 0.5 + Math.random() * 2.5, ph: Math.random() * 6.28,
    }));
    loop(0);
  }
  function resize() {
    dpr = Math.min(2, devicePixelRatio || 1);
    W = canvas.width = Math.floor(innerWidth * dpr);
    H = canvas.height = Math.floor(innerHeight * dpr);
    canvas.style.width = innerWidth + 'px'; canvas.style.height = innerHeight + 'px';
  }

  function setSky(pitch) { skyFactor = Math.max(0, Math.min(1, (pitch - 42) / 22)); }
  function setStars(on) { starsEnabled = on; }
  function setWeather(kind) {
    weather = kind;
    drops = [];
    if (!kind) return;
    const n = kind === 'snow' ? 160 : 220;
    for (let i = 0; i < n; i++) drops.push(newDrop(kind, true));
  }
  function newDrop(kind, anywhere) {
    return kind === 'snow'
      ? { x: Math.random(), y: anywhere ? Math.random() : -0.05, vy: 0.04 + Math.random() * 0.06, vx: (Math.random() - 0.5) * 0.02, r: 1 + Math.random() * 2.2, ph: Math.random() * 6.28 }
      : { x: Math.random(), y: anywhere ? Math.random() : -0.1, vy: 0.9 + Math.random() * 0.6, vx: 0.12, len: 0.02 + Math.random() * 0.025, a: 0.25 + Math.random() * 0.35 };
  }

  function loop(ts) {
    requestAnimationFrame(loop);
    const dt = Math.min(0.05, (ts - last) / 1000 || 0.016); last = ts;
    const wantStars = starsEnabled && skyFactor > 0 && !reduced;
    const wantWx = !!weather && !reduced;
    if (!wantStars && !wantWx) { if (running) { ctx.clearRect(0, 0, W, H); running = false; } return; }
    running = true;
    ctx.clearRect(0, 0, W, H);

    if (wantStars) {
      const skyH = H * 0.42 * skyFactor; // fascia alta dello schermo
      const t = ts / 1000;
      for (const s of stars) {
        const y = s.y * skyH;
        const fade = 1 - y / skyH;                       // più luminose in alto
        const tw = 0.6 + 0.4 * Math.sin(t * s.tw + s.ph);
        ctx.globalAlpha = s.a * fade * tw * skyFactor;
        ctx.fillStyle = '#eef3ff';
        ctx.beginPath(); ctx.arc(s.x * W, y, s.r * dpr, 0, 6.28); ctx.fill();
      }
      ctx.globalAlpha = 1;
    }

    if (wantWx) {
      if (weather === 'rain') {
        ctx.strokeStyle = '#bcd4ff'; ctx.lineWidth = 1 * dpr; ctx.lineCap = 'round';
        for (const d of drops) {
          d.y += d.vy * dt; d.x += d.vx * dt * 0.3;
          if (d.y > 1.05) Object.assign(d, newDrop('rain', false));
          ctx.globalAlpha = d.a;
          ctx.beginPath();
          ctx.moveTo(d.x * W, d.y * H);
          ctx.lineTo((d.x - d.len * 0.25) * W, (d.y - d.len) * H);
          ctx.stroke();
        }
        ctx.globalAlpha = 1;
      } else {
        ctx.fillStyle = '#ffffff';
        for (const d of drops) {
          d.ph += dt; d.y += d.vy * dt; d.x += (d.vx + Math.sin(d.ph) * 0.01) * dt;
          if (d.y > 1.05) Object.assign(d, newDrop('snow', false));
          ctx.globalAlpha = 0.75;
          ctx.beginPath(); ctx.arc(d.x * W, d.y * H, d.r * dpr, 0, 6.28); ctx.fill();
        }
        ctx.globalAlpha = 1;
      }
    }
  }

  /** Intro cinematica: picchiata dall'alto e lenta orbita finché l'utente non interviene */
  function intro(map, center, { onDone } = {}) {
    if (reduced) { map.jumpTo({ center, zoom: 15.6, pitch: 52, bearing: -17 }); onDone?.(); return () => {}; }
    let cancelled = false;
    const stop = () => { if (cancelled) return; cancelled = true; map.stop(); onDone?.(); };
    const cancelOnInput = e => { if (e.originalEvent) { stop(); cleanup(); } };
    const cleanup = () => ['mousedown', 'touchstart', 'wheel', 'dragstart'].forEach(ev => map.off(ev, cancelOnInput));
    ['mousedown', 'touchstart', 'wheel', 'dragstart'].forEach(ev => map.on(ev, cancelOnInput));

    map.jumpTo({ center, zoom: 9.5, pitch: 0, bearing: 0 });
    window.rotta?.rlog?.('[intro] jump fatto', { c: map.getCenter(), z: map.getZoom() });
    map.flyTo({ center, zoom: 15.9, pitch: 62, bearing: -25, duration: 5200, curve: 1.2, essential: true });
    window.rotta?.rlog?.('[intro] flyTo avviato', { moving: map.isMoving() });
    const orbit = () => {
      if (cancelled) return;
      map.easeTo({ bearing: map.getBearing() + 60, pitch: 58, duration: 16000, easing: t => t, essential: true });
      map.once('moveend', () => { if (!cancelled) orbit(); });
    };
    map.once('moveend', () => { if (!cancelled) orbit(); });
    return () => { stop(); cleanup(); };
  }

  return { init, setSky, setStars, setWeather, intro };
})();
