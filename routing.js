/* Rotta — ricerca indirizzi e calcolo percorso
   Motore principale: Valhalla (istruzioni in italiano native, auto/bici/piedi).
   Riserva: OSRM (istruzioni generate qui).
   Modello unificato della rotta:
   { engine, coords: [[lon,lat]...], distance (m), duration (s),
     maneuvers: [{ kind, text, voice, street, length (m), beginIndex }] }
*/
window.Routing = (() => {
  const VALHALLA = 'https://valhalla1.openstreetmap.de/route';
  const OSRM = 'https://router.project-osrm.org/route/v1';
  const PHOTON = 'https://photon.komoot.io';

  /* ─────────── Geocoding (Photon / OpenStreetMap) ─────────── */

  function describe(p) {
    const name = p.name || [p.street, p.housenumber].filter(Boolean).join(' ') || p.city || p.country || 'Luogo';
    const parts = [];
    if (p.street && p.name) parts.push([p.street, p.housenumber].filter(Boolean).join(' '));
    if (p.city && p.city !== name) parts.push(p.city);
    else if (p.county && p.county !== name) parts.push(p.county);
    if (p.state && p.state !== p.city) parts.push(p.state);
    if (p.country && p.country !== name) parts.push(p.country);
    return { name, address: parts.join(', ') };
  }

  function featureToPlace(f) {
    const { name, address } = describe(f.properties || {});
    return { name, address, coord: f.geometry.coordinates.slice(0, 2), kind: f.properties?.osm_value || '' };
  }

  async function search(query, bias) {
    const u = new URL(`${PHOTON}/api/`);
    u.searchParams.set('q', query);
    u.searchParams.set('limit', '7');
    if (bias) { u.searchParams.set('lat', bias[1]); u.searchParams.set('lon', bias[0]); }
    const r = await fetch(u);
    if (!r.ok) throw new Error('Ricerca non disponibile');
    const j = await r.json();
    return (j.features || []).map(featureToPlace);
  }

  async function reverse(coord) {
    try {
      const u = new URL(`${PHOTON}/reverse`);
      u.searchParams.set('lat', coord[1]); u.searchParams.set('lon', coord[0]);
      const r = await fetch(u);
      const j = await r.json();
      if (j.features?.length) return featureToPlace(j.features[0]);
    } catch (e) { /* ignora */ }
    return { name: 'Punto sulla mappa', address: `${coord[1].toFixed(5)}, ${coord[0].toFixed(5)}`, coord };
  }

  /* ─────────── Valhalla ─────────── */

  const VH_KIND = {
    1: 'depart', 2: 'depart', 3: 'depart',
    4: 'arrive', 5: 'arrive', 6: 'arrive',
    7: 'straight', 8: 'straight',
    9: 'slight-right', 10: 'right', 11: 'sharp-right',
    12: 'uturn-right', 13: 'uturn-left',
    14: 'sharp-left', 15: 'left', 16: 'slight-left',
    17: 'straight', 18: 'ramp-right', 19: 'ramp-left',
    20: 'exit-right', 21: 'exit-left',
    22: 'straight', 23: 'fork-right', 24: 'fork-left',
    25: 'merge', 26: 'roundabout', 27: 'straight',
    28: 'ferry', 29: 'straight', 37: 'merge', 38: 'merge-left',
  };

  // qualche frase che Valhalla lascia in inglese
  const IT_PATCH = [
    [/take the stairs/i, 'Prendi le scale'], [/take the escalator/i, 'Prendi la scala mobile'],
    [/take the elevator/i, 'Prendi l\'ascensore'], [/enter the building/i, 'Entra nell\'edificio'],
    [/exit the building/i, 'Esci dall\'edificio'], [/take the ferry/i, 'Prendi il traghetto'],
  ];
  const patchIt = s => IT_PATCH.reduce((t, [re, it]) => t.replace(re, it), s || '');

  function vhManeuver(m, isLastLeg) {
    const text = patchIt(m.instruction);
    const voice = patchIt(m.verbal_pre_transition_instruction || text)
      .replace(/\.$/, '')
      .replace(/\.\s*poi\s+(\S)/gi, (_, c) => `, poi ${c.toLowerCase()}`);
    const out = {
      kind: VH_KIND[m.type] || 'straight',
      text: text.replace(/\.$/, ''),
      voice,
      street: (m.street_names || [])[0] || '',
      length: (m.length || 0) * 1000,
      duration: m.time || 0,
      beginIndex: m.begin_shape_index,
      exit: m.roundabout_exit_count,
    };
    if (out.kind === 'arrive' && !isLastLeg) { out.kind = 'waypoint'; out.text = 'Tappa raggiunta'; out.voice = 'Tappa raggiunta, prosegui'; }
    return out;
  }

  /** Unisce le tappe (legs) di un trip Valhalla in un'unica rotta */
  function vhTrip(trip) {
    const coords = [], maneuvers = [];
    trip.legs.forEach((leg, li) => {
      const shape = Geo.decodePolyline(leg.shape, 6);
      const offset = coords.length;
      coords.push(...shape);
      const last = li === trip.legs.length - 1;
      for (const m of leg.maneuvers) {
        const mm = vhManeuver(m, last);
        mm.beginIndex += offset;
        // la "partenza" delle tappe intermedie non serve
        if (li > 0 && mm.kind === 'depart') continue;
        maneuvers.push(mm);
      }
    });
    return {
      engine: 'valhalla', coords,
      distance: (trip.summary.length || 0) * 1000,
      duration: trip.summary.time || 0,
      maneuvers,
    };
  }

  async function routeValhalla(points, mode, opt) {
    const body = {
      locations: points.map(p => ({ lat: p[1], lon: p[0] })),
      costing: mode,
      units: 'kilometers',
      language: 'it-IT',
      directions_options: { language: 'it-IT', units: 'kilometers' },
      alternates: opt.alternates && points.length === 2 ? 2 : 0,
    };
    if (mode === 'auto' && (opt.avoidTolls || opt.avoidHighways)) {
      body.costing_options = { auto: { use_tolls: opt.avoidTolls ? 0 : 0.5, use_highways: opt.avoidHighways ? 0 : 1 } };
    }
    const r = await fetch(`${VALHALLA}?json=${encodeURIComponent(JSON.stringify(body))}`);
    if (!r.ok) throw new Error(`Valhalla ${r.status}`);
    const j = await r.json();
    if (!j.trip?.legs?.length) throw new Error('Nessun percorso');
    const trips = [j.trip, ...(j.alternates || []).map(a => a.trip).filter(Boolean)];
    return trips.map(vhTrip);
  }

  /** Profilo altimetrico lungo la rotta (Valhalla /height). Ritorna [[distanza m, quota m], ...] */
  async function elevation(coords) {
    const step = Math.max(1, Math.ceil(coords.length / 180));
    const shape = coords.filter((_, i) => i % step === 0 || i === coords.length - 1).map(c => ({ lat: +c[1].toFixed(5), lon: +c[0].toFixed(5) }));
    const r = await fetch(`${VALHALLA.replace(/\/route$/, '')}/height?json=${encodeURIComponent(JSON.stringify({ shape, range: true }))}`);
    if (!r.ok) throw new Error(`height ${r.status}`);
    const j = await r.json();
    return (j.range_height || []).filter(p => p[1] != null);
  }

  /* ─────────── OSRM (riserva) ─────────── */

  const DIR = { left: 'a sinistra', right: 'a destra', 'slight left': 'leggermente a sinistra', 'slight right': 'leggermente a destra', 'sharp left': 'decisamente a sinistra', 'sharp right': 'decisamente a destra', straight: 'dritto', uturn: 'inversione a U' };
  const ORD = ['', 'prima', 'seconda', 'terza', 'quarta', 'quinta', 'sesta', 'settima', 'ottava'];

  function osrmKind(m) {
    const mod = (m.modifier || '').replace(' ', '-');
    switch (m.type) {
      case 'depart': return 'depart';
      case 'arrive': return 'arrive';
      case 'roundabout': case 'rotary': case 'roundabout turn': return 'roundabout';
      case 'merge': return mod.includes('left') ? 'merge-left' : 'merge';
      case 'on ramp': return mod.includes('left') ? 'ramp-left' : 'ramp-right';
      case 'off ramp': return mod.includes('left') ? 'exit-left' : 'exit-right';
      case 'fork': return mod.includes('left') ? 'fork-left' : 'fork-right';
      default:
        if (mod === 'uturn') return 'uturn-left';
        if (['left', 'right', 'slight-left', 'slight-right', 'sharp-left', 'sharp-right'].includes(mod)) return mod;
        return 'straight';
    }
  }

  function osrmText(step) {
    const m = step.maneuver;
    const name = step.name ? ` su ${step.name}` : '';
    const dir = DIR[m.modifier] || '';
    switch (m.type) {
      case 'depart': return `Parti${name}`;
      case 'arrive': return `Sei arrivato a destinazione` + (m.modifier && m.modifier !== 'straight' ? `, ${dir}` : '');
      case 'roundabout': case 'rotary': case 'roundabout turn':
        return `Alla rotonda prendi la ${ORD[m.exit] || `${m.exit}ª`} uscita${name}`;
      case 'exit roundabout': case 'exit rotary': return `Esci dalla rotonda${name}`;
      case 'merge': return `Immettiti ${dir}${name}`;
      case 'on ramp': return `Prendi la rampa ${dir}${name}`;
      case 'off ramp': return `Prendi l'uscita ${dir}${name}`;
      case 'fork': return `Al bivio tieni ${dir.replace('a ', 'la ')}${name}`;
      case 'end of road': return `A fine strada svolta ${dir}${name}`;
      case 'new name': case 'continue': case 'notification': case 'use lane':
        return m.modifier && m.modifier !== 'straight' && m.type === 'continue' ? `Svolta ${dir}${name}` : `Continua${name}`;
      default:
        if (m.modifier === 'uturn') return `Fai inversione a U${name}`;
        if (m.modifier === 'straight') return `Continua dritto${name}`;
        return dir ? `Svolta ${dir}${name}` : `Continua${name}`;
    }
  }

  function osrmRoute(route) {
    const coords = [];
    const maneuvers = [];
    route.legs.forEach((leg, li) => {
      const last = li === route.legs.length - 1;
      for (const step of leg.steps) {
        const g = step.geometry.coordinates;
        const beginIndex = coords.length;
        // evita duplicati fra fine di uno step e inizio del successivo
        const start = coords.length && g.length && coords[coords.length - 1][0] === g[0][0] && coords[coords.length - 1][1] === g[0][1] ? 1 : 0;
        for (let i = start; i < g.length; i++) coords.push(g[i]);
        let kind = osrmKind(step.maneuver), text = osrmText(step);
        if (kind === 'depart' && li > 0) continue;
        if (kind === 'arrive' && !last) { kind = 'waypoint'; text = 'Tappa raggiunta'; }
        maneuvers.push({
          kind, text, voice: text, street: step.name || '',
          length: step.distance, duration: step.duration,
          beginIndex: Math.max(0, beginIndex - (start ? 1 : 0)), exit: step.maneuver.exit,
        });
      }
    });
    return { engine: 'osrm', coords, distance: route.distance, duration: route.duration, maneuvers };
  }

  async function routeOsrm(points, mode, opt) {
    const profile = { auto: 'driving', bicycle: 'bike', pedestrian: 'foot' }[mode] || 'driving';
    const excl = [opt.avoidTolls && 'toll', opt.avoidHighways && 'motorway'].filter(Boolean).join(',');
    const u = `${OSRM}/${profile}/${points.map(p => `${p[0]},${p[1]}`).join(';')}?overview=full&geometries=geojson&steps=true` +
      (opt.alternates && points.length === 2 ? '&alternatives=true' : '') + (excl ? `&exclude=${excl}` : '');
    const r = await fetch(u);
    if (!r.ok) throw new Error(`OSRM ${r.status}`);
    const j = await r.json();
    if (!j.routes?.length) throw new Error('Nessun percorso');
    return j.routes.map(osrmRoute);
  }

  /* ─────────── API ─────────── */

  /**
   * Calcola i percorsi. points = [partenza, ...tappe, destinazione] in [lon, lat].
   * Ritorna un array: il primo è il percorso consigliato, gli altri le alternative.
   */
  async function routes(points, mode = 'auto', opt = {}) {
    let lastErr;
    for (const fn of [routeValhalla, routeOsrm]) {
      try {
        const list = (await fn(points, mode, opt)).filter(r => r.coords.length >= 2);
        if (list.length) return list;
      } catch (e) { lastErr = e; console.warn('[routing]', fn.name, e); }
    }
    throw lastErr || new Error('Percorso non trovato');
  }
  const route = async (from, to, mode, opt) => (await routes([from, to], mode, opt))[0];

  /* ─────────── Meteo (Open-Meteo, gratuito) ─────────── */
  const WX = {
    0: ['Sereno', '☀️'], 1: ['Poco nuvoloso', '🌤️'], 2: ['Nuvoloso', '⛅'], 3: ['Coperto', '☁️'],
    45: ['Nebbia', '🌫️'], 48: ['Nebbia', '🌫️'], 51: ['Pioviggine', '🌦️'], 53: ['Pioviggine', '🌦️'], 55: ['Pioviggine', '🌧️'],
    61: ['Pioggia', '🌧️'], 63: ['Pioggia', '🌧️'], 65: ['Pioggia forte', '🌧️'], 66: ['Pioggia gelata', '🌧️'], 67: ['Pioggia gelata', '🌧️'],
    71: ['Neve', '🌨️'], 73: ['Neve', '🌨️'], 75: ['Neve forte', '❄️'], 77: ['Neve', '🌨️'],
    80: ['Rovesci', '🌦️'], 81: ['Rovesci', '🌧️'], 82: ['Rovesci forti', '⛈️'], 85: ['Neve', '🌨️'], 86: ['Neve', '❄️'],
    95: ['Temporale', '⛈️'], 96: ['Temporale', '⛈️'], 99: ['Temporale', '⛈️'],
  };
  async function weather(coord) {
    const u = `https://api.open-meteo.com/v1/forecast?latitude=${coord[1].toFixed(3)}&longitude=${coord[0].toFixed(3)}&current=temperature_2m,weather_code,wind_speed_10m,is_day&timezone=auto`;
    const r = await fetch(u);
    if (!r.ok) throw new Error('meteo');
    const c = (await r.json()).current;
    const code = c.weather_code;
    const [label, icon] = WX[code] || ['—', '🌡️'];
    const kind = (code >= 71 && code <= 77) || code === 85 || code === 86 ? 'snow'
      : (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95 ? 'rain' : null;
    return { temp: Math.round(c.temperature_2m), label, icon, kind, wind: Math.round(c.wind_speed_10m), isDay: !!c.is_day };
  }

  return { search, reverse, route, routes, elevation, weather };
})();
