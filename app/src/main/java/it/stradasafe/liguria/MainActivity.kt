package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var client: FusedLocationProviderClient
    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L).setMinUpdateIntervalMillis(800L).setMinUpdateDistanceMeters(2f).build()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { l ->
                val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
                val bearing = if (l.hasBearing()) l.bearing else 0f
                web.evaluateJavascript("window.nativeLocation&&window.nativeLocation(${l.latitude},${l.longitude},$speed,$bearing,${l.accuracy});", null)
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { ok -> ok }) startGps() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = LocationServices.getFusedLocationProviderClient(this)
        web = WebView(this); setContentView(web)
        web.settings.apply { javaScriptEnabled=true; domStorageEnabled=true; databaseEnabled=true; geolocationEnabled=true; allowFileAccess=true; allowContentAccess=true; mixedContentMode=0 }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() { override fun onGeolocationPermissionsShowPrompt(origin:String?, callback:GeolocationPermissions.Callback?) { callback?.invoke(origin,true,false) } }
        web.loadUrl("file:///android_asset/index.html")
        web.postDelayed({ ensureLocation() }, 900)
    }
    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startGps()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    @SuppressLint("MissingPermission") private fun startGps() { client.requestLocationUpdates(request, callback, mainLooper) }
    override fun onPause() { super.onPause(); client.removeLocationUpdates(callback) }
    override fun onResume() { super.onResume(); if (::client.isInitialized && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startGps() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }
}
