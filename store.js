/* Rotta — memoria locale: preferiti, recenti, impostazioni, auto parcheggiata */
window.Store = (() => {
  const get = (k, d) => { try { const v = localStorage.getItem('rotta.' + k); return v ? JSON.parse(v) : d; } catch (e) { return d; } };
  const set = (k, v) => { try { localStorage.setItem('rotta.' + k, JSON.stringify(v)); } catch (e) { /* spazio o privacy */ } };

  const DEFAULTS = { theme: 'notte', fuelL100: 6.5, fuelPrice: 1.85, voice: true, intro: true, avoidTolls: false, avoidHighways: false, cams: true };
  const settings = () => ({ ...DEFAULTS, ...get('settings', {}) });
  const saveSettings = patch => set('settings', { ...settings(), ...patch });

  const favorites = () => get('favorites', {});                 // { home: place, work: place }
  const setFavorite = (key, place) => { const f = favorites(); if (place) f[key] = place; else delete f[key]; set('favorites', f); };

  const recents = () => get('recents', []);
  const addRecent = place => {
    const list = recents().filter(p => !(Math.abs(p.coord[0] - place.coord[0]) < 1e-4 && Math.abs(p.coord[1] - place.coord[1]) < 1e-4));
    list.unshift({ name: place.name, address: place.address || '', coord: place.coord });
    set('recents', list.slice(0, 6));
  };
  const clearRecents = () => set('recents', []);

  const parking = () => get('parking', null);                   // { coord, at }
  const setParking = p => set('parking', p);

  const trips = () => get('trips', []);
  const addTrip = t => set('trips', [t, ...trips()].slice(0, 20));

  return { settings, saveSettings, favorites, setFavorite, recents, addRecent, clearRecents, parking, setParking, trips, addTrip };
})();
