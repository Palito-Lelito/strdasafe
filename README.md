# StradaSafe 🧭

StradaSafe è un'applicazione di navigazione Android open-source, progettata originariamente per le complesse reti viarie della Liguria. È costruita con un'architettura **"Zero-Account"**, focalizzata al 100% sulla privacy dell'utente e sull'efficienza alla guida.

L'interfaccia utente è disegnata seguendo i principi del **Brutalismo Digitale** e del **Liquid Glassmorphism**: nessun fronzolo, geometrie squadrate, massima leggibilità sotto la luce del sole e zero distrazioni durante la marcia.

## ✨ Funzionalità Principali

*   **Routing Avanzato (ORS):** Motore di calcolo basato su OpenRouteService. Supporta il routing dinamico con esclusione rigorosa di Autostrade e Pedaggi.
*   **Dead Reckoning (Simulazione Galleria):** Algoritmo vettoriale integrato. Quando il segnale GPS viene perso (es. nei lunghi trafori appenninici), l'app calcola e simula l'avanzamento del veicolo basandosi sull'ultima velocità e direzione registrate.
*   **Navigazione in Background (Foreground Service):** Il GPS e le indicazioni vocali TTS (Text-to-Speech) non si interrompono mai, nemmeno spegnendo lo schermo o ricevendo una telefonata.
*   **Calcolo Tutor e Velox:** Monitoraggio in tempo reale della velocità media all'interno delle zone Tutor e avvisi spaziali intelligenti (segnala solo i pericoli fisicamente presenti sulla tua rotta, ignorando le strade parallele).
*   **Interfaccia Adattiva:** Layout a isole fluttuanti (Floating Islands) per massimizzare l'area visibile della mappa. Transizione fluida e riposizionamento dinamico degli elementi passando dalla modalità Portrait a quella Landscape.
*   **Privacy First:** Nessuna registrazione richiesta. Nessun log utente. 

## 🛠 Stack Tecnologico

*   **Linguaggio:** Kotlin
*   **UI Toolkit:** Jetpack Compose (Material 3)
*   **Motore Cartografico:** MapLibre GL Android (Mappe vettoriali leggere e personalizzabili)
*   **Dati Cartografici:** OpenStreetMap (OSM) via OpenFreeMap
*   **Motore di Routing:** OpenRouteService (API REST in POST)
*   **Servizi Posizione:** Google Fused Location Provider

## 🚀 Installazione e Build

1. Clona il repository:
   ```bash
   git clone https://github.com/Palito-Lelito/strdasafe.git
   ```
2. Crea una chiave gratuita su [openrouteservice.org](https://openrouteservice.org/dev/#/signup) e inseriscila in `local.properties` (file da **non** committare):
   ```properties
   ORS_API_KEY=la_tua_chiave
   ```
   In alternativa esporta la variabile d'ambiente `ORS_API_KEY` (è così che la riceve la GitHub Action, tramite il secret omonimo).
3. Compila l'APK di debug (servono JDK 17, Android SDK 36 e Gradle 8.11.1+):
   ```bash
   gradle :app:assembleDebug
   ```
   L'APK viene generato in `app/build/outputs/apk/debug/app-debug.apk`.
