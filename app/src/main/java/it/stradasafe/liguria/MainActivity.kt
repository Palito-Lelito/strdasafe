package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

// CHIAVE OPENROUTESERVICE DELL'UTENTE
private const val ORS_API_KEY = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImZjZDcxY2MyNmJiNjQwYzU4OTIzZDY0ZGE2MDEyMWJiIiwiaCI6Im11cm11cjY0In0="

// --- SERVIZIO IN BACKGROUND PER GPS A SCHERMO SPENTO ---
class NavigationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = "stradasafe_nav"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Navigazione Attiva", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("StradaSafe 1.1")
            .setContentText("Navigazione in background attiva")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}

private val DarkBackground = Color(0xFF000000)
private val CardSurface = Color(0xE61C1C1E)
private val AppleBlue = Color(0xFF0A84FF)
private val WazeCyan = Color(0xFF64D2FF)
private val WazeGreen = Color(0xFF32D74B)
private val AlertAmber = Color(0xFFFF9F0A)
private val AlertRed = Color(0xFFFF453A)
private val TextWhite = Color(0xFFFFFFFF)
private val TextGray = Color(0xFF8E8E93)

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SEARCH_BASE = "https://nominatim.openstreetmap.org"

object AppState {
    val query = mutableStateOf("")
    val searchResults = mutableStateOf<List<Place>>(emptyList())
    val searchHistory = mutableStateOf<List<Place>>(emptyList())
    val place = mutableStateOf<Place?>(null)
    val route = mutableStateOf<RouteData?>(null)
    val navigating = mutableStateOf(false)
    val followUser = mutableStateOf(true)
    val isSearchExpanded = mutableStateOf(false)
    val stepIndex = mutableIntStateOf(0)
    
    val avoidTolls = mutableStateOf(false)
    val avoidHighways = mutableStateOf(false)
    
    val safetyDevices = mutableStateOf<List<SafetyDevice>>(emptyList())
    val activeSafetyDevices = mutableStateOf<List<SafetyDevice>>(emptyList())
    val alertedDevices = mutableSetOf<SafetyDevice>()
    
    val speedLimit = mutableStateOf<Int?>(null)
    val lastSpeedLimitCheck = mutableLongStateOf(0L)

    val inTutorZone = mutableStateOf(false)
    val tutorStartTime = mutableLongStateOf(0L)
    val tutorStartDistanceRemaining = mutableDoubleStateOf(0.0)
}

class AnimationState {
    var animator: ValueAnimator? = null
    var lat: Double = Double.NaN
    var lon: Double = Double.NaN
    var bearing: Double = 0.0
    
    var lastTargetLat: Double = Double.NaN
    var lastTargetLon: Double = Double.NaN
    var lastTargetBearing: Double = 0.0
}

class MainActivity : ComponentActivity() {
    private lateinit var client: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        client = LocationServices.getFusedLocationProviderClient(this)
        setContent { App(client) }
    }
}

data class Place(val name: String, val lat: Double, val lon: Double)
data class Step(val text: String, val lat: Double, val lon: Double, val distance: Double, val duration: Double, val maneuver: String)
data class RouteData(val points: List<Point>, val distance: Double, val duration: Double, val steps: List<Step>)
data class SafetyDevice(val lat: Double, val lon: Double, val type: String)

@SuppressLint("MissingPermission")
@Composable
private fun rememberGps(client: FusedLocationProviderClient, granted: Boolean): Location? {
    var location by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose {}
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let { newLoc ->
                    if (newLoc.accuracy < 40f) {
                        val currentLoc = location
                        if (currentLoc != null) {
                            val timeDelta = (newLoc.time - currentLoc.time) / 1000f
                            val dist = currentLoc.distanceTo(newLoc)
                            if (timeDelta > 0 && (dist / timeDelta) > 55f) return@let 
                        }
                        location = newLoc 
                    }
                }
            }
        }
        
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0f)
            .build()
            
        client.requestLocationUpdates(req, cb, null)
        onDispose { client.removeLocationUpdates(cb) }
    }
    return location
}

@Composable
private fun rememberSpeaker(): Pair<TextToSpeech?, Boolean> {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.ITALIAN
                engine?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
            }
        }
        tts = engine
        onDispose { engine?.stop(); engine?.shutdown() }
    }
    return tts to ready
}

