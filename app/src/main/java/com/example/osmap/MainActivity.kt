package com.example.osmap

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import retrofit2.http.GET
import retrofit2.http.Query
import android.util.Log
import org.osmdroid.views.overlay.Polyline
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapController: IMapController
    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private lateinit var locationOverlay: MyLocationNewOverlay

    companion object {
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", 0))
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.DEFAULT_TILE_SOURCE)
        mapView.setMultiTouchControls(true)
        mapView.minZoomLevel = 2.0
        mapView.maxZoomLevel = 19.0
        mapController = mapView.controller

        // Solicitar permisos de ubicación si no están concedidos
        if (!checkPermissions()) {
            requestPermissions()
        } else {
            setupMap()
        }

        mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                val projection = mapView?.projection
                val geoPoint = projection?.fromPixels(e!!.x.toInt(), e.y.toInt())
                if (geoPoint != null) {
                    if (startPoint == null) {
                        startPoint = geoPoint as GeoPoint?
                        addMarker(startPoint!!)
                    } else if (endPoint == null) {
                        endPoint = geoPoint as GeoPoint?
                        addMarker(endPoint!!)
                        calculateAndShowRoute(startPoint!!, endPoint!!)
                    }
                    return true
                }
                return false
            }
        })

        // Configurar el botón para ir a la ubicación del usuario
        val btnLocate: Button = findViewById(R.id.myLocationButton)
        btnLocate.setOnClickListener {
            if (locationOverlay.myLocation != null) {
                val userLocation = GeoPoint(locationOverlay.myLocation)
                mapController.setCenter(userLocation)
                mapController.setZoom(16.0)
            } else {
                Toast.makeText(this, "No se puede obtener la ubicación del usuario", Toast.LENGTH_SHORT).show()
            }
        }

        // Configurar el botón para reiniciar la selección de puntos
        val btnReset: Button = findViewById(R.id.resetButton)
        btnReset.setOnClickListener {
            resetPoints()
        }
    }

    private fun resetPoints() {
        startPoint = null
        endPoint = null
        // Mantener solo la capa de ubicación y eliminar otros overlays (marcadores, rutas, etc.)
        mapView.overlays.clear()
        mapView.overlays.add(locationOverlay)
        mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                val projection = mapView?.projection
                val geoPoint = projection?.fromPixels(e!!.x.toInt(), e.y.toInt())
                if (geoPoint != null) {
                    if (startPoint == null) {
                        startPoint = geoPoint as GeoPoint?
                        addMarker(startPoint!!)
                    } else if (endPoint == null) {
                        endPoint = geoPoint as GeoPoint?
                        addMarker(endPoint!!)
                        calculateAndShowRoute(startPoint!!, endPoint!!)
                    }
                    return true
                }
                return false
            }
        })
        mapView.invalidate()
        Toast.makeText(this, "Selección de puntos reiniciada", Toast.LENGTH_SHORT).show()
    }

    private fun addMarker(geoPoint: GeoPoint) {
        val marker = Marker(mapView)
        marker.position = geoPoint
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun calculateAndShowRoute(startPoint: GeoPoint, endPoint: GeoPoint) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://router.project-osrm.org")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(OSRMRoutingService::class.java)
        val coordinates = "${startPoint.longitude},${startPoint.latitude};${endPoint.longitude},${endPoint.latitude}"
        val call = service.getRoute(coordinates)

        call.enqueue(object : Callback<RouteResponse> {
            override fun onResponse(call: Call<RouteResponse>, response: Response<RouteResponse>) {
                val polyline = Polyline().apply {
                    width = 5f // Ancho de la polilínea
                    color = Color.RED // Color de la polilínea
                }
                if (response.isSuccessful) {
                    val routeResponse = response.body()

                    if (routeResponse != null && routeResponse.routes.isNotEmpty()) {
                        val geometry = routeResponse.routes[0].geometry
                        val pointsList = PolylineDecoder.decode(geometry)
                        polyline.setPoints(pointsList)
                        mapView.overlays.add(polyline)
                        mapView.invalidate()
                    }
                }
            }

            override fun onFailure(call: Call<RouteResponse>, t: Throwable) {
                Log.e("OSRM", "Error fetching route", t)
            }
        })
    }

    object PolylineDecoder {
        private const val PRECISION = 1E5 // Utilizado para decodificar la polilínea

        fun decode(polyline: String): List<GeoPoint> {
            val points = mutableListOf<GeoPoint>()
            var index = 0
            var lat = 0
            var lng = 0

            while (index < polyline.length) {
                var result = 1
                var shift = 0
                var b: Int
                do {
                    b = polyline[index++].toInt() - 63 - 1
                    result += b shl shift
                    shift += 5
                } while (b >= 0x1f)
                lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                result = 1
                shift = 0
                do {
                    b = polyline[index++].toInt() - 63 - 1
                    result += b shl shift
                    shift += 5
                } while (b >= 0x1f)
                lng += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                points.add(GeoPoint(lat / PRECISION, lng / PRECISION))
            }

            return points
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
    }

    private fun checkPermissions(): Boolean {
        val permissionState = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        return permissionState == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_PERMISSIONS_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupMap()
            } else {
                Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMap() {
        // Crear un nuevo overlay para la ubicación del usuario
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.enableMyLocation()
        mapView.overlays.add(locationOverlay)

        // Configurar un listener para detectar cuando se obtiene la ubicación del usuario
        locationOverlay.runOnFirstFix {
            runOnUiThread {
                // Obtener la ubicación del usuario después de que se ha fijado por primera vez
                val myLocation = locationOverlay.myLocation
                val startPoint = if (myLocation != null) {
                    // Si la ubicación del usuario está disponible, centrar el mapa en esa ubicación
                    GeoPoint(myLocation.latitude, myLocation.longitude)
                } else {
                    // Si la ubicación del usuario no está disponible, usar una ubicación predeterminada (por ejemplo, 0, 0)
                    GeoPoint(0.0, 0.0)
                }
                mapController.setCenter(startPoint)
                mapController.setZoom(16.0)
            }
        }
    }
}

interface OSRMRoutingService {
    @GET("/route/v1/driving/{coordinates}")
    fun getRoute(
        @retrofit2.http.Path("coordinates") coordinates: String,
        @Query("alternatives") alternatives: Boolean = false,
        @Query("geometries") geometries: String = "polyline",
        @Query("overview") overview: String = "full",
        @Query("steps") steps: Boolean = true,
        @Query("annotations") annotations: String = "true"
    ): Call<RouteResponse>
}

data class RouteResponse(
    val routes: List<Route>
)

data class Route(
    val geometry: String,
    val legs: List<Leg>
)

data class Leg(
    val steps: List<Step>
)

data class Step(
    val geometry: String
)
