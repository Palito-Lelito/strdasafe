# Compilare senza Android Studio

Non devi installare Android Studio. Il progetto include un flusso GitHub Actions che genera l'APK online.

## Procedura

1. Crea un account gratuito su GitHub, se non ne hai uno.
2. Crea un nuovo repository privato, per esempio `stradasafe-liguria`.
3. Carica nella radice del repository tutto il contenuto di questa cartella, compresa la cartella nascosta `.github`.
4. Apri la scheda **Actions** del repository.
5. Seleziona **Compila APK StradaSafe**.
6. Premi **Run workflow**, poi ancora **Run workflow**.
7. Attendi la conclusione del processo.
8. Apri l'esecuzione completata e, nella sezione **Artifacts**, scarica `StradaSafe-Liguria-APK`.
9. Estrai lo ZIP ottenuto e trasferisci `app-debug.apk` sul telefono.
10. Apri l'APK sul telefono e autorizza l'installazione dalla sorgente usata.

La compilazione avviene sui server GitHub. Sul computer servono solo un browser e una connessione Internet.
