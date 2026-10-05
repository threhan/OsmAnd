package net.osmand.plus.plugins.weather

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import net.osmand.data.LatLon
import net.osmand.data.RotatedTileBox
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.weather.enums.WeatherSource
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.layers.base.OsmandMapLayer
import kotlin.math.*

/** Screen overlay of sampled forecast cells. It never reuses a different model's tiles. */
class OpenForecastLayer(context: Context) : OsmandMapLayer(context) {
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plugin get() = PluginsHelper.getPlugin(WeatherPlugin::class.java)
    private var requested = ""
    private var generation = 0
    private var dead = false
    private var refreshAt = 0L
    private var status = ""
    private var forecast: List<OpenForecastData.Forecast>? = null
    private var scheduled: Runnable? = null
    private data class Grid(val source: WeatherSource, val step: Double, val west: Double, val south: Double, val cols: Int, val rows: Int) {
        val key get() = "$source/$step/$west/$south/$cols/$rows"
        fun point(x: Int, y: Int) = LatLon((south + y * step).coerceIn(-85.0, 85.0), wrap(west + x * step))
        val points get() = (0 until rows).flatMap { y -> (0 until cols).map { x -> point(x, y) } }
    }
    override fun drawInScreenPixels() = true
    override fun initLayer(view: net.osmand.plus.views.OsmandMapTileView) {
        super.initLayer(view)
        dead = false; generation++; requested = ""; forecast = null; refreshAt = 0
    }
    private fun active() = plugin?.let { it.isActive && it.weatherSource.isExternal && (it.isWeatherEnabled || it.hasCustomForecast()) } == true
    override fun onDraw(canvas: Canvas, box: RotatedTileBox, settings: DrawSettings) {
        if (dead || !active()) {
            if (requested.isNotEmpty()) { generation++; requested = ""; forecast = null; scheduled?.let(main::removeCallbacks) }
            return
        }
        val p = plugin ?: return
        val center = NativeUtilities.getLatLonFromPixel(mapRenderer, box, box.centerPixelX.toFloat(), box.centerPixelY.toFloat())
        val corners = listOf(0 to 0, box.pixWidth to 0, box.pixWidth to box.pixHeight, 0 to box.pixHeight).map {
            NativeUtilities.getLatLonFromPixel(mapRenderer, box, it.first.toFloat(), it.second.toFloat())
        }
        val longitudes = corners.map { center.longitude + wrap(it.longitude - center.longitude) }
        val span = max(longitudes.max() - longitudes.min(), corners.maxOf { it.latitude } - corners.minOf { it.latitude })
        val base = if (p.weatherSource == WeatherSource.ICON) .125 else .15
        val step = base * 2.0.pow(ceil(log2(max(1.0, span / (base * 4)))))
        val west = floor(longitudes.min() / step) * step
        val south = floor(corners.minOf { it.latitude } / step) * step
        val wanted = Grid(p.weatherSource, step, west, south,
            (ceil((longitudes.max() - west) / step).toInt() + 1).coerceIn(2, 7),
            (ceil((corners.maxOf { it.latitude } - south) / step).toInt() + 1).coerceIn(2, 7))
        if (wanted.key != requested || System.currentTimeMillis() >= refreshAt) {
            requested = wanted.key; generation++; val token = generation
            forecast = null; refreshAt = Long.MAX_VALUE
            status = context.getString(R.string.open_weather_loading)
            scheduled?.let(main::removeCallbacks)
            scheduled = Runnable {
                if (dead || token != generation || !active()) return@Runnable
                application.weatherHelper.openForecastData.request(wanted.source, wanted.points) { result, error ->
                    if (!dead && token == generation && active() && plugin?.weatherSource == wanted.source) {
                        forecast = result; status = error ?: ""; refreshAt = System.currentTimeMillis() + if (result == null) 60000 else OpenForecastData.TTL
                        view.refreshMap()
                        scheduled = Runnable { if (!dead && token == generation && active()) view.refreshMap() }
                            .also { main.postDelayed(it, if (result == null) 60000 else OpenForecastData.TTL) }
                    }
                }
            }.also { main.postDelayed(it, 1200) }
        }
        val data = forecast
        val time = p.forecastTime
        val bands = if (p.hasCustomForecast()) application.weatherHelper.visibleForecastBands else application.weatherHelper.visibleBands
        if (data != null) {
            val pixels = wanted.points.map { NativeUtilities.getPixelFromLatLon(mapRenderer, box, it) }
            val relativeLongitudes = wanted.points.map { wrap(it.longitude - center.longitude) }
            fun crossesProjectionSeam(ids: IntArray) = ids.indices.any { i ->
                abs(relativeLongitudes[ids[i]] - relativeLongitudes[ids[(i + 1) % 4]]) > 180
            }
            for (band in bands) {
                val index = band.bandIndex
                if (index.toInt() == 0) continue
                for (y in 0 until wanted.rows - 1) for (x in 0 until wanted.cols - 1) {
                    val ids = intArrayOf(y * wanted.cols + x, y * wanted.cols + x + 1, (y + 1) * wanted.cols + x + 1, (y + 1) * wanted.cols + x)
                    val values = ids.map { data[it].value(OpenForecastData.variable(index), time) }.filterNotNull()
                    if (values.size != 4) continue
                    // Do not join projected cells across the date-line seam.
                    if (crossesProjectionSeam(ids)) continue
                    val path = Path(); ids.forEachIndexed { i, id -> val pixel = pixels[id]; if (i == 0) path.moveTo(pixel.x, pixel.y) else path.lineTo(pixel.x, pixel.y) }; path.close()
                    paint.style = Paint.Style.FILL
                    paint.color = color(index, values.average())
                    paint.alpha = ((band.alphaPreference?.get() ?: .5f).coerceIn(0f, 1f) * 160).toInt()
                    canvas.drawPath(path, paint)
                }
            }
            val contour = if (p.hasCustomForecast()) p.selectedForecastContoursType else if (p.isContoursEnabled) p.selectedContoursType else null
            contour?.let { c ->
                val band = c.bandIndex
                val interval = when (band.toInt()) { 1 -> 20.0; 2 -> 5.0; 3 -> 5.0; 4 -> 5.0; else -> 2.0 }
                paint.style = Paint.Style.STROKE; paint.color = Color.DKGRAY; paint.alpha = 130; paint.strokeWidth = context.resources.displayMetrics.density
                for (y in 0 until wanted.rows - 1) for (x in 0 until wanted.cols - 1) {
                    val ids = intArrayOf(y*wanted.cols+x, y*wanted.cols+x+1, (y+1)*wanted.cols+x+1, (y+1)*wanted.cols+x)
                    if (crossesProjectionSeam(ids)) continue
                    val v = ids.map { data[it].value(OpenForecastData.variable(band), time) }
                    if (v.any { it == null }) continue
                    val vs = v.map { it!! }; var level = ceil(vs.min()/interval)*interval; var lines = 0
                    while (level <= vs.max() && lines++ < 30) {
                        val crossings = mutableListOf<PointF>()
                        for (i in 0..3) { val j=(i+1)%4
                            if ((vs[i] < level) != (vs[j] < level)) {
                                val t=((level-vs[i])/(vs[j]-vs[i])).toFloat(); val a=pixels[ids[i]]; val b=pixels[ids[j]]
                                crossings.add(PointF(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t))
                            }
                        }
                        for (i in 0 until crossings.size-1 step 2) canvas.drawLine(crossings[i].x,crossings[i].y,crossings[i+1].x,crossings[i+1].y,paint)
                        level += interval
                    }
                }
            }
            if (bands.any { it.bandIndex.toInt() == 0 }) {
                paint.color = Color.DKGRAY; paint.alpha = 170; paint.strokeWidth = 2 * context.resources.displayMetrics.density
                for (i in data.indices) {
                    val direction = data[i].value("wind_direction_10m", time) ?: continue
                    val speed = data[i].value("wind_speed_10m", time) ?: continue
                    if (speed < .1) continue
                    val pixel = pixels[i]; val a = (direction + 180 - box.rotate) * PI / 180
                    val len = 10 * context.resources.displayMetrics.density
                    val dx = (sin(a)*len).toFloat(); val dy = (-cos(a)*len).toFloat()
                    canvas.drawLine(pixel.x-dx,pixel.y-dy,pixel.x+dx,pixel.y+dy,paint)
                    canvas.drawLine(pixel.x+dx,pixel.y+dy,pixel.x+dx*.4f-dy*.4f,pixel.y+dy*.4f+dx*.4f,paint)
                    canvas.drawLine(pixel.x+dx,pixel.y+dy,pixel.x+dx*.4f+dy*.4f,pixel.y+dy*.4f-dx*.4f,paint)
                }
            }
        }
        val density = context.resources.displayMetrics.density
        val noTime = data != null && data.none { it.value("temperature_2m",time) != null }
        val caption = context.getString(R.string.open_weather_map_caption, context.getString(p.weatherSource.titleId), wanted.step * 111) +
            " · " + if (status.isNotEmpty()) status else if (noTime) context.getString(R.string.open_weather_no_time) else context.getString(R.string.open_weather_tap)
        paint.textSize = 11*density
        val captionLines = mutableListOf<String>()
        var remaining = caption
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, max(1f, box.pixWidth - 16*density), null).coerceAtLeast(1)
            captionLines.add(remaining.take(count))
            remaining = remaining.drop(count)
        }
        val captionBottom = box.pixHeight*.24f + (captionLines.size - 1)*15*density
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = 225
        canvas.drawRect(0f, box.pixHeight*.24f-21*density, box.pixWidth.toFloat(),captionBottom+7*density,paint)
        paint.color = Color.DKGRAY; paint.alpha = 255; paint.textSize = 11*density
        captionLines.forEachIndexed { i, line -> canvas.drawText(line,8*density,box.pixHeight*.24f+i*15*density,paint) }
        var legendY = captionBottom + 22*density
        for (band in bands.filter { it.bandIndex.toInt() != 0 }) {
            val index = band.bandIndex
            val limits = when(index.toInt()) { 1 -> 0.0 to 100.0; 2 -> -25.0 to 40.0; 3 -> 970.0 to 1040.0; 4 -> 0.0 to 25.0; else -> 0.0 to 10.0 }
            val unit = when(index.toInt()) { 1 -> "%"; 2 -> "°C"; 3 -> "hPa"; 4 -> "m/s"; else -> "mm/h" }
            paint.color=Color.WHITE; paint.alpha=230
            canvas.drawRect(5*density,legendY-14*density,205*density,legendY+3*density,paint)
            for (i in 0..19) {
                paint.color=color(index,limits.first+(limits.second-limits.first)*i/19)
                canvas.drawRect((55+i*4)*density,legendY-9*density,(59+i*4)*density,legendY,paint)
            }
            paint.color=Color.DKGRAY; paint.alpha=255; paint.textSize=10*density
            canvas.drawText("${limits.first.toInt()}",10*density,legendY,paint)
            canvas.drawText("${limits.second.toInt()}+ $unit",140*density,legendY,paint)
            legendY += 19*density
        }
    }
    override fun onSingleTap(point: PointF, tileBox: RotatedTileBox): Boolean {
        if (!active() || plugin?.hasCustomForecast() != true) return false
        val activity = mapActivity ?: return false
        val pos = NativeUtilities.getLatLonFromPixel(mapRenderer,tileBox,point.x,point.y)
        OpenForecastUi.show(activity,pos.latitude,pos.longitude,plugin!!.weatherSource)
        return true
    }
    override fun destroyLayer() { dead = true; generation++; scheduled?.let(main::removeCallbacks); super.destroyLayer() }
    companion object {
        private fun wrap(lon: Double) = ((lon + 180) % 360 + 360) % 360 - 180
        private fun color(band: Short, v: Double): Int {
            val t = when(band.toInt()) { 1 -> v/100; 2 -> (v+25)/65; 3 -> (v-970)/70; 4 -> v/25; else -> v/10 }.coerceIn(0.0,1.0)
            return if (band.toInt()==1) Color.rgb((240-110*t).toInt(),(240-110*t).toInt(),(250-100*t).toInt())
                else Color.HSVToColor(floatArrayOf((240*(1-t)).toFloat(),.8f,.95f))
        }
    }
}
