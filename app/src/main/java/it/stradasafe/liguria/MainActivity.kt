package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
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

// Palette iOS 27 Dark Mode & Waze Hybrid
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

// Stato globale persistente
object AppState {
    val query = mutableStateOf("")
    val searchResults = mutableStateOf<List<Place>>(emptyList())
    val place = mutableStateOf<Place?>(null)
    val route = mutableStateOf<RouteData?>(null)
    val navigating = mutableStateOf(false)
    val isSearchExpanded = mutableStateOf(false)
    val stepIndex = mutableIntStateOf(0)
    
    // Safety Devices
    val safetyDevices = mutableStateOf<List<SafetyDevice>>(emptyList())
    val alertedDevices = mutableSetOf<SafetyDevice>()
    
    // Limiti di velocità
    val speedLimit = mutableStateOf<Int?>(null)
    val lastSpeedLimitCheck = mutableLongStateOf(0L)

    // Modalità Tutor (Velocità Media)
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
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(2f)
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
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }
    return tts to ready
}

@Composable
private fun App(client: FusedLocationProviderClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val (tts, ttsReady) = rememberSpeaker()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AppState.safetyDevices.value = loadSafetyDevices(context) }
    }

    var query by AppState.query
    var searchResults by AppState.searchResults
    var place by AppState.place
    var route by AppState.route
    var navigating by AppState.navigating
    var isSearchExpanded by AppState.isSearchExpanded
    var stepIndex by AppState.stepIndex
    var speedLimit by AppState.speedLimit

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gps = rememberGps(client, granted)

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableIntStateOf(-1) }
    var lastReroute by remember { mutableLongStateOf(0L) }
    var avgTutorSpeed by remember { mutableIntStateOf(0) }

    val currentStep = route?.steps?.getOrNull(stepIndex)
    val distanceToStep = if (gps != null && currentStep != null) distanceMeters(gps.latitude, gps.longitude, currentStep.lat, currentStep.lon) else Double.NaN

    val speed = ((gps?.speed ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)
    val remainingDistance = route?.steps?.drop(stepIndex)?.sumOf { it.distance } ?: 0.0
    val remainingDuration = route?.steps?.drop(stepIndex)?.sumOf { it.duration } ?: 0.0

    LaunchedEffect(gps, navigating, route) {
        if (!navigating || gps == null || route == null) return@LaunchedEffect
        val currentTime = System.currentTimeMillis()

        // --- 1. Overpass API Limiti di Velocità ---
        if (currentTime - AppState.lastSpeedLimitCheck.longValue > 20000) {
            AppState.lastSpeedLimitCheck.longValue = currentTime
            scope.launch {
                val limit = fetchSpeedLimit(gps.latitude, gps.longitude)
                if (limit != null) speedLimit = limit
            }
        }

        // --- 2. Manovre TTS ---
        val steps = route!!.steps
        if (stepIndex < steps.lastIndex && distanceToStep < 30) stepIndex++

        val s = steps.getOrNull(stepIndex)
        if (s != null && stepIndex != lastSpoken && distanceMeters(gps.latitude, gps.longitude, s.lat, s.lon) < 300) {
            if (!muted && ttsReady) tts?.speak("Tra ${distanceMeters(gps.latitude, gps.longitude, s.lat, s.lon).roundToInt()} metri, ${s.text}", TextToSpeech.QUEUE_ADD, null, "step-$stepIndex")
            lastSpoken = stepIndex
        }

        // --- 3. Safety Devices & Logica TUTOR ---
        val unalerted = AppState.safetyDevices.value.filter { it !in AppState.alertedDevices }
        val nearbyDevice = unalerted.firstOrNull { distanceMeters(gps.latitude, gps.longitude, it.lat, it.lon) < 500 }
        
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

        // Calcolo velocità media live se nel tutor
        if (AppState.inTutorZone.value) {
            val distTraveledMeters = AppState.tutorStartDistanceRemaining.doubleValue - remainingDistance
            val timeElapsedMillis = currentTime - AppState.tutorStartTime.longValue
            
            // Buffer iniziale di sicurezza per evitare velocità infinite (5 sec, 50 metri)
            if (timeElapsedMillis > 5000 && distTraveledMeters > 50) {
                val timeHours = timeElapsedMillis / 3600000.0
                val distKm = distTraveledMeters / 1000.0
                avgTutorSpeed = (distKm / timeHours).roundToInt().coerceAtLeast(0)
            }
        }

        // --- 4. Ricalcolo Percorso ---
        val offRoute = distanceToPolyline(gps.latitude, gps.longitude, route!!.points) > 75
        if (offRoute && currentTime - lastReroute > 15000) {
            lastReroute = currentTime
            scope.launch {
                try {
                    route = fetchRoute(gps, place!!)
                    stepIndex = 0; lastSpoken = -1
                    if (!muted && ttsReady) tts?.speak("Ricalcolo del percorso", TextToSpeech.QUEUE_FLUSH, null, "reroute")
                } catch (_: Exception) {}
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AppleBlue, background = DarkBackground, surface = CardSurface)) {
        Box(Modifier.fillMaxSize().background(DarkBackground)) {
            NavMap(gps, place, route, navigating)

            Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                if (isLandscape) {
                    Column(Modifier.fillMaxHeight().widthIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TopStatusBar(gps, navigating, muted, onMuteToggle = { muted = !muted })
                        if (navigating && route != null) {
                            ManeuverCard(currentStep, distanceToStep)
                            EtaCard(speed, speedLimit, AppState.inTutorZone.value, avgTutorSpeed, remainingDistance, remainingDuration, onStop = { resetNavigation() })
                        } else if (route != null) {
                            OverviewCard(route!!, place, onStart = { navigating = true; isSearchExpanded = false }, onReset = { route = null; place = null })
                        } else if (isSearchExpanded) {
                            SearchExpandedCard(query, { query = it }, granted, { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, busy, error, searchResults,
                                onSearch = { scope.launch { busy = true; error = null; try { searchResults = searchPlaces(query) } catch (e: Exception) { error = e.message } finally { busy = false } } },
                                onPlaceSelected = { p -> scope.launch { busy = true; error = null; searchResults = emptyList(); place = p; try { route = fetchRoute(gps ?: error("Attendi GPS"), p) } catch (e: Exception) { error = e.message } finally { busy = false; isSearchExpanded = false } } },
                                onClose = { isSearchExpanded = false }
                            )
                        } else {
                            HomeBottomBar(onClick = { isSearchExpanded = true })
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            TopStatusBar(gps, navigating, muted, onMuteToggle = { muted = !muted })
                            Spacer(Modifier.height(10.dp))
                            if (navigating && route != null) ManeuverCard(currentStep, distanceToStep)
                        }
                        Column {
                            if (navigating && route != null) {
                                EtaCard(speed, speedLimit, AppState.inTutorZone.value, avgTutorSpeed, remainingDistance, remainingDuration, onStop = { resetNavigation() })
                            } else if (route != null) {
                                OverviewCard(route!!, place, onStart = { navigating = true; isSearchExpanded = false }, onReset = { route = null; place = null })
                            } else if (isSearchExpanded) {
                                SearchExpandedCard(query, { query = it }, granted, { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, busy, error, searchResults,
                                    onSearch = { scope.launch { busy = true; error = null; try { searchResults = searchPlaces(query) } catch (e: Exception) { error = e.message } finally { busy = false } } },
                                    onPlaceSelected = { p -> scope.launch { busy = true; error = null; searchResults = emptyList(); place = p; try { route = fetchRoute(gps ?: error("Attendi GPS"), p) } catch (e: Exception) { error = e.message } finally { busy = false; isSearchExpanded = false } } },
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
    AppState.speedLimit.value = null
    AppState.inTutorZone.value = false
}

// --- LOGICA OVERPASS API ---
private suspend fun fetchSpeedLimit(lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
    try {
        val query = "[out:json][timeout:3];way(around:20,$lat,$lon)[\"maxspeed\"];out tags;"
        val e = URLEncoder.encode(query, "UTF-8")
        val c = (URL("https://overpass-api.de/api/interpreter?data=$e").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
            setRequestProperty("User-Agent", "StradaSafeLiguria/0.92")
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

// --- LOGICA PARSING SAFETY DEVICES ---
private fun loadSafetyDevices(context: Context): List<SafetyDevice> {
    return try {
        val jsonString = context.assets.open("safety_devices.demo.json").bufferedReader().use { it.readText() }
        val list = mutableListOf<SafetyDevice>()
        try {
            val root = JSONObject(jsonString)
            if (root.optString("type") == "FeatureCollection") {
                val features = root.getJSONArray("features")
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val geom = f.optJSONObject("geometry")
                    if (geom != null && geom.optString("type") == "Point") {
                        val coords = geom.getJSONArray("coordinates")
                        val props = f.optJSONObject("properties")
                        val type = props?.optString("type") ?: props?.optString("name") ?: "Segnalazione"
                        list.add(SafetyDevice(coords.getDouble(1), coords.getDouble(0), type))
                    }
                }
                return list
            }
        } catch (_: Exception) {}

        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val lat = obj.optDouble("lat", obj.optDouble("latitude", Double.NaN))
            val lon = obj.optDouble("lon", obj.optDouble("longitude", Double.NaN))
            val type = obj.optString("type", obj.optString("name", "Segnalazione"))
            if (!lat.isNaN() && !lon.isNaN()) list.add(SafetyDevice(lat, lon, type))
        }
        list
    } catch (e: Exception) { emptyList() }
}

// --- COMPONENTI UI MODULARI ---

@Composable
private fun TopStatusBar(gps: Location?, navigating: Boolean, muted: Boolean, onMuteToggle: () -> Unit) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(8.dp, RectangleShape)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("STRADASAFE 0.92", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    if (gps != null) "GPS Attivo (${gps.accuracy.roundToInt()}m)" else "Ricerca segnale GPS...",
                    color = if (gps != null) WazeGreen else AlertAmber,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            }
            if (navigating) IconButton(onClick = onMuteToggle, modifier = Modifier.size(32.dp)) { Text(if (muted) "🔇" else "🔊", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun HomeBottomBar(onClick: () -> Unit) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(8.dp, RectangleShape).clickable { onClick() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text("Cerca destinazione in Liguria...", color = TextGray, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SearchExpandedCard(
    query: String, onQuery: (String) -> Unit, granted: Boolean, onGps: () -> Unit,
    busy: Boolean, error: String?, results: List<Place>,
    onSearch: () -> Unit, onPlaceSelected: (Place) -> Unit, onClose: () -> Unit
) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(16.dp, RectangleShape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cerca Luogo", color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("CHIUDI", color = AppleBlue) }
            }
            OutlinedTextField(
                value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Indirizzo o luogo...", color = TextGray) }, shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppleBlue, unfocusedBorderColor = TextGray, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
            )
            if (!granted) Button(onClick = onGps, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) { Text("ATTIVA PERMESSO GPS", color = TextWhite, fontWeight = FontWeight.Bold) }
            Button(onClick = onSearch, enabled = query.length > 2 && granted && !busy, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) { Text(if (busy) "CERCANDO..." else "CERCA", color = TextWhite, fontWeight = FontWeight.Bold) }
            error?.let { Text(it, color = AlertRed, fontSize = 13.sp) }
            if (results.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(results) { res ->
                        TextButton(onClick = { onPlaceSelected(res) }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, contentPadding = PaddingValues(12.dp)) {
                            Text(res.name, color = TextWhite, maxLines = 2, fontSize = 14.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(place?.name ?: "Destinazione", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Distanza", color = TextGray, fontSize = 12.sp); Text("%.1f km".format(route.distance / 1000), color = WazeCyan, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Column(horizontalAlignment = Alignment.End) { Text("Tempo stimato", color = TextGray, fontSize = 12.sp); Text("${(route.duration / 60).roundToInt()} min", color = WazeGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReset, Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)) { Text("ANNULLA") }
                Button(onClick = onStart, Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) { Text("AVVIA GUIDA", color = TextWhite, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ManeuverCard(step: Step?, distanceToStep: Double) {
    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(12.dp, RectangleShape)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Surface(shape = RectangleShape, color = AppleBlue, modifier = Modifier.size(64.dp)) { Box(contentAlignment = Alignment.Center) { Text(getManifoldSymbol(step?.maneuver), fontSize = 36.sp, color = TextWhite) } }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (distanceToStep.isNaN()) "..." else "${distanceToStep.roundToInt()} m", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(step?.text ?: "Prosegui dritto", color = WazeCyan, fontSize = 16.sp, maxLines = 2, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun EtaCard(
    speed: Int, speedLimit: Int?, inTutorZone: Boolean, avgTutorSpeed: Int, 
    remainingDistance: Double, remainingDuration: Double, onStop: () -> Unit
) {
    val etaMillis = System.currentTimeMillis() + (remainingDuration * 1000).toLong()
    val etaFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
    val etaString = if (remainingDuration > 0) etaFormat.format(java.util.Date(etaMillis)) else "--:--"

    val isSpeeding = speedLimit != null && speed > speedLimit
    val speedColor = if (isSpeeding) AlertRed else TextWhite

    Surface(color = CardSurface, shape = RectangleShape, modifier = Modifier.fillMaxWidth().shadow(16.dp, RectangleShape)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                
                // Modulo Velocità + Cartello Limite + Modulo Tutor Media
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricDashboard("$speed", "KM/H", speedColor)
                    
                    if (speedLimit != null) {
                        Surface(shape = RectangleShape, color = TextWhite, border = BorderStroke(3.dp, AlertRed), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("$speedLimit", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                    }

                    if (inTutorZone) {
                        val isAvgSpeeding = speedLimit != null && avgTutorSpeed > speedLimit
                        val avgColor = if (isAvgSpeeding) AlertRed else AlertAmber
                        Surface(shape = RectangleShape, color = Color(0x33FF9F0A), modifier = Modifier.padding(start = 8.dp)) {
                            MetricDashboard("$avgTutorSpeed", "MEDIA", avgColor, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
                
                MetricDashboard("%.1f".format(remainingDistance / 1000), "KM", TextWhite)
                MetricDashboard(etaString, "ARRIVO", WazeGreen)
            }
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AlertRed)) { Text("TERMINA VIAGGIO", color = TextWhite, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun MetricDashboard(value: String, unit: String, color: Color, modifier: Modifier = Modifier) = Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    Text(unit, color = TextGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

private fun getManifoldSymbol(maneuver: String?): String = when (maneuver) {
    "left", "slight left" -> "↰"
    "right", "slight right" -> "↱"
    "uturn" -> "⮌"
    "roundabout" -> "⟳"
    else -> "↑"
}

@Composable
private fun NavMap(location: Location?, place: Place?, route: RouteData?, follow: Boolean) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    var ready by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }

    AndroidView(
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { map ->
                    map.cameraPosition = CameraPosition.Builder().target(LatLng(44.4, 8.9)).zoom(8.3).build()
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
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
                location?.let { m.style?.getSourceAs<GeoJsonSource>("gps")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
                place?.let { m.style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }
                
                route?.let { r ->
                    m.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.points)))
                    if (!follow && !fitted) {
                        val b = LatLngBounds.Builder()
                        r.points.forEach { b.include(LatLng(it.latitude(), it.longitude())) }
                        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 150), 800)
                        fitted = true
                    }
                }

                val safetyPoints = AppState.safetyDevices.value.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
                if (safetyPoints.isNotEmpty()) m.style?.getSourceAs<GeoJsonSource>("safety")?.setGeoJson(FeatureCollection.fromFeatures(safetyPoints))

                if (follow && location != null) {
                    m.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(17.5)
                        .bearing(if (location.hasBearing()) location.bearing.toDouble() else 0.0)
                        .tilt(55.0)
                        .build()
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

private suspend fun fetchRoute(l: Location, p: Place): RouteData = withContext(Dispatchers.IO) {
    val root = JSONObject(get("$ROUTE_BASE/route/v1/driving/${l.longitude},${l.latitude};${p.lon},${p.lat}?overview=full&geometries=geojson&steps=true"))
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
        setRequestProperty("User-Agent", "StradaSafeLiguria/0.92 private prototype")
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
