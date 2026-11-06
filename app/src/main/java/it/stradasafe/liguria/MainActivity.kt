package it.stradasafe.liguria

import android.Manifest
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import it.stradasafe.liguria.ui.*
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
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
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

private const val SEARCH_BASE = "https://nominatim.openstreetmap.org"
private const val STYLE_DAY = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
private const val STYLE_NIGHT = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Chiara"), DARK("Scura") }

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

data class Place(val name: String, val lat: Double, val lon: Double)
data class Step(val text: String, val lat: Double, val lon: Double, val distance: Double = 0.0)
data class RouteData(val points: List<Point>, val distance: Double, val duration: Double, val steps: List<Step>)
data class SafetyDevice(val id: String, val lat: Double, val lon: Double, val type: String, val speedLimit: Int? = null)

object SmoothNav {
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastBearing = 0f
    private var lastSpeed = 0f
    private var lastFixMs = 0L
    private var initialized = false

    var outLat by mutableDoubleStateOf(0.0); private set
    var outLon by mutableDoubleStateOf(0.0); private set
    var outBearing by mutableFloatStateOf(0f); private set
    var outSpeed by mutableFloatStateOf(0f); private set

    fun onNewFix(loc: Location) {
        if (loc.accuracy > 40f) return
        lastLat = loc.latitude
        lastLon = loc.longitude
        lastSpeed = max(0f, loc.speed)
        if (loc.hasBearing()) lastBearing = loc.bearing
        lastFixMs = System.currentTimeMillis()
        if (!initialized) {
            outLat = lastLat; outLon = lastLon
            outBearing = lastBearing; outSpeed = lastSpeed
            initialized = true
        }
    }

    fun step() {
        if (!initialized) return
        val now = System.currentTimeMillis()
        val dtSec = ((now - lastFixMs).coerceAtMost(3000L)) / 1000.0
        val dist = (lastSpeed * dtSec).toDouble()
        val (predLat, predLon) = if (dist < 0.3) lastLat to lastLon
        else predictPosition(lastLat, lastLon, lastBearing, dist)
        val pAlpha = 0.15
        outLat += (predLat - outLat) * pAlpha
        outLon += (predLon - outLon) * pAlpha
        var diff = lastBearing - outBearing
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        outBearing = (outBearing + diff * 0.10f + 360f) % 360f
        outSpeed += (lastSpeed - outSpeed) * 0.15f
    }

    fun reset() { initialized = false; lastFixMs = 0L; lastSpeed = 0f }

    private fun predictPosition(lat: Double, lon: Double, bearing: Float, dist: Double): Pair<Double, Double> {
        val r = 6371000.0
        val brng = Math.toRadians(bearing.toDouble())
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(dist / r) + cos(lat1) * sin(dist / r) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(dist / r) * cos(lat1), cos(dist / r) - sin(lat1) * sin(lat2))
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { App() }
    }
}

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

