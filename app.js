/* Rotta — applicazione */
(() => {
  'use strict';

  const VERSION = 'v26';
  const $ = id => document.getElementById(id);
  const els = {
    topbar: $('topbar'), input: $('search-input'), clear: $('search-clear'), results: $('results'), chips: $('chips'),
    banner: $('banner'), bannerIcon: $('banner-icon'), bannerDist: $('banner-distance'), bannerStreet: $('banner-street'),
    bannerNext: $('banner-next'), bannerNextIcon: $('banner-next-icon'), bannerNextText: $('banner-next-text'),
    rail: $('rail'), btnLocate: $('btn-locate'), btn3d: $('btn-3d'), btnNorth: $('btn-north'), compass: $('compass'),
    btnTheme: $('btn-theme'), btnSettings: $('btn-settings'),
    sheet: $('sheet'), destName: $('dest-name'), destAddr: $('dest-addr'), btnCloseSheet: $('btn-close-sheet'),
    btnFavHome: $('btn-fav-home'), btnFavWork: $('btn-fav-work'), stops: $('stops'),
    opts: $('opts'), optTolls: $('opt-tolls'), optHighways: $('opt-highways'),
    summary: $('summary'), sumTime: $('sum-time'), sumDist: $('sum-dist'), sumEta: $('sum-eta'), sumCost: $('sum-cost'), sumNote: $('sum-note'),
    elev: $('elev'), elevSvg: $('elev-svg'), elevMeta: $('elev-meta'), alts: $('alts'),
    btnStart: $('btn-start'), btnSim: $('btn-sim'), btnSteps: $('btn-steps'), steps: $('steps'),
    navbar: $('navbar'), navTime: $('nav-time'), navDist: $('nav-dist'), navEta: $('nav-eta'),
    btnRecenter: $('btn-recenter'), btnShare: $('btn-share'), btnMute: $('btn-mute'), icoSound: $('ico-sound'), icoMuted: $('ico-muted'), btnStop: $('btn-stop'),
    hud: $('hud'), hudSpeed: $('hud-speed'), hudLimit: $('hud-limit'), hudAvg: $('hud-avg'), hudAvgVal: $('hud-avg-val'), toast: $('toast'),
    sumCams: $('sum-cams'), camAlert: $('cam-alert'), camLimit: $('cam-alert-limit'), camTitle: $('cam-alert-title'), camDist: $('cam-alert-dist'), gpsLost: $('gps-lost'), setCams: $('set-cams'),
    dlgSettings: $('dlg-settings'), setTheme: $('set-theme'), setFuel: $('set-fuel'), setPrice: $('set-price'), setIntro: $('set-intro'),
    dlgTrip: $('dlg-trip'), tripTitle: $('trip-title'), tripTime: $('trip-time'), tripDist: $('trip-dist'), tripAvg: $('trip-avg'), tripMax: $('trip-max'),
  };

  const STYLE_URL = 'https://tiles.openfreemap.org/styles/dark';
  const RASTER_STYLE = {
    version: 8,
    sources: { carto: { type: 'raster', tileSize: 256, tiles: ['a', 'b', 'c', 'd'].map(s => `https://${s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png`), attribution: '© OpenStreetMap, © CARTO' } },
    layers: [{ id: 'carto', type: 'raster', source: 'carto' }],
  };
  const DEFAULT_CENTER = [9.19, 45.464];
  const ZOOM_NAV = { auto: 17, bicycle: 17.4, pedestrian: 18 };
  const SPEED_SIM = { auto: 13, bicycle: 5, pedestrian: 1.5 };
  const FAR = { auto: 400, bicycle: 150, pedestrian: 70 };
  const NEAR = { auto: 50, bicycle: 25, pedestrian: 15 };
  const PARAMS = new URLSearchParams(location.search);
  const TERRAIN_ENABLED = PARAMS.get('t') !== '0';

  const settings = Store.settings();
  const state = {
    mode: 'auto', theme: settings.theme,
    position: null, heading: null, speed: 0, lastFix: null, gpsStatus: 'off',
    dest: null, stops: [], route: null, routes: [], track: null, elevation: null,
    avoidTolls: settings.avoidTolls, avoidHighways: settings.avoidHighways,
    navigating: false, simulating: false, follow: true, threeD: true,
    offRoute: 0, rerouting: false, lastReroute: 0, announced: new Set(),
    sim: { along: 0, timer: null }, trip: null,
    wakeLock: null, gpsWatch: null, popup: null, introStop: null, weather: null,
    // da StradaSafe: autovelox/Tutor sul percorso, limite di velocità, stima in galleria, GPS nativo in background
    safetyOn: settings.cams, cams: [], camWarned: new Set(), camPassed: new Set(), camNear: null, tutor: null,
    limit: null, limitAt: 0, limitOkAt: 0, lastAlong: 0, lastFixAt: 0, nativeFeedAt: 0,
    dr: { timer: null, active: false, along: 0, since: 0 },
  };
  const CAM_WARN = 500;        // m: preavviso autovelox
  const CAM_OFFSET = 45;       // m: distanza massima dal percorso perché un dispositivo "sia sulla rotta"
  const DR_AFTER = 6000;       // ms senza GPS prima di stimare la posizione (galleria)
  const DR_MAX = 180000;       // ms: oltre, la stima si ferma

  const isWide = () => matchMedia('(min-width: 760px)').matches;
  const lower = s => s ? s.charAt(0).toLowerCase() + s.slice(1) : s;

  /* ─────────── diagnostica (solo in locale: manda i log a serve.py) ─────────── */
  const LOCAL = /^(localhost|127\.0\.0\.1)$/.test(location.hostname);
  function rlog(...args) {
    if (!LOCAL) return;
    const line = `${new Date().toISOString().slice(11, 19)} ${args.map(a => (a instanceof Error ? `${a.message}\n${a.stack}` : typeof a === 'string' ? a : JSON.stringify(a))).join(' ')}`;
    try { fetch('/__log', { method: 'POST', body: line, keepalive: true }); } catch (e) { /* ignora */ }
  }
  if (LOCAL) {
    window.addEventListener('error', e => rlog('[window.error]', e.message, e.filename, e.lineno, e.error));
    window.addEventListener('unhandledrejection', e => rlog('[unhandled]', e.reason));
    for (const level of ['error', 'warn']) {
      const orig = console[level].bind(console);
      console[level] = (...a) => { orig(...a); rlog(`[console.${level}]`, ...a); };
    }
  }

  /* ─────────── toast ─────────── */
  let toastTimer;
  function toast(msg, ms = 3200) {
    els.toast.textContent = msg;
    els.toast.classList.add('is-visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => els.toast.classList.remove('is-visible'), ms);
  }

  /* ─────────── mappa ─────────── */
  let map, puck, destMarker, firstSymbolLayer, stopMarkers = [], parkMarker = null;

  async function loadStyle() {
    try {
      const r = await fetch(STYLE_URL);
      if (!r.ok) throw new Error(r.status);
      return await r.json();
    } catch (e) {
      console.warn('[style]', e);
      toast('Mappa vettoriale non raggiungibile: uso la mappa di riserva (senza 3D)');
      return RASTER_STYLE;
    }
  }

  async function initMap() {
    const style = await loadStyle();
    map = new maplibregl.Map({
      container: 'map', style,
      center: DEFAULT_CENTER, zoom: 15.2, pitch: 52, bearing: -17,
      maxPitch: 70, attributionControl: false, fadeDuration: 150,
    });
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-left');
    map.touchPitch.enable();

    map.on('load', () => {
      rlog('[load] stile caricato', { layers: map.getStyle().layers.length });
      setTimeout(() => map.resize(), 0);
      firstSymbolLayer = (map.getStyle().layers || []).find(l => l.type === 'symbol')?.id;
      const steps = { addTerrain, addRoadGlow, add3DBuildings, addRouteLayers, applyTheme };
      for (const [name, fn] of Object.entries(steps)) {
        try { fn(); rlog('[load] ok', name); } catch (e) { console.error(`[rotta] ${name}`, e); toast(`Errore in ${name}: ${e.message}`, 12000); }
      }
      FX.setSky(map.getPitch());
      renderParking();
    });
    map.on('error', e => { if (e?.error?.message) console.warn('[map]', e.error.message); });
    map.on('rotate', () => { els.compass.style.transform = `rotate(${-map.getBearing()}deg)`; });
    map.on('pitch', () => FX.setSky(map.getPitch()));
    map.on('click', onMapClick);

    const userMoved = e => { if (e.originalEvent && state.navigating) leaveFollow(); };
    ['dragstart', 'rotatestart', 'pitchstart', 'zoomstart', 'wheel'].forEach(ev => map.on(ev, userMoved));

    const el = document.createElement('div');
    el.className = 'puck';
    el.innerHTML = '<div class="puck-cone"></div><div class="puck-ring"></div><div class="puck-dot"></div>';
    puck = new maplibregl.Marker({ element: el, rotationAlignment: 'map', pitchAlignment: 'map' });

    destMarker = new maplibregl.Marker({ element: pinElement('dest'), anchor: 'bottom' });
  }

  function pinElement(kind, label) {
    const pin = document.createElement('div');
    pin.className = `pin pin-${kind}`;
    pin.innerHTML = (kind === 'dest' ? '<span class="pin-ring"></span>' : '') +
      '<svg viewBox="0 0 34 44"><path d="M17 43S3 26.5 3 16a14 14 0 0 1 28 0c0 10.5-14 27-14 27z" fill="currentColor"/><circle cx="17" cy="16" r="6" style="fill:var(--ink-800)"/>' +
      (label ? `<text x="17" y="20" text-anchor="middle" font-size="11" font-weight="700" font-family="Barlow, sans-serif" fill="currentColor">${label}</text>` : '') + '</svg>';
    return pin;
  }

  function applyTheme() {
    const T = Themes.apply(map, state.theme);
    FX.setStars(T.stars);
    els.setTheme.value = state.theme;
  }
  function setTheme(key, { announce = true, persist = true } = {}) {
    state.theme = key;
    if (persist) Store.saveSettings({ theme: key });
    if (map?.isStyleLoaded()) applyTheme();
    if (announce) toast(`Tema: ${Themes.get(key).name}`);
  }

  /* Rilievo: ombreggiatura sempre attiva, terreno 3D con la vista 3D */
  const DEM_SOURCE = 'terrain-dem', DEM_SOURCE_3D = 'terrain-dem-3d';
  const DEM = { type: 'raster-dem', encoding: 'terrarium', tileSize: 256, maxzoom: 15, tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'], attribution: 'Rilievo: Mapzen / AWS Terrain Tiles' };
  function addTerrain() {
    if (map.getSource(DEM_SOURCE)) return;
    map.addSource(DEM_SOURCE, DEM);
    map.addSource(DEM_SOURCE_3D, DEM);
    const firstFill = (map.getStyle().layers || []).find(l => l.type === 'fill')?.id;
    map.addLayer({ id: 'rotta-hillshade', type: 'hillshade', source: DEM_SOURCE, paint: { 'hillshade-exaggeration': 0.6, 'hillshade-illumination-direction': 325 } }, firstFill);
    // il terreno va attivato FUORI dal ciclo di rendering (dentro "load" manda in errore la coda di MapLibre)
    map.once('idle', () => setTimeout(applyTerrain, 0));
  }
  let terrainReadyResolve;
  const terrainReady = new Promise(res => { terrainReadyResolve = res; });
  function applyTerrain() {
    const on = state.threeD && TERRAIN_ENABLED;
    try { map.setTerrain(on ? { source: DEM_SOURCE_3D, exaggeration: 1.25 } : null); rlog('[terrain]', on); } catch (e) { console.warn('[terrain]', e); }
    terrainReadyResolve();
  }

  /* Alone luminoso sotto le strade principali */
  function addRoadGlow() {
    const style = map.getStyle();
    const srcId = Object.keys(style.sources).find(id => style.sources[id].type === 'vector');
    if (!srcId) return;
    const firstRoad = style.layers.find(l => l.type === 'line' && l['source-layer'] === 'transportation')?.id;
    map.addLayer({
      id: 'rotta-road-glow', type: 'line', source: srcId, 'source-layer': 'transportation', minzoom: 11,
      filter: ['in', ['get', 'class'], ['literal', ['motorway', 'trunk', 'primary']]],
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#5b78c8', 'line-opacity': 0.22, 'line-blur': 10, 'line-width': ['interpolate', ['exponential', 1.4], ['zoom'], 11, 4, 16, 18, 20, 48] },
    }, firstRoad);
  }

  function add3DBuildings() {
    const style = map.getStyle();
    const srcId = Object.keys(style.sources).find(id => style.sources[id].type === 'vector');
    if (!srcId) return;
    for (const l of style.layers) {
      if (l['source-layer'] === 'building' && (l.type === 'fill' || l.type === 'fill-extrusion')) map.setLayoutProperty(l.id, 'visibility', 'none');
    }
    map.addLayer({
      id: 'rotta-3d-buildings', type: 'fill-extrusion', source: srcId, 'source-layer': 'building', minzoom: 13.5,
      paint: {
        'fill-extrusion-color': '#2e3c5e',
        'fill-extrusion-height': ['interpolate', ['linear'], ['zoom'], 13.5, 0, 15, ['coalesce', ['get', 'render_height'], ['get', 'height'], 6]],
        'fill-extrusion-base': ['interpolate', ['linear'], ['zoom'], 13.5, 0, 15, ['coalesce', ['get', 'render_min_height'], ['get', 'min_height'], 0]],
        'fill-extrusion-opacity': 0.9, 'fill-extrusion-vertical-gradient': true,
      },
    }, firstSymbolLayer);
  }

  /* Percorso: alone, bordo, linea, flusso animato, alternative */
  const FLOW = [[0, 4, 3], [0.5, 4, 2.5], [1, 4, 2], [1.5, 4, 1.5], [2, 4, 1], [2.5, 4, 0.5], [3, 4, 0],
    [0, 0.5, 3, 3.5], [0, 1, 3, 3], [0, 1.5, 3, 2.5], [0, 2, 3, 2], [0, 2.5, 3, 1.5], [0, 3, 3, 1], [0, 3.5, 3, 0.5]];
  let flowStep = 0, flowLast = 0;
  function animateFlow(ts) {
    if (state.track && ts - flowLast > 70) {
      flowLast = ts; flowStep = (flowStep + 1) % FLOW.length;
      if (map.getLayer('route-flow')) map.setPaintProperty('route-flow', 'line-dasharray', FLOW[flowStep]);
    }
    requestAnimationFrame(animateFlow);
  }
  function addRouteLayers() {
    map.addSource('route', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    const before = firstSymbolLayer, remaining = ['==', ['get', 'part'], 'remaining'];
    const w = (a, b, c) => ['interpolate', ['linear'], ['zoom'], 10, a, 16, b, 20, c];
    map.addLayer({ id: 'route-alt', type: 'line', source: 'route', filter: ['==', ['get', 'part'], 'alt'], layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#6d7a99', 'line-width': w(3, 8, 14), 'line-opacity': 0.75, 'line-dasharray': [1, 1.6] } }, before);
    map.addLayer({ id: 'route-glow', type: 'line', source: 'route', filter: remaining, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#f5a93b', 'line-width': w(8, 30, 60), 'line-blur': 14, 'line-opacity': 0.45 } }, before);
    map.addLayer({ id: 'route-casing', type: 'line', source: 'route', filter: ['!=', ['get', 'part'], 'alt'], layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#8a5410', 'line-width': w(6, 17, 30) } }, before);
    map.addLayer({ id: 'route-line', type: 'line', source: 'route', filter: ['!=', ['get', 'part'], 'alt'], layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#f5a93b', 'line-width': w(4, 11, 20) } }, before);
    map.addLayer({ id: 'route-flow', type: 'line', source: 'route', filter: remaining, layout: { 'line-cap': 'butt', 'line-join': 'round' }, paint: { 'line-color': '#fff1d2', 'line-opacity': 0.35, 'line-width': w(1, 2.5, 4), 'line-dasharray': FLOW[0] } }, before);
    map.addSource('cams', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    map.addLayer({ id: 'cams', type: 'circle', source: 'cams', paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 9, 4, 15, 8, 18, 11],
      'circle-color': ['case', ['get', 'tutor'], '#ff9f0a', '#ff453a'], 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2,
    } }, before);
    drawCameras();
    map.on('click', 'route-alt', e => { const i = e.features?.[0]?.properties?.index; if (i != null) selectRoute(+i); });
    map.on('mouseenter', 'route-alt', () => { map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', 'route-alt', () => { map.getCanvas().style.cursor = ''; });
    if (!matchMedia('(prefers-reduced-motion: reduce)').matches) requestAnimationFrame(animateFlow);
  }

  function drawRoute(along = 0) {
    if (!map.getSource('route')) return;
    const features = [];
    if (state.track) {
      const coords = state.track.coords;
      if (along > 0) {
        const { point, index } = state.track.pointAt(along);
        features.push({ type: 'Feature', properties: { part: 'done' }, geometry: { type: 'LineString', coordinates: [...coords.slice(0, index + 1), point] } });
        features.push({ type: 'Feature', properties: { part: 'remaining' }, geometry: { type: 'LineString', coordinates: [point, ...coords.slice(index + 1)] } });
      } else {
        features.push({ type: 'Feature', properties: { part: 'remaining' }, geometry: { type: 'LineString', coordinates: coords } });
      }
      if (!state.navigating) {
        state.routes.forEach((r, i) => { if (r !== state.route) features.push({ type: 'Feature', properties: { part: 'alt', index: i }, geometry: { type: 'LineString', coordinates: r.coords } }); });
      }
    }
    map.getSource('route').setData({ type: 'FeatureCollection', features });
  }

  function fitRoute() {
    if (!state.track) return;
    const b = new maplibregl.LngLatBounds();
    state.routes.forEach(r => r.coords.forEach(c => b.extend(c)));
    if (state.position) b.extend(state.position);
    const sheetH = els.sheet.hidden ? 0 : els.sheet.offsetHeight;
    const padding = isWide() ? { left: 500, top: 110, right: 90, bottom: 110 } : { top: 110, bottom: sheetH + 36, left: 36, right: 36 };
    map.fitBounds(b, { padding, pitch: 0, bearing: 0, duration: 900, maxZoom: 17 });
  }

  let puckOnMap = false;
  function updatePuck(coord, heading) {
    if (!coord) return;
    puck.setLngLat(coord);
    if (!puckOnMap) { puck.addTo(map); puckOnMap = true; }
    const el = puck.getElement();
    if (heading != null && isFinite(heading)) { el.classList.add('has-heading'); puck.setRotation(heading); }
    else el.classList.remove('has-heading');
  }

  /* ─────────── GPS ─────────── */
  function setGpsStatus(status) {
    state.gpsStatus = status;
    els.btnLocate.classList.toggle('gps-off', status !== 'ok');
    els.btnLocate.title = status === 'ok' ? 'La mia posizione' : 'Posizione non disponibile';
  }
  async function approximateLocate() {
    try {
      const j = await fetch('https://ipwho.is/').then(r => r.json());
      if (j && j.success && isFinite(j.latitude)) { arrivedAt([j.longitude, j.latitude], { approximate: true }); return true; }
    } catch (e) { /* nessuna rete */ }
    return false;
  }
  function gpsHelpMessage(code) {
    if (location.protocol === 'file:') return 'Hai aperto il file direttamente: il browser blocca il GPS. Avvia "avvia.bat" oppure apri la pagina da http://localhost';
    if (location.protocol === 'http:' && !LOCAL) return 'Il GPS funziona solo da https:// (o da localhost). Pubblica la cartella su un hosting HTTPS per usarlo dal telefono';
    return { 1: 'Posizione negata: consenti l\'accesso alla posizione nel browser (icona a sinistra dell\'indirizzo)', 2: 'Nessun sensore di posizione disponibile su questo dispositivo', 3: 'Il GPS non risponde' }[code] || 'Posizione non disponibile';
  }

  /** Prima posizione nota (GPS o stimata): intro cinematica, meteo, chip */
  let firstArrival = true;
  function arrivedAt(coord, { approximate = false } = {}) {
    if (!firstArrival) return;
    firstArrival = false;
    loadWeather(coord);
    const run = () => {
      rlog('[arrivedAt] run', { dest: !!state.dest, intro: Store.settings().intro, styleLoaded: map.isStyleLoaded() });
      if (state.navigating || state.route || state.dest) return;
      if (Store.settings().intro && !PARAMS.get('to')) {
        state.introStop = FX.intro(map, coord, { onDone: () => { state.introStop = null; } });
      } else {
        map.flyTo({ center: coord, zoom: approximate ? 13.5 : 15.6, pitch: 52, duration: 1600, essential: true });
      }
    };
    terrainReady.then(() => setTimeout(run, 50));
  }
  function stopIntro() { if (state.introStop) { state.introStop(); state.introStop = null; } }

  let firstFix = true;
  /** Nuova posizione reale: dal browser (watchPosition) o dal servizio Android (window.rottaNativeFix) */
  function onFix(c, rawHeading, rawSpeed, accuracy) {
    if (state.simulating) return;
    // in navigazione un fix molto impreciso (uscita galleria, palazzi) fa più danni che altro
    if (state.navigating && state.position && accuracy > 60) return;
    let heading = rawHeading;
    const speed = rawSpeed > 0 ? rawSpeed : 0;
    if (heading == null || !isFinite(heading) || speed < 0.8) {
      heading = (state.lastFix && Geo.distance(state.lastFix, c) > 4) ? Geo.bearing(state.lastFix, c) : state.heading;
    }
    state.lastFix = state.position;
    state.position = c; state.heading = heading; state.speed = speed;
    state.lastFixAt = Date.now();
    if (state.dr.active) { state.dr.active = false; els.gpsLost.hidden = true; }
    updatePuck(c, heading);
    setGpsStatus('ok');
    if (firstFix) {
      firstFix = false;
      rlog('[gps] primo fix', { acc: Math.round(accuracy || 0) });
      els.btnLocate.classList.add('is-following');
      arrivedAt(c);
      if (state.dest && !state.route) computeRoute();
    }
    if (state.navigating) navTick(c, heading, false);
  }

  function startGps() {
    setGpsStatus('off');
    if (!navigator.geolocation) { toast('Questo browser non espone la posizione', 5000); approximateLocate(); return; }
    state.gpsWatch = navigator.geolocation.watchPosition(pos => {
      // nell'app Android, in navigazione, i fix arrivano dal servizio nativo: evita i doppioni
      if (Date.now() - state.nativeFeedAt < 4000) return;
      onFix([pos.coords.longitude, pos.coords.latitude], pos.coords.heading, pos.coords.speed, pos.coords.accuracy);
    }, async err => {
      console.warn('[gps]', err.code, err.message);
      if (state.position) return;
      setGpsStatus('off');
      toast(gpsHelpMessage(err.code), 7000);
      if (!state.route) await approximateLocate();
    }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 });
  }

  /* ─────────── meteo ─────────── */
  async function loadWeather(coord) {
    const forced = PARAMS.get('wx');
    if (forced) { state.weather = { temp: 9, label: forced === 'snow' ? 'Neve' : 'Pioggia', icon: forced === 'snow' ? '🌨️' : '🌧️', kind: forced }; FX.setWeather(forced); renderChips(); return; }
    try {
      state.weather = await Routing.weather(coord);
      FX.setWeather(state.weather.kind);
      renderChips();
    } catch (e) { /* senza meteo si vive */ }
  }

  /* ─────────── chip: meteo, Casa, Lavoro, auto, recenti ─────────── */
  const ICON = {
    home: '<svg viewBox="0 0 24 24"><path d="M3 11 12 4l9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round"/></svg>',
    work: '<svg viewBox="0 0 24 24"><rect x="3" y="7" width="18" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2M3 12h18" fill="none" stroke="currentColor" stroke-width="2.2"/></svg>',
    car: '<svg viewBox="0 0 24 24"><path d="M5 11l1.6-4.2A2 2 0 0 1 8.5 5.5h7a2 2 0 0 1 1.9 1.3L19 11m-14 0h14a2 2 0 0 1 2 2v3.5a1 1 0 0 1-1 1h-1.5M5 11a2 2 0 0 0-2 2v3.5a1 1 0 0 0 1 1h1.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><circle cx="7.5" cy="14.5" r="1.3" fill="currentColor"/><circle cx="16.5" cy="14.5" r="1.3" fill="currentColor"/></svg>',
    clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M12 7.5V12l3 2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>',
  };
  function chip(html, title, onClick, cls = '') {
    const b = document.createElement('button');
    b.type = 'button'; b.className = `chip ${cls}`; b.innerHTML = html; if (title) b.title = title;
    if (onClick) b.addEventListener('click', onClick);
    return b;
  }
  function renderChips() {
    els.chips.innerHTML = '';
    if (state.weather) {
      const w = state.weather;
      els.chips.appendChild(chip(`<span>${w.icon}</span><span>${w.temp}° · ${w.label}</span>`, 'Meteo nella tua zona (Open-Meteo)', null, 'is-wx'));
    }
    const fav = Store.favorites();
    for (const [key, label] of [['home', 'Casa'], ['work', 'Lavoro']]) {
      if (fav[key]) els.chips.appendChild(chip(`${ICON[key]}<span>${label}</span>`, fav[key].name, () => goTo(fav[key])));
    }
    const park = Store.parking();
    if (park) {
      els.chips.appendChild(chip(`${ICON.car}<span>La mia auto</span>`, `Parcheggiata alle ${Geo.fmtClock(new Date(park.at))}`, () => {
        document.querySelector('.mode[data-mode="pedestrian"]')?.click();
        goTo({ name: 'La mia auto', address: `Parcheggiata alle ${Geo.fmtClock(new Date(park.at))}`, coord: park.coord });
      }, 'is-car'));
    }
    for (const r of Store.recents()) els.chips.appendChild(chip(`${ICON.clock}<span></span>`, r.address, () => goTo(r))).querySelector('span').textContent = r.name;
  }
  function goTo(place) {
    stopIntro();
    els.input.value = place.name; els.clear.hidden = false;
    setDestination({ ...place, coord: place.coord.slice() });
  }
  function renderParking() {
    const park = Store.parking();
    if (parkMarker) { parkMarker.remove(); parkMarker = null; }
    if (park) parkMarker = new maplibregl.Marker({ element: pinElement('park', 'P'), anchor: 'bottom' }).setLngLat(park.coord).addTo(map);
  }

  /* ─────────── ricerca ─────────── */
  let searchTimer, searchSeq = 0, activeResult = -1, currentResults = [];
  function renderResults(list, { empty = false } = {}) {
    currentResults = list; activeResult = -1;
    els.results.innerHTML = '';
    if (empty) { els.results.innerHTML = '<li class="result-empty">Nessun risultato. Prova con città o via.</li>'; els.results.hidden = false; return; }
    if (!list.length) { els.results.hidden = true; return; }
    list.forEach((p, i) => {
      const li = document.createElement('li');
      li.className = 'result'; li.setAttribute('role', 'option'); li.dataset.index = i;
      li.innerHTML = `<span class="result-pin"><svg viewBox="0 0 24 24" width="18" height="18"><path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0C18.5 15.4 12 21 12 21z" fill="currentColor"/><circle cx="12" cy="10" r="2.4" style="fill:var(--ink-800)"/></svg></span><span class="result-text"><span class="result-name"></span><span class="result-addr"></span></span>`;
      li.querySelector('.result-name').textContent = p.name;
      li.querySelector('.result-addr').textContent = p.address;
      li.addEventListener('mousedown', e => e.preventDefault());
      li.addEventListener('click', () => chooseResult(i));
      els.results.appendChild(li);
    });
    els.results.hidden = false;
  }
  function highlight(i) {
    activeResult = i;
    [...els.results.children].forEach((li, k) => li.setAttribute('aria-selected', k === i ? 'true' : 'false'));
    els.results.children[i]?.scrollIntoView({ block: 'nearest' });
  }
  function chooseResult(i) {
    const p = currentResults[i];
    if (!p) return;
    els.results.hidden = true; els.input.value = p.name; els.input.blur();
    stopIntro();
    setDestination(p);
  }
  els.input.addEventListener('input', () => {
    const q = els.input.value.trim();
    els.clear.hidden = !q;
    clearTimeout(searchTimer);
    if (q.length < 2) { els.results.hidden = true; return; }
    searchTimer = setTimeout(async () => {
      const seq = ++searchSeq;
      try {
        const bias = state.position || [map.getCenter().lng, map.getCenter().lat];
        const list = await Routing.search(q, bias);
        if (seq !== searchSeq) return;
        renderResults(list, { empty: !list.length });
      } catch (e) { if (seq === searchSeq) toast('Ricerca non raggiungibile. Controlla la connessione.'); }
    }, 260);
  });
  els.input.addEventListener('keydown', e => {
    if (e.key === 'Escape') { els.results.hidden = true; els.input.blur(); return; }
    if (els.results.hidden) return;
    const n = currentResults.length;
    if (e.key === 'ArrowDown') { e.preventDefault(); highlight((activeResult + 1) % n); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); highlight((activeResult - 1 + n) % n); }
    else if (e.key === 'Enter') { e.preventDefault(); chooseResult(activeResult >= 0 ? activeResult : 0); }
  });
  els.input.addEventListener('focus', () => { stopIntro(); if (currentResults.length && els.input.value.trim().length >= 2) els.results.hidden = false; });
  document.addEventListener('click', e => { if (!e.target.closest('#search')) els.results.hidden = true; });
  els.clear.addEventListener('click', () => { els.input.value = ''; els.clear.hidden = true; els.results.hidden = true; els.input.focus(); });

  /* ─────────── click sulla mappa: destinazione o tappa ─────────── */
  async function onMapClick(e) {
    if (state.navigating) return;
    if (e.originalEvent.target.closest?.('.maplibregl-marker, .maplibregl-popup')) return;
    if (map.queryRenderedFeatures(e.point, { layers: ['route-alt'] }).length) return;
    stopIntro();
    const coord = [e.lngLat.lng, e.lngLat.lat];
    state.popup?.remove();
    const popup = new maplibregl.Popup({ offset: 14, maxWidth: '300px' }).setLngLat(coord).setHTML('<div class="popup-title">Cerco l\'indirizzo…</div>').addTo(map);
    state.popup = popup;
    const place = await Routing.reverse(coord);
    place.coord = coord;
    if (state.popup !== popup || !popup.isOpen()) return;
    popup.setHTML(`<div class="popup-title"></div><div class="popup-addr"></div><div class="popup-actions"><button class="btn btn-primary popup-go">Vai qui</button>${state.dest ? '<button class="btn btn-ghost popup-stop">Aggiungi tappa</button>' : ''}</div>`);
    const el = popup.getElement();
    el.querySelector('.popup-title').textContent = place.name;
    el.querySelector('.popup-addr').textContent = place.address;
    el.querySelector('.popup-go').addEventListener('click', () => { popup.remove(); els.input.value = place.name; els.clear.hidden = false; setDestination(place); });
    el.querySelector('.popup-stop')?.addEventListener('click', () => { popup.remove(); addStop(place); });
  }

  /* ─────────── percorso ─────────── */
  let routeSeq = 0;

  function setDestination(place) {
    state.dest = place;
    state.stops = [];
    renderStops();
    destMarker.setLngLat(place.coord).addTo(map);
    els.destName.textContent = place.name;
    els.destAddr.textContent = place.address || '';
    els.sheet.hidden = false;
    Store.addRecent(place); renderChips();
    computeRoute();
  }
  function addStop(place) {
    state.stops.push(place);
    renderStops();
    computeRoute();
    toast(`Tappa aggiunta: ${place.name}`);
  }
  function removeStop(i) { state.stops.splice(i, 1); renderStops(); computeRoute(); }
  function renderStops() {
    stopMarkers.forEach(m => m.remove()); stopMarkers = [];
    els.stops.innerHTML = '';
    els.stops.hidden = !state.stops.length;
    state.stops.forEach((s, i) => {
      const li = document.createElement('li');
      li.className = 'stop';
      li.innerHTML = `<span class="stop-dot"></span><span class="stop-name"></span><button class="stop-rm" type="button" aria-label="Rimuovi tappa"><svg viewBox="0 0 24 24" width="14" height="14"><path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg></button>`;
      li.querySelector('.stop-name').textContent = `Tappa ${i + 1}: ${s.name}`;
      li.querySelector('.stop-rm').addEventListener('click', () => removeStop(i));
      els.stops.appendChild(li);
      stopMarkers.push(new maplibregl.Marker({ element: pinElement('stop', String(i + 1)), anchor: 'bottom' }).setLngLat(s.coord).addTo(map));
    });
  }

  async function computeRoute({ silent = false } = {}) {
    if (!state.dest) return;
    const seq = ++routeSeq;
    const origin = state.position || [map.getCenter().lng, map.getCenter().lat];
    const fromMapCenter = !state.position;
    if (!silent) {
      els.summary.classList.add('is-loading');
      els.sumTime.textContent = 'Calcolo…';
      els.sumDist.textContent = '—'; els.sumEta.textContent = '—'; els.sumCost.hidden = true;
      els.sumNote.hidden = true; els.elev.hidden = true; els.alts.hidden = true;
      [els.btnStart, els.btnSim, els.btnSteps].forEach(b => b.disabled = true);
    }
    els.opts.hidden = state.mode !== 'auto';
    try {
      const points = [origin, ...state.stops.map(s => s.coord), state.dest.coord];
      const list = await Routing.routes(points, state.mode, { alternates: !state.stops.length, avoidTolls: state.avoidTolls, avoidHighways: state.avoidHighways });
      if (seq !== routeSeq) return;
      state.routes = list;
      useRoute(list[0]);
      renderAlts();
      [els.btnStart, els.btnSim, els.btnSteps].forEach(b => b.disabled = false);
      if (fromMapCenter) { els.sumNote.textContent = 'Partenza dal centro della mappa: il GPS non è ancora disponibile.'; els.sumNote.hidden = false; }
      if (!silent) fitRoute();
      loadElevation(seq);
    } catch (e) {
      if (seq !== routeSeq) return;
      console.error(e);
      els.sumTime.textContent = 'Nessun percorso';
      els.sumNote.textContent = 'Il servizio di percorsi non ha risposto. Riprova tra qualche secondo.';
      els.sumNote.hidden = false;
      els.btnSteps.disabled = true;
    } finally {
      if (seq === routeSeq) els.summary.classList.remove('is-loading');
    }
  }
  function useRoute(r) {
    state.route = r;
    state.track = new Geo.Track(r.coords);
    drawRoute(0);
    renderSummary(r);
    renderSteps(r);
    refreshCameras();
  }

  /* ─────────── autovelox e Tutor lungo il percorso ─────────── */
  function refreshCameras() {
    state.cams = state.safetyOn && state.mode === 'auto' && state.track ? Safety.onRoute(state.track, CAM_OFFSET) : [];
    drawCameras();
    const tutor = state.cams.filter(c => c.tutor).length, velox = state.cams.length - tutor;
    const parts = [];
    if (velox) parts.push(velox === 1 ? '1 autovelox' : `${velox} autovelox`);
    if (tutor) parts.push(tutor === 1 ? '1 Tutor' : `${tutor} Tutor`);
    els.sumCams.textContent = parts.length ? `⚠ ${parts.join(' e ')} lungo il percorso` : '';
    els.sumCams.hidden = !parts.length;
  }
  function drawCameras() {
    if (!map?.getSource('cams')) return;
    map.getSource('cams').setData({ type: 'FeatureCollection', features: state.cams.map(c => ({ type: 'Feature', properties: { tutor: c.tutor }, geometry: { type: 'Point', coordinates: c.coord } })) });
  }
  const camLabel = c => c.tutor ? 'Tutor' : 'Autovelox';
  function hideCamAlert() { state.camNear = null; els.camAlert.hidden = true; }

  /** A ogni tick di navigazione: preavviso, passaggio, tratto Tutor con velocità media */
  function safetyTick(along) {
    state.lastAlong = along;
    const cams = state.cams;
    if (!cams.length) { if (!els.camAlert.hidden) hideCamAlert(); return; }

    const ahead = cams.find(c => c.along >= along - 20);
    const gap = ahead ? ahead.along - along : Infinity;
    if (ahead && gap <= CAM_WARN) {
      state.camNear = ahead;
      els.camTitle.textContent = camLabel(ahead);
      els.camDist.textContent = gap > 15 ? Geo.fmtDistance(gap) : 'ora';
      els.camLimit.textContent = ahead.limit || '';
      els.camLimit.hidden = !ahead.limit;
      els.camAlert.hidden = false;
      if (!state.camWarned.has(ahead.id)) {
        state.camWarned.add(ahead.id);
        const where = gap > 120 ? ` tra ${spokenDistance(gap)}` : '';
        Voice.say(`Attenzione, ${ahead.tutor ? 'Tutor' : 'autovelox'}${where}${ahead.limit ? `, limite ${ahead.limit}` : ''}`, { interrupt: false });
      }
    } else if (!els.camAlert.hidden) hideCamAlert();

    for (const c of cams) {
      if (c.along > along) break;
      if (state.camPassed.has(c.id)) continue;
      state.camPassed.add(c.id);
      if (!c.tutor) continue;
      if (state.tutor) {
        const sec = (Date.now() - state.tutor.at) / 1000;
        const avg = sec > 1 ? Math.round((c.along - state.tutor.along) / sec * 3.6) : 0;
        state.tutor = null;
        els.hudAvg.hidden = true;
        toast(`Fine tratto Tutor · media ${avg} km/h`, 6000);
        Voice.say(`Fine tratto Tutor. Media ${avg} chilometri orari`, { interrupt: false });
      } else if (cams.some(n => n.tutor && n.along > c.along + 200 && n.along - c.along < 40000)) {
        // il Tutor misura la media fra due portali: parte solo se più avanti sul percorso ce n'è un altro
        state.tutor = { along: c.along, at: Date.now(), limit: c.limit || null };
        Voice.say('Inizio tratto Tutor', { interrupt: false });
      }
    }

    if (state.tutor) {
      const sec = (Date.now() - state.tutor.at) / 1000;
      if (sec > 3) {
        const avg = Math.round((along - state.tutor.along) / sec * 3.6);
        els.hudAvgVal.textContent = Math.max(0, avg);
        els.hudAvg.classList.toggle('is-over', !!state.tutor.limit && avg > state.tutor.limit);
        els.hudAvg.hidden = false;
      }
    }
  }

  /* ─────────── limite di velocità (OpenStreetMap via Overpass) ─────────── */
  const currentLimit = () => state.limit ?? state.camNear?.limit ?? null;
  function renderLimit() {
    const lim = currentLimit();
    els.hudLimit.textContent = lim || '';
    els.hudLimit.hidden = !lim;
  }
  function pollLimit(coord, street) {
    if (!state.safetyOn || state.mode !== 'auto' || state.dr.active) return;
    const now = Date.now();
    if (now - state.limitAt < 20000) return;
    state.limitAt = now;
    Safety.speedLimit(coord, street).then(lim => {
      if (!state.navigating) return;
      state.limit = lim; state.limitOkAt = Date.now();
    }).catch(() => {
      // servizio non raggiungibile: un limite vecchio di oltre un minuto non è più affidabile
      if (Date.now() - state.limitOkAt > 60000) state.limit = null;
    });
  }

  /* ─────────── galleria: senza GPS la posizione avanza lungo il percorso all'ultima velocità nota ─────────── */
  function drTick() {
    const track = state.track;
    if (!state.navigating || state.simulating || !track || state.rerouting) return;
    const now = Date.now(), silent = now - state.lastFixAt;
    if (!state.dr.active) {
      if (!state.lastFixAt || silent < DR_AFTER || state.speed < 3 || state.offRoute > 0) return;
      state.dr.active = true; state.dr.since = now;
      state.dr.along = state.lastAlong + state.speed * Math.min(silent, DR_AFTER + 2000) / 1000;
      els.gpsLost.textContent = 'Segnale GPS perso · posizione stimata';
      els.gpsLost.hidden = false;
    } else if (now - state.dr.since > DR_MAX) {
      els.gpsLost.textContent = 'Segnale GPS perso';
      return;
    } else {
      state.dr.along += state.speed;
    }
    // non "arrivare" mai per stima: l'arrivo lo decide solo il GPS vero
    state.dr.along = Math.min(state.dr.along, track.length - 60);
    if (state.dr.along <= state.lastAlong) return;
    navTick(track.pointAt(state.dr.along).point, track.bearingAt(state.dr.along + 2), true, true);
  }
  function stopDeadReckoning() {
    clearInterval(state.dr.timer);
    state.dr.timer = null; state.dr.active = false;
    els.gpsLost.hidden = true;
  }

  /* ponte con l'app Android: servizio in primo piano che tiene vivo il GPS a schermo spento */
  const nativeNav = () => { try { return window.AndroidNav || null; } catch (e) { return null; } };
  function selectRoute(i) {
    const r = state.routes[i];
    if (!r || r === state.route) return;
    useRoute(r);
    renderAlts();
    loadElevation(routeSeq);
  }
  function renderAlts() {
    els.alts.innerHTML = '';
    els.alts.hidden = state.routes.length < 2;
    state.routes.forEach((r, i) => {
      const li = document.createElement('li');
      li.innerHTML = `<button class="alt${r === state.route ? ' is-active' : ''}" type="button"><strong>${Geo.fmtDuration(r.duration)}</strong>${Geo.fmtDistance(r.distance)} · ${i === 0 ? 'consigliato' : `alternativa ${i}`}</button>`;
      li.querySelector('button').addEventListener('click', () => selectRoute(i));
      els.alts.appendChild(li);
    });
  }

  function splitDuration(s) {
    const min = Math.round(s / 60);
    if (min < 60) return `${Math.max(1, min)}<small>min</small>`;
    const h = Math.floor(min / 60), m = min % 60;
    return `${h}<small>h</small> ${m ? `${m}<small>min</small>` : ''}`;
  }
  function fuelCost(distance) {
    const s = Store.settings();
    const liters = distance / 1000 * s.fuelL100 / 100;
    return { liters, cost: liters * s.fuelPrice };
  }
  function renderSummary(r) {
    els.sumTime.innerHTML = splitDuration(r.duration);
    els.sumDist.textContent = Geo.fmtDistance(r.distance);
    els.sumEta.textContent = Geo.fmtClock(new Date(Date.now() + r.duration * 1000));
    if (state.mode === 'auto' && r.distance > 500) {
      const f = fuelCost(r.distance);
      els.sumCost.textContent = `≈ ${f.cost.toFixed(2).replace('.', ',')} € · ${f.liters.toFixed(1).replace('.', ',')} l`;
      els.sumCost.hidden = false;
    } else els.sumCost.hidden = true;
  }
  function renderSteps(r) {
    els.steps.innerHTML = '';
    r.maneuvers.forEach(m => {
      const li = document.createElement('li');
      li.className = 'step';
      li.innerHTML = `<span class="step-icon">${Icons.get(m.kind)}</span><span class="step-text"></span><span class="step-dist"></span>`;
      li.querySelector('.step-text').textContent = m.text;
      li.querySelector('.step-dist').textContent = m.kind === 'arrive' ? '' : Geo.fmtDistance(m.length);
      li.addEventListener('click', () => map.flyTo({ center: r.coords[m.beginIndex], zoom: 17, pitch: 50, duration: 900 }));
      els.steps.appendChild(li);
    });
  }

  /* Profilo altimetrico */
  async function loadElevation(seq) {
    if (!state.route) return;
    els.elev.hidden = true;
    try {
      const pts = await Routing.elevation(state.route.coords);
      if (seq !== routeSeq || pts.length < 3) return;
      state.elevation = pts;
      let up = 0, down = 0, min = Infinity, max = -Infinity;
      for (let i = 1; i < pts.length; i++) { const d = pts[i][1] - pts[i - 1][1]; if (d > 0) up += d; else down -= d; }
      for (const p of pts) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); }
      const span = Math.max(30, max - min);
      const L = pts[pts.length - 1][0] || 1;
      const X = d => (d / L) * 400, Y = h => 52 - ((h - min) / span) * 46;
      const line = pts.map((p, i) => `${i ? 'L' : 'M'}${X(p[0]).toFixed(1)} ${Y(p[1]).toFixed(1)}`).join(' ');
      els.elevSvg.innerHTML = `<path class="area" d="${line} L400 56 L0 56 Z"/><path class="line" d="${line}"/>`;
      els.elevMeta.innerHTML = `<span>↗ <strong>+${Math.round(up)} m</strong></span><span>↘ <strong>−${Math.round(down)} m</strong></span><span>min <strong>${Math.round(min)} m</strong></span><span>max <strong>${Math.round(max)} m</strong></span>`;
      els.elev.hidden = false;
    } catch (e) { console.warn('[elev]', e); }
  }

  document.querySelectorAll('.mode').forEach(btn => btn.addEventListener('click', () => {
    if (btn.dataset.mode === state.mode) return;
    state.mode = btn.dataset.mode;
    document.querySelectorAll('.mode').forEach(b => { const on = b === btn; b.classList.toggle('is-active', on); b.setAttribute('aria-selected', on); });
    els.opts.hidden = state.mode !== 'auto';
    if (state.dest) computeRoute();
  }));
  for (const [el, key] of [[els.optTolls, 'avoidTolls'], [els.optHighways, 'avoidHighways']]) {
    el.setAttribute('aria-pressed', state[key]);
    el.addEventListener('click', () => { state[key] = !state[key]; el.setAttribute('aria-pressed', state[key]); Store.saveSettings({ [key]: state[key] }); computeRoute(); });
  }
  els.btnSteps.addEventListener('click', () => { const open = els.steps.hidden; els.steps.hidden = !open; els.btnSteps.setAttribute('aria-expanded', open); });
  els.btnCloseSheet.addEventListener('click', clearRoute);
  els.btnFavHome.addEventListener('click', () => { if (state.dest) { Store.setFavorite('home', state.dest); renderChips(); toast('Salvata come Casa'); } });
  els.btnFavWork.addEventListener('click', () => { if (state.dest) { Store.setFavorite('work', state.dest); renderChips(); toast('Salvata come Lavoro'); } });

  function clearRoute() {
    routeSeq++;
    state.dest = null; state.route = null; state.routes = []; state.track = null; state.stops = []; state.elevation = null;
    refreshCameras();
    destMarker.remove(); renderStops();
    drawRoute();
    els.sheet.hidden = true; els.steps.hidden = true; els.btnSteps.setAttribute('aria-expanded', 'false');
    els.input.value = ''; els.clear.hidden = true;
    if (state.position) map.easeTo({ center: state.position, zoom: 15.6, pitch: state.threeD ? 52 : 0, duration: 900 });
  }

  /* ─────────── navigazione ─────────── */
  let shownIdx = -1, tickCount = 0;

  function beginNavigation(simulate) {
    if (!state.route) return;
    stopIntro();
    state.popup?.remove();
    state.navigating = true; state.simulating = simulate; state.follow = true;
    state.offRoute = 0; state.announced = new Set(); state.track.lastIndex = 0; shownIdx = -1;
    state.camWarned = new Set(); state.camPassed = new Set(); state.camNear = null; state.tutor = null;
    state.limit = null; state.limitAt = 0; state.limitOkAt = 0; state.lastAlong = 0;
    els.camAlert.hidden = true; els.hudAvg.hidden = true; els.gpsLost.hidden = true; renderLimit();
    stopDeadReckoning();
    if (!simulate) {
      state.lastFixAt = Date.now();
      state.dr.timer = setInterval(drTick, 1000);
      nativeNav()?.start(state.dest?.name || '');
    }
    state.trip = { start: Date.now(), maxSpeed: 0, samples: 0, speedSum: 0, distance: state.route.distance, mode: state.mode, dest: state.dest?.name || '' };
    document.body.classList.add('is-navigating');
    els.sheet.hidden = true; els.topbar.hidden = true; els.results.hidden = true;
    els.banner.hidden = false; els.navbar.hidden = false; els.btnRecenter.hidden = true;
    els.btnLocate.classList.add('is-following');
    drawRoute(0);
    requestWakeLock();
    Voice.say(state.route.maneuvers[0]?.voice || 'Avvio della navigazione');
    showManeuver(1, Infinity);
    if (simulate) {
      state.sim.along = 0;
      const tick = () => {
        if (!state.simulating) return;
        state.sim.along += SPEED_SIM[state.mode] * 0.4;
        const { point } = state.track.pointAt(state.sim.along);
        navTick(point, state.track.bearingAt(state.sim.along + 2), true);
      };
      tick();
      state.sim.timer = setInterval(tick, 400);
      toast('Simulazione del percorso in corso');
    } else if (state.position) {
      navTick(state.position, state.heading, false);
    } else {
      toast(state.gpsStatus === 'ok' ? 'In attesa del segnale GPS…' : `${gpsHelpMessage(1)}. Intanto puoi usare "Simula"`, 8000);
      map.easeTo({ center: state.track.coords[0], zoom: ZOOM_NAV[state.mode], pitch: state.threeD ? 60 : 0, bearing: state.track.bearingAt(0), duration: 1200 });
    }
  }

  function stopNavigation({ arrived = false } = {}) {
    clearInterval(state.sim.timer);
    stopDeadReckoning();
    nativeNav()?.stop();
    state.navigating = false; state.simulating = false; state.rerouting = false;
    state.tutor = null; state.limit = null; hideCamAlert(); els.hudAvg.hidden = true; els.hud.classList.remove('is-over');
    document.body.classList.remove('is-navigating');
    els.banner.hidden = true; els.navbar.hidden = true; els.topbar.hidden = false; els.btnRecenter.hidden = true; els.hud.hidden = true;
    Voice.stop();
    releaseWakeLock();
    map.setPadding({ top: 0, bottom: 0, left: 0, right: 0 });
    if (state.position) updatePuck(state.position, state.heading);
    if (arrived) { clearRoute(); return; }
    if (state.route) { drawRoute(0); els.sheet.hidden = false; renderSummary(state.route); fitRoute(); }
  }

  function navZoom(speedMs) {
    const kmh = (speedMs || 0) * 3.6;
    const k = Math.min(1, Math.max(0, (kmh - 45) / 85));
    return ZOOM_NAV[state.mode] - 1.2 * k;
  }

  function navTick(coord, heading, sim, estimated = false) {
    const track = state.track, route = state.route;
    if (!track || !state.navigating) return;
    const snap = track.snap(coord);
    if (!sim && !state.rerouting) {
      if (snap.distance > 45) { if (++state.offRoute >= 3) { reroute(coord); return; } }
      else state.offRoute = 0;
    }
    const along = snap.along;
    const shown = snap.distance < 25 ? snap.point : coord;
    let hdg = heading;
    if (hdg == null || !isFinite(hdg)) hdg = track.bearingAt(along + 2);
    if (!sim && state.speed < 1.2 && snap.distance < 25) hdg = track.bearingAt(along + 2);
    if (!sim) state.lastAlong = along;
    updatePuck(shown, hdg);

    const mans = route.maneuvers, cum = track.cum;
    let idx = mans.findIndex((m, i) => i > 0 && cum[m.beginIndex] > along + 3);
    if (idx === -1) idx = mans.length - 1;
    const m = mans[idx];
    const distTo = Math.max(0, cum[m.beginIndex] - along);
    const remaining = track.length - along;
    if (remaining < 20 || (m.kind === 'arrive' && distTo < 20)) { arrive(); return; }

    const speed = sim && !estimated ? SPEED_SIM[state.mode] : state.speed;
    const t = state.trip; t.samples++; t.speedSum += speed; t.maxSpeed = Math.max(t.maxSpeed, speed); t.along = along;
    showManeuver(idx, distTo);
    updateNavbar(idx, distTo, remaining);
    updateHud(speed);
    announce(idx, distTo);
    drawRoute(along);
    safetyTick(along);
    pollLimit(shown, mans[idx - 1]?.street || '');
    renderLimit();
    const lim = currentLimit();
    els.hud.classList.toggle('is-over', !!lim && speed * 3.6 > lim + 0.5);

    if (state.follow) {
      map.easeTo({
        center: shown, bearing: hdg, pitch: state.threeD ? 60 : 0, zoom: navZoom(speed),
        padding: isWide() ? { top: 0, bottom: Math.round(innerHeight * 0.3), left: 460, right: 0 } : { top: 0, bottom: Math.round(innerHeight * 0.32), left: 0, right: 0 },
        duration: sim && !estimated ? 400 : 950, easing: x => x, essential: true,
      });
    }
    if (++tickCount % 25 === 1) rlog('[nav] tick', { along: Math.round(along), zoom: +map.getZoom().toFixed(2), pitch: map.getPitch(), tiles: map.areTilesLoaded() });
  }

  function fmtBig(m) {
    if (m < 1000) { const step = m < 100 ? 5 : 10; return `${Math.round(m / step) * step}<small>m</small>`; }
    return `${(m / 1000).toFixed(m < 10000 ? 1 : 0).replace('.', ',')}<small>km</small>`;
  }
  function showManeuver(idx, distTo) {
    const mans = state.route.maneuvers;
    const m = mans[Math.min(idx, mans.length - 1)];
    if (!m) return;
    if (shownIdx !== idx) {
      shownIdx = idx;
      els.bannerIcon.innerHTML = Icons.get(m.kind);
      els.bannerStreet.textContent = m.kind === 'arrive' ? (state.dest?.name || m.text) : m.text;
      const next = mans[idx + 1];
      if (next && m.length < 160 && m.kind !== 'arrive') {
        els.bannerNextIcon.innerHTML = Icons.get(next.kind);
        els.bannerNextText.textContent = `poi ${lower(next.text)}`;
        els.bannerNext.hidden = false;
      } else els.bannerNext.hidden = true;
    }
    els.bannerDist.innerHTML = isFinite(distTo) ? fmtBig(distTo) : '—';
  }
  function updateHud(speedMs) {
    els.hudSpeed.textContent = Math.max(0, Math.round((speedMs || 0) * 3.6));
    els.hud.hidden = !state.navigating;
  }
  function remainingTime(idx, distTo) {
    const mans = state.route.maneuvers;
    const seg = mans[idx - 1] || mans[0];
    const frac = Math.min(1, distTo / Math.max(1, seg.length));
    let t = frac * (seg.duration || 0);
    for (let k = idx; k < mans.length; k++) t += mans[k].duration || 0;
    return t;
  }
  let lastEta = null;
  function updateNavbar(idx, distTo, remaining) {
    const t = remainingTime(idx, distTo);
    lastEta = new Date(Date.now() + t * 1000);
    els.navTime.innerHTML = splitDuration(t);
    els.navDist.textContent = Geo.fmtDistance(remaining);
    els.navEta.textContent = Geo.fmtClock(lastEta);
  }
  function spokenDistance(m) {
    if (m >= 950) { const km = Math.round(m / 500) / 2; return km === 1 ? '1 chilometro' : `${String(km).replace('.', ',')} chilometri`; }
    return `${Math.round(m / 50) * 50} metri`;
  }
  function announce(idx, distTo) {
    const mans = state.route.maneuvers, m = mans[idx];
    const far = FAR[state.mode], near = NEAR[state.mode];
    const kFar = `${idx}-far`, kNear = `${idx}-near`;
    const next = mans[idx + 1];
    const chain = next && m.length < 120 && m.kind !== 'arrive' && !/\bpoi\b/i.test(m.voice) ? `, poi ${lower(next.voice)}` : '';
    if (distTo <= near && !state.announced.has(kNear)) {
      state.announced.add(kNear); state.announced.add(kFar);
      Voice.say(`${m.voice}${chain}`);
    } else if (distTo <= far && distTo > near + 40 && !state.announced.has(kFar)) {
      state.announced.add(kFar);
      Voice.say(`Tra ${spokenDistance(distTo)}, ${lower(m.voice)}${chain}`);
    }
  }
  async function reroute(coord) {
    if (state.rerouting || Date.now() - state.lastReroute < 8000) return;
    state.rerouting = true; state.lastReroute = Date.now();
    toast('Ricalcolo del percorso…');
    Voice.say('Ricalcolo del percorso');
    try {
      // salta le tappe già raggiunte
      const reached = state.route.maneuvers.filter(m => m.kind === 'waypoint' && state.track.cum[m.beginIndex] <= state.lastAlong + 30).length;
      const stops = state.stops.slice(reached);
      const list = await Routing.routes([coord, ...stops.map(s => s.coord), state.dest.coord], state.mode, { avoidTolls: state.avoidTolls, avoidHighways: state.avoidHighways });
      if (!state.navigating) return;
      if (reached) { state.stops = stops; renderStops(); }
      state.routes = list; state.route = list[0]; state.track = new Geo.Track(list[0].coords);
      state.announced = new Set(); state.offRoute = 0; shownIdx = -1; state.lastAlong = 0;
      // un tratto Tutor iniziato sul vecchio percorso non è più misurabile
      state.tutor = null; els.hudAvg.hidden = true;
      refreshCameras();
      renderSteps(list[0]); drawRoute(0);
    } catch (e) { toast('Ricalcolo non riuscito, riprovo più avanti'); }
    finally { state.rerouting = false; }
  }

  function arrive() {
    if (!state.navigating) return;
    clearInterval(state.sim.timer);
    stopDeadReckoning();
    nativeNav()?.stop();
    hideCamAlert(); els.hudAvg.hidden = true; state.tutor = null;
    state.simulating = false;
    const m = state.route.maneuvers[state.route.maneuvers.length - 1];
    els.bannerIcon.innerHTML = Icons.get('arrive');
    els.bannerDist.innerHTML = 'Arrivo';
    els.bannerStreet.textContent = state.dest?.name || 'Destinazione';
    els.bannerNext.hidden = true;
    drawRoute(state.track.length);
    Voice.say(m?.voice || 'Sei arrivato a destinazione');
    state.navigating = false;
    const t = state.trip, dur = (Date.now() - t.start) / 1000;
    const avg = t.samples ? t.speedSum / t.samples * 3.6 : 0;
    Store.addTrip({ at: t.start, dest: t.dest, mode: t.mode, distance: t.distance, duration: dur });
    els.tripTitle.textContent = `Sei arrivato: ${t.dest || 'destinazione'}`;
    els.tripTime.textContent = Geo.fmtDuration(dur);
    els.tripDist.textContent = Geo.fmtDistance(t.distance);
    els.tripAvg.textContent = Math.round(avg);
    els.tripMax.textContent = Math.round(t.maxSpeed * 3.6);
    $('trip-park').hidden = t.mode !== 'auto';
    const endCoord = state.dest?.coord;
    $('trip-park').onclick = () => { Store.setParking({ coord: endCoord, at: Date.now() }); renderParking(); renderChips(); toast('Auto parcheggiata: la ritrovi nei chip in alto'); els.dlgTrip.close(); };
    $('trip-close').onclick = () => els.dlgTrip.close();
    els.dlgTrip.addEventListener('close', () => stopNavigation({ arrived: true }), { once: true });
    setTimeout(() => { if (!els.dlgTrip.open) els.dlgTrip.showModal(); }, 1500);
  }

  function leaveFollow() {
    if (!state.follow) return;
    state.follow = false; els.btnRecenter.hidden = false; els.btnLocate.classList.remove('is-following');
  }
  function recenter() {
    state.follow = true; els.btnRecenter.hidden = true; els.btnLocate.classList.add('is-following');
    if (state.simulating) return;
    if (state.position) navTick(state.position, state.heading, false);
  }
  async function requestWakeLock() { try { state.wakeLock = await navigator.wakeLock?.request('screen'); } catch (e) { /* non supportato */ } }
  function releaseWakeLock() { state.wakeLock?.release?.(); state.wakeLock = null; }
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { map?.resize(); if (state.navigating) requestWakeLock(); } });

  async function shareEta() {
    if (!state.navigating || !lastEta) return;
    const text = `Sto andando a ${state.dest?.name || 'destinazione'}: arrivo previsto alle ${Geo.fmtClock(lastEta)} (${els.navDist.textContent} mancanti). — Rotta`;
    try {
      if (navigator.share) await navigator.share({ text });
      else { await navigator.clipboard.writeText(text); toast('Orario di arrivo copiato negli appunti'); }
    } catch (e) { /* annullato */ }
  }

  /* ─────────── impostazioni ─────────── */
  function openSettings() {
    const s = Store.settings();
    els.setTheme.value = state.theme; els.setFuel.value = s.fuelL100; els.setPrice.value = s.fuelPrice; els.setIntro.checked = !!s.intro; els.setCams.checked = !!s.cams;
    els.dlgSettings.returnValue = '';
    els.dlgSettings.showModal();
  }
  els.dlgSettings.addEventListener('close', () => {
    if (els.dlgSettings.returnValue !== 'ok') return;
    Store.saveSettings({ fuelL100: +els.setFuel.value || 6.5, fuelPrice: +els.setPrice.value || 1.85, intro: els.setIntro.checked, cams: els.setCams.checked });
    if (state.safetyOn !== els.setCams.checked) {
      state.safetyOn = els.setCams.checked;
      refreshCameras();
      if (!state.safetyOn) { state.limit = null; state.tutor = null; hideCamAlert(); els.hudAvg.hidden = true; renderLimit(); }
    }
    if (els.setTheme.value !== state.theme) setTheme(els.setTheme.value);
    if (state.route) renderSummary(state.route);
    toast('Impostazioni salvate');
  });
  $('set-clear-home').addEventListener('click', () => { Store.setFavorite('home', null); renderChips(); toast('Casa dimenticata'); });
  $('set-clear-work').addEventListener('click', () => { Store.setFavorite('work', null); renderChips(); toast('Lavoro dimenticato'); });
  $('set-clear-recents').addEventListener('click', () => { Store.clearRecents(); renderChips(); toast('Recenti svuotati'); });

  /* ─────────── pulsanti e tastiera ─────────── */
  els.btnStart.addEventListener('click', () => beginNavigation(false));
  els.btnSim.addEventListener('click', () => beginNavigation(true));
  els.btnStop.addEventListener('click', () => stopNavigation());
  els.btnRecenter.addEventListener('click', recenter);
  els.btnShare.addEventListener('click', shareEta);
  els.btnSettings.addEventListener('click', openSettings);
  els.btnTheme.addEventListener('click', () => setTheme(Themes.next(state.theme)));
  els.btnMute.addEventListener('click', () => {
    const muted = !Voice.isMuted();
    Voice.setMuted(muted);
    els.btnMute.setAttribute('aria-pressed', muted);
    els.btnMute.setAttribute('aria-label', muted ? 'Attiva la voce' : 'Disattiva la voce');
    els.icoSound.hidden = muted; els.icoMuted.hidden = !muted;
  });
  els.btnLocate.addEventListener('click', () => {
    stopIntro();
    if (state.navigating) { recenter(); return; }
    if (!state.position) { toast(gpsHelpMessage(state.gpsStatus === 'off' ? 1 : 3), 7000); return; }
    map.flyTo({ center: state.position, zoom: 16, pitch: state.threeD ? 52 : 0, duration: 1000 });
  });
  els.btn3d.addEventListener('click', () => {
    stopIntro();
    state.threeD = !state.threeD;
    els.btn3d.setAttribute('aria-pressed', state.threeD);
    applyTerrain();
    map.easeTo({ pitch: state.threeD ? (state.navigating ? 60 : 52) : 0, duration: 600 });
  });
  els.btnNorth.addEventListener('click', () => { stopIntro(); if (state.navigating) leaveFollow(); map.easeTo({ bearing: 0, duration: 500 }); });

  document.addEventListener('keydown', e => {
    if (e.target.matches('input, select, textarea') || e.ctrlKey || e.metaKey || e.altKey) return;
    if (document.querySelector('dialog[open]')) return;
    switch (e.key) {
      case '/': e.preventDefault(); els.input.focus(); break;
      case '3': els.btn3d.click(); break;
      case 'n': case 'N': els.btnNorth.click(); break;
      case 't': case 'T': els.btnTheme.click(); break;
      case 's': case 'S': if (state.route && !state.navigating) beginNavigation(true); break;
      case 'Enter': if (state.route && !state.navigating) beginNavigation(false); break;
      case 'Escape': if (state.navigating) stopNavigation(); else if (state.dest) clearRoute(); break;
      case 'm': case 'M': if (state.navigating) els.btnMute.click(); break;
      case ',': openSettings(); break;
      default: return;
    }
  });

  /* ─────────── link diretti: ?to=lat,lon&q=Nome&mode=…&sim=1&from=lat,lon&theme=… ─────────── */
  async function applyDeepLink() {
    const parse = s => { const m = /^(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)$/.exec(s || ''); return m ? [+m[2], +m[1]] : null; };
    if (PARAMS.get('theme') && Themes.THEMES[PARAMS.get('theme')]) setTheme(PARAMS.get('theme'), { announce: false, persist: false });
    const to = parse(PARAMS.get('to'));
    if (!to) return;
    stopIntro();
    const from = parse(PARAMS.get('from'));
    if (from && !state.position) { state.position = from; updatePuck(from, null); }
    if (['auto', 'bicycle', 'pedestrian'].includes(PARAMS.get('mode'))) document.querySelector(`.mode[data-mode="${PARAMS.get('mode')}"]`)?.click();
    const place = PARAMS.get('q') ? { name: PARAMS.get('q'), address: '', coord: to } : await Routing.reverse(to);
    place.coord = to;
    els.input.value = place.name; els.clear.hidden = false;
    setDestination(place);
    if (PARAMS.get('sim') === '1') {
      const wait = setInterval(() => { if (state.route) { clearInterval(wait); beginNavigation(true); } }, 300);
      setTimeout(() => clearInterval(wait), 20000);
    }
  }

  /* ─────────── avvio ─────────── */
  document.body.dataset.theme = state.theme;
  FX.init($('fx'));
  initMap().then(() => {
    startGps();
    renderChips();
    Safety.load().then(() => { if (state.track) refreshCameras(); });
    // fix GPS dal servizio Android (velocità in m/s; -1 = dato assente)
    window.rottaNativeFix = (lat, lon, speed, bearing, accuracy) => {
      state.nativeFeedAt = Date.now();
      onFix([lon, lat], bearing >= 0 ? bearing : null, speed, accuracy);
    };
    window.addEventListener('resize', () => map.resize());
    window.rotta = { map, state, setDestination, beginNavigation, stopNavigation, setTheme, addStop, rlog, onFix };
    // tasto Indietro dell'app Android: true = gestito qui, false = l'app può andare in background
    window.rottaBack = () => {
      const dlg = document.querySelector('dialog[open]');
      if (dlg) { dlg.close(); return true; }
      if (!els.results.hidden) { els.results.hidden = true; return true; }
      if (state.navigating) { stopNavigation(); return true; }
      if (state.dest) { clearRoute(); return true; }
      return false;
    };
    window.addEventListener('error', e => toast(`Errore: ${e.message}`, 12000));
    map.once('idle', applyDeepLink);
  });
  document.body.insertAdjacentHTML('beforeend', `<span class="version" aria-hidden="true">${VERSION}</span>`);
})();
