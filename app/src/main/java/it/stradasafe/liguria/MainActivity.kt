package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
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
            .setContentTitle("StradaSafe 1.0")
            .setContentText("Navigazione in background attiva")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}

// Palette Squadrata e Solida
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
private const val ROUTE_BASE = "https://router.project-osrm.org"

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
    
    // Filtri percorso
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
                r.lastLocation?.let { location = it }
            }
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(1000)
            .setMinUpdateDistanceMeters(1f)
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
                ready = true
            }
        }
        tts = engine
        onDispose { engine?.stop(); engine?.shutdown() }
    }
    return tts to ready
}

// ALGORITMO DEAD RECKONING (Simulazione Galleria)
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

@Composable
private fun App(client: FusedLocationProviderClient) {
    val context = LocalContext.current
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

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val rawGps = rememberGps(client, granted)
    
    // Logica Gestione Galleria (Dead Reckoning)
    var effectiveGps by remember { mutableStateOf<Location?>(null) }
    var isSimulating by remember { mutableStateOf(false) }
    
    LaunchedEffect(rawGps, navigating) {
        if (rawGps != null) {
            effectiveGps = rawGps
            isSimulating = false
        }
    }
    
    LaunchedEffect(navigating) {
        while(navigating) {
            delay(1000)
            if (effectiveGps != null) {
                val timeSinceLastGps = System.currentTimeMillis() - effectiveGps!!.time
                // Se non riceviamo segnale per più di 3 secondi in movimento, simuliamo.
                if (timeSinceLastGps > 3000 && effectiveGps!!.speed > 2f) {
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
        val serviceIntent = Intent(context, NavigationService::class.java)
        if (navigating) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            serviceIntent.action = "STOP"
            context.startService(serviceIntent)
        }
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

        val offRoute = distanceToPolyline(effectiveGps!!.latitude, effectiveGps!!.longitude, route!!.points) > 50
        // Ricalcola solo se non stiamo simulando (in galleria)
        if (offRoute && !isSimulating && effectiveGps!!.accuracy < 40f && currentTime - lastReroute > 10000) {
            lastReroute = currentTime
            scope.launch {
                try {
                    val newRoute = fetchRoute(effectiveGps!!, place!!, avoidTolls, avoidHighways)
                    route = newRoute
                    AppState.activeSafetyDevices.value = AppState.safetyDevices.value.filter {
                        distanceToPolyline(it.lat, it.lon, newRoute.points) < 100.0
                    }
                    stepIndex = 0; lastSpoken = -1
                    if (!muted && ttsReady) tts?.speak("Ricalcolo in corso", TextToSpeech.QUEUE_FLUSH, null, "reroute")
                } catch (_: Exception) {}
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AppleBlue, background = DarkBackground, surface = CardSurface)) {
        Box(Modifier.fillMaxSize().background(DarkBackground)) {
            
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed } && navigating) followUser = false
                    }
                }
            }) {
                NavMap(effectiveGps, place, route, navigating, followUser, speed, isSimulating)
            }
            
            // Banner Simulazione GPS
            if (navigating && isSimulating) {
                Surface(color = AlertAmber, shape = RectangleShape, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 24.dp)) {
                    Text("SEGNALE GPS PERSO - SIMULAZIONE IN CORSO", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
                }
            }

            Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                if (isLandscape) {
                    Column(modifier = Modifier.fillMaxHeight().widthIn(max = 340.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            if (!navigating) { TopStatusBar(effectiveGps); Spacer(Modifier.height(10.dp)) }
                            if (navigating && route != null) ManeuverCard(currentStep, distanceToStep, muted, onMuteToggle = { muted = !muted })
                        }
                        
                        Column(horizontalAlignment = Alignment.End) {
                            if (navigating && !followUser) {
                                Button(onClick = { followUser = true }, shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue, contentColor = TextWhite), modifier = Modifier.padding(bottom = 16.dp).shadow(8.dp)) {
                                    Text("📍 RICENTRA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            if (navigating && route != null) {
                                EtaCard(speed, speedLimit, AppState.inTutorZone.value, avgTutorSpeed, remainingDistance, remainingDuration, showStopButton, { showStopButton = true }, { resetNavigation(); showStopButton = true })
                            } else if (route != null) {
                                OverviewCard(route!!, place, onStart = {
                                    navigating = true; isSearchExpanded = false; followUser = true; showStopButton = true
                                    AppState.activeSafetyDevices.value = AppState.safetyDevices.value.filter { distanceToPolyline(it.lat, it.lon, route!!.points) < 100.0 }
                                }, onReset = { resetNavigation() })
                            } else if (isSearchExpanded) {
                                SearchExpandedCard(query, { query = it }, granted, { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, busy, error, searchResults, searchHistory, avoidTolls, { avoidTolls = it }, avoidHighways, { avoidHighways = it },
                                    onSearch = { scope.launch { busy = true; error = null; try { searchResults = searchPlaces(query) } catch (e: Exception) { error = e.message } finally { busy = false } } },
                                    onPlaceSelected = { p -> scope.launch { 
                                        busy = true; error = null; searchResults = emptyList(); place = p
                                        val newHistory = (listOf(p) + searchHistory).distinctBy { it.name }.take(5)
                                        searchHistory = newHistory; saveHistory(context, newHistory)
                                        try { route = fetchRoute(effectiveGps ?: error("Attendi GPS"), p, avoidTolls, avoidHighways) } catch (e: Exception) { error = e.message } finally { busy = false; isSearchExpanded = false } 
                                    } },
                                    onClose = { isSearchExpanded = false }
                                )
                            } else {
                                HomeBottomBar(onClick = { isSearchExpanded = true })
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            if (!navigating) { TopStatusBar(effectiveGps); Spacer(Modifier.height(10.dp)) }
                            if (navigating && route != null) ManeuverCard(currentStep, distanceToStep, muted, onMuteToggle = { muted = !muted })
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (navigating && !followUser) {
                                Button(onClick = { followUser = true }, shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue, contentColor = TextWhite), modifier = Modifier.padding(bottom = 16.dp).shadow(8.dp)) {
                                    Text("📍 RICENTRA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            if (navigating && route != null) {
                                EtaCard(speed, speedLimit, AppState.inTutorZone.value, avgTutorSpeed, remainingDistance, remainingDuration, showStopButton, { showStopButton = true }, { resetNavigation(); showStopButton = true })
                            } else if (route != null) {
                                OverviewCard(route!!, place, onStart = {
                                    navigating = true; isSearchExpanded = false; followUser = true; showStopButton = true
                                    AppState.activeSafetyDevices.value = AppState.safetyDevices.value.filter { distanceToPolyline(it.lat, it.lon, route!!.points) < 100.0 }
                                }, onReset = { resetNavigation() })
                            } else if (isSearchExpanded) {
                                SearchExpandedCard(query, { query = it }, granted, { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, busy, error, searchResults, searchHistory, avoidTolls, { avoidTolls = it }, avoidHighways, { avoidHighways = it },
                                    onSearch = { scope.launch { busy = true; error = null; try { searchResults = searchPlaces(query) } catch (e: Exception) { error = e.message } finally { busy = false } } },
                                    onPlaceSelected = { p -> scope.launch { 
                                        busy = true; error = null; searchResults = emptyList(); place = p
                                        val newHistory = (listOf(p) + searchHistory).distinctBy { it.name }.take(5)
                                        searchHistory = newHistory; saveHistory(context, newHistory)
                                        try { route = fetchRoute(effectiveGps ?: error("Attendi GPS"), p, avoidTolls, avoidHighways) } catch (e: Exception) { error = e.message } finally { busy = false; isSearchExpanded = false } 
                                    } },
                                    onClose = { isSearchExpanded = false }
                                )
                            } else {
                                HomeBottomBar(onClick = { isSearchExpanded = true })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resetNavigation() {
    AppState.navigating.value = false
    AppState.stepIndex.intValue = 0
    AppState.alertedDevices.clear()
    AppState.activeSafetyDevices.value = emptyList()
    AppState.speedLimit.value = null
    AppState.inTutorZone.value = false
    AppState.followUser.value = true
    AppState.route.value = null 
    AppState.place.value = null 
}

private fun saveHistory(context: Context, history: List<Place>) {
    val prefs = context.getSharedPreferences("StradaSafePrefs", Context.MODE_PRIVATE)
    val jsonArray = JSONArray()
    history.forEach { 
        val obj = JSONObject().apply { put("name", it.name); put("lat", it.lat); put("lon", it.lon) }
        jsonArray.put(obj)
    }
    prefs.edit().putString("search_history", jsonArray.toString()).apply()
}

private fun loadHistory(context: Context): List<Place> {
    val prefs = context.getSharedPreferences("StradaSafePrefs", Context.MODE_PRIVATE)
    val historyStr = prefs.getString("search_history", "[]") ?: "[]"
    val list = mutableListOf<Place>()
    try {
        val arr = JSONArray(historyStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(Place(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lon")))
        }
    } catch(e: Exception){}
    return list
}

private suspend fun fetchSpeedLimit(lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
    try {
        val query = "[out:json][timeout:3];way(around:20,$lat,$lon)[\"maxspeed\"];out tags;"
        val e = URLEncoder.encode(query, "UTF-8")
        val c = (URL("https://overpass-api.de/api/interpreter?data=$e").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
            setRequestProperty("User-Agent", "StradaSafeLiguria/1.0")
        }
        val res = c.inputStream.bufferedReader().use { it.readText() }
        val els = JSONObject(res).optJSONArray("elements") ?: return@withContext null
        
        for (i in 0 until els.length()) {
            val ms = els.getJSONObject(i).optJSONObject("tags")?.optString("maxspeed")
            if (!ms.isNullOrEmpty()) {
                return@withContext when (ms) {
                    "IT:urban" -> 50
                    "IT:rural" -> 90
                    "IT:motorway" -> 130
                    "IT:extra_urban" -> 110
                    else -> ms.filter { it.isDigit() }.toIntOrNull()
                }
            }
        }
    } catch (_: Exception) {}
    null
}

private fun loadSafetyDevices(context: Context): List<SafetyDevice> {
    return try {
        val jsonString = context.assets.open("safety_devices.demo.json").bufferedReader().use { it.readText() }
        val list = mutableListOf<SafetyDevice>()
        try {
            val root = JSONObject(jsonString)
            if (root.has("devices")) {
                val devices = root.getJSONArray("devices")
                for (i in 0 until devices.length()) {
                    val obj = devices.getJSONObject(i)
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lon = obj.optDouble("lon", Double.NaN)
                    val type = obj.optString("type", obj.optString("id", "Segnalazione"))
                    if (!lat.isNaN() && !lon.isNaN()) list.add(SafetyDevice(lat, lon, type))
                }
                return list
            }
        } catch (_: Exception) {}
        emptyList()
    } catch (e: Exception) { emptyList() }
}

@Composable
private fun TopStatusBar(gps: Location?) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(8.dp, RectangleShape)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("STRADASAFE 1.0", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    if (gps != null) "GPS Attivo (${gps.accuracy.roundToInt()}m)" else "Ricerca segnale GPS...",
                    color = if (gps != null) WazeGreen else AlertAmber,
                    fontSize = 10.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun HomeBottomBar(onClick: () -> Unit) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(8.dp, RectangleShape).clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Text("Cerca destinazione in Liguria...", color = TextGray, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SearchExpandedCard(
    query: String, onQuery: (String) -> Unit, granted: Boolean, onGps: () -> Unit,
    busy: Boolean, error: String?, results: List<Place>, history: List<Place>,
    avoidTolls: Boolean, onTollsChange: (Boolean) -> Unit,
    avoidHighways: Boolean, onHighwaysChange: (Boolean) -> Unit,
    onSearch: () -> Unit, onPlaceSelected: (Place) -> Unit, onClose: () -> Unit
) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(16.dp, RectangleShape)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cerca Luogo", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("CHIUDI", color = AppleBlue, fontSize = 13.sp) }
            }
            
            // Nuove Opzioni di Routing Squadrate
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onTollsChange(!avoidTolls) }) {
                    Checkbox(checked = avoidTolls, onCheckedChange = onTollsChange, colors = CheckboxDefaults.colors(checkedColor = AppleBlue, uncheckedColor = TextGray))
                    Text("No Pedaggi", color = TextWhite, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onHighwaysChange(!avoidHighways) }) {
                    Checkbox(checked = avoidHighways, onCheckedChange = onHighwaysChange, colors = CheckboxDefaults.colors(checkedColor = AppleBlue, uncheckedColor = TextGray))
                    Text("No Autostrade", color = TextWhite, fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Indirizzo o luogo...", color = TextGray, fontSize = 13.sp) }, shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppleBlue, unfocusedBorderColor = TextGray, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
            )
            if (!granted) Button(onClick = onGps, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) { Text("ATTIVA PERMESSO GPS", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Button(onClick = onSearch, enabled = query.length > 2 && granted && !busy, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) { Text(if (busy) "CERCANDO..." else "CERCA", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            error?.let { Text(it, color = AlertRed, fontSize = 12.sp) }
            
            if (results.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(results) { res ->
                        TextButton(onClick = { onPlaceSelected(res) }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, contentPadding = PaddingValues(10.dp)) {
                            Text(res.name, color = TextWhite, maxLines = 2, fontSize = 13.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                        }
                        HorizontalDivider(color = Color(0x33FFFFFF))
                    }
                }
            } else if (query.isEmpty() && history.isNotEmpty()) {
                Text("CRONOLOGIA RECENTE", color = TextGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(history) { res ->
                        TextButton(onClick = { onPlaceSelected(res) }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, contentPadding = PaddingValues(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("🕒", fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
                                Text(res.name, color = TextWhite, maxLines = 2, fontSize = 13.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        HorizontalDivider(color = Color(0x33FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(route: RouteData, place: Place?, onStart: () -> Unit, onReset: () -> Unit) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(16.dp, RectangleShape)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(place?.name ?: "Destinazione", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Distanza", color = TextGray, fontSize = 11.sp); Text("%.1f km".format(route.distance / 1000), color = WazeCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                Column(horizontalAlignment = Alignment.End) { Text("Tempo stimato", color = TextGray, fontSize = 11.sp); Text("${(route.duration / 60).roundToInt()} min", color = WazeGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReset, Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)) { Text("ANNULLA", fontSize = 13.sp) }
                Button(onClick = onStart, Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) { Text("AVVIA GUIDA", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ManeuverCard(step: Step?, distanceToStep: Double, muted: Boolean, onMuteToggle: () -> Unit) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(12.dp, RectangleShape)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            Surface(shape = RectangleShape, color = AppleBlue, modifier = Modifier.size(54.dp)) { Box(contentAlignment = Alignment.Center) { Text(getManifoldSymbol(step?.maneuver), fontSize = 30.sp, color = TextWhite) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (distanceToStep.isNaN()) "..." else "${distanceToStep.roundToInt()} m", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(step?.text ?: "Prosegui", color = WazeCyan, fontSize = 14.sp, maxLines = 2, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onMuteToggle, modifier = Modifier.size(40.dp)) {
                Text(if (muted) "🔇" else "🔊", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun EtaCard(
    speed: Int, speedLimit: Int?, inTutorZone: Boolean, avgTutorSpeed: Int, 
    remainingDistance: Double, remainingDuration: Double, 
    showStopButton: Boolean, onCardClick: () -> Unit, onStop: () -> Unit
) {
    val etaMillis = System.currentTimeMillis() + (remainingDuration * 1000).toLong()
    val etaFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
    val etaString = if (remainingDuration > 0) etaFormat.format(java.util.Date(etaMillis)) else "--:--"

    val isSpeeding = speedLimit != null && speed > speedLimit
    val speedColor = if (isSpeeding) AlertRed else TextWhite

    Surface(
        color = CardSurface, 
        shape = RectangleShape, 
        modifier = Modifier.fillMaxWidth().shadow(16.dp, RectangleShape).clickable { onCardClick() }
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricDashboard("$speed", "KM/H", speedColor)
                    if (speedLimit != null) {
                        Surface(shape = RectangleShape, color = TextWhite, border = BorderStroke(2.dp, AlertRed), modifier = Modifier.size(32.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("$speedLimit", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        }
                    }
                    if (inTutorZone) {
                        val isAvgSpeeding = speedLimit != null && avgTutorSpeed > speedLimit
                        val avgColor = if (isAvgSpeeding) AlertRed else AlertAmber
                        Surface(shape = RectangleShape, color = Color(0x33FF9F0A), modifier = Modifier.padding(start = 6.dp)) { MetricDashboard("$avgTutorSpeed", "MEDIA", avgColor, modifier = Modifier.padding(horizontal = 6.dp)) }
                    }
                }
                
                MetricDashboard("%.1f".format(remainingDistance / 1000), "KM", TextWhite)
                MetricDashboard(etaString, "ARRIVO", WazeGreen)
            }
            
            if (showStopButton) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AlertRed)) { 
                    Text("TERMINA VIAGGIO", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp) 
                }
            }
        }
    }
}

@Composable
private fun MetricDashboard(value: String, unit: String, color: Color, modifier: Modifier = Modifier) = Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    Text(unit, color = TextGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

private fun getManifoldSymbol(maneuver: String?): String = when (maneuver) {
    "left", "slight left" -> "↰"
    "right", "slight right" -> "↱"
    "uturn" -> "⮌"
    "roundabout" -> "⟳"
    else -> "↑"
}

@Composable
private fun NavMap(location: Location?, place: Place?, route: RouteData?, navigating: Boolean, followUser: Boolean, speed: Int, isSimulating: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val mapView = remember { MapView(context) }
    var ready by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }

    val paddingLeft = if (isLandscape && navigating) with(density) { 360.dp.toPx().toInt() } else 0
    val paddingTop = if (!isLandscape && navigating) with(density) { 100.dp.toPx().toInt() } else 0
    val paddingBottom = if (!isLandscape && navigating) with(density) { 140.dp.toPx().toInt() } else 0

    AndroidView(
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { map ->
                    map.cameraPosition = CameraPosition.Builder().target(LatLng(44.4, 8.9)).zoom(8.3).build()
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                        // Livello GPS simulato o reale (il colore cambia in galleria)
                        s.addSource(GeoJsonSource("gps"))
                        s.addLayer(CircleLayer("gps-l", "gps").withProperties(circleRadius(10f), circleColor("#0A84FF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))

                        s.addSource(GeoJsonSource("dest"))
                        s.addLayer(CircleLayer("dest-l", "dest").withProperties(circleRadius(9f), circleColor("#32D74B")))

                        s.addSource(GeoJsonSource("route"))
                        s.addLayer(LineLayer("route-l", "route").withProperties(lineColor("#64D2FF"), lineWidth(8f)))

                        s.addSource(GeoJsonSource("safety"))
                        s.addLayer(CircleLayer("safety-l", "safety").withProperties(circleRadius(7f), circleColor("#FF453A"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))

                        ready = true
                    }
                }
            }
        },
        update = { v ->
            if (ready) v.getMapAsync { m ->
                
                m.setPadding(paddingLeft, paddingTop, 0, paddingBottom)

                location?.let { 
                    m.style?.getSourceAs<GeoJsonSource>("gps")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) 
                    // Cambia colore cursore in caso di simulazione
                    m.style?.getLayerAs<CircleLayer>("gps-l")?.setProperties(circleColor(if (isSimulating) "#FF9F0A" else "#0A84FF"))
                }
                
                if (place != null) {
                    m.style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)))
                } else {
                    m.style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
                }
                
                if (route != null) {
                    m.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(route.points)))
                    if (!navigating && !fitted) {
                        val b = LatLngBounds.Builder()
                        route.points.forEach { b.include(LatLng(it.latitude(), it.longitude())) }
                        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 150), 800)
                        fitted = true
                    }
                } else {
                    m.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
                    fitted = false
                }

                val safetyPoints = if (navigating) {
                    AppState.activeSafetyDevices.value.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
                } else {
                    emptyList()
                }
                m.style?.getSourceAs<GeoJsonSource>("safety")?.setGeoJson(FeatureCollection.fromFeatures(safetyPoints))

                if (navigating && followUser && location != null) {
                    val targetZoom = when {
                        speed > 90 -> 15.0
                        speed > 50 -> 16.5
                        else -> 17.5
                    }
                    val targetTilt = if (speed > 80) 60.0 else 45.0

                    val pos = CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(targetZoom)
                        .bearing(if (location.hasBearing()) location.bearing.toDouble() else 0.0)
                        .tilt(targetTilt)
                        .build()
                        
                    m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 1000)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(mapView) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
}

private suspend fun searchPlaces(q: String): List<Place> = withContext(Dispatchers.IO) {
    val e = URLEncoder.encode(q, "UTF-8")
    val res = get("$SEARCH_BASE/search?q=$e&format=jsonv2&limit=5&countrycodes=it&viewbox=7.45,44.75,10.10,43.70&bounded=1")
    val a = JSONArray(res)
    if (a.length() == 0) error("Nessun risultato trovato in Liguria.")

    val list = mutableListOf<Place>()
    for (i in 0 until a.length()) {
        val o = a.getJSONObject(i)
        list.add(Place(o.getString("display_name"), o.getString("lat").toDouble(), o.getString("lon").toDouble()))
    }
    list
}

private suspend fun fetchRoute(l: Location, p: Place, avoidTolls: Boolean, avoidHighways: Boolean): RouteData = withContext(Dispatchers.IO) {
    var url = "$ROUTE_BASE/route/v1/driving/${l.longitude},${l.latitude};${p.lon},${p.lat}?overview=full&geometries=geojson&steps=true"
    
    val excludes = mutableListOf<String>()
    if (avoidTolls) excludes.add("toll")
    if (avoidHighways) excludes.add("motorway")
    if (excludes.isNotEmpty()) url += "&exclude=${excludes.joinToString(",")}"

    val root = JSONObject(get(url))
    if (root.optString("code") != "Ok") error("Percorso non disponibile")

    val r = root.getJSONArray("routes").getJSONObject(0)
    val c = r.getJSONObject("geometry").getJSONArray("coordinates")
    val pts = (0 until c.length()).map { val a = c.getJSONArray(it); Point.fromLngLat(a.getDouble(0), a.getDouble(1)) }

    val steps = mutableListOf<Step>()
    val legs = r.getJSONArray("legs")
    for (i in 0 until legs.length()) {
        val ss = legs.getJSONObject(i).getJSONArray("steps")
        for (j in 0 until ss.length()) {
            val s = ss.getJSONObject(j)
            val man = s.getJSONObject("maneuver")
            val loc = man.getJSONArray("location")
            steps += Step(
                text = instruction(man.optString("type"), man.optString("modifier"), s.optString("name")),
                lat = loc.getDouble(1), lon = loc.getDouble(0),
                distance = s.optDouble("distance"), duration = s.optDouble("duration"),
                maneuver = man.optString("modifier")
            )
        }
    }
    RouteData(pts, r.getDouble("distance"), r.getDouble("duration"), steps)
}

private fun instruction(type: String, mod: String, name: String): String {
    val road = if (name.isBlank()) "" else " in $name"
    return when (type) {
        "depart" -> "Parti e prosegui$road"
        "arrive" -> "Sei arrivato a destinazione"
        "roundabout", "rotary" -> "Entra nella rotatoria$road"
        "turn" -> when (mod) {
            "left" -> "Svolta a sinistra$road"
            "right" -> "Svolta a destra$road"
            "slight left" -> "Tieni la sinistra$road"
            "slight right" -> "Tieni la destra$road"
            "straight" -> "Continua dritto$road"
            "uturn" -> "Fai inversione a U$road"
            else -> "Prosegui$road"
        }
        else -> "Prosegui$road"
    }
}

private fun get(address: String): String {
    val c = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
        setRequestProperty("User-Agent", "StradaSafeLiguria/1.0")
        setRequestProperty("Accept-Language", "it")
    }
    try {
        if (c.responseCode !in 200..299) error("Servizio non disponibile (${c.responseCode})")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }
}

private fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
    val r = 6371000.0; val p1 = Math.toRadians(a); val p2 = Math.toRadians(c)
    val dp = Math.toRadians(c - a); val dl = Math.toRadians(d - b)
    val x = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * atan2(sqrt(x), sqrt(1 - x))
}

private fun distanceToPolyline(lat: Double, lon: Double, pts: List<Point>): Double =
    pts.minOfOrNull { distanceMeters(lat, lon, it.latitude(), it.longitude()) } ?: Double.MAX_VALUE
