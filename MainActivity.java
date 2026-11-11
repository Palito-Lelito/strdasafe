package it.lucarisso.rotta;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.Locale;

/**
 * Rotta — contenitore nativo della web app.
 * Serve i file da assets/www con origine https://appassets.androidplatform.net
 * (così posizione, localStorage e WebGL si comportano come su un sito vero),
 * inoltra la richiesta di posizione ai permessi Android, legge le istruzioni
 * con il TextToSpeech di sistema e tiene lo schermo acceso.
 * Durante la navigazione avvia NavigationService (servizio in primo piano):
 * il GPS continua ad arrivare alla web app e la voce a parlare anche a schermo
 * spento o con un'altra app davanti.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_LOCATION = 41;
    private static final int REQ_NOTIFICATIONS = 42;

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    /** true mentre la web app sta navigando: la WebView non va messa in pausa in background */
    private boolean navActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF121A2C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        WebView.setWebContentsDebuggingEnabled(true);
        // in background la pagina deve continuare a ricevere i fix e a parlare
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoCallback = callback;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }
        });

        web.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
        web.addJavascriptInterface(new NavBridge(), "AndroidNav");

        // posizioni dal servizio in primo piano → web app (window.rottaNativeFix)
        NavigationService.setListener(l -> {
            if (web == null || !navActive) return;
            web.evaluateJavascript(String.format(Locale.US,
                    "window.rottaNativeFix&&window.rottaNativeFix(%.7f,%.7f,%.2f,%.1f,%.1f)",
                    l.getLatitude(), l.getLongitude(),
                    l.hasSpeed() ? l.getSpeed() : -1f,
                    l.hasBearing() ? l.getBearing() : -1f,
                    l.hasAccuracy() ? l.getAccuracy() : 0f), null);
        });

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.ITALIAN);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });

        // chiedi subito la posizione, così il navigatore parte già centrato
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }

        web.loadUrl(START_URL);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        boolean granted = hasLocationPermission();
        if (pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    @Override
    public void onBackPressed() {
        // lascia decidere alla web app (chiude navigazione / scheda); se non ha nulla da chiudere, vai in background
        web.evaluateJavascript("(window.rottaBack && window.rottaBack()) ? 'true' : 'false'", value -> {
            if (!"\"true\"".equals(value)) moveTaskToBack(true);
        });
    }

    private void startNavService(String destination) {
        navActive = true;
        // senza permesso di posizione il servizio non può partire: resta il GPS della WebView
        if (!hasLocationPermission()) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        try {
            Intent i = new Intent(this, NavigationService.class);
            i.putExtra(NavigationService.EXTRA_DESTINATION, destination == null ? "" : destination);
            startForegroundService(i);
        } catch (Exception e) {
            // il sistema può rifiutare l'avvio (es. app non in primo piano): la navigazione prosegue senza servizio
        }
    }

    private void stopNavService() {
        navActive = false;
        try { stopService(new Intent(this, NavigationService.class)); } catch (Exception e) { /* già fermo */ }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // in navigazione la pagina resta viva: riceve i fix dal servizio e continua a dare le indicazioni
        if (!navActive) web.onPause();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onDestroy() {
        NavigationService.setListener(null);
        stopNavService();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        web.destroy();
        web = null;
        super.onDestroy();
    }

    /** Esposto al JavaScript come window.AndroidNav: la web app segnala inizio e fine della navigazione */
    private class NavBridge {
        @JavascriptInterface
        public void start(final String destination) { runOnUiThread(() -> startNavService(destination)); }

        @JavascriptInterface
        public void stop() { runOnUiThread(() -> stopNavService()); }
    }

    /** Esposto al JavaScript come window.AndroidTTS */
    private class TtsBridge {
        @JavascriptInterface
        public boolean available() { return ttsReady; }

        @JavascriptInterface
        public void speak(String text, boolean interrupt) {
            if (!ttsReady || text == null) return;
            tts.speak(text, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "rotta");
        }

        @JavascriptInterface
        public void stop() { if (ttsReady) tts.stop(); }
    }
}
