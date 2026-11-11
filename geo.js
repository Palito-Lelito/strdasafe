/* Rotta — utilità geografiche (coordinate sempre [lon, lat]) */
window.Geo = (() => {
  const R = 6371008.8;
  const rad = d => d * Math.PI / 180;
  const deg = r => r * 180 / Math.PI;

  /** Distanza in metri tra due punti [lon, lat] */
  function distance(a, b) {
    const dLat = rad(b[1] - a[1]);
    const dLon = rad(b[0] - a[0]);
    const s = Math.sin(dLat / 2) ** 2 +
      Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(s));
  }

  /** Direzione (0–360, 0 = nord) da a verso b */
  function bearing(a, b) {
    const φ1 = rad(a[1]), φ2 = rad(b[1]), Δλ = rad(b[0] - a[0]);
    const y = Math.sin(Δλ) * Math.cos(φ2);
    const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
    return (deg(Math.atan2(y, x)) + 360) % 360;
  }

  /** Interpolazione lineare tra due punti */
  function lerp(a, b, t) {
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  }

  /** Differenza angolare minima (-180..180) */
  function angleDelta(from, to) {
    return ((to - from + 540) % 360) - 180;
  }

  /** Decodifica polyline (Google precision 5 / Valhalla precision 6) */
  function decodePolyline(str, precision = 6) {
    const f = Math.pow(10, precision);
    let i = 0, lat = 0, lon = 0;
    const out = [];
    while (i < str.length) {
      let b, shift = 0, result = 0;
      do { b = str.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
      lat += (result & 1) ? ~(result >> 1) : (result >> 1);
      shift = 0; result = 0;
      do { b = str.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
      lon += (result & 1) ? ~(result >> 1) : (result >> 1);
      out.push([lon / f, lat / f]);
    }
    return out;
  }

  /**
   * Linea con proiezione locale in metri per calcoli veloci.
   * Espone: snap(point) → {index, t, point, distance, along}
   *         pointAt(along) → [lon, lat]
   */
  class Track {
    constructor(coords) {
      this.coords = coords;
      const lat0 = coords[0][1], lon0 = coords[0][0];
      this.kx = 111320 * Math.cos(rad(lat0));
      this.ky = 110540;
      this.lon0 = lon0; this.lat0 = lat0;
      this.xy = coords.map(c => this.project(c));
      this.cum = [0];
      for (let i = 1; i < coords.length; i++) {
        const dx = this.xy[i][0] - this.xy[i - 1][0];
        const dy = this.xy[i][1] - this.xy[i - 1][1];
        this.cum.push(this.cum[i - 1] + Math.hypot(dx, dy));
      }
      this.length = this.cum[this.cum.length - 1];
      this.lastIndex = 0;
    }

    project(c) { return [(c[0] - this.lon0) * this.kx, (c[1] - this.lat0) * this.ky]; }

    /** Proietta il punto sul segmento più vicino. Cerca prima vicino all'ultima posizione nota. */
    snap(point, windowSize = 150) {
      const p = this.project(point);
      const tryRange = (from, to) => {
        let best = { index: 0, t: 0, d2: Infinity };
        for (let i = Math.max(0, from); i < Math.min(this.xy.length - 1, to); i++) {
          const a = this.xy[i], b = this.xy[i + 1];
          const vx = b[0] - a[0], vy = b[1] - a[1];
          const len2 = vx * vx + vy * vy || 1e-9;
          let t = ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / len2;
          t = Math.max(0, Math.min(1, t));
          const qx = a[0] + vx * t, qy = a[1] + vy * t;
          const d2 = (p[0] - qx) ** 2 + (p[1] - qy) ** 2;
          if (d2 < best.d2) best = { index: i, t, d2 };
        }
        return best;
      };
      let best = tryRange(this.lastIndex - windowSize, this.lastIndex + windowSize);
      if (Math.sqrt(best.d2) > 60 || !isFinite(best.d2)) {
        const full = tryRange(0, this.xy.length);
        if (full.d2 < best.d2) best = full;
      }
      this.lastIndex = best.index;
      const segLen = this.cum[best.index + 1] - this.cum[best.index];
      const along = this.cum[best.index] + segLen * best.t;
      return {
        index: best.index,
        t: best.t,
        distance: Math.sqrt(best.d2),
        along,
        point: lerp(this.coords[best.index], this.coords[best.index + 1], best.t),
      };
    }

    /** Come snap(), ma su tutta la linea e senza toccare lo stato: per oggetti fissi (autovelox…) */
    locate(point) {
      const p = this.project(point);
      let best = { index: 0, t: 0, d2: Infinity };
      for (let i = 0; i < this.xy.length - 1; i++) {
        const a = this.xy[i], b = this.xy[i + 1];
        const vx = b[0] - a[0], vy = b[1] - a[1];
        const len2 = vx * vx + vy * vy || 1e-9;
        const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / len2));
        const d2 = (p[0] - (a[0] + vx * t)) ** 2 + (p[1] - (a[1] + vy * t)) ** 2;
        if (d2 < best.d2) best = { index: i, t, d2 };
      }
      const segLen = this.cum[best.index + 1] - this.cum[best.index];
      return { distance: Math.sqrt(best.d2), along: this.cum[best.index] + segLen * best.t };
    }

    /** Punto a una certa distanza (m) dall'inizio */
    pointAt(along) {
      const a = Math.max(0, Math.min(this.length, along));
      let lo = 0, hi = this.cum.length - 1;
      while (lo < hi - 1) { const mid = (lo + hi) >> 1; if (this.cum[mid] <= a) lo = mid; else hi = mid; }
      const segLen = this.cum[hi] - this.cum[lo] || 1;
      const t = (a - this.cum[lo]) / segLen;
      return { point: lerp(this.coords[lo], this.coords[hi], t), index: lo, t };
    }

    /** Direzione del segmento che contiene la distanza `along` */
    bearingAt(along) {
      const { index } = this.pointAt(along);
      const i = Math.min(index, this.coords.length - 2);
      // usa un tratto un po' più lungo per una direzione stabile
      const j = Math.min(this.coords.length - 1, i + 1);
      let k = j;
      while (k < this.coords.length - 1 && this.cum[k] - this.cum[i] < 15) k++;
      return bearing(this.coords[i], this.coords[k]);
    }
  }

  const fmtDistance = m => {
    if (!isFinite(m)) return '—';
    if (m < 1000) return `${Math.round(m / (m < 100 ? 5 : 10)) * (m < 100 ? 5 : 10)} m`;
    if (m < 10000) return `${(m / 1000).toFixed(1).replace('.', ',')} km`;
    return `${Math.round(m / 1000)} km`;
  };
  const fmtDuration = s => {
    const min = Math.round(s / 60);
    if (min < 1) return '<1 min';
    if (min < 60) return `${min} min`;
    const h = Math.floor(min / 60), m = min % 60;
    return m ? `${h} h ${m} min` : `${h} h`;
  };
  const fmtClock = date => date.toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' });

  return { distance, bearing, lerp, angleDelta, decodePolyline, Track, fmtDistance, fmtDuration, fmtClock };
})();
