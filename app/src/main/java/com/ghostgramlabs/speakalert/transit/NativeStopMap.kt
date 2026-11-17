package com.ghostgramlabs.speakalert.transit

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.delay

@Composable
internal fun NativeStopMap(
    stops: List<BusStop>, selectedId: String?, focus: StopMapPoint,
    modifier: Modifier, picked: (BusStop) -> Unit, centered: (StopMapPoint) -> Unit, status: (String) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestStops by rememberUpdatedState(stops)
    val latestPicked by rememberUpdatedState(picked)
    val latestCentered by rememberUpdatedState(centered)
    val latestStatus by rememberUpdatedState(status)
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    var cameraVersion by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val mapView = remember {
        runCatching {
            MapView(context, GoogleMapOptions().zoomControlsEnabled(true).mapToolbarEnabled(false))
                .apply { onCreate(Bundle()) }
        }.getOrNull()
    }
    if (mapView == null) {
        LaunchedEffect(Unit) { latestStatus("Map could not start. Use List or check map setup.") }
        Text("Map unavailable", modifier)
        return
    }

    DisposableEffect(mapView, owner) {
        var disposed = false
        var started = false
        var resumed = false
        fun start() { if (!started) { mapView.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { mapView.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        mapView.getMapAsync { ready ->
            if (disposed) return@getMapAsync
            map = ready
            ready.uiSettings.isMapToolbarEnabled = false
            ready.setOnMapLoadedCallback {
                loaded = true
                latestStatus("Google map loaded")
            }
            ready.setOnCameraIdleListener {
                val target = ready.cameraPosition.target
                latestCentered(StopMapPoint(target.latitude, target.longitude))
                cameraVersion++
            }
            ready.setOnMarkerClickListener { marker ->
                (marker.tag as? BusStop)?.let { latestPicked(it) }
                true
            }
            latestStatus("Loading Google map…")
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.latitude, focus.longitude), 16f))
        }
        onDispose {
            disposed = true
            owner.lifecycle.removeObserver(observer)
            map?.setOnMapLoadedCallback(null)
            map?.setOnCameraIdleListener(null)
            map?.setOnMarkerClickListener(null)
            stop()
            mapView.onDestroy()
            map = null
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
    LaunchedEffect(map, focus) {
        map?.let { ready ->
            val current = ready.cameraPosition
            if (current.target.latitude != focus.latitude || current.target.longitude != focus.longitude)
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.latitude, focus.longitude), maxOf(16f, current.zoom)))
        }
    }
    LaunchedEffect(map, cameraVersion, stops, selectedId) {
        val ready = map ?: return@LaunchedEffect
        runCatching {
            val bounds = ready.projection.visibleRegion.latLngBounds
            val target = ready.cameraPosition.target
            val center = StopMapPoint(target.latitude, target.longitude)
            val visible = latestStops.filter { stop ->
                stop.latitude != null && stop.longitude != null &&
                    bounds.contains(LatLng(stop.latitude, stop.longitude))
            }.sortedBy { StopPickerResults.distance(it, center) }
                .distinctBy { Pair(it.latitude, it.longitude) }.take(120)
            ready.clear()
            visible.forEach { stop ->
                ready.addMarker(MarkerOptions().position(LatLng(stop.latitude!!, stop.longitude!!))
                    .title(stop.name).snippet(stop.codeLabel)
                    .icon(BitmapDescriptorFactory.defaultMarker(
                        if (stop.id == selectedId) BitmapDescriptorFactory.HUE_RED else BitmapDescriptorFactory.HUE_AZURE)))
                    ?.tag = stop
            }
        }
    }
    LaunchedEffect(mapView) {
        delay(15_000)
        if (!loaded) latestStatus("Map tiles have not loaded. Check network and Maps SDK access, or use List.")
    }
}
