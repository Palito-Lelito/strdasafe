package it.stradasafe.liguria

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectAsState
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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
import kotlin.math.*

// ---- Costanti ----
private const val SEARCH_BASE = "https://nominatim.openstreetmap.org"
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

// ---- Colori ----
private val AppleBlue = Color(0xFF0A84FF)
private val WazeCyan = Color(0xFF64D2FF)
private val WazeGreen = Color(0xFF32D74B)
private val AlertAmber = Color(0xFFFF9F0A)
private val AlertRed = Color(0xFFFF453A)
private val TextGray = Color(0xFF8E8E93)

enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Chiara"), DARK("Scura") }

// ---- Stato applicativo (Compose) ----
object AppState {
    val query = mutableStateOf("")
    val searchResults = mutableStateOf<List<Place>>(emptyList())
    val searchHistory = mutableStateOf<List<Place>>(emptyList())
    val place = mutableStateOf<Place?>(null)
    val route = mutableStateOf<RouteData?>(null)
    val navigating = mutableStateOf(false)
    val followUser = mutableStateOf(true)
    val isSearchExpanded = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val muted = mutableStateOf(false)
    val avoidTolls = mutableStateOf(false)
    val avoidHighways = mutableStateOf(false)
    val safetyDevices = mutableStateOf<List<SafetyDevice>>(emptyList())
    val themeMode = mutableStateOf(ThemeMode.AUTO)
    val granted = mutableStateOf(false)
}

// ---- Modelli ----
data class Place(val name: String, val lat: Double, val lon: Double)
data class Step(val text: String, val lat: Double, val lon: Double, val distance: Double = 0.0)
data class RouteData(val points: List<Point>, val distance: Double, val duration: Double, val steps: List<Step>)
data class SafetyDevice(val id: String, val lat: Double, val lon: Double, val type: String, val speedLimit: Int? = null)

// ---- Activity ----
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { App() }
    }
}

// ---- Orologio astronomico ----
private fun isNightLocal(): Boolean {
    val cal = java.util.Calendar.getInstance()
    val month = cal.get(java.util.Calendar.MONTH)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    val time = hour + minute / 60.0
    val sunrises = floatArrayOf(7.8f, 7.3f, 6.5f, 6.6f, 6.0f, 5.7f, 5.9f, 6.3f, 7.0f, 7.5f, 7.4f, 7.9f)
    val sunsets  = floatArrayOf(17.1f, 17.8f, 18.4f, 20.2f, 20.8f, 21.2f, 21.0f, 20.4f, 19.6f, 18.7f, 17.8f, 16.9f)
    return time < sunrises[month] || time > sunsets[month]
}

