# StradaSafe Liguria 0.2 alpha

Progetto Android privato e installabile via APK. La versione 0.2 collega realmente il GPS del telefono alla velocità mostrata, gestisce il permesso di posizione e include una UI automotive adattiva.

## Funziona ora

- richiesta del permesso GPS;
- aggiornamenti GPS ad alta precisione ogni secondo;
- velocità reale del dispositivo in km/h;
- stato e precisione del fix;
- ricerca e navigazione dimostrative;
- tema giorno/notte;
- nessun account, pubblicità o telemetria applicativa.

## Ancora da integrare prima dell'uso stradale

- mappa vettoriale offline della Liguria;
- geocoding e routing offline;
- istruzioni reali e ricalcolo;
- database ufficiale verificato di autovelox/Tutor;
- limiti stradali collegati al tratto percorso;
- firma release stabile.

## Compilazione

1. Apri la cartella con Android Studio e JDK 17.
2. Attendi Gradle Sync. La prima sincronizzazione richiede Internet.
3. Collega il telefono con debug USB oppure crea l'APK con `Build > Build APK(s)`.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Avvertenza

Questa alpha usa percorso, limite e avviso dimostrativi. Non usarla come unico ausilio durante la guida. Osserva sempre la segnaletica reale.
