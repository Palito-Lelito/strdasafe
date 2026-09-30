package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

// Palette ispirata a Waze (sfondi scuri profondi, accenti ad alto contrasto) e Apple Mappe (pulizia visiva)
private val DarkBackground = Color(0xFF0B131D)
private val CardSurface = Color(0xEE142232)
private val AppleBlue = Color(0xFF0A84FF)
private val WazeCyan = Color(0xFF00D2FF)
private val WazeGreen = Color(0xFF32D74B)
private val AlertAmber = Color(0xFFFF9F0A)
private val TextWhite = Color(0xFFFFFFFF)
private val TextGray = Color(0xFF8E8E93)

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SEARCH_BASE = "https://nominatim.openstreetmap.org"
private const val ROUTE_BASE = "https://router.project-osrm.org"

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
    val (tts, ttsReady) = rememberSpeaker()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gps = rememberGps(client, granted)

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Place>>(emptyList()) }
    var place by remember { mutableStateOf<Place?>(null) }
    var route by remember { mutableStateOf<RouteData?>(null) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }

    var stepIndex by remember { mutableIntStateOf(0) }
    var lastSpoken by remember { mutableIntStateOf(-1) }
    var lastReroute by remember { mutableLongStateOf(0L) }

    val currentStep = route?.steps?.getOrNull(stepIndex)
    val distanceToStep = if (gps != null && currentStep != null) {
        distanceMeters(gps.latitude, gps.longitude, currentStep.lat, currentStep.lon)
    } else Double.NaN

    val speed = ((gps?.speed ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)

    // Logica dinamica: calcolo dei chilometri e del tempo rimanente scartando i passaggi già completati
    val remainingDistance = route?.steps?.drop(stepIndex)?.sumOf { it.distance } ?: 0.0
    val remainingDuration = route?.steps?.drop(stepIndex)?.sumOf { it.duration } ?: 0.0

    LaunchedEffect(gps, navigating, route) {
        if (!navigating || gps == null || route == null) return@LaunchedEffect
        val steps = route!!.steps

        if (stepIndex < steps.lastIndex && distanceToStep < 30) {
            stepIndex++
        }

        val s = steps.getOrNull(stepIndex)
        if (s != null && stepIndex != lastSpoken && distanceMeters(gps.latitude, gps.longitude, s.lat, s.lon) < 300) {
            if (!muted && ttsReady) {
                tts?.speak(
                    "Tra ${distanceMeters(gps.latitude, gps.longitude, s.lat, s.lon).roundToInt()} metri, ${s.text}",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "step-$stepIndex"
                )
            }
            lastSpoken = stepIndex
        }

        val offRoute = distanceToPolyline(gps.latitude, gps.longitude, route!!.points) > 75
        if (offRoute && System.currentTimeMillis() - lastReroute > 15000) {
            lastReroute = System.currentTimeMillis()
            scope.launch {
                try {
                    route = fetchRoute(gps, place!!)
                    stepIndex = 0
                    lastSpoken = -1
                    if (!muted && ttsReady) {
                        tts?.speak("Ricalcolo del percorso in corso", TextToSpeech.QUEUE_FLUSH, null, "reroute")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AppleBlue, background = DarkBackground, surface = CardSurface)) {
        Box(Modifier.fillMaxSize().background(DarkBackground)) {
            NavMap(gps, place, route, navigating)

            Column(
                Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Barra Superiore Stile Apple Mappe / Waze
                Surface(color = CardSurface, shape = RectangleShape) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("STRADASAFE · 0.8", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                if (gps != null) "GPS attivo (${gps.accuracy.roundToInt()}m)" else "Ricerca segnale GPS...",
                                color = if (gps != null) WazeGreen else AlertAmber,
                                fontSize = 11.sp
                            )
                        }

                        if (navigating) {
                            IconButton(onClick = { muted = !muted }) {
                                Text(if (muted) "🔇" else "🔊", fontSize = 20.sp)
                            }
                        }
                    }
                }

                // Pannello Inferiore di Navigazione o Ricerca
                if (navigating && route != null) {
                    AppleWazeNavigationCard(
                        step = currentStep,
                        distanceToStep = distanceToStep,
                        speed = speed,
                        remainingDistance = remainingDistance,
                        remainingDuration = remainingDuration,
                        onStop = {
                            navigating = false
                            stepIndex = 0
                            tts?.stop()
                        }
                    )
                } else {
                    SearchCard(
                        query,
                        { query = it },
                        granted,
                        { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        busy,
                        error,
                        searchResults,
                        route,
                        place,
                        onSearch = {
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    searchResults = searchPlaces(query)
                                } catch (e: Exception) {
                                    error = e.message ?: "Errore di ricerca"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onPlaceSelected = { p ->
                            scope.launch {
                                busy = true
                                error = null
                                searchResults = emptyList()
                                place = p
                                try {
                                    val l = gps ?: error("Attendi il segnale GPS")
                                    route = fetchRoute(l, p)
                                } catch (e: Exception) {
                                    error = e.message ?: "Errore calcolo percorso"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onStart = {
                            navigating = true
                            stepIndex = 0
                            lastSpoken = -1
                            if (!muted && ttsReady) {
                                tts?.speak("Navigazione avviata", TextToSpeech.QUEUE_FLUSH, null, "start")
                            }
                        },
                        onReset = {
                            route = null
                            place = null
                            searchResults = emptyList()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCard(
    query: String,
    onQuery: (String) -> Unit,
    granted: Boolean,
    onGps: () -> Unit,
    busy: Boolean,
    error: String?,
    results: List<Place>,
    route: RouteData?,
    place: Place?,
    onSearch: () -> Unit,
    onPlaceSelected: (Place) -> Unit,
    onStart: () -> Unit,
    onReset: () -> Unit
) {
    Surface(color = CardSurface, shape = RectangleShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (route == null) {
                Text("Dove andiamo?", color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Indirizzo o luogo in Liguria", color = TextGray) },
                    shape = RectangleShape
                )

                if (!granted) {
                    Button(onClick = onGps, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)) {
                        Text("ATTIVA PERMESSO GPS", color = TextWhite)
                    }
                }

                Button(
                    onClick = onSearch,
                    enabled = query.length > 2 && granted && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)
                ) {
                    Text(if (busy) "CERCANDO..." else "CERCA", color = TextWhite, fontWeight = FontWeight.Bold)
                }

                if (results.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(results) { res ->
                            TextButton(
                                onClick = { onPlaceSelected(res) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RectangleShape
                            ) {
                                Text(res.name, color = TextWhite, maxLines = 2, fontSize = 13.sp)
                            }
                            HorizontalDivider(color = Color(0x33FFFFFF))
                        }
                    }
                }
            } else {
                Text(place?.name ?: "Destinazione", color = TextWhite, fontWeight = FontWeight.Bold, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Distanza: %.1f km".format(route.distance / 1000), color = WazeCyan, fontWeight = FontWeight.Bold)
                    Text("Tempo: ${(route.duration / 60).roundToInt()} min", color = WazeGreen, fontWeight = FontWeight.Bold)
                }

                Button(onClick = onStart, Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = WazeGreen)) {
                    Text("AVVIA GUIDA", color = DarkBackground, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(onClick = onReset, Modifier.fillMaxWidth(), shape = RectangleShape) {
                    Text("ANNULLA", color = TextWhite)
                }
            }

            error?.let { Text(it, color = AlertAmber, fontSize = 12.sp) }
            Text("StradaSafe 0.8 · Grafica ibrida Waze & Apple", color = TextGray, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AppleWazeNavigationCard(
    step: Step?,
    distanceToStep: Double,
    speed: Int,
    remainingDistance: Double,
    remainingDuration: Double,
    onStop: () -> Unit
) {
    // Calcolo dell'Orario di Arrivo Stimato (ETA)
    val etaMillis = System.currentTimeMillis() + (remainingDuration * 1000).toLong()
    val etaFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
    val etaString = if (remainingDuration > 0) etaFormat.format(java.util.Date(etaMillis)) else "--:--"

    Surface(color = CardSurface, shape = RectangleShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Blocco Manovra Principale (Stile Apple/Waze)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0x33000000)).padding(10.dp)) {
                Surface(shape = RectangleShape, color = AppleBlue, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(getManifoldSymbol(step?.maneuver), fontSize = 34.sp, color = TextWhite)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (distanceToStep.isNaN()) "..." else "${distanceToStep.roundToInt()} m",
                        color = WazeCyan,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        step?.text ?: "Prosegui dritto",
                        color = TextWhite,
                        fontSize = 15.sp,
                        maxLines = 2,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            HorizontalDivider(color = Color(0x33FFFFFF))

            // Cruscotto inferiore in tempo reale (Waze style dinamico)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricDashboard("$speed", "KM/H", if (speed > 130) AlertAmber else WazeCyan)
                MetricDashboard("%.1f".format(remainingDistance / 1000), "KM", TextWhite)
                MetricDashboard(etaString, "ARRIVO", WazeGreen)
            }

            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertAmber)
            ) {
                Text("TERMINA VIAGGIO", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MetricDashboard(value: String, unit: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(44.4, 8.9))
                        .zoom(8.3)
                        .build()

                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                        s.addSource(GeoJsonSource("gps"))
                        s.addLayer(
                            CircleLayer("gps-l", "gps")
                                .withProperties(
                                    circleRadius(10f),
                                    circleColor("#0A84FF"),
                                    circleStrokeColor("#FFFFFF"),
                                    circleStrokeWidth(3f)
                                )
                        )

                        s.addSource(GeoJsonSource("dest"))
                        s.addLayer(
                            CircleLayer("dest-l", "dest")
                                .withProperties(
                                    circleRadius(9f),
                                    circleColor("#32D74B")
                                )
                        )

                        s.addSource(GeoJsonSource("route"))
                        s.addLayer(
                            LineLayer("route-l", "route")
                                .withProperties(
                                    lineColor("#00D2FF"),
                                    lineWidth(8f)
                                )
                        )

                        ready = true
                    }
                }
            }
        },
        update = { v ->
            if (ready) v.getMapAsync { m ->
                location?.let {
                    m.style?.getSourceAs<GeoJsonSource>("gps")
                        ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
                }

                place?.let {
                    m.style?.getSourceAs<GeoJsonSource>("dest")
                        ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)))
                }

                route?.let { r ->
                    m.style?.getSourceAs<GeoJsonSource>("route")
                        ?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.points)))

                    if (!follow && !fitted) {
                        val b = LatLngBounds.Builder()
                        r.points.forEach { b.include(LatLng(it.latitude(), it.longitude())) }
                        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100), 800)
                        fitted = true
                    }
                }

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
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
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
    val root = JSONObject(
        get("$ROUTE_BASE/route/v1/driving/${l.longitude},${l.latitude};${p.lon},${p.lat}?overview=full&geometries=geojson&steps=true")
    )

    if (root.optString("code") != "Ok") error("Percorso non disponibile")

    val r = root.getJSONArray("routes").getJSONObject(0)
    val c = r.getJSONObject("geometry").getJSONArray("coordinates")

    val pts = (0 until c.length()).map {
        val a = c.getJSONArray(it)
        Point.fromLngLat(a.getDouble(0), a.getDouble(1))
    }

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
                lat = loc.getDouble(1),
                lon = loc.getDouble(0),
                distance = s.optDouble("distance"),
                duration = s.optDouble("duration"),
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
        connectTimeout = 15000
        readTimeout = 20000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "StradaSafeLiguria/0.8 private prototype")
        setRequestProperty("Accept-Language", "it")
    }

    try {
        if (c.responseCode !in 200..299) error("Servizio non disponibile (${c.responseCode})")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

private fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(a)
    val p2 = Math.toRadians(c)
    val dp = Math.toRadians(c - a)
    val dl = Math.toRadians(d - b)
    val x = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * atan2(sqrt(x), sqrt(1 - x))
}

private fun distanceToPolyline(lat: Double, lon: Double, pts: List<Point>): Double =
    pts.minOfOrNull { distanceMeters(lat, lon, it.latitude(), it.longitude()) } ?: Double.MAX_VALUE
