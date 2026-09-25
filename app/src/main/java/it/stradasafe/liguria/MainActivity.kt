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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlin.math.roundToInt

private val Navy = Color(0xFF07131D)
private val Panel = Color(0xEB122431)
private val Cyan = Color(0xFF39D5FF)
private val Amber = Color(0xFFFFC857)

class MainActivity : ComponentActivity() {
    private lateinit var locationClient: FusedLocationProviderClient
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        setContent { StradaSafeApp(locationClient) }
    }
}

data class GpsState(val location: Location? = null, val active: Boolean = false)

@SuppressLint("MissingPermission")
@Composable
fun rememberGps(client: FusedLocationProviderClient, granted: Boolean): GpsState {
    var state by remember { mutableStateOf(GpsState()) }
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { state = GpsState(it, true) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).setMinUpdateDistanceMeters(2f).build()
        client.requestLocationUpdates(request, callback, null)
        onDispose { client.removeLocationUpdates(callback) }
    }
    return state
}

@Composable
fun StradaSafeApp(client: FusedLocationProviderClient) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gps = rememberGps(client, granted)
    var navigating by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    val colors = if (dark) darkColorScheme(primary = Cyan, background = Navy, surface = Panel) else lightColorScheme(primary = Color(0xFF006C84))

    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            MapPlaceholder(dark, gps.location)
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                TopBar(navigating, dark, { dark = !dark })
                if (navigating) {
                    NavigationPanel(gps, onStop = { navigating = false })
                } else {
                    SearchPanel(
                        value = destination,
                        onValue = { destination = it },
                        gpsGranted = granted,
                        onGps = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        onStart = { navigating = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(navigating: Boolean, dark: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel) {
            Text(if (navigating) "NAVIGAZIONE" else "STRADASAFE · LIGURIA", Modifier.padding(16.dp, 12.dp), fontWeight = FontWeight.Bold, color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = toggle) { Text(if (dark) "☀" else "☾", fontSize = 20.sp) }
            Spacer(Modifier.width(8.dp)); SpeedLimit(50)
        }
    }
}

@Composable
private fun MapPlaceholder(dark: Boolean, location: Location?) = Canvas(Modifier.fillMaxSize()) {
    drawRect(if (dark) Color(0xFF0B2230) else Color(0xFFDCECF1))
    val grid = if (dark) Color(0x2239D5FF) else Color(0x22006C84)
    for (x in 0..size.width.toInt() step 64) drawLine(grid, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1f)
    for (y in 0..size.height.toInt() step 64) drawLine(grid, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1f)
    val points = listOf(Offset(size.width*.08f,size.height*.72f),Offset(size.width*.32f,size.height*.53f),Offset(size.width*.58f,size.height*.58f),Offset(size.width*.90f,size.height*.25f))
    for (i in 0 until points.lastIndex) drawLine(Cyan, points[i], points[i+1], 14f)
    if (location != null) {
        drawCircle(Color.White, 24f, points[1]); drawCircle(Cyan, 15f, points[1])
    }
}

@Composable
private fun SpeedLimit(limit: Int) = Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(70.dp)) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFFE53935), style = Stroke(9f)) }
        Text("$limit", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 26.sp)
    }
}

@Composable
private fun SearchPanel(value: String, onValue: (String)->Unit, gpsGranted: Boolean, onGps:()->Unit, onStart:()->Unit) =
    Surface(shape = RoundedCornerShape(28.dp), color = Panel) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("Dove vuoi andare?", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
            OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), placeholder = { Text("Indirizzo o località") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Genova", "Savona", "La Spezia").forEach { city -> AssistChip(onClick = { onValue(city) }, label = { Text(city) }) }
            }
            if (!gpsGranted) OutlinedButton(onClick = onGps, modifier = Modifier.fillMaxWidth()) { Text("ATTIVA GPS") }
            Button(onClick = onStart, enabled = value.isNotBlank() && gpsGranted, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("AVVIA ANTEPRIMA") }
            Text("Alpha privata. La mappa e il percorso sono dimostrativi.", color = Amber, fontSize = 12.sp)
        }
    }

@Composable
private fun NavigationPanel(gps: GpsState, onStop:()->Unit) = Surface(shape = RoundedCornerShape(28.dp), color = Panel) {
    val speed = ((gps.location?.speed ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("↱", fontSize = 46.sp, color = Cyan); Spacer(Modifier.width(12.dp))
            Column { Text("Tra 350 m", color = Cyan, fontWeight = FontWeight.Bold); Text("Svolta a destra", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
        HorizontalDivider(color = Color(0x334DD8F5))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("$speed", "km/h GPS"); Metric("18 min", "arrivo 12:04"); Metric("6,8 km", "restanti")
        }
        val status = if (gps.active) "GPS attivo · precisione ${gps.location?.accuracy?.roundToInt() ?: 0} m" else "Ricerca del segnale GPS..."
        Text(status, color = if (gps.active) Cyan else Amber, fontSize = 13.sp)
        Surface(shape = RoundedCornerShape(15.dp), color = Color(0x33FFC857)) {
            Text("⚠ Avviso dimostrativo tra 800 m", Modifier.padding(14.dp), color = Amber, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("TERMINA") }
    }
}

@Composable
private fun Metric(a: String, b: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(a, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White); Text(b, fontSize = 11.sp, color = Color.LightGray)
}
