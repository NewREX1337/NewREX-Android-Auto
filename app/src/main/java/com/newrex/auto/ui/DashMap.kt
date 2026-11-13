// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
// Araç Göstergesi uygulamasındaki (aynı geliştirici) haritadan sadeleştirilerek uyarlandı.
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.newrex.auto.DriveState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

/**
 * Göstergenin ortasındaki harita: OpenFreeMap'in ücretsiz vektör haritası, yazısız gece stili. Konum işareti alt
 * ortada sabit durur, harita gidiş yönüne döner ve konumlar arası yumuşakça kayar; bu yolculukta geçilen yol tema
 * renginde iz bırakır. Harita bilgisi internetten gelir; konum hiçbir yere gönderilmez (sadece harita parçaları
 * indirilir).
 */
class DashMap(context: Context) : FrameLayout(context) {
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var lastFix: Location? = null
    private var lastDest: Triple<Double, Double, String>? = null
    private var bearing = 0.0
    private var destroyed = false
    private val trail = ArrayList<DoubleArray>()
    private val handler = Handler(Looper.getMainLooper())
    private val overlay = Marker(context)

    /** Kullanıcı haritayı elle kaydırınca bu ana kadar takip durur ("konumuma dön" ya da 30 sn sonra döner). */
    private var freeUntil = 0L
    /** Kullanıcının seçtiği yakınlık; null ise hıza göre otomatik. */
    private var userZoom: Double? = null
    private val dd = context.resources.displayMetrics.density
    private val recenterBtn = context.circleButton(com.newrex.auto.R.drawable.ic_place, 44, bg = 0xE6101218.toInt(), tint = gaugeAccent()) { recenter() }
        .apply { visibility = View.GONE }
    private val controls = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        val s = (44 * dd).toInt()
        addView(context.circleButton(com.newrex.auto.R.drawable.ic_add, 44, bg = 0xE6101218.toInt()) { zoomBy(1.0) }, lp(s, s))
        addView(context.circleButton(com.newrex.auto.R.drawable.ic_remove, 44, bg = 0xE6101218.toInt()) { zoomBy(-1.0) }, lp(s, s).margins(t = (10 * dd).toInt()))
        addView(recenterBtn, lp(s, s).margins(t = (10 * dd).toInt()))
    }
    private val freeEnd = Runnable { recenter() }

    private fun following() = android.os.SystemClock.uptimeMillis() >= freeUntil

    private fun setFree(free: Boolean) {
        handler.removeCallbacks(freeEnd)
        if (free) {
            freeUntil = android.os.SystemClock.uptimeMillis() + 30_000
            handler.postDelayed(freeEnd, 30_000)
        } else freeUntil = 0L
        recenterBtn.visibility = if (free) View.VISIBLE else View.GONE
        if (overlay.free != free) { overlay.free = free; overlay.invalidate() }
        invalidate()
    }

    private fun recenter() {
        setFree(false)
        lastFix = null
        follow()
    }

    /** + / − tuşları ve araç ekranında iki parmakla büyütme. */
    fun zoomBy(dz: Double) {
        val m = map ?: return
        val z = (m.cameraPosition.zoom + dz).coerceIn(4.0, 19.0)
        if (following()) userZoom = z
        m.easeCamera(CameraUpdateFactory.zoomTo(z), 300)
    }

    // Harita üstündeki kaydırma haritanındır: sayfa (yolculuk geçmişine geçiş) burada başlamasın
    override fun canScrollVertically(direction: Int) = map != null
    override fun canScrollHorizontally(direction: Int) = map != null

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val handled = super.dispatchTouchEvent(e)
        // Haritanın kendisi kaydırıldıysa (tuşlar değil) takip bir süre durur
        if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && map != null) {
            val onControls = e.x >= controls.left && e.x <= controls.right && e.y >= controls.top && e.y <= controls.bottom
            if (!onControls) setFree(true)
        }
        return handled
    }

    // Telefondan paylaşılan hedef: üstte ad ve kalan mesafe, çarpıyla silinir
    private val destText = context.label("", 16f, bold = true)
    private val destLabel = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val dd = context.resources.displayMetrics.density
        setPadding((14 * dd).toInt(), (4 * dd).toInt(), (4 * dd).toInt(), (4 * dd).toInt())
        background = rounded(0xCC101218.toInt(), 18 * dd, (1 * dd).toInt(), Palette.withAlpha(gaugeAccent(), 0x88))
        addView(context.icon(com.newrex.auto.R.drawable.ic_place, 16, gaugeAccent()), lp((16 * dd).toInt(), (16 * dd).toInt()).margins(r = (8 * dd).toInt()))
        addView(destText)
        addView(context.circleButton(com.newrex.auto.R.drawable.ic_close, 28, bg = 0x26FFFFFF) { com.newrex.auto.data.Prefs.clearDestination() },
            lp((28 * dd).toInt(), (28 * dd).toInt()).margins(l = (10 * dd).toInt()))
        visibility = View.GONE
    }
    private val tick = object : Runnable {
        override fun run() { follow(); handler.postDelayed(this, 1000) }
    }

    init {
        MapLibre.getInstance(context.applicationContext)
        val opts = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true).translucentTextureSurface(true).attributionEnabled(false).logoEnabled(false).compassEnabled(false)
        mapView = MapView(context, opts)
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply { rightMargin = (14 * context.resources.displayMetrics.density).toInt() })
        addView(destLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = (44 * resources.displayMetrics.density).toInt() })
        mapView.onCreate(null)
        // Araç ekranında 30 kare/sn yeterli; telefonun işlemcisi ve pili korunur
        runCatching { mapView.setMaximumFps(30) }
        mapView.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            map = m
            m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isScrollGesturesEnabled = true
            m.uiSettings.isZoomGesturesEnabled = true
            m.uiSettings.isDoubleTapGesturesEnabled = true
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromJson(styleJson())) { st -> style = st; follow(force = true) }
        }
    }

    // Kenarlar arka plana yumuşakça karışsın (kutu gibi durmasın): harita bir katmanda çizilip kenarları saydamlaşır
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    init { setLayerType(View.LAYER_TYPE_HARDWARE, null) }

    private var maskX: Shader? = null
    private var maskY: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        maskX = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(0, 0xFF000000.toInt(), 0xFF000000.toInt(), 0),
            floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        maskY = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0, 0xFF000000.toInt(), 0xFF000000.toInt(), 0),
            floatArrayOf(0f, 0.3f, 0.8f, 1f), Shader.TileMode.CLAMP)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        maskPaint.shader = maskX
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        maskPaint.shader = maskY
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        // Hedef etiketi soluklaşmasın: maskeden sonra yeniden çizilir
        if (destLabel.visibility == View.VISIBLE) drawChild(canvas, destLabel, drawingTime)
        drawChild(canvas, controls, drawingTime)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (destroyed) return
        mapView.onStart(); mapView.onResume()
        handler.post(tick)
        pinch = { f -> zoomBy(kotlin.math.ln(f.toDouble()) / kotlin.math.ln(2.0)) }
        MapGestures.onScale = pinch
    }

    private var pinch: ((Float) -> Unit)? = null

    override fun onDetachedFromWindow() {
        if (MapGestures.onScale === pinch) MapGestures.onScale = null
        handler.removeCallbacks(freeEnd)
        handler.removeCallbacks(tick)
        if (!destroyed) { mapView.onPause(); mapView.onStop() }
        super.onDetachedFromWindow()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacks(tick)
        map = null; style = null
        runCatching { mapView.onDestroy() }
    }

    private fun follow(force: Boolean = false) {
        val m = map ?: return
        if (height == 0) return
        val fix = DriveState.lastLocation ?: DriveState.bestLocation(context)
        val dest = com.newrex.auto.data.Prefs.destination()
        if (!force && fix === lastFix && dest == lastDest) return
        lastDest = dest
        if (overlay.hasFix != (fix != null)) { overlay.hasFix = fix != null; overlay.invalidate() }
        DriveState.bearing.value?.let { bearing = it.toDouble() }
        showDestination(fix)
        if (!force && fix === lastFix) return
        lastFix = fix
        if (fix != null) style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)))
        if (fix != null) {
            val prev = trail.lastOrNull()
            if (prev == null || distance(prev, fix) > 8f) {
                trail += doubleArrayOf(fix.longitude, fix.latitude)
                if (trail.size > 400) trail.removeAt(0)
                style?.getSourceAs<GeoJsonSource>("trail")?.let { src ->
                    if (trail.size >= 2) src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(trail.map { Point.fromLngLat(it[0], it[1]) })))
                }
            }
        }
        val speed = DriveState.speedKmh.value ?: 0f
        if (!force && !following()) return
        val zoom = if (fix == null) 5.0 else userZoom ?: when { speed < 35 -> 15.6; speed < 80 -> 15.0; else -> 14.4 }
        val cam = CameraPosition.Builder()
            .target(if (fix != null) LatLng(fix.latitude, fix.longitude) else LatLng(39.0, 35.2))
            .zoom(zoom)
            .bearing(if (fix != null) bearing else 0.0)
            .tilt(if (fix != null) 45.0 else 0.0)
            .padding(0.0, height * 0.36, 0.0, 0.0)
            .build()
        if (!force) m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 1000, false)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    private var destKey: Triple<Double, Double, String>? = null

    // ---- Yol üzerinden rota (OSRM, ücretsiz açık kaynak rota servisi; sadece başlangıç ve hedef gönderilir) ----

    private class Route(val points: List<Point>, val meters: Double, val seconds: Double, val dest: Triple<Double, Double, String>,
        val delay: Double = 0.0, val traffic: Boolean = false)
    @Volatile private var route: Route? = null
    @Volatile private var routing = false
    private var routeAt = 0L
    private val routeHttp by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Rota yoksa, hedef değiştiyse ya da araç rotadan 120 m'den fazla saptıysa (en sık 20 sn'de bir) yeniden hesaplar. */
    private fun ensureRoute(fix: Location, dest: Triple<Double, Double, String>) {
        val r = route
        val now = android.os.SystemClock.uptimeMillis()
        val off = r != null && r.dest == dest && offRoute(fix, r.points) > 120f
        // Trafikli rotada varış süresi 5 dakikada bir tazelenir (trafik değişir)
        val stale = r != null && r.traffic && now - routeAt > 300_000
        if (routing || (r != null && r.dest == dest && !off && !stale) || now - routeAt < 20_000 && r?.dest == dest) return
        routing = true
        routeAt = now
        Thread {
            val tt = com.newrex.auto.data.TomTom.route(fix.latitude, fix.longitude, dest.first, dest.second)?.let { t ->
                Route(t.points.map { Point.fromLngLat(it[0], it[1]) }, t.meters, t.seconds, dest, t.delaySeconds, traffic = true)
            }
            val url = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                fix.longitude, fix.latitude, dest.second, dest.first)
            val result = tt ?: runCatching {
                routeHttp.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "NewREX-Android-Auto").build()).execute().use { resp ->
                    val o = org.json.JSONObject(resp.body!!.string()).getJSONArray("routes").getJSONObject(0)
                    val coords = o.getJSONObject("geometry").getJSONArray("coordinates")
                    val pts = (0 until coords.length()).map { val c = coords.getJSONArray(it); Point.fromLngLat(c.getDouble(0), c.getDouble(1)) }
                    Route(pts, o.getDouble("distance"), o.getDouble("duration"), dest)
                }
            }.getOrNull()
            handler.post {
                routing = false
                if (result != null && com.newrex.auto.data.Prefs.destination() == dest) { route = result; showDestination(lastFix) }
            }
        }.start()
    }

    private fun offRoute(fix: Location, pts: List<Point>): Float {
        var best = Float.MAX_VALUE
        // Kabaca: en yakın rota noktasına uzaklık
        for (i in pts.indices step 2) {
            val d = fastDist(fix.latitude, fix.longitude, pts[i].latitude(), pts[i].longitude())
            if (d < best) best = d
        }
        return best
    }

    /** Hedef işareti, hedefe kesikli çizgi ve üstteki etiket. */
    private fun showDestination(fix: Location?) {
        val st = style ?: return
        val dest = com.newrex.auto.data.Prefs.destination()
        val pointSrc = st.getSourceAs<GeoJsonSource>("dest") ?: return
        val lineSrc = st.getSourceAs<GeoJsonSource>("destline") ?: return
        if (dest == null) {
            if (destKey != null) {
                pointSrc.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                lineSrc.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                route = null
                destKey = null
            }
            destLabel.visibility = View.GONE
            return
        }
        val (lat, lng, name) = dest
        if (dest != destKey) {
            pointSrc.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lng, lat)))
            destKey = dest
        }
        val title = name.ifBlank { com.newrex.auto.t("Hedef") }
        if (fix != null) {
            ensureRoute(fix, dest)
            val rt = route?.takeIf { it.dest == dest }
            val routeSrc = st.getSourceAs<GeoJsonSource>("route")
            if (rt != null) {
                // Yol üzerinden rota: sadece aracın önünde kalan kısmı çizilir
                var nearest = 0; var bestD = Float.MAX_VALUE
                rt.points.forEachIndexed { i, p ->
                    val dd = fastDist(fix.latitude, fix.longitude, p.latitude(), p.longitude())
                    if (dd < bestD) { bestD = dd; nearest = i }
                }
                val ahead = listOf(Point.fromLngLat(fix.longitude, fix.latitude)) + rt.points.drop(nearest)
                routeSrc?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(ahead)))
                lineSrc.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                // Kalan yol, toplamın oranıyla
                var left = 0.0
                for (i in 0 until ahead.size - 1) {
                    left += fastDist(ahead[i].latitude(), ahead[i].longitude(), ahead[i + 1].latitude(), ahead[i + 1].longitude())
                }
                val mins = (rt.seconds * (left / rt.meters.coerceAtLeast(1.0)) / 60).toInt().coerceAtLeast(1)
                val late = (rt.delay * (left / rt.meters.coerceAtLeast(1.0)) / 60).toInt()
                destText.text = "$title  ·  ${fmtDist(left.toFloat())}  ·  ${com.newrex.auto.t("{0} dk", mins)}" +
                    if (late >= 1) "  ·  " + com.newrex.auto.t("trafik +{0} dk", late) else ""
            } else {
                routeSrc?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                lineSrc.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(fix.longitude, fix.latitude), Point.fromLngLat(lng, lat)))))
                val r = FloatArray(1)
                Location.distanceBetween(fix.latitude, fix.longitude, lat, lng, r)
                destText.text = "$title  ·  ${fmtDist(r[0])}"
            }
        } else destText.text = title
        destLabel.visibility = View.VISIBLE
    }

    /** Kısa mesafeler için hızlı yaklaşık uzaklık (metre); rota döngülerinde her noktada ağır hesap yapılmasın. */
    private fun fastDist(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val x = Math.toRadians(lng2 - lng1) * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return (kotlin.math.sqrt(x * x + y * y) * 6_371_000.0).toFloat()
    }

    private fun fmtDist(m: Float) = if (m < 1000f) "${(m / 10).toInt() * 10} m" else String.format(com.newrex.auto.I18n.locale(), "%.1f km", m / 1000f)

    private fun distance(a: DoubleArray, b: Location): Float {
        val r = FloatArray(1)
        Location.distanceBetween(a[1], a[0], b.latitude, b.longitude, r)
        return r[0]
    }

    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    private fun styleJson(): String {
        val acc = hex(gaugeAccent())
        val tt = com.newrex.auto.data.TomTom
        val trafficSrc = if (tt.has) """,
           "traffic":{"type":"raster","tiles":["${tt.trafficTiles()}"],"tileSize":256,"minzoom":6,"maxzoom":22}""" else ""
        val trafficLayer = if (tt.has) """{"id":"traffic","type":"raster","source":"traffic","paint":{"raster-opacity":0.9}},
           """ else ""
        val roads = """["match",["get","class"],["motorway","trunk","primary","secondary","tertiary","minor","service"],true,false]"""
        fun width(extra: Double) = """["interpolate",["exponential",1.6],["zoom"],
            12,["match",["get","class"],["motorway","trunk"],${3.0 + extra},"primary",${2.4 + extra},["secondary","tertiary"],${1.8 + extra},${0.9 + extra}],
            18,["match",["get","class"],["motorway","trunk"],${34 + extra * 3},"primary",${30 + extra * 3},["secondary","tertiary"],${26 + extra * 3},${18 + extra * 3}]]"""
        return """
        {"version":8,
         "sources":{
           "omt":{"type":"vector","url":"https://tiles.openfreemap.org/planet"},
           "trail":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}},
           "dest":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}},
           "destline":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}},
           "route":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}},
           "me":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}$trafficSrc
         },
         "layers":[
           {"id":"bg","type":"background","paint":{"background-color":"#0C0E16","background-opacity":0.55}},
           {"id":"park","type":"fill","source":"omt","source-layer":"park","paint":{"fill-color":"#111A17"}},
           {"id":"water","type":"fill","source":"omt","source-layer":"water","paint":{"fill-color":"#0A0D18"}},
           {"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":14,"paint":{"fill-color":"#141726"}},
           {"id":"road-case","type":"line","source":"omt","source-layer":"transportation","filter":$roads,
            "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"#1E2235","line-width":${width(2.0)}}},
           {"id":"road","type":"line","source":"omt","source-layer":"transportation","filter":$roads,
            "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"#3A3F58","line-width":${width(0.0)}}},
           $trafficLayer{"id":"trail-glow","type":"line","source":"trail","layout":{"line-cap":"round","line-join":"round"},
            "paint":{"line-color":"$acc","line-width":16,"line-opacity":0.2,"line-blur":4}},
           {"id":"trail","type":"line","source":"trail","layout":{"line-cap":"round","line-join":"round"},
            "paint":{"line-color":"$acc","line-width":6}},
           {"id":"route-glow","type":"line","source":"route","layout":{"line-cap":"round","line-join":"round"},
            "paint":{"line-color":"$acc","line-width":18,"line-opacity":0.25,"line-blur":5}},
           {"id":"route","type":"line","source":"route","layout":{"line-cap":"round","line-join":"round"},
            "paint":{"line-color":"$acc","line-width":7}},
           {"id":"destline","type":"line","source":"destline","layout":{"line-cap":"round"},
            "paint":{"line-color":"#FFFFFF","line-width":3,"line-opacity":0.75,"line-dasharray":[1.5,2]}},
           {"id":"me","type":"circle","source":"me","paint":{"circle-radius":7,"circle-color":"$acc","circle-stroke-width":3,"circle-stroke-color":"#FFFFFF"}},
           {"id":"dest-glow","type":"circle","source":"dest","paint":{"circle-radius":22,"circle-color":"$acc","circle-opacity":0.25,"circle-blur":0.6}},
           {"id":"dest","type":"circle","source":"dest","paint":{"circle-radius":9,"circle-color":"$acc","circle-stroke-width":3,"circle-stroke-color":"#FFFFFF"}}
         ]}
        """.trimIndent()
    }

    /** Konum işareti ve kenarların göstergenin zeminine yumuşakça karışması. */
    private class Marker(context: Context) : View(context) {
        var hasFix = false
        var free = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = context.resources.displayMetrics.density
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // Harita verisinin kaynağı (lisans gereği)
            p.textSize = 8 * d; p.color = 0x66FFFFFF; p.textAlign = Paint.Align.RIGHT
            c.drawText("© OpenStreetMap · OpenFreeMap", w - 6 * d, h - 6 * d, p)
            p.textAlign = Paint.Align.LEFT
            if (!hasFix) {
                p.textSize = 13 * d; p.color = 0x99FFFFFF.toInt(); p.textAlign = Paint.Align.CENTER
                c.drawText(com.newrex.auto.t("Konum bekleniyor…"), w / 2, h * 0.55f, p)
                p.textAlign = Paint.Align.LEFT
                return
            }
            if (free) return
            val accent = gaugeAccent()
            val px = w / 2; val py = h * 0.68f
            p.shader = RadialGradient(px, py, 40 * d, Palette.withAlpha(accent, 110), 0, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, 40 * d, p)
            p.shader = null
            val s = 16 * d
            path.rewind()
            path.moveTo(px, py - s); path.lineTo(px + s * 0.75f, py + s * 0.8f); path.lineTo(px, py + s * 0.4f); path.lineTo(px - s * 0.75f, py + s * 0.8f); path.close()
            p.color = accent
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0xFFFFFFFF.toInt()
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
        }
    }
}

/** Android Auto'dan gelen iki parmakla büyütme, açık olan haritaya iletilir. */
object MapGestures {
    @Volatile var onScale: ((Float) -> Unit)? = null
}