// ---- Main Composable ----
@Composable
private fun App() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val gps by LocationBus.location.collectAsState()
    val navigating by NavState.navigating.collectAsState()
    val muted by NavState.muted.collectAsState()
    val speedLimit by NavState.speedLimit.collectAsState()
    val isSimulating by NavState.isSimulating.collectAsState()
    val inTutor by NavState.isInTutor.collectAsState()
    val tutorAvg by NavState.tutorAvgSpeed.collectAsState()
    val currentStepIdx by NavState.currentStepIdx.collectAsState()
    val currentStep = NavState.steps.getOrNull(currentStepIdx)
    val distanceToStep = if (gps != null && currentStep != null)
        distanceMeters(gps!!.latitude, gps!!.longitude, currentStep.lat, currentStep.lon) else 0.0

    // ---- Tema ----
    var isDark by remember { mutableStateOf(false) }
    LaunchedEffect(AppState.themeMode.value) {
        while (true) {
            isDark = when (AppState.themeMode.value) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.AUTO -> isNightLocal()
            }
            delay(60000)
        }
    }
    val colorScheme = if (isDark) {
        darkColorScheme(
            background = Color(0xFF000000),
            surface = Color(0xE61C1C1E),
            onBackground = Color.White,
            primary = AppleBlue
        )
    } else {
        lightColorScheme(
            background = Color(0xFFF2F2F7),
            surface = Color(0xE6FFFFFF),
            onBackground = Color.Black,
            primary = AppleBlue
        )
    }

    // ---- Permission launcher ----
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        AppState.granted.value = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS è opzionale, non blocca il GPS
        }
    }
    LaunchedEffect(Unit) {
        AppState.granted.value = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    // ---- Caricamento safety devices ----
    LaunchedEffect(Unit) {
        AppState.safetyDevices.value = loadSafetyDevices(context)
        AppState.searchHistory.value = loadHistory(context)
    }

    // ---- Redirect GPS se granted ----
    LaunchedEffect(AppState.granted.value) {
        if (AppState.granted.value) {
            // il service parte solo quando si naviga; qui niente
        }
    }

    // ---- Frame ----
    MaterialTheme(colorScheme = colorScheme) {
        Box(Modifier.fillMaxSize().background(colorScheme.background)) {
            NavMap(gps, AppState.place.value, AppState.route.value, navigating, AppState.followUser.value, speedLimit)
            if (navigating && isSimulating) {
                Surface(color = AlertAmber, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    Text("SEGNALE GPS PERSO - SIMULAZIONE IN CORSO",
                        color = Color.Black, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
                }
            }
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                if (isLandscape) {
                    Column(Modifier.fillMaxHeight().widthIn(max = 340.dp)) {
                        if (!navigating) TopStatusBar(gps)
                        Spacer(Modifier.height(12.dp))
                        if (navigating && currentStep != null) {
                            ManeuverCard(currentStep, distanceToStep, muted) {
                                NavState.muted.value = !NavState.muted.value
                                AppState.muted.value = NavState.muted.value
                            }
                        }
                    }
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End) {
                        if (navigating) {
                            EtaCard(gps?.speed?.toInt() ?: 0, speedLimit, inTutor, tutorAvg.toDouble(),
                                AppState.route.value?.duration ?: 0.0, AppState.route.value?.distance ?: 0.0,
                                onStop = { stopNavigation(context, scope) })
                        } else if (AppState.route.value != null) {
                            OverviewCard(AppState.route.value!!, AppState.place.value,
                                onStart = { startNavigation(context, scope) },
                                onReset = { resetNavigation() })
                        } else if (AppState.isSearchExpanded.value) {
                            SearchExpandedCard(
                                onClose = { AppState.isSearchExpanded.value = false }
                            )
                        } else {
                            HomeBottomBar { AppState.isSearchExpanded.value = true }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Column {
                            if (!navigating) {
                                TopStatusBar(gps)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (navigating && currentStep != null) {
                                ManeuverCard(currentStep, distanceToStep, muted) {
                                    NavState.muted.value = !NavState.muted.value
                                    AppState.muted.value = NavState.muted.value
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                            if (navigating) {
                                EtaCard(gps?.speed?.toInt() ?: 0, speedLimit, inTutor, tutorAvg.toDouble(),
                                    AppState.route.value?.duration ?: 0.0, AppState.route.value?.distance ?: 0.0,
                                    onStop = { stopNavigation(context, scope) })
                            } else if (AppState.route.value != null) {
                                OverviewCard(AppState.route.value!!, AppState.place.value,
                                    onStart = { startNavigation(context, scope) },
                                    onReset = { resetNavigation() })
                            } else if (AppState.isSearchExpanded.value) {
                                SearchExpandedCard(
                                    onClose = { AppState.isSearchExpanded.value = false }
                                )
                            } else {
                                HomeBottomBar { AppState.isSearchExpanded.value = true }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Navigazione start/stop ----
private fun startNavigation(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    val route = AppState.route.value ?: return
    val place = AppState.place.value ?: return
    NavState.steps = route.steps
    NavState.routePoints = route.points
    NavState.activeDevices = AppState.safetyDevices.value
    NavState.alertedIds.clear()
    NavState.currentStepIdx.value = 0
    NavState.navigating.value = true
    NavState.destLat = place.lat
    NavState.destLon = place.lon
    NavState.avoidTolls = AppState.avoidTolls.value
    NavState.avoidHighways = AppState.avoidHighways.value
    AppState.navigating.value = true
    AppState.followUser.value = true
    AppState.isSearchExpanded.value = false
    val i = Intent(context, NavigationService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
    else context.startService(i)
}

private fun stopNavigation(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    NavState.navigating.value = false
    AppState.navigating.value = false
    val i = Intent(context, NavigationService::class.java).apply { action = "STOP" }
    context.startService(i)
    resetNavigation()
}

private fun resetNavigation() {
    AppState.navigating.value = false
    AppState.route.value = null
    AppState.place.value = null
    AppState.followUser.value = true
    AppState.isSearchExpanded.value = false
    NavState.navigating.value = false
    NavState.steps = emptyList()
    NavState.routePoints = emptyList()
    NavState.speedLimit.value = null
    NavState.isInTutor.value = false
    NavState.currentStepIdx.value = 0
}

// ---- Persistenza cronologia ----
private fun saveHistory(context: Context, list: List<Place>) {
    val prefs = context.getSharedPreferences("StradaSafePrefs", Context.MODE_PRIVATE)
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().apply { put("name", it.name); put("lat", it.lat); put("lon", it.lon) }) }
    prefs.edit().putString("search_history", arr.toString()).apply()
}

private fun loadHistory(context: Context): List<Place> {
    val prefs = context.getSharedPreferences("StradaSafePrefs", Context.MODE_PRIVATE)
    val s = prefs.getString("search_history", "[]") ?: "[]"
    return try {
        val a = JSONArray(s)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
        }
    } catch (_: Exception) { emptyList() }
}

// ---- Caricamento safety devices ----
private fun loadSafetyDevices(context: Context): List<SafetyDevice> = try {
    val json = context.assets.open("safety_devices.demo.json").bufferedReader().use { it.readText() }
    val root = JSONObject(json)
    val arr = root.getJSONArray("devices")
    (0 until arr.length()).mapNotNull {
        val o = arr.getJSONObject(it)
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) null
        else SafetyDevice(
            id = o.optString("id", "dev_$it"),
            lat = lat, lon = lon,
            type = o.optString("type", "Autovelox"),
            speedLimit = if (o.has("speed_limit")) o.optInt("speed_limit") else null
        )
    }
} catch (_: Exception) { emptyList() }

// ---- Nominatim search ----
private suspend fun searchPlaces(q: String): List<Place> = withContext(Dispatchers.IO) {
    val enc = URLEncoder.encode(q, "UTF-8")
    val url = "$SEARCH_BASE/search?q=$enc&format=jsonv2&limit=8&countrycodes=it&accept-language=it"
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
        setRequestProperty("User-Agent", "StradaSafe/1.1 (contact: dev@stradasafe.it)")
    }
    val res = c.inputStream.bufferedReader().use { it.readText() }
    val a = JSONArray(res)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Place(o.getString("display_name"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

// ---- ORS route ----
private suspend fun fetchRoute(
    from: Location, to: Place,
    avoidTolls: Boolean, avoidHighways: Boolean,
    currentBearing: Double? = null
): RouteData = withContext(Dispatchers.IO) {
    val key = BuildConfig.ORS_API_KEY
    if (key.isEmpty()) error("Manca ORS_API_KEY in local.properties")
    val urlStr = "https://api.openrouteservice.org/v2/directions/driving-car/geojson"
    val body = JSONObject().apply {
        put("coordinates", JSONArray().apply {
            put(JSONArray().apply { put(from.longitude); put(from.latitude) })
            put(JSONArray().apply { put(to.lon); put(to.lat) })
        })
        put("language", "it")
        put("instructions", true)
        if (currentBearing != null) {
            put("bearings", JSONArray().apply {
                put(JSONArray().apply { put(currentBearing.toInt()); put(30) })
                put(JSONArray().apply { put(JSONObject.NULL) })
            })
        }
        val avoids = JSONArray()
        if (avoidTolls) avoids.put("tollways")
        if (avoidHighways) avoids.put("highways")
        if (avoids.length() > 0) put("options", JSONObject().apply { put("avoid_features", avoids) })
    }
    val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 20000; requestMethod = "POST"
        setRequestProperty("Authorization", key)
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
    }
    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    if (c.responseCode !in 200..299) {
        val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}"
        error("Errore routing: $err")
    }
    val res = c.inputStream.bufferedReader().use { it.readText() }
    val root = JSONObject(res)
    if (root.has("error")) error(root.getJSONObject("error").optString("message", "Errore ORS"))
    val feature = root.getJSONArray("features").getJSONObject(0)
    val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
    val pts = (0 until coords.length()).map {
        val a = coords.getJSONArray(it)
        Point.fromLngLat(a.getDouble(0), a.getDouble(1))
    }
    val props = feature.getJSONObject("properties")
    val summary = props.getJSONObject("summary")
    val segments = props.getJSONArray("segments")
    val steps = mutableListOf<Step>()
    for (i in 0 until segments.length()) {
        val segSteps = segments.getJSONObject(i).getJSONArray("steps")
        for (j in 0 until segSteps.length()) {
            val s = segSteps.getJSONObject(j)
            val wpIdx = s.getJSONArray("way_points").getInt(0)
            val pt = pts.getOrNull(wpIdx) ?: pts.first()
            steps += Step(
                text = s.optString("instruction", "Prosegui"),
                lat = pt.latitude(), lon = pt.longitude(),
                distance = s.optDouble("distance", 0.0)
            )
        }
    }
    RouteData(pts, summary.getDouble("distance"), summary.getDouble("duration"), steps)
}

// ---- UI components ----

@Composable
private fun TopStatusBar(gps: Location?) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("STRADASAFE 1.1", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    if (gps != null) "GPS Attivo (${gps.accuracy.roundToInt()}m)" else "GPS in attesa...",
                    fontSize = 11.sp, color = TextGray
                )
            }
        }
    }
}

@Composable
private fun HomeBottomBar(onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text("Cerca una destinazione", fontSize = 15.sp)
        }
    }
}

@Composable
private fun SearchExpandedCard(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val query by AppState.query
    val results by AppState.searchResults
    val history by AppState.searchHistory
    val busy by AppState.busy
    val error by AppState.error
    val granted by AppState.granted
    val avoidTolls by AppState.avoidTolls
    val avoidHighways by AppState.avoidHighways
    val themeMode by AppState.themeMode

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cerca destinazione", fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Chiudi") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = avoidTolls, onCheckedChange = { AppState.avoidTolls.value = it })
                    Text("Evita pedaggi", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = avoidHighways, onCheckedChange = { AppState.avoidHighways.value = it })
                    Text("Evita autostrade", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    AppState.themeMode.value = when (themeMode) {
                        ThemeMode.AUTO -> ThemeMode.DARK
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.AUTO
                    }
                }) { Text(themeMode.label, fontSize = 12.sp) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { AppState.query.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Es. Genova, Via Roma 10") },
                singleLine = true
            )
            if (!granted) {
                Button(onClick = {
                    // richiedi permesso
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Attiva GPS")
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        AppState.busy.value = true
                        AppState.error.value = null
                        try {
                            AppState.searchResults.value = searchPlaces(query)
                            if (AppState.searchResults.value.isEmpty()) AppState.error.value = "Nessun risultato"
                        } catch (e: Exception) {
                            AppState.error.value = e.message
                        } finally {
                            AppState.busy.value = false
                        }
                    }
                },
                enabled = query.length > 2 && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Cerca")
            }
            error?.let { Text(it, color = AlertRed, fontSize = 12.sp) }
            if (results.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(results) { res ->
                        TextButton(onClick = {
                            scope.launch {
                                AppState.busy.value = true
                                AppState.error.value = null
                                try {
                                    val gps = LocationBus.current()
                                    if (gps == null) error("GPS non disponibile")
                                    val newHistory = (listOf(res) + history).distinctBy { it.name }.take(10)
                                    AppState.searchHistory.value = newHistory
                                    saveHistory(context, newHistory)
                                    val route = fetchRoute(
                                        gps!!, res,
                                        AppState.avoidTolls.value,
                                        AppState.avoidHighways.value,
                                        if (gps.hasBearing()) gps.bearing.toDouble() else null
                                    )
                                    AppState.route.value = route
                                    AppState.place.value = res
                                    AppState.isSearchExpanded.value = false
                                } catch (e: Exception) {
                                    AppState.error.value = e.message
                                } finally {
                                    AppState.busy.value = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(res.name, fontSize = 13.sp, maxLines = 2)
                        }
                        HorizontalDivider(color = TextGray.copy(alpha = 0.3f))
                    }
                }
            } else if (query.isEmpty() && history.isNotEmpty()) {
                Text("CRONOLOGIA RECENTE", color = TextGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(history) { res ->
                        TextButton(onClick = {
                            scope.launch {
                                AppState.busy.value = true
                                try {
                                    val gps = LocationBus.current()
                                    if (gps != null) {
                                        val route = fetchRoute(gps, res,
                                            AppState.avoidTolls.value,
                                            AppState.avoidHighways.value,
                                            if (gps.hasBearing()) gps.bearing.toDouble() else null)
                                        AppState.route.value = route
                                        AppState.place.value = res
                                        AppState.isSearchExpanded.value = false
                                    }
                                } catch (e: Exception) {
                                    AppState.error.value = e.message
                                } finally { AppState.busy.value = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(res.name, fontSize = 13.sp, maxLines = 1)
                        }
                        HorizontalDivider(color = TextGray.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(route: RouteData, place: Place?, onStart: () -> Unit, onReset: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(place?.name ?: "Destinazione", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Distanza", color = TextGray, fontSize = 11.sp)
                    Text("${(route.distance / 1000).let { "%.1f".format(it) }} km", fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Tempo stimato", color = TextGray, fontSize = 11.sp)
                    Text("${(route.duration / 60).roundToInt()} min", fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Annulla") }
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("AVVIA") }
            }
        }
    }
}

@Composable
private fun ManeuverCard(step: Step, distanceToStep: Double, muted: Boolean, onMuteToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = AppleBlue,
                modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("➤", color = Color.White, fontSize = 22.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (distanceToStep.isNaN()) "..." else "${distanceToStep.roundToInt()} m",
                    fontWeight = FontWeight.Bold, color = WazeCyan, fontSize = 14.sp
                )
                Text(step.text, fontSize = 14.sp, maxLines = 2)
            }
            TextButton(onClick = onMuteToggle) {
                Text(if (muted) "🔇" else "🔊", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun EtaCard(speed: Int, speedLimit: Int?, inTutorZone: Boolean, avgTutorSpeed: Double,
                    remainingDuration: Double, remainingDistance: Double, onStop: () -> Unit) {
    val speedColor = if (speedLimit != null && speed > speedLimit) AlertRed else WazeGreen
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricDashboard("$speed", "KM/H", speedColor)
                if (speedLimit != null) MetricDashboard("$speedLimit", "LIMITE", AlertAmber)
                if (inTutorZone) {
                    val tColor = if (speedLimit != null && avgTutorSpeed > speedLimit) AlertRed else WazeGreen
                    MetricDashboard("%.0f".format(avgTutorSpeed), "MEDIA TUTOR", tColor)
                }
                MetricDashboard("%.1f".format(remainingDistance / 1000), "KM", MaterialTheme.colorScheme.onBackground)
                MetricDashboard("${(remainingDuration / 60).roundToInt()}", "MIN", MaterialTheme.colorScheme.onBackground)
            }
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AlertRed)) {
                Text("TERMINA NAVIGAZIONE")
            }
        }
    }
}

@Composable
private fun MetricDashboard(value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(unit, fontSize = 10.sp, color = TextGray)
    }
}

// ---- Mappa ----
@Composable
private fun NavMap(
    gps: Location?, place: Place?, route: RouteData?,
    navigating: Boolean, followUser: Boolean, speedLimit: Int?
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                getMapAsync { map ->
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                        // Sorgente posizione utente
                        val userFeature = FeatureCollection.fromFeatures(emptyList())
                        style.addSource(GeoJsonSource("user-src", userFeature))
                        style.addLayer(
                            CircleLayer("user-layer", "user-src").withProperties(
                                circleRadius(8f),
                                circleColor("#0A84FF".toInt()),
                                circleStrokeWidth(3f),
                                circleStrokeColor("#FFFFFF".toInt())
                            )
                        )
                        // Sorgente destinazione
                        style.addSource(GeoJsonSource("dest-src", FeatureCollection.fromFeatures(emptyList())))
                        style.addLayer(
                            CircleLayer("dest-layer", "dest-src").withProperties(
                                circleRadius(10f),
                                circleColor("#FF453A".toInt()),
                                circleStrokeWidth(2f),
                                circleStrokeColor("#FFFFFF".toInt())
                            )
                        )
                        // Sorgente rotta
                        style.addSource(GeoJsonSource("route-src", FeatureCollection.fromFeatures(emptyList())))
                        style.addLayer(
                            LineLayer("route-layer", "route-src").withProperties(
                                lineColor("#0A84FF".toInt()),
                                lineWidth(6f),
                                lineCap("round"),
                                lineJoin("round")
                            )
                        )
                        // Sorgente safety
                        style.addSource(GeoJsonSource("safety-src", FeatureCollection.fromFeatures(emptyList())))
                        style.addLayer(
                            CircleLayer("safety-layer", "safety-src").withProperties(
                                circleRadius(7f),
                                circleColor("#FF9F0A".toInt()),
                                circleStrokeWidth(2f),
                                circleStrokeColor("#FFFFFF".toInt())
                            )
                        )

                        // Location component
                        val lc = map.locationComponent
                        lc.activateLocationComponent(
                            LocationComponentActivationOptions.builder(ctx, style)
                                .useDefaultLocationEngine(false)
                                .build()
                        )
                        lc.isLocationComponentEnabled = true
                        lc.cameraMode = if (followUser) CameraMode.TRACKING else CameraMode.NONE
                        lc.renderMode = RenderMode.COMPASS

                        // Redraw loop
                        map.addOnCameraIdleListener { /* niente */ }
                    }
                }
                tag = "stradasafe_map"
            }
        },
        update = { mapView ->
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                // Utente
                if (gps != null) {
                    style.getSourceAs<GeoJsonSource>("user-src")?.setGeoJson(
                        Feature.fromGeometry(Point.fromLngLat(gps.longitude, gps.latitude))
                    )
                }
                // Destinazione
                if (place != null) {
                    style.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(
                        Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat))
                    )
                } else {
                    style.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
                // Rotta
                if (route != null && route.points.size >= 2) {
                    style.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(
                        Feature.fromGeometry(LineString.fromLngLats(route.points))
                    )
                } else {
                    style.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
                // Safety
                val safeties = if (navigating) AppState.safetyDevices.value else emptyList()
                val feats = safeties.map {
                    Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))
                }
                style.getSourceAs<GeoJsonSource>("safety-src")?.setGeoJson(FeatureCollection.fromFeatures(feats))

                // Camera
                if (gps != null) {
                    if (navigating && followUser) {
                        val zoom = when {
                            (gps.speed) > 25 -> 15.0
                            (gps.speed) > 13 -> 16.0
                            else -> 17.0
                        }
                        val tilt = if (gps.speed > 22) 55.0 else 40.0
                        val pos = CameraPosition.Builder()
                            .target(LatLng(gps.latitude, gps.longitude))
                            .zoom(zoom)
                            .tilt(tilt)
                            .bearing(gps.bearing.toDouble())
                            .build()
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 800)
                        // Aggiorna anche la location nativa
                        if (map.locationComponent.isLocationComponentActivated) {
                            val sim = Location("nav").apply {
                                latitude = gps.latitude; longitude = gps.longitude
                                bearing = gps.bearing; speed = gps.speed
                                time = gps.time; accuracy = gps.accuracy
                            }
                            map.locationComponent.forceLocationUpdate(sim)
                        }
                    } else if (route != null) {
                        val b = LatLngBounds.Builder()
                        route.points.forEach { b.include(LatLng(it.latitude(), it.longitude())) }
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
                    }
                }
            }
        }
    )
}
