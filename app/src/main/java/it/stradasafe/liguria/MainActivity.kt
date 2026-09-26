package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

private val Navy = Color(0xFF07131D)
private val Panel = Color(0xEE102635)
private val Cyan = Color(0xFF39D5FF)
private val Amber = Color(0xFFFFC857)
private const val ONLINE_STYLE = "https://tiles.openfreemap.org/styles/liberty"

class MainActivity : ComponentActivity() {
    private lateinit var client: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        client = LocationServices.getFusedLocationProviderClient(this)
        setContent { StradaSafe03(client) }
    }
}

data class GpsState(val location: Location? = null, val active: Boolean = false)

@SuppressLint("MissingPermission")
@Composable
private fun rememberGps(client: FusedLocationProviderClient, granted: Boolean): GpsState {
    var state by remember { mutableStateOf(GpsState()) }
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { state = GpsState(it, true) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        client.requestLocationUpdates(request, callback, null)
        onDispose { client.removeLocationUpdates(callback) }
    }
    return state
}

@Composable
private fun StradaSafe03(client: FusedLocationProviderClient) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gps = rememberGps(client, granted)
    var follow by remember { mutableStateOf(true) }
    var darkUi by remember { mutableStateOf(true) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Navy)) {
            RealMap(gps.location, follow)

            Column(
                Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("STRADASAFE · LIGURIA", color = Color.White, fontSize = 18.sp)
                            Text(
                                if (gps.active) "GPS attivo · precisione ${gps.location?.accuracy?.roundToInt()} m" else "Attiva la posizione",
                                color = if (gps.active) Cyan else Amber,
                                fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = { darkUi = !darkUi }) { Text(if (darkUi) "GIORNO" else "NOTTE") }
                    }
                }

                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    FloatingActionButton(onClick = { follow = !follow }, containerColor = if (follow) Cyan else Panel) {
                        Text("⌖", fontSize = 25.sp, color = if (follow) Navy else Color.White)
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val speed = ((gps.location?.speed ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)
                            Text("$speed", color = Color.White, fontSize = 34.sp)
                            Text("km/h", color = Cyan, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Mappa reale della Liguria", color = Color.White, fontSize = 20.sp)
                            Text("La cartografia viene caricata online in questa alpha. Il supporto al pacchetto locale PMTiles è predisposto per il prossimo aggiornamento dati.", color = Color.LightGray, fontSize = 13.sp)
                            if (!granted) {
                                Button(onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("ATTIVA GPS")
                                }
                            } else {
                                Text("Trascina la mappa per esplorare. Premi ⌖ per seguire nuovamente la posizione.", color = Cyan, fontSize = 13.sp)
                            }
                            Text("© OpenStreetMap contributors · mappa OpenFreeMap", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RealMap(location: Location?, follow: Boolean) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    var initialized by remember { mutableStateOf(false) }

    AndroidView(
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { map ->
                    map.uiSettings.isLogoEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(44.4072, 8.9340))
                        .zoom(9.0)
                        .build()
                    map.setStyle(Style.Builder().fromUri(ONLINE_STYLE)) { style ->
                        style.addSource(GeoJsonSource("gps-source"))
                        style.addLayer(
                            CircleLayer("gps-layer", "gps-source").withProperties(
                                circleRadius(9f),
                                circleColor("#39D5FF"),
                                circleStrokeColor("#FFFFFF"),
                                circleStrokeWidth(3f)
                            )
                        )
                        initialized = true
                    }
                }
            }
        },
        update = { view ->
            if (initialized && location != null) {
                view.getMapAsync { map ->
                    map.style?.getSourceAs<GeoJsonSource>("gps-source")
                        ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)))
                    if (follow) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(location.latitude, location.longitude))
                            .zoom(16.0)
                            .bearing(if (location.hasBearing()) location.bearing.toDouble() else 0.0)
                            .tilt(45.0)
                            .build()
                    }
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
