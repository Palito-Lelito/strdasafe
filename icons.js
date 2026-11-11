/* Rotta — icone delle manovre (SVG inline, tratto 2.6) */
window.Icons = (() => {
  const wrap = (inner, transform = '') =>
    `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">` +
    (transform ? `<g transform="${transform}">${inner}</g>` : inner) + `</svg>`;

  const straight = `<path d="M12 20V5"/><path d="M6.5 10.5 12 5l5.5 5.5"/>`;
  const turnRight = `<path d="M7 20V11a4 4 0 0 1 4-4h8"/><path d="m15 3 4 4-4 4"/>`;
  const uturnRight = `<path d="M7 20V9.5a4.5 4.5 0 0 1 9 0V14"/><path d="m12 11 4 4 4-4"/>`;
  const roundabout = `<circle cx="10.5" cy="14" r="5"/><path d="m14.5 10.5 5.5-5.5"/><path d="M14.5 5H20v5.5"/>`;
  const depart = `<circle cx="12" cy="18.5" r="2.5" fill="currentColor"/><path d="M12 15V5"/><path d="M7.5 9.5 12 5l4.5 4.5"/>`;
  const arrive = `<path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0C18.5 15.4 12 21 12 21z"/><circle cx="12" cy="10" r="2.2" fill="currentColor"/>`;
  const merge = `<path d="M12 20v-7"/><path d="M12 13 7 7"/><path d="m12 13 5-6"/><path d="M14 3h3v3"/>`;
  const ferry = `<path d="M4 17c2 1.5 4 1.5 6 0s4-1.5 6 0 4 1.5 4 0"/><path d="M6 14V9h12v5"/><path d="M10 9V6h4v3"/>`;

  const mirror = 'translate(24 0) scale(-1 1)';
  const rot = a => `rotate(${a} 12 12)`;

  const map = {
    'straight': () => wrap(straight),
    'depart': () => wrap(depart),
    'arrive': () => wrap(arrive),
    'right': () => wrap(turnRight),
    'left': () => wrap(turnRight, mirror),
    'slight-right': () => wrap(straight, rot(35)),
    'slight-left': () => wrap(straight, rot(-35)),
    'sharp-right': () => wrap(turnRight, rot(45)),
    'sharp-left': () => wrap(turnRight, `${mirror} ${rot(45)}`),
    'uturn-right': () => wrap(uturnRight),
    'uturn-left': () => wrap(uturnRight, mirror),
    'roundabout': () => wrap(roundabout),
    'roundabout-left': () => wrap(roundabout, mirror),
    'merge': () => wrap(merge),
    'merge-left': () => wrap(merge, mirror),
    'ramp-right': () => wrap(straight, rot(35)),
    'ramp-left': () => wrap(straight, rot(-35)),
    'exit-right': () => wrap(straight, rot(35)),
    'exit-left': () => wrap(straight, rot(-35)),
    'fork-right': () => wrap(straight, rot(25)),
    'fork-left': () => wrap(straight, rot(-25)),
    'ferry': () => wrap(ferry),
    'waypoint': () => wrap(`<path d="M6 21V4"/><path d="M6 4h11l-2.5 4L17 12H6"/>`),
  };

  return { get: kind => (map[kind] || map.straight)() };
})();