@Composable
private fun App() {
    val context = LocalContext.current
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

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        AppState.granted.value = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        AppState.granted.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    LaunchedEffect(Unit) {
        AppState.safetyDevices.value = loadSafetyDevices(context)
        AppState.searchHistory.value = loadHistory(context)
    }

    StradaSafeTheme(isDark = isDark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed } && navigating) {
                                AppState.followUser.value = false
                            }
                        }
                    }
                }
        ) {
            NavMap(
                gps = gps,
                place = AppState.place.value,
                route = AppState.route.value,
                navigating = navigating,
                followUser = AppState.followUser.value,
                isDark = isDark,
                granted = AppState.granted.value
            )

            AnimatedVisibility(
                visible = navigating && isSimulating,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            ) {
                Surface(shape = StradaShapes.Pill, color = StradaColors.Amber, shadowElevation = 8.dp) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDot(StradaColors.Red, 8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "SEGNALE GPS PERSO · SIMULAZIONE",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                if (isLandscape) {
                    LandscapeLayout(
                        gps = gps, navigating = navigating, muted = muted,
                        currentStep = currentStep, distanceToStep = distanceToStep,
                        speedLimit = speedLimit, inTutor = inTutor, tutorAvg = tutorAvg.toDouble(),
                        onMuteToggle = {
                            NavState.muted.value = !NavState.muted.value
                            AppState.muted.value = NavState.muted.value
                        },
                        onStart = { startNavigation(context) },
                        onStop = { stopNavigation(context) },
                        onReset = { resetNavigation() },
                        onOpenSearch = { AppState.isSearchExpanded.value = true },
                        onCloseSearch = { AppState.isSearchExpanded.value = false }
                    )
                } else {
                    PortraitLayout(
                        gps = gps, navigating = navigating, muted = muted,
                        currentStep = currentStep, distanceToStep = distanceToStep,
                        speedLimit = speedLimit, inTutor = inTutor, tutorAvg = tutorAvg.toDouble(),
                        onMuteToggle = {
                            NavState.muted.value = !NavState.muted.value
                            AppState.muted.value = NavState.muted.value
                        },
                        onStart = { startNavigation(context) },
                        onStop = { stopNavigation(context) },
                        onReset = { resetNavigation() },
                        onOpenSearch = { AppState.isSearchExpanded.value = true },
                        onCloseSearch = { AppState.isSearchExpanded.value = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitLayout(
    gps: Location?, navigating: Boolean, muted: Boolean,
    currentStep: Step?, distanceToStep: Double, speedLimit: Int?,
    inTutor: Boolean, tutorAvg: Double,
    onMuteToggle: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onReset: () -> Unit,
    onOpenSearch: () -> Unit, onCloseSearch: () -> Unit
) {
    val isDark = LocalIsDark.current
    val route by AppState.route
    val place by AppState.place
    val isSearchExpanded by AppState.isSearchExpanded

    Column(Modifier.fillMaxSize()) {
        Column {
            if (!navigating) {
                TopStatusPill(gps = gps, isDark = isDark)
                Spacer(Modifier.height(12.dp))
            }
            if (navigating && currentStep != null) {
                ManeuverCard(currentStep, distanceToStep, muted, isDark, onMuteToggle)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
            when {
                navigating -> EtaCard(
                    speed = gps?.speed?.toInt() ?: 0,
                    speedLimit = speedLimit, inTutor = inTutor, tutorAvg = tutorAvg,
                    duration = route?.duration ?: 0.0, distance = route?.distance ?: 0.0,
                    isDark = isDark, onStop = onStop
                )
                route != null -> OverviewCard(route!!, place, isDark, onStart, onReset)
                isSearchExpanded -> SearchSheet(isDark = isDark, onClose = onCloseSearch)
                else -> SearchPrompt(isDark = isDark, onClick = onOpenSearch)
            }
        }
    }
}

@Composable
private fun LandscapeLayout(
    gps: Location?, navigating: Boolean, muted: Boolean,
    currentStep: Step?, distanceToStep: Double, speedLimit: Int?,
    inTutor: Boolean, tutorAvg: Double,
    onMuteToggle: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onReset: () -> Unit,
    onOpenSearch: () -> Unit, onCloseSearch: () -> Unit
) {
    val isDark = LocalIsDark.current
    val route by AppState.route
    val place by AppState.place
    val isSearchExpanded by AppState.isSearchExpanded

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxHeight().widthIn(max = 400.dp)) {
            if (!navigating) {
                TopStatusPill(gps = gps, isDark = isDark)
                Spacer(Modifier.height(12.dp))
            }
            if (navigating && currentStep != null) {
                ManeuverCard(currentStep, distanceToStep, muted, isDark, onMuteToggle)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxHeight().widthIn(max = 400.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            when {
                navigating -> EtaCard(
                    speed = gps?.speed?.toInt() ?: 0,
                    speedLimit = speedLimit, inTutor = inTutor, tutorAvg = tutorAvg,
                    duration = route?.duration ?: 0.0, distance = route?.distance ?: 0.0,
                    isDark = isDark, onStop = onStop
                )
                route != null -> OverviewCard(route!!, place, isDark, onStart, onReset)
                isSearchExpanded -> SearchSheet(isDark = isDark, onClose = onCloseSearch)
                else -> SearchPrompt(isDark = isDark, onClick = onOpenSearch)
            }
        }
    }
}

@Composable
private fun TopStatusPill(gps: Location?, isDark: Boolean) {
    val hasGps = gps != null && gps.accuracy < 30f
    val dotColor = when {
        gps == null -> StradaColors.Amber
        gps.accuracy < 10f -> StradaColors.Green
        gps.accuracy < 30f -> StradaColors.Cyan
        else -> StradaColors.Amber
    }
    GlassSurface(isDark = isDark, elevation = 8.dp) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulsingDot(color = dotColor)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "STRADASAFE",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (hasGps) "GPS attivo · ${gps!!.accuracy.roundToInt()}m" else "Ricerca segnale...",
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchPrompt(isDark: Boolean, onClick: () -> Unit) {
    val bg = if (isDark) StradaColors.DarkSurfaceElevated else Color.White
    Surface(
        onClick = onClick,
        shape = StradaShapes.Pill,
        color = bg,
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text("Dove vuoi andare?", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchSheet(isDark: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val query by AppState.query
    val results by AppState.searchResults
    val history by AppState.searchHistory
    val busy by AppState.busy
    val error by AppState.error
    val avoidTolls by AppState.avoidTolls
    val avoidHighways by AppState.avoidHighways
    val themeMode by AppState.themeMode

    GlassSurface(isDark = isDark, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Cerca destinazione", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Chiudi",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { AppState.query.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Es. Genova, Via Roma 10") },
                singleLine = true,
                shape = StradaShapes.Medium,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = if (isDark) StradaColors.DarkBorder else StradaColors.LightBorder
                )
            )
            SectionLabel("Preferenze")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterPill("Evita pedaggi", avoidTolls, isDark) { AppState.avoidTolls.value = it }
                FilterPill("Evita autostrade", avoidHighways, isDark) { AppState.avoidHighways.value = it }
                FilterPill(themeMode.label, true, isDark) {
                    AppState.themeMode.value = when (themeMode) {
                        ThemeMode.AUTO -> ThemeMode.DARK
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.AUTO
                    }
                }
            }
            GradientButton(
                text = if (busy) "Ricerca..." else "Cerca",
                onClick = {
                    scope.launch {
                        AppState.busy.value = true
                        AppState.error.value = null
                        try {
                            AppState.searchResults.value = searchPlaces(query)
                            if (AppState.searchResults.value.isEmpty()) AppState.error.value = "Nessun risultato"
                        } catch (e: Exception) {
                            AppState.error.value = e.message
                        } finally { AppState.busy.value = false }
                    }
                },
                enabled = query.length > 2 && !busy,
                modifier = Modifier.fillMaxWidth(),
                icon = "🔍"
            )
            error?.let { Text(it, color = StradaColors.Red, fontSize = 12.sp) }
            if (results.isNotEmpty()) {
                SectionLabel("Risultati")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(results) { res ->
                        PlaceRow(res, isDark) {
                            scope.launch {
                                AppState.busy.value = true
                                AppState.error.value = null
                                try {
                                    val gps = LocationBus.current() ?: error("GPS non disponibile")
                                    val newHistory = (listOf(res) + history).distinctBy { it.name }.take(10)
                                    AppState.searchHistory.value = newHistory
                                    saveHistory(context, newHistory)
                                    val route = fetchRoute(
                                        gps, res, AppState.avoidTolls.value, AppState.avoidHighways.value,
                                        if (gps.hasBearing()) gps.bearing.toDouble() else null
                                    )
                                    AppState.route.value = route
                                    AppState.place.value = res
                                    AppState.isSearchExpanded.value = false
                                } catch (e: Exception) {
                                    AppState.error.value = e.message
                                } finally { AppState.busy.value = false }
                            }
                        }
                    }
                }
            } else if (query.isEmpty() && history.isNotEmpty()) {
                SectionLabel("Cronologia recente")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(history) { res ->
                        PlaceRow(res, isDark) {
                            scope.launch {
                                AppState.busy.value = true
                                try {
                                    val gps = LocationBus.current()
                                    if (gps != null) {
                                        val route = fetchRoute(
                                            gps, res, AppState.avoidTolls.value, AppState.avoidHighways.value,
                                            if (gps.hasBearing()) gps.bearing.toDouble() else null
                                        )
                                        AppState.route.value = route
                                        AppState.place.value = res
                                        AppState.isSearchExpanded.value = false
                                    }
                                } catch (e: Exception) {
                                    AppState.error.value = e.message
                                } finally { AppState.busy.value = false }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, active: Boolean, isDark: Boolean, onClick: (Boolean) -> Unit) {
    val bg = when {
        active -> MaterialTheme.colorScheme.primary
        isDark -> StradaColors.DarkSurfaceElevated
        else -> Color(0xFFF1F5F9)
    }
    val textColor = if (active) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = StradaShapes.Pill, color = bg, modifier = Modifier.clickable { onClick(!active) }) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColor)
    }
}

@Composable
private fun PlaceRow(place: Place, isDark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(StradaShapes.Medium).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Text("📍", fontSize = 16.sp) }
        Spacer(Modifier.width(12.dp))
        Text(place.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OverviewCard(route: RouteData, place: Place?, isDark: Boolean,
                        onStart: () -> Unit, onReset: () -> Unit) {
    GlassSurface(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(StradaGradients.Danger),
                    contentAlignment = Alignment.Center) { Text("📍", fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Destinazione", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(place?.name ?: "—", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBlock(
                    value = String.format("%.1f", route.distance / 1000),
                    label = "KM", valueColor = MaterialTheme.colorScheme.primary, large = true
                )
                StatBlock(
                    value = formatDuration(route.duration),
                    label = "TEMPO", valueColor = MaterialTheme.colorScheme.onSurface, large = true
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = StradaShapes.Medium,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                ) { Text("Annulla", fontWeight = FontWeight.SemiBold) }
                GradientButton(
                    text = "AVVIA", onClick = onStart,
                    modifier = Modifier.weight(1.2f), icon = "▶"
                )
            }
        }
    }
}

@Composable
private fun ManeuverCard(step: Step, distanceToStep: Double, muted: Boolean,
                        isDark: Boolean, onMuteToggle: () -> Unit) {
    GlassSurface(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(StradaGradients.Primary),
                contentAlignment = Alignment.Center) {
                Text("➤", fontSize = 34.sp, color = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(formatDistance(distanceToStep), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
                Text(step.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onMuteToggle, modifier = Modifier.size(44.dp)) {
                Text(if (muted) "🔇" else "🔊", fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun EtaCard(speed: Int, speedLimit: Int?, inTutor: Boolean, tutorAvg: Double,
                    duration: Double, distance: Double, isDark: Boolean, onStop: () -> Unit) {
    GlassSurface(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Speedometer(speed = speed, speedLimit = speedLimit, size = 110.dp)
                Column(horizontalAlignment = Alignment.End) {
                    StatBlock(value = String.format("%.1f", distance / 1000), label = "KM RIMANENTI",
                        valueColor = MaterialTheme.colorScheme.onSurface, large = true)
                    Spacer(Modifier.height(10.dp))
                    StatBlock(value = formatDuration(duration), label = "TEMPO STIMATO",
                        valueColor = MaterialTheme.colorScheme.primary, large = true)
                }
            }
            if (inTutor) {
                Row(
                    Modifier.fillMaxWidth().clip(StradaShapes.Medium)
                        .background(StradaColors.Amber.copy(alpha = 0.15f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TUTOR ATTIVO", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = StradaColors.Amber)
                        Text("Media: ${"%.0f".format(tutorAvg)} km/h", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            GradientButton(
                text = "TERMINA NAVIGAZIONE", onClick = onStop,
                modifier = Modifier.fillMaxWidth(), gradient = StradaGradients.Danger
            )
        }
    }
}

// ============================================================
// NAVMAP con lifecycle corretto + LocationComponent condizionale
// ============================================================
@Composable
private fun NavMap(
    gps: Location?, place: Place?, route: RouteData?,
    navigating: Boolean, followUser: Boolean, isDark: Boolean,
    granted: Boolean
) {
    // ✅ FIX: leggiamo il context qui, NON dentro il LaunchedEffect
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var routeFitted by remember { mutableStateOf(false) }
    var currentStyleUrl by remember { mutableStateOf<String?>(null) }

    var curZoom by remember { mutableDoubleStateOf(17.0) }
    var curTilt by remember { mutableDoubleStateOf(40.0) }

    LaunchedEffect(gps) { gps?.let { SmoothNav.onNewFix(it) } }
    LaunchedEffect(navigating) { if (!navigating) { SmoothNav.reset(); routeFitted = false } }

    // Lifecycle del MapView
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mv = mapViewRef ?: return@DisposableEffect onDispose { }
        // Riallinea lo stato del MapView allo stato attuale del lifecycle
        val state = lifecycleOwner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.CREATED)) mv.onCreate(Bundle())
        if (state.isAtLeast(Lifecycle.State.STARTED)) mv.onStart()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) mv.onResume()
    
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(gps, mapRef, navigating) {
        if (navigating) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!map.locationComponent.isLocationComponentActivated) return@LaunchedEffect
        gps?.let { map.locationComponent.forceLocationUpdate(it) }
    }

    LaunchedEffect(navigating, followUser) {
        if (!navigating) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            SmoothNav.step()
            val map = mapRef ?: continue
            if (!map.locationComponent.isLocationComponentActivated) continue
            val sim = Location("smooth").apply {
                latitude = SmoothNav.outLat; longitude = SmoothNav.outLon
                bearing = SmoothNav.outBearing; speed = SmoothNav.outSpeed
                accuracy = 5f; time = System.currentTimeMillis()
            }
            map.locationComponent.forceLocationUpdate(sim)
            if (followUser) {
                val targetZoom = when {
                    SmoothNav.outSpeed > 25f -> 15.0
                    SmoothNav.outSpeed > 13f -> 16.0
                    else -> 17.0
                }
                val targetTilt = if (SmoothNav.outSpeed > 22f) 55.0 else 40.0
                curZoom += (targetZoom - curZoom) * 0.03
                curTilt += (targetTilt - curTilt) * 0.03
                val pos = CameraPosition.Builder()
                    .target(LatLng(SmoothNav.outLat, SmoothNav.outLon))
                    .zoom(curZoom).tilt(curTilt).bearing(SmoothNav.outBearing.toDouble())
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
            }
        }
    }

    LaunchedEffect(isDark, mapViewRef) {
        val mv = mapViewRef ?: return@LaunchedEffect
        val targetUrl = if (isDark) STYLE_NIGHT else STYLE_DAY
        if (currentStyleUrl == targetUrl) return@LaunchedEffect
        mv.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(targetUrl)) { style ->
                setupMapLayers(style)
                currentStyleUrl = targetUrl
            }
        }
    }

    // Attivazione LocationComponent quando il permesso è concesso
    LaunchedEffect(granted, mapRef) {
        if (!granted) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (map.locationComponent.isLocationComponentActivated) return@LaunchedEffect
        try {
            // ✅ Usa `context` (letto fuori dal LaunchedEffect), non LocalContext.current
            val locationOptions = LocationComponentOptions.builder(context)
                .trackingAnimationDurationMultiplier(0f)
                .enableStaleState(false)
                .build()
            val activationOptions = LocationComponentActivationOptions
                .builder(context, style)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(locationOptions)
                .build()
            map.locationComponent.activateLocationComponent(activationOptions)
            map.locationComponent.isLocationComponentEnabled = true
            map.locationComponent.cameraMode = CameraMode.NONE
            map.locationComponent.renderMode = RenderMode.COMPASS
        } catch (e: SecurityException) {
            // permesso non ancora concesso, ignora
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                mapViewRef = this
                getMapAsync { map ->
                    mapRef = map
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(44.4056, 8.9463)).zoom(10.0).build()
                    val initialUrl = if (isDark) STYLE_NIGHT else STYLE_DAY
                    map.setStyle(Style.Builder().fromUri(initialUrl)) { style ->
                        setupMapLayers(style)
                        currentStyleUrl = initialUrl
                    }
                }
            }
        },
        update = { mapView ->
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                if (place != null) {
                    style.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(
                        Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)))
                } else {
                    style.getSourceAs<GeoJsonSource>("dest-src")
                        ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
                if (route != null && route.points.size >= 2) {
                    style.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(
                        Feature.fromGeometry(LineString.fromLngLats(route.points)))
                } else {
                    style.getSourceAs<GeoJsonSource>("route-src")
                        ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
                val safeties = if (navigating) AppState.safetyDevices.value else emptyList()
                val feats = safeties.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
                style.getSourceAs<GeoJsonSource>("safety-src")
                    ?.setGeoJson(FeatureCollection.fromFeatures(feats))
                if (route != null && !navigating && !routeFitted) {
                    val b = LatLngBounds.Builder()
                    route.points.forEach { b.include(LatLng(it.latitude(), it.longitude())) }
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100), 800)
                    routeFitted = true
                }
            }
        }
    )
}

private fun setupMapLayers(style: Style) {
    style.addSource(GeoJsonSource("route-src", FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(LineLayer("route-casing", "route-src").withProperties(
        lineColor("#0A0E1A".toInt()), lineWidth(11f), lineCap("round"), lineJoin("round")))
    style.addLayer(LineLayer("route-layer", "route-src").withProperties(
        lineColor("#00D9FF".toInt()), lineWidth(7f), lineCap("round"), lineJoin("round")))

    style.addSource(GeoJsonSource("safety-src", FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(CircleLayer("safety-glow", "safety-src").withProperties(
        circleRadius(16f), circleColor("#F59E0B".toInt()), circleOpacity(0.25f)))
    style.addLayer(CircleLayer("safety-layer", "safety-src").withProperties(
        circleRadius(8f), circleColor("#F59E0B".toInt()), circleStrokeWidth(2f),
        circleStrokeColor("#FFFFFF".toInt())))

    style.addSource(GeoJsonSource("dest-src", FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(CircleLayer("dest-glow", "dest-src").withProperties(
        circleRadius(20f), circleColor("#EF4444".toInt()), circleOpacity(0.2f)))
    style.addLayer(CircleLayer("dest-layer", "dest-src").withProperties(
        circleRadius(10f), circleColor("#EF4444".toInt()), circleStrokeWidth(3f),
        circleStrokeColor("#FFFFFF".toInt())))

    style.addSource(GeoJsonSource("user-src", FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(CircleLayer("user-layer", "user-src").withProperties(
        circleRadius(8f), circleColor("#00D9FF".toInt()), circleStrokeWidth(3f),
        circleStrokeColor("#FFFFFF".toInt())))
}

private fun startNavigation(context: Context) {
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

private fun stopNavigation(context: Context) {
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
    SmoothNav.reset()
}

private fun saveHistory(context: Context, list: List<Place>) {
    val prefs = context.getSharedPreferences("StradaSafePrefs", Context.MODE_PRIVATE)
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().apply {
        put("name", it.name); put("lat", it.lat); put("lon", it.lon) }) }
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

private suspend fun searchPlaces(q: String): List<Place> = withContext(Dispatchers.IO) {
    val enc = URLEncoder.encode(q, "UTF-8")
    val url = "$SEARCH_BASE/search?q=$enc&format=jsonv2&limit=8&countrycodes=it&accept-language=it"
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
        setRequestProperty("User-Agent", "StradaSafe/1.1")
    }
    val res = c.inputStream.bufferedReader().use { it.readText() }
    val a = JSONArray(res)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Place(o.getString("display_name"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

private suspend fun fetchRoute(
    from: Location, to: Place, avoidTolls: Boolean, avoidHighways: Boolean,
    currentBearing: Double? = null
): RouteData = withContext(Dispatchers.IO) {
    val key = BuildConfig.ORS_API_KEY
    if (key.isEmpty()) error("Manca ORS_API_KEY")
    val urlStr = "https://api.openrouteservice.org/v2/directions/driving-car/geojson"
    val body = JSONObject().apply {
        put("coordinates", JSONArray().apply {
            put(JSONArray().apply { put(from.longitude); put(from.latitude) })
            put(JSONArray().apply { put(to.lon); put(to.lat) })
        })
        put("language", "it"); put("instructions", true)
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
