# Rotta 🧭

Navigatore Android per la Liguria (e non solo): l'interfaccia e le mappe di **Rotta**, con le funzioni di sicurezza alla guida di **StradaSafe**. Nessun account, nessuna chiave API, nessun log.

## Cosa fa

Da Rotta:

- Mappa 3D con rilievo ed edifici (MapLibre GL + OpenFreeMap), tre temi: Notte, Neon, Mercer · noir 1960
- Ricerca indirizzi (Photon), percorsi auto / bici / a piedi con **tappe** e **alternative** (Valhalla, riserva OSRM)
- Evita pedaggi / autostrade, profilo altimetrico, costo carburante stimato
- Navigazione con voce italiana, ricalcolo automatico, condivisione dell'orario di arrivo
- Casa, Lavoro, destinazioni recenti, riepilogo viaggio, "Ho parcheggiato qui"

Da StradaSafe:

- **Autovelox e Tutor**: avviso vocale e a schermo 500 m prima, solo per i dispositivi che stanno davvero sul percorso (una strada parallela non fa scattare nulla); sono segnati anche sulla mappa e nel riepilogo prima di partire
- **Velocità media nel tratto Tutor**, calcolata fra un portale e il successivo
- **Limite di velocità** della strada (OpenStreetMap via Overpass) accanto al tachimetro, che diventa rosso se lo superi
- **Galleria**: se il GPS sparisce per più di 6 secondi mentre sei in movimento, la posizione avanza lungo il percorso all'ultima velocità nota
- **Navigazione a schermo spento**: un servizio in primo piano tiene attivi GPS e voce anche con un'altra app davanti

Gli avvisi si spengono da Impostazioni → "Avvisi autovelox e Tutor, limite di velocità".

> L'elenco dei dispositivi (`app/src/main/assets/www/data/safety_devices.json`) è un file dimostrativo per la Liguria: non è completo né garantito aggiornato. Rispetta sempre la segnaletica.

## Compilare l'APK

**Con GitHub**: a ogni push su `main` la Action "Compila APK StradaSafe" (`.github/workflows/main.yml`, la stessa di prima) costruisce l'APK di debug; lo trovi negli *Artifacts* della run, con il nome `StradaSafe-Liguria-APK`.

**Sul PC** (JDK 17, Android SDK 36, Gradle 8.11.1 o successivo):

```bash
gradle :app:assembleDebug
```

L'APK esce in `app/build/outputs/apk/debug/app-debug.apk`. In alternativa apri la cartella con Android Studio e premi Run.

## Provarla nel browser

La web app sta in `app/src/main/assets/www` e gira anche da sola:

```bash
cd app/src/main/assets/www
python -m http.server 8765
```

poi apri `http://localhost:8765`. Con **Simula** percorri la rotta senza GPS: è il modo più rapido per vedere avvisi autovelox, Tutor e limite.

## Struttura

```
app/src/main/java/it/lucarisso/rotta/
  MainActivity.java        WebView + voce di sistema + ponte con la web app
  NavigationService.java   servizio in primo piano: notifica e GPS durante la navigazione
app/src/main/assets/www/
  index.html, css/         interfaccia
  js/app.js                mappa, navigazione, avvisi, stima in galleria
  js/safety.js             autovelox/Tutor sul percorso, limite di velocità
  js/routing.js            ricerca, percorsi, meteo
  js/geo.js                distanze e proiezione sulla rotta
  js/voice.js, store.js, themes.js, fx.js, icons.js
  data/safety_devices.json elenco autovelox e Tutor
```

Servizi usati (istanze pubbliche gratuite, adatte all'uso personale): OpenFreeMap, Photon, Valhalla, OSRM, Overpass, Open-Meteo.
