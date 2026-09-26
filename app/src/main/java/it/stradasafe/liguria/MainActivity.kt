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
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt

private val Navy=Color(0xFF07131D); private val Panel=Color(0xEE102635); private val Cyan=Color(0xFF39D5FF); private val Amber=Color(0xFFFFC857)
private const val STYLE_URL="https://tiles.openfreemap.org/styles/liberty"
private const val SEARCH_BASE="https://nominatim.openstreetmap.org"
private const val ROUTE_BASE="https://router.project-osrm.org"

class MainActivity:ComponentActivity(){
 private lateinit var client:FusedLocationProviderClient
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);MapLibre.getInstance(this);client=LocationServices.getFusedLocationProviderClient(this);setContent{App(client)}}
}
data class Gps(val location:Location?=null)
data class Place(val name:String,val lat:Double,val lon:Double)
data class Route(val points:List<Point>,val distance:Double,val duration:Double,val steps:List<String>)

@SuppressLint("MissingPermission") @Composable
private fun rememberGps(client:FusedLocationProviderClient,granted:Boolean):Gps{
 var state by remember{mutableStateOf(Gps())}
 DisposableEffect(granted){if(!granted)return@DisposableEffect onDispose{}
  val cb=object:LocationCallback(){override fun onLocationResult(r:LocationResult){r.lastLocation?.let{state=Gps(it)}}}
  val req=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,1000).setMinUpdateIntervalMillis(500).setMinUpdateDistanceMeters(2f).build()
  client.requestLocationUpdates(req,cb,null);onDispose{client.removeLocationUpdates(cb)}
 };return state
}

@Composable private fun App(client:FusedLocationProviderClient){
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var granted by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)}
 val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted=it};val gps=rememberGps(client,granted)
 var query by remember{mutableStateOf("")};var place by remember{mutableStateOf<Place?>(null)};var route by remember{mutableStateOf<Route?>(null)}
 var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var follow by remember{mutableStateOf(false)}

 MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,background=Navy,surface=Panel)){
  Box(Modifier.fillMaxSize().background(Navy)){
   NavMap(gps.location,place,route,follow)
   Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.SpaceBetween){
    Surface(color=Panel,shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(14.dp)){Text("STRADASAFE · 0.4",color=Color.White,fontSize=18.sp);Text(if(gps.location!=null)"GPS ${gps.location.accuracy.roundToInt()} m" else "GPS non disponibile",color=if(gps.location!=null)Cyan else Amber,fontSize=12.sp)}}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){FloatingActionButton(onClick={follow=!follow},containerColor=if(follow)Cyan else Panel){Text("⌖",fontSize=24.sp)}}
     Surface(color=Panel,shape=RoundedCornerShape(24.dp)){
      Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
       if(route==null){
        Text("Cerca una destinazione",color=Color.White,fontSize=20.sp)
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Via, città o luogo in Liguria")})
        if(!granted)Button(onClick={ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)},Modifier.fillMaxWidth()){Text("ATTIVA GPS")}
        Button(onClick={scope.launch{busy=true;error=null;try{place=searchPlace(query);val l=gps.location?:error("Attendi il GPS");route=fetchRoute(l,place!!)}catch(e:Exception){error=e.message?:"Errore"}finally{busy=false}}},enabled=query.length>2&&granted&&!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"CALCOLO..." else "CERCA E CALCOLA PERCORSO")}
       }else{
        val r=route!!;Text(place?.name?:"Destinazione",color=Color.White,fontSize=17.sp,maxLines=2)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("%.1f km".format(r.distance/1000),color=Cyan);Text("%d min".format((r.duration/60).roundToInt()),color=Cyan)}
        Text(r.steps.firstOrNull()?:"Percorso pronto",color=Color.White)
        Button(onClick={follow=true},Modifier.fillMaxWidth()){Text("AVVIA ANTEPRIMA")}
        OutlinedButton(onClick={route=null;place=null},Modifier.fillMaxWidth()){Text("NUOVA RICERCA")}
       }
       error?.let{Text(it,color=Color(0xFFFF6B6B),fontSize=12.sp)}
       Text("Ricerca e percorso online in questa alpha · © OpenStreetMap contributors",color=Color.LightGray,fontSize=10.sp)
      }
     }
    }
   }
  }
 }
}

