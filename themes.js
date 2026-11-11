/* Rotta — temi della mappa (ricolorazione al volo dello stile base "Dark Matter") */
window.Themes = (() => {
  const THEMES = {
    notte: {
      name: 'Notte', ui: 'dark', stars: true,
      p: {
        bg: '#121a2c', land: '#1b2742', park: '#173430', wood: '#142b2a', water: '#0a1f4a', waterway: '#1e4484',
        motorway: '#9fb0d4', motorwayCasing: '#34436a', major: '#7d90ba', majorCasing: '#27334f',
        minor: '#465776', path: '#364463', rail: '#3a4766', boundary: '#45527a',
        text: '#e1e7f2', textDim: '#aab6cf', textRoad: '#c4cfe6', textWater: '#7ea4e6', halo: '#0e1526',
      },
      roadGlow: { color: '#5b78c8', opacity: 0.22 },
      hillshade: { shadow: '#04070f', highlight: '#5a6a90', accent: '#0a1022', exaggeration: 0.6 },
      buildings: ['#222d47', '#2e3c5e', '#3c4d78', '#4f6494'],
      route: { line: '#f5a93b', casing: '#8a5410', glow: '#f5a93b', flow: '#fff1d2', done: '#55617a', doneCasing: '#2a3348', alt: '#6d7a99' },
      sky: { 'sky-color': '#050812', 'horizon-color': '#2b2a4a', 'fog-color': '#121a2c' },
      light: { color: '#ffe3b8', intensity: 0.5 },
    },
    neon: {
      name: 'Neon', ui: 'dark', stars: true,
      p: {
        bg: '#08051a', land: '#0f0a2a', park: '#0c1a2e', wood: '#0b1528', water: '#060c2e', waterway: '#1a2a7a',
        motorway: '#ff4fd8', motorwayCasing: '#5a1a52', major: '#8a5cff', majorCasing: '#2e1a66',
        minor: '#3a2d7a', path: '#2a2460', rail: '#2a2a5a', boundary: '#3a3a7a',
        text: '#f4ecff', textDim: '#b9a8e6', textRoad: '#d8c8ff', textWater: '#6fd3ff', halo: '#08051a',
      },
      roadGlow: { color: '#c23cff', opacity: 0.35 },
      hillshade: { shadow: '#03020c', highlight: '#4a2f8a', accent: '#0a0620', exaggeration: 0.7 },
      buildings: ['#1a1040', '#2a1a66', '#3d2a8f', '#5a3fc0'],
      route: { line: '#39ffb0', casing: '#0f7a55', glow: '#39ffb0', flow: '#eafff5', done: '#4a5a6a', doneCasing: '#22303a', alt: '#6a5aa8' },
      sky: { 'sky-color': '#05020f', 'horizon-color': '#4a1a6a', 'fog-color': '#0f0a2a' },
      light: { color: '#ff9bf0', intensity: 0.6 },
    },
    mercer: {
      name: 'Mercer · noir 1960', ui: 'paper', stars: false,
      p: {
        bg: '#e7dcc2', land: '#dccfae', park: '#d2cfa5', wood: '#cbc49e', water: '#b7c2ba', waterway: '#9aa9a4',
        motorway: '#6b5a3c', motorwayCasing: '#4a3a22', major: '#8f7c5a', majorCasing: '#5a4a30',
        minor: '#b3a384', path: '#a99a7c', rail: '#7a6a50', boundary: '#8a7a5a',
        text: '#2e2416', textDim: '#5a4a34', textRoad: '#3b2f1e', textWater: '#4a5a58', halo: '#efe6d0',
      },
      roadGlow: { color: '#000000', opacity: 0 },
      hillshade: { shadow: '#6e5f46', highlight: '#f6efdc', accent: '#9a8a6a', exaggeration: 0.5 },
      buildings: ['#cdbf9f', '#bfae8c', '#ad9b78', '#998766'],
      route: { line: '#b3261e', casing: '#6a1410', glow: '#b3261e', flow: '#ffd9d4', done: '#9a8c74', doneCasing: '#6a5f4e', alt: '#8a7a62' },
      sky: { 'sky-color': '#cfc2a3', 'horizon-color': '#efe3c6', 'fog-color': '#e7dcc2' },
      light: { color: '#fff3dd', intensity: 0.35 },
    },
  };
  const ORDER = ['notte', 'neon', 'mercer'];

  const z = (a, b, c, d) => ['interpolate', ['exponential', 1.4], ['zoom'], a, b, c, d];

  /** Ricolora gli strati dello stile base secondo la palette */
  function restyle(map, P) {
    for (const l of map.getStyle().layers) {
      const id = l.id, sl = l['source-layer'] || '', t = l.type;
      if (id.startsWith('route') || id.startsWith('rotta')) continue;
      const set = (prop, v) => { try { map.setPaintProperty(id, prop, v); } catch (e) { /* proprietà non applicabile */ } };
      if (t === 'background') { set('background-color', P.bg); continue; }
      if (t === 'fill') {
        if (sl === 'water' || /water/.test(id)) set('fill-color', P.water);
        else if (/wood|forest/.test(id)) { set('fill-pattern', null); set('fill-color', P.wood); set('fill-opacity', 0.55); }
        else if (/park|grass|green|cemetery/.test(id)) { set('fill-color', P.park); set('fill-opacity', 0.6); }
        else if (/ice|glacier/.test(id)) set('fill-color', P.land);
        else if (/aeroway|pier|runway/.test(id)) set('fill-color', P.minor);
        else if (/residential/.test(id)) { set('fill-color', P.land); set('fill-opacity', 0.55); }
        else if (/landuse|landcover|sand|beach/.test(id)) set('fill-color', P.land);
        continue;
      }
      if (t === 'line') {
        if (sl === 'aeroway' || /aeroway|runway|taxiway/.test(id)) { set('line-color', /casing/.test(id) ? P.majorCasing : P.minor); continue; }
        if (sl === 'waterway' || /waterway|river|stream/.test(id)) { set('line-color', P.waterway); continue; }
        if (sl === 'boundary' || /boundary|admin/.test(id)) { set('line-color', P.boundary); set('line-opacity', 0.6); continue; }
        if (sl === 'transportation' || /road|highway|tunnel|bridge|rail|street|motorway/.test(id)) {
          if (/rail|transit|ferry/.test(id)) { set('line-color', P.rail); continue; }
          const motorway = /motorway|trunk/.test(id), major = /major|primary|secondary|tertiary/.test(id);
          if (/casing|outline/.test(id)) {
            set('line-color', motorway ? P.motorwayCasing : P.majorCasing);
            set('line-width', motorway ? z(8, 1.6, 20, 24) : z(10, 1.6, 20, 16));
          } else if (/subtle/.test(id)) {
            set('line-color', motorway ? P.motorway : P.major);
          } else if (motorway) {
            set('line-color', P.motorway); set('line-width', z(8, 1, 20, 18));
          } else if (major) {
            set('line-color', P.major); set('line-width', z(10, 1, 20, 12));
          } else if (/path|track|pedestrian|footway|steps|cycle/.test(id)) {
            set('line-color', P.path); set('line-opacity', 0.7); set('line-width', z(14, 0.5, 20, 2.5));
          } else {
            set('line-color', P.minor); set('line-opacity', 1); set('line-width', z(13, 1, 20, 9));
          }
        }
        continue;
      }
      if (t === 'symbol') {
        let hasText = false;
        try { hasText = !!map.getLayoutProperty(id, 'text-field'); } catch (e) { /* ignora */ }
        if (!hasText) continue;
        if (/water|ocean|sea|lake/.test(id)) set('text-color', P.textWater);
        else if (/highway|road|street/.test(id)) set('text-color', P.textRoad);
        else if (/place|city|town|village|country|state|capital|continent/.test(id)) set('text-color', P.text);
        else set('text-color', P.textDim);
        set('text-halo-color', P.halo);
        set('text-halo-width', 1.4);
        set('text-halo-blur', 0.4);
      }
    }
  }

  /** Applica un tema completo: stile base, rilievo, edifici, percorso, cielo, interfaccia */
  function apply(map, key) {
    const T = THEMES[key] || THEMES.notte;
    restyle(map, T.p);
    const set = (id, prop, v) => { if (map.getLayer(id)) { try { map.setPaintProperty(id, prop, v); } catch (e) { /* ignora */ } } };
    set('rotta-hillshade', 'hillshade-shadow-color', T.hillshade.shadow);
    set('rotta-hillshade', 'hillshade-highlight-color', T.hillshade.highlight);
    set('rotta-hillshade', 'hillshade-accent-color', T.hillshade.accent);
    set('rotta-hillshade', 'hillshade-exaggeration', T.hillshade.exaggeration);
    set('rotta-road-glow', 'line-color', T.roadGlow.color);
    set('rotta-road-glow', 'line-opacity', T.roadGlow.opacity);
    set('rotta-3d-buildings', 'fill-extrusion-color', ['interpolate', ['linear'], ['coalesce', ['get', 'render_height'], ['get', 'height'], 6],
      0, T.buildings[0], 30, T.buildings[1], 90, T.buildings[2], 200, T.buildings[3]]);
    set('route-glow', 'line-color', T.route.glow);
    set('route-casing', 'line-color', ['match', ['get', 'part'], 'done', T.route.doneCasing, T.route.casing]);
    set('route-line', 'line-color', ['match', ['get', 'part'], 'done', T.route.done, T.route.line]);
    set('route-flow', 'line-color', T.route.flow);
    set('route-alt', 'line-color', T.route.alt);
    try { map.setLight({ anchor: 'viewport', color: T.light.color, intensity: T.light.intensity, position: [1.3, 90, 40] }); } catch (e) { /* ignora */ }
    try {
      map.setSky?.({ ...T.sky, 'fog-ground-blend': 0.6, 'horizon-fog-blend': 0.75, 'sky-horizon-blend': 0.7, 'atmosphere-blend': 0.85 });
    } catch (e) { /* ignora */ }
    document.body.dataset.theme = key;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', T.p.bg);
    return T;
  }

  function next(key) { return ORDER[(ORDER.indexOf(key) + 1) % ORDER.length]; }

  return { THEMES, ORDER, apply, next, get: k => THEMES[k] || THEMES.notte };
})();
