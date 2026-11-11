/* Rotta — sicurezza alla guida (portato da StradaSafe)
   · autovelox e Tutor: elenco locale in data/safety_devices.json, filtrato su quelli che stanno davvero sul percorso
   · limite di velocità della strada: OpenStreetMap interrogato via Overpass
*/
window.Safety = (() => {
  const OVERPASS = 'https://overpass-api.de/api/interpreter';
  let devices = [];
  let loading = null;

  /** Carica l'elenco dei dispositivi (una volta sola). Senza file o senza rete resta vuoto. */
  function load() {
    if (!loading) {
      loading = fetch('data/safety_devices.json')
        .then(r => (r.ok ? r.json() : { devices: [] }))
        .then(j => {
          devices = (j.devices || [])
            .filter(d => isFinite(d.lat) && isFinite(d.lon))
            .map((d, i) => ({
              id: d.id || `dev-${i}`,
              type: d.type || 'Autovelox',
              tutor: /tutor/i.test(d.type || d.id || ''),
              limit: +d.speed_limit > 0 ? +d.speed_limit : null,
              coord: [+d.lon, +d.lat],
            }));
          return devices;
        })
        .catch(e => { console.warn('[safety]', e); devices = []; return devices; });
    }
    return loading;
  }

  /**
   * Dispositivi che stanno sul percorso (entro maxOffset metri dalla linea), in ordine di percorrenza.
   * Così una strada parallela con il suo autovelox non fa scattare avvisi.
   */
  function onRoute(track, maxOffset = 45) {
    if (!devices.length || !track || track.coords.length < 2) return [];
    let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;
    for (const c of track.coords) {
      if (c[0] < minLon) minLon = c[0]; if (c[0] > maxLon) maxLon = c[0];
      if (c[1] < minLat) minLat = c[1]; if (c[1] > maxLat) maxLat = c[1];
    }
    const pad = 0.002; // ≈ 200 m
    const out = [];
    for (const d of devices) {
      const [lon, lat] = d.coord;
      if (lon < minLon - pad || lon > maxLon + pad || lat < minLat - pad || lat > maxLat + pad) continue;
      const hit = track.locate(d.coord);
      if (hit.distance <= maxOffset) out.push({ ...d, along: hit.along });
    }
    return out.sort((a, b) => a.along - b.along);
  }

  /* ─────────── limite di velocità ─────────── */

  const IMPLICIT = { 'IT:urban': 50, 'IT:rural': 90, 'IT:trunk': 110, 'IT:motorway': 130 };
  const RANK = ['motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary', 'tertiary_link', 'unclassified', 'residential', 'living_street', 'service'];

  function parseMaxspeed(v) {
    if (!v) return null;
    if (IMPLICIT[v]) return IMPLICIT[v];
    if (/mph/i.test(v)) return null;
    const n = parseInt(v, 10);
    return n > 0 && n <= 150 ? n : null;
  }

  /**
   * Limite della strada su cui ci si trova, in km/h, oppure null se OpenStreetMap non lo riporta.
   * Se intorno al punto ci sono più strade sceglie quella con il nome della via corrente,
   * altrimenti la più importante (di solito è quella che si sta percorrendo).
   * Lancia un errore se il servizio non risponde.
   */
  async function speedLimit(coord, street = '') {
    const q = `[out:json][timeout:5];way(around:18,${coord[1].toFixed(6)},${coord[0].toFixed(6)})[highway][maxspeed];out tags 8;`;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 7000);
    try {
      const r = await fetch(`${OVERPASS}?data=${encodeURIComponent(q)}`, { signal: ctl.signal });
      if (!r.ok) throw new Error(`Overpass ${r.status}`);
      const ways = ((await r.json()).elements || [])
        .map(e => ({ tags: e.tags || {}, limit: parseMaxspeed(e.tags?.maxspeed) }))
        .filter(w => w.limit);
      if (!ways.length) return null;
      const wanted = street.trim().toLowerCase();
      const named = wanted && ways.find(w => (w.tags.name || '').toLowerCase() === wanted);
      if (named) return named.limit;
      const rank = w => { const i = RANK.indexOf(w.tags.highway); return i === -1 ? RANK.length : i; };
      return ways.sort((a, b) => rank(a) - rank(b))[0].limit;
    } finally {
      clearTimeout(timer);
    }
  }

  return { load, onRoute, speedLimit, parseMaxspeed, count: () => devices.length };
})();
