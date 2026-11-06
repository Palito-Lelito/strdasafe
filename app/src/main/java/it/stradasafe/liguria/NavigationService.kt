package it.stradasafe.liguria

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

/** Bus per condividere la posizione dal Service alla UI */
object LocationBus {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    internal fun update(loc: Location) { _location.value = loc }
    fun current(): Location? = _location.value
}

/** Stato di navigazione condiviso Service <-> UI */
object NavState {
    val navigating = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val currentStepIdx = MutableStateFlow(0)
    val isSimulating = MutableStateFlow(false)
    val isInTutor = MutableStateFlow(false)
    val tutorAvgSpeed = MutableStateFlow(0f)
    val speedLimit = MutableStateFlow<Int?>(null)

    @Volatile var steps: List<Step> = emptyList()
    @Volatile var routePoints: List<org.maplibre.geojson.Point> = emptyList()
    @Volatile var activeDevices: List<SafetyDevice> = emptyList()
    @Volatile var alertedIds: MutableSet<String> = mutableSetOf()
    @Volatile var destLat: Double? = null
    @Volatile var destLon: Double? = null
    @Volatile var avoidTolls: Boolean = false
    @Volatile var avoidHighways: Boolean = false
}

class NavigationService : Service() {

    companion object {
        private var instance: NavigationService? = null
        fun speakNow(text: String) {
            instance?.speak(text, flush = true)
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastKnownLocation: Location? = null
    private var lastGoodLocation: Location? = null
    private var lastSimCheck = 0L
    private var lastSpeedLimitCheck = 0L
    private var lastTutorCheck = 0L
    private var tutorStartTime = 0L
    private var tutorStartDistanceRemaining = 0.0
    private var lastSpokenStep = -1
    private var lastReroute = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fused = LocationServices.getFusedLocationProviderClient(this)
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        requestLocation()
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "stradasafe_nav"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Navigazione", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Navigazione in corso"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("StradaSafe 1.1")
            .setContentText("Navigazione in corso")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notif)
        }
    }

    @Suppress("MissingPermission")
    private fun requestLocation() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleLocation(it) }
            }
        }
        fused.requestLocationUpdates(req, cb, null)
        scope.launch { navigationLoop() }
    }

    private fun handleLocation(newLoc: Location) {
        if (newLoc.accuracy >= 40f) return
        val prev = lastKnownLocation
        if (prev != null) {
            val dt = (newLoc.time - prev.time) / 1000.0
            if (dt > 0 && (prev.distanceTo(newLoc) / dt) > 55.0) return // salto teletrasporto
        }
        lastKnownLocation = newLoc
        lastGoodLocation = newLoc
        LocationBus.update(newLoc)
    }

    private suspend fun navigationLoop() {
        while (true) {
            delay(1000)
            if (!NavState.navigating.value) continue
            val gps = LocationBus.current() ?: continue

            // ---- Simulazione galleria ----
            val lastGood = lastGoodLocation
            val since = System.currentTimeMillis() - (lastGood?.time ?: 0L)
            if (since > 12000 && gps.speed > 3f && lastGood != null) {
                val sim = simulateMovement(lastGood, 1000L)
                sim.time = System.currentTimeMillis()
                lastKnownLocation = sim
                LocationBus.update(sim)
                NavState.isSimulating.value = true
            } else {
                NavState.isSimulating.value = false
            }

            val effective = LocationBus.current() ?: continue

            // ---- Speed limit ogni 20s ----
            val now = System.currentTimeMillis()
            if (now - lastSpeedLimitCheck > 20000 && !NavState.isSimulating.value) {
                lastSpeedLimitCheck = now
                scope.launch {
                    fetchSpeedLimit(effective.latitude, effective.longitude)?.let {
                        NavState.speedLimit.value = it
                    }
                }
            }

            // ---- Step index ----
            val steps = NavState.steps
            val idx = NavState.currentStepIdx.value
            if (steps.isNotEmpty() && idx < steps.lastIndex) {
                val s = steps[idx]
                val d = distanceMeters(effective.latitude, effective.longitude, s.lat, s.lon)
                if (d < 30.0) {
                    NavState.currentStepIdx.value = idx + 1
                    lastSpokenStep = idx + 1
                }
            }

            // ---- TTS istruzione corrente ----
            val currentIdx = NavState.currentStepIdx.value
            if (currentIdx in steps.indices && lastSpokenStep != currentIdx && !NavState.muted.value) {
                val s = steps[currentIdx]
                val d = distanceMeters(effective.latitude, effective.longitude, s.lat, s.lon).roundToInt()
                val prefix = when {
                    d > 500 -> "Tra ${d / 1000} chilometri, "
                    d > 100 -> "Tra $d metri, "
                    else -> ""
                }
                speak(prefix + s.text)
                lastSpokenStep = currentIdx
            }

            // ---- Safety devices ----
            for (dev in NavState.activeDevices) {
                if (dev.id in NavState.alertedIds) continue
                val d = distanceMeters(effective.latitude, effective.longitude, dev.lat, dev.lon)
                if (d < 300.0) {
                    NavState.alertedIds.add(dev.id)
                    if (!NavState.muted.value) {
                        if (dev.type.lowercase().contains("tutor")) {
                            NavState.isInTutor.value = true
                            tutorStartTime = now
                            tutorStartDistanceRemaining =
                                NavState.routePoints.lastOrNull()?.let {
                                    distanceMeters(effective.latitude, effective.longitude, it.latitude(), it.longitude())
                                } ?: 0.0
                            speak("Inizio zona Tutor")
                        } else {
                            speak("Attenzione, ${dev.type} tra ${d.roundToInt()} metri")
                        }
                    }
                }
            }

            // ---- Calcolo media Tutor ----
            if (NavState.isInTutor.value) {
                val elapsed = (now - tutorStartTime) / 1000.0
                if (elapsed > 5.0) {
                    val done = tutorStartDistanceRemaining -
                        (NavState.routePoints.lastOrNull()?.let {
                            distanceMeters(effective.latitude, effective.longitude, it.latitude(), it.longitude())
                        } ?: 0.0)
                    val avg = (done / elapsed) * 3.6
                    NavState.tutorAvgSpeed.value = avg.toFloat()
                }
                // Fine Tutor quando esci dal raggio di 500m
                val stillNear = NavState.activeDevices.any {
                    it.type.lowercase().contains("tutor") &&
                        distanceMeters(effective.latitude, effective.longitude, it.lat, it.lon) < 500.0
                }
                if (!stillNear) {
                    NavState.isInTutor.value = false
                    if (!NavState.muted.value) speak("Fine zona Tutor")
                }
            }

            // ---- Reroute ----
            val offRoute = distanceToPolyline(effective.latitude, effective.longitude, NavState.routePoints) > 80.0
            if (offRoute && !NavState.isSimulating.value && effective.accuracy < 30f &&
                now - lastReroute > 15000) {
                lastReroute = now
                if (!NavState.muted.value) speak("Ricalcolo in corso")
                // Il ricalcolo vero avviene in MainActivity che osserva NavState; per semplicità qui solo segnalazione
            }
        }
    }

    private fun simulateMovement(last: Location, dtMs: Long): Location {
        val speed = max(0f, last.speed)
        val dist = speed * (dtMs / 1000f)
        val r = 6371000.0
        val lat1 = Math.toRadians(last.latitude)
        val lon1 = Math.toRadians(last.longitude)
        val brng = Math.toRadians(last.bearing.toDouble())
        val lat2 = asin(sin(lat1) * cos(dist / r) + cos(lat1) * sin(dist / r) * cos(brng))
        val lon2 = lon1 + atan2(
            sin(brng) * sin(dist / r) * cos(lat1),
            cos(dist / r) - sin(lat1) * sin(lat2)
        )
        return Location(last).apply {
            latitude = Math.toDegrees(lat2)
            longitude = Math.toDegrees(lon2)
            provider = "simulated"
            speed = last.speed
            bearing = last.bearing
        }
    }

    private suspend fun fetchSpeedLimit(lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
        try {
            val q = "[out:json][timeout:3];way(around:20,$lat,$lon)[\"maxspeed\"];out tags 1;"
            val enc = URLEncoder.encode(q, "UTF-8")
            val c = (URL("https://overpass-api.de/api/interpreter?data=$enc").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
                setRequestProperty("User-Agent", "StradaSafe/1.1")
            }
            val res = c.inputStream.bufferedReader().use { it.readText() }
            val els = JSONObject(res).optJSONArray("elements") ?: return@withContext null
            for (i in 0 until els.length()) {
                val ms = els.getJSONObject(i).optJSONObject("tags")?.optString("maxspeed") ?: continue
                if (ms.isNotEmpty()) {
                    return@withContext when (ms) {
                        "IT:urban" -> 50
                        "IT:rural" -> 90
                        "IT:motorway" -> 130
                        "IT:trunk" -> 110
                        else -> ms.toIntOrNull()
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ITALIAN
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            }
        }
    }

    private fun speak(text: String, flush: Boolean = true) {
        if (!ttsReady || NavState.muted.value) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "ss_${System.currentTimeMillis()}")
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.stop(); tts?.shutdown()
        instance = null
        super.onDestroy()
    }
}

// ---- Utility geometriche ----

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
    val x = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * atan2(sqrt(x), sqrt(1 - x))
}

fun distanceToPolyline(lat: Double, lon: Double, pts: List<org.maplibre.geojson.Point>): Double {
    if (pts.isEmpty()) return Double.MAX_VALUE
    var minD = Double.MAX_VALUE
    val cosLat = cos(Math.toRadians(lat))
    for (i in 0 until pts.size - 1) {
        val p1 = pts[i]; val p2 = pts[i + 1]
        val x0 = lon * cosLat; val y0 = lat
        val x1 = p1.longitude() * cosLat; val y1 = p1.latitude()
        val x2 = p2.longitude() * cosLat; val y2 = p2.latitude()
        val dx = x2 - x1; val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) continue
        val t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy)
        val cx = when { t < 0 -> x1; t > 1 -> x2; else -> x1 + t * dx }
        val cy = when { t < 0 -> y1; t > 1 -> y2; else -> y1 + t * dy }
        val d = distanceMeters(lat, lon, cy, cx / cosLat)
        if (d < minD) minD = d
    }
    return minD
}