private fun simulateMovement(lastLoc: Location, timeDeltaMs: Long): Location {
    val distanceMeters = (lastLoc.speed) * (timeDeltaMs / 1000f)
    val r = 6371000.0
    val lat1 = Math.toRadians(lastLoc.latitude)
    val lon1 = Math.toRadians(lastLoc.longitude)
    val brng = Math.toRadians(lastLoc.bearing.toDouble())
    
    val lat2 = asin(sin(lat1) * cos(distanceMeters / r) + cos(lat1) * sin(distanceMeters / r) * cos(brng))
    val lon2 = lon1 + atan2(sin(brng) * sin(distanceMeters / r) * cos(lat1), cos(distanceMeters / r) - sin(lat1) * sin(lat2))
    
    return Location(lastLoc).apply {
        latitude = Math.toDegrees(lat2)
        longitude = Math.toDegrees(lon2)
        provider = "simulated"
    }
}

private fun snapToRoute(lat: Double, lon: Double, routePts: List<Point>): Pair<Double, Double> {
    if (routePts.isEmpty()) return lat to lon
    var minDistance = Double.MAX_VALUE
    var snappedLat = lat
    var snappedLon = lon
    val cosLat = cos(Math.toRadians(lat))

    for (i in 0 until routePts.size - 1) {
        val p1 = routePts[i]
        val p2 = routePts[i+1]
        
        val x0 = lon * cosLat; val y0 = lat
        val x1 = p1.longitude() * cosLat; val y1 = p1.latitude()
        val x2 = p2.longitude() * cosLat; val y2 = p2.latitude()

        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) continue

        val t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy)
        val closestX = when { t < 0 -> x1; t > 1 -> x2; else -> x1 + t * dx }
        val closestY = when { t < 0 -> y1; t > 1 -> y2; else -> y1 + t * dy }

        val projLon = closestX / cosLat
        val projLat = closestY

        val d = distanceMeters(lat, lon, projLat, projLon)
        if (d < minDistance) {
            minDistance = d
            snappedLat = projLat
            snappedLon = projLon
        }
    }
    return if (minDistance < 50.0) snappedLat to snappedLon else lat to lon
}

private fun createArrowBitmap(colorHex: String): android.graphics.Bitmap {
    val size = 96
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val path = android.graphics.Path().apply {
        moveTo(size / 2f, 8f)           
        lineTo(size - 12f, size - 12f)  
        lineTo(size / 2f, size - 28f)   
        lineTo(12f, size - 12f)         
        close()
    }
    canvas.drawPath(path, android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor(colorHex)
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    })
    canvas.drawPath(path, android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    })
    return bitmap
}