@Composable private fun NavMap(location:Location?,place:Place?,route:Route?,follow:Boolean){
 val context=LocalContext.current;val mapView=remember{MapView(context)};var ready by remember{mutableStateOf(false)}
 AndroidView(factory={mapView.apply{onCreate(null);getMapAsync{map->map.cameraPosition=CameraPosition.Builder().target(LatLng(44.3,8.8)).zoom(8.3).build();map.setStyle(Style.Builder().fromUri(STYLE_URL)){s->
  s.addSource(GeoJsonSource("gps"));s.addLayer(CircleLayer("gps-layer","gps").withProperties(circleRadius(9f),circleColor("#39D5FF"),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
  s.addSource(GeoJsonSource("dest"));s.addLayer(CircleLayer("dest-layer","dest").withProperties(circleRadius(8f),circleColor("#FFC857"),circleStrokeColor("#07131D"),circleStrokeWidth(3f)))
  s.addSource(GeoJsonSource("route"));s.addLayer(LineLayer("route-layer","route").withProperties(lineColor("#39D5FF"),lineWidth(7f),lineOpacity(.9f)))
  ready=true
 }}}},update={v->if(ready)v.getMapAsync{m->
  location?.let{m.style?.getSourceAs<GeoJsonSource>("gps")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.longitude,it.latitude)))}
  place?.let{m.style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.lon,it.lat)))}
  route?.let{r->m.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.points)));if(!follow){val b=LatLngBounds.Builder();r.points.forEach{b.include(LatLng(it.latitude(),it.longitude()))};m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(),100),900)}}
  if(follow&&location!=null)m.cameraPosition=CameraPosition.Builder().target(LatLng(location.latitude,location.longitude)).zoom(16.5).bearing(if(location.hasBearing())location.bearing.toDouble() else 0.0).tilt(45.0).build()
 }},modifier=Modifier.fillMaxSize())
 DisposableEffect(mapView){mapView.onStart();mapView.onResume();onDispose{mapView.onPause();mapView.onStop();mapView.onDestroy()}}
}

private suspend fun searchPlace(q:String):Place=withContext(Dispatchers.IO){
 val encoded=URLEncoder.encode("$q, Liguria, Italia","UTF-8");val url="$SEARCH_BASE/search?q=$encoded&format=jsonv2&limit=1&countrycodes=it&viewbox=7.45,44.75,10.10,43.70&bounded=1"
 val arr=JSONArray(get(url));if(arr.length()==0)error("Destinazione non trovata");val o=arr.getJSONObject(0);Place(o.getString("display_name"),o.getString("lat").toDouble(),o.getString("lon").toDouble())
}
private suspend fun fetchRoute(l:Location,p:Place):Route=withContext(Dispatchers.IO){
 val url="$ROUTE_BASE/route/v1/driving/${l.longitude},${l.latitude};${p.lon},${p.lat}?overview=full&geometries=geojson&steps=true"
 val root=JSONObject(get(url));if(root.optString("code")!="Ok")error("Percorso non disponibile");val r=root.getJSONArray("routes").getJSONObject(0);val coords=r.getJSONObject("geometry").getJSONArray("coordinates");val pts=mutableListOf<Point>();for(i in 0 until coords.length()){val a=coords.getJSONArray(i);pts+=Point.fromLngLat(a.getDouble(0),a.getDouble(1))}
 val steps=mutableListOf<String>();val legs=r.getJSONArray("legs");for(i in 0 until legs.length()){val ss=legs.getJSONObject(i).getJSONArray("steps");for(j in 0 until ss.length()){val s=ss.getJSONObject(j);val name=s.optString("name");val type=s.getJSONObject("maneuver").optString("type");steps+=(if(name.isBlank())type else "$type · $name")}}
 Route(pts,r.getDouble("distance"),r.getDouble("duration"),steps)
}
private fun get(address:String):String{val c=(URL(address).openConnection() as HttpURLConnection).apply{connectTimeout=15000;readTimeout=20000;requestMethod="GET";setRequestProperty("User-Agent","StradaSafeLiguria/0.4 (private prototype)");setRequestProperty("Accept-Language","it")};try{if(c.responseCode !in 200..299)error("Servizio non disponibile (${c.responseCode})");return c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
