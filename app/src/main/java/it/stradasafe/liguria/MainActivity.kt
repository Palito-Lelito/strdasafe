package it.stradasafe.liguria

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import it.stradasafe.liguria.ui.ThemeMode
import it.stradasafe.liguria.ui.LiguriaTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre

// COSTANTI API
const val ORS_API_KEY = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImZjZDcxY2MyNmJiNjQwYzU4OTIzZDY0ZGE2MDEyMWJiIiwiaCI6Im11cm11cjY0In0="
const val SEARCH_BASE = "https://nominatim.openstreetmap.org"

class MainActivity : ComponentActivity() {
    private lateinit var client: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        client = LocationServices.getFusedLocationProviderClient(this)
        setContent { App(client) }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun App(client: FusedLocationProviderClient) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    
    // Leggiamo lo stato globale dal tuo NavState e AppState separati
    val isNavigating by NavState.navigating.collectAsState()
    var isSimulating by remember { mutableStateOf(false) }

    DisposableEffect(granted) {
        if (granted) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500).build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(res: LocationResult) {
                    res.lastLocation?.let { LocationBus.update(it) }
                }
            }
            client.requestLocationUpdates(req, cb, null)
            onDispose { client.removeLocationUpdates(cb) }
        } else {
            onDispose {}
        }
    }

    LiguriaTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Mappa
            Box(Modifier.fillMaxSize()) {
                // Inserire qui il tuo nuovo NavMap component refattorizzato
                // NavMap(...)
            }

            if (isSimulating) {
                Surface(color = Color(0xFFFF9F0A), modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 24.dp)) {
                    Text("GPS PERSO - SIMULAZIONE IN CORSO", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                }
            }
            
            // Interfaccia
            if (!granted) {
                Button(onClick = { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                    Text("Attiva GPS")
                }
            }
        }
    }
}