@Composable
private fun App(client: FusedLocationProviderClient) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val (tts, ttsReady) = rememberSpeaker()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AppState.safetyDevices.value = loadSafetyDevices(context)
            AppState.searchHistory.value = loadHistory(context)
        }
    }

    var query by AppState.query
    var searchResults by AppState.searchResults
    var searchHistory by AppState.searchHistory
    var place by AppState.place
    var route by AppState.route
    var navigating by AppState.navigating
    var followUser by AppState.followUser
    var isSearchExpanded by AppState.isSearchExpanded
    var stepIndex by AppState.stepIndex
    var speedLimit by AppState.speedLimit
    
    var avoidTolls by AppState.avoidTolls
    var avoidHighways by AppState.avoidHighways

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val rawGps = rememberGps(client, granted)
    
    var effectiveGps by remember { mutableStateOf<Location?>(null) }
    var isSimulating by remember { mutableStateOf(false) }
    
    DisposableEffect(navigating) {
        if (navigating) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    
    LaunchedEffect(rawGps, navigating) {
        if (rawGps != null) { effectiveGps = rawGps; isSimulating = false }
    }
    
    LaunchedEffect(navigating) {
        while(navigating) {
            delay(1000)
            if (effectiveGps != null) {
                val timeSinceLastGps = System.currentTimeMillis() - effectiveGps!!.time
                if (timeSinceLastGps > 12000 && effectiveGps!!.speed > 3f) {
                    isSimulating = true
                    effectiveGps = simulateMovement(effectiveGps!!, 1000)
                    effectiveGps!!.time = System.currentTimeMillis()
                }
            }
        }
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableIntStateOf(-1) }
    var lastReroute by remember { mutableLongStateOf(0L) }
    var avgTutorSpeed by remember { mutableIntStateOf(0) }
    
    var showStopButton by remember { mutableStateOf(true) }

    val currentStep = route?.steps?.getOrNull(stepIndex)
    val distanceToStep = if (effectiveGps != null && currentStep != null) distanceMeters(effectiveGps!!.latitude, effectiveGps!!.longitude, currentStep.lat, currentStep.lon) else Double.NaN

    val speed = ((effectiveGps?.speed ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)
    val remainingDistance = route?.steps?.drop(stepIndex)?.sumOf { it.distance } ?: 0.0
    val remainingDuration = route?.steps?.drop(stepIndex)?.sumOf { it.duration } ?: 0.0

    LaunchedEffect(showStopButton, navigating) {
        if (navigating && showStopButton) { delay(10000); showStopButton = false }
    }

    LaunchedEffect(navigating) {
        try {
            val serviceIntent = Intent(context, NavigationService::class.java)
            if (navigating) ContextCompat.startForegroundService(context, serviceIntent)
            else { serviceIntent.action = "STOP"; context.startService(serviceIntent) }
        } catch (e: Exception) {}
    }

    LaunchedEffect(effectiveGps, navigating, route) {
        if (!navigating || effectiveGps == null || route == null) return@LaunchedEffect
        val currentTime = System.currentTimeMillis()

        if (currentTime - AppState.lastSpeedLimitCheck.longValue > 20000 && !isSimulating) {
            AppState.lastSpeedLimitCheck.longValue = currentTime
            scope.launch {
                val limit = fetchSpeedLimit(effectiveGps!!.latitude, effectiveGps!!.longitude)
                if (limit != null) speedLimit = limit
            }
        }

        val steps = route!!.steps
        if (stepIndex < steps.lastIndex && distanceToStep < 30) stepIndex++

        val s = steps.getOrNull(stepIndex)
        if (s != null && stepIndex != lastSpoken && distanceMeters(effectiveGps!!.latitude, effectiveGps!!.longitude, s.lat, s.lon) < 300) {
            if (!muted && ttsReady) tts?.speak("Tra ${distanceMeters(effectiveGps!!.latitude, effectiveGps!!.longitude, s.lat, s.lon).roundToInt()} metri, ${s.text}", TextToSpeech.QUEUE_ADD, null, "step-$stepIndex")
            lastSpoken = stepIndex
        }

        val unalerted = AppState.activeSafetyDevices.value.filter { it !in AppState.alertedDevices }
        val nearbyDevice = unalerted.firstOrNull { distanceMeters(effectiveGps!!.latitude, effectiveGps!!.longitude, it.lat, it.lon) < 500 }
        
        if (nearbyDevice != null) {
            AppState.alertedDevices.add(nearbyDevice)
            val isTutor = nearbyDevice.type.lowercase().contains("tutor")

            if (isTutor) {
                if (!AppState.inTutorZone.value) {
                    AppState.inTutorZone.value = true
                    AppState.tutorStartTime.longValue = currentTime
                    AppState.tutorStartDistanceRemaining.doubleValue = remainingDistance
                    if (!muted && ttsReady) tts?.speak("Inizio misurazione Tutor", TextToSpeech.QUEUE_ADD, null, "tutor_start")
                } else {
                    AppState.inTutorZone.value = false
                    if (!muted && ttsReady) tts?.speak("Fine zona Tutor", TextToSpeech.QUEUE_ADD, null, "tutor_end")
                }
            } else {
                if (!muted && ttsReady) tts?.speak("Attenzione, ${nearbyDevice.type} a 500 metri", TextToSpeech.QUEUE_ADD, null, "safety_${nearbyDevice.hashCode()}")
            }
        }

        if (AppState.inTutorZone.value) {
            val distTraveledMeters = AppState.tutorStartDistanceRemaining.doubleValue - remainingDistance
            val timeElapsedMillis = currentTime - AppState.tutorStartTime.longValue
            if (timeElapsedMillis > 5000 && distTraveledMeters > 50) {
                val timeHours = timeElapsedMillis / 3600000.0
                val distKm = distTraveledMeters / 1000.0
                avgTutorSpeed = (distKm / timeHours).roundToInt().coerceAtLeast(0)
            }
        }

        val offRoute = distanceToPolyline(effectiveGps!!.latitude, effectiveGps!!.longitude, route!!.points) > 80
        if (offRoute && !isSimulating && effectiveGps!!.accuracy < 30f && currentTime - lastReroute > 15000) {
            lastReroute = currentTime
            scope.launch {
                try {
                    val newRoute = fetchRoute(effectiveGps!!, place!!, avoidTolls, avoidHighways)
                    route = newRoute
                    AppState.activeSafetyDevices.value = AppState.safetyDevices.value.filter { distanceToPolyline(it.lat, it.lon, newRoute.points) < 100.0 }
                    stepIndex = 0; lastSpoken = -1
                    if (!muted && ttsReady) tts?.speak("Ricalcolo in corso", TextToSpeech.QUEUE_FLUSH, null, "reroute")
                } catch (_: Exception) {}
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AppleBlue, background = DarkBackground, surface = CardSurface)) {
        Box(Modifier.fillMaxSize().background(DarkBackground)) {
            
            Box(Modifier
