/* Rotta — guida vocale in italiano (Web Speech API) */
window.Voice = (() => {
  const synth = window.speechSynthesis;
  let muted = false;
  let voice = null;

  function pickVoice() {
    if (!synth) return;
    const voices = synth.getVoices();
    // preferisci una voce italiana "naturale" (Google / Microsoft), altrimenti qualunque it-*
    voice = voices.find(v => /^it/i.test(v.lang) && /google|microsoft|elsa|alice|luca|cosimo|paola/i.test(v.name))
      || voices.find(v => /^it/i.test(v.lang)) || null;
  }
  if (synth) {
    pickVoice();
    synth.addEventListener?.('voiceschanged', pickVoice);
  }

  // dentro l'app Android la voce è il TextToSpeech di sistema (la WebView non ha speechSynthesis)
  const android = () => { try { return window.AndroidTTS && window.AndroidTTS.available() ? window.AndroidTTS : null; } catch (e) { return null; } };

  function say(text, { interrupt = true } = {}) {
    if (muted || !text) return;
    const a = android();
    if (a) { a.speak(text, !!interrupt); return; }
    if (!synth) return;
    if (interrupt) synth.cancel();
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'it-IT';
    if (voice) u.voice = voice;
    u.rate = 1.02;
    u.pitch = 1;
    synth.speak(u);
  }

  function setMuted(v) { muted = v; if (v) stop(); }
  function isMuted() { return muted; }
  function stop() { const a = android(); if (a) a.stop(); synth && synth.cancel(); }
  function available() { return !!(synth || android()); }

  return { say, setMuted, isMuted, stop, available };
})();
