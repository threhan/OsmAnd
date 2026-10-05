package net.osmand.plus.plugins.weather

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import net.osmand.plus.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Shared scales and selectable hourly/daily columns keep models comparable. */
class ForecastComparisonChart(context: Context, private val start: Long,
    private val moved: (Float) -> Unit, private val selected: (Long) -> Unit) : View(context) {
    var forecast: OpenForecastData.Forecast? = null
        set(value) { field = value; invalidate() }
    var dailyForecast: DailyForecast? = null
        set(value) {field=value;invalidate()}
    val days = 7
    var intervalHours = 3
        set(value) { field = value; invalidate() }
    var clouds = false
        set(value) { field = value; invalidate() }
    var offset = 0f
        set(value) { field = value.coerceIn(0f, maxOffset()); invalidate() }
    private val d = resources.displayMetrics.density
    private val font = resources.displayMetrics.scaledDensity
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = 46 * d
    private val column get() = (if (intervalHours == 24) 68 else 31) * d
    private var timelineInterval = 0
    private var cachedTimes = emptyList<Long>()
    private val times: List<Long> get() {
        if (timelineInterval != intervalHours) { cachedTimes = ForecastComparisonValues.timeline(start, intervalHours); timelineInterval = intervalHours }
        return cachedTimes
    }
    private fun time(i: Int) = times[i]
    private fun day(t: Long) = dailyForecast?.days?.firstOrNull {it.date == SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(t))}
    private fun sample(name: String, t: Long): Double? {
        val day = if(intervalHours == 24) day(t) else null
        if(day != null) when(name) {
            "temperature_2m" -> return if(day.high != null && day.low != null) (day.high+day.low)/2 else null
            "precipitation" -> return day.precipitation
            "snowfall" -> return day.snowfall
            "wind_speed_10m" -> return day.meanWind
            "wind_gusts_10m" -> return day.gust
        }
        return ForecastComparisonValues.sample(forecast,name,t,intervalHours)
    }
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { isClickable = true; contentDescription = context.getString(R.string.forecast_chart_accessibility) }
    fun maxOffset() = max(0f, times.size * column - (width - label).coerceAtLeast(column))
    private fun color(value: String) = Color.parseColor(value)
    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, shade: Int) {
        p.color = shade; p.style = Paint.Style.FILL; c.drawRect(x, y * d, x + w, (y + h) * d, p)
    }
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float = 12f,
                     shade: Int = Color.DKGRAY, center: Boolean = true) {
        p.color = shade; p.textSize = size * font; p.style = Paint.Style.FILL
        p.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s, x, y * d, p)
    }
    private fun value(name: String, time: Long) = forecast?.value(name, time)
    private fun number(v: Double?, decimals: Int = 0): String = v?.let {
        String.format(Locale.getDefault(), if (decimals == 0) "%.0f" else "%.1f", it)
    } ?: "—"
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(color("#f7f7fa"))
        val count = times.size
        val first = max(0, (offset / column).toInt() - 1)
        val last = min(count - 1, ((offset + width - label) / column).toInt() + 1)
        val date = SimpleDateFormat("EEE d", Locale.getDefault())
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val hour = SimpleDateFormat("H", Locale.getDefault())
        val dayGroups = (0 until count).groupBy { dateKey.format(Date(time(it))) }
        c.save(); c.clipRect(label, 0f, width.toFloat(), height.toFloat())
        for ((_, group) in dayGroups) {
            val x = label + group.first() * column - offset
            val right = label + (group.last() + 1) * column - offset
            if (right < label || x > width) continue
            rect(c, x, 0f, right-x, 27f, color("#e9e9e9"))
            p.color = color("#c7c7c7"); p.strokeWidth = d
            c.drawLine(x, 0f, x, height.toFloat(), p)
            text(c, date.format(Date(time(group.first()))), max(x+8*d,label+8*d), 19f, 12f, color("#777777"), false)
        }
        for (i in first..last) {
            val time = time(i)
            val x = label + i * column - offset
            val localHour = hour.format(Date(time)).toInt()
            if (intervalHours != 24 && (localHour < 7 || localHour >= 19)) rect(c, x, 27f, column, 160f, color("#e9e9f3"))
            text(c, (if (intervalHours == 24) "" else hour.format(Date(time))), x+column/2, 44f, 12f, color("#808080"))
        }
        if (clouds) drawClouds(c, first, last) else drawBasic(c, first, last)
        c.restore()
        // Units remain fixed while every model shares the same horizontal offset.
        rect(c, 0f, 0f, label, 210f, color("#e9e9e9"))
        text(c, context.getString(if(intervalHours == 24) R.string.forecast_chart_interval_day else R.string.forecast_chart_hour), label/2, 43f, 10f)
        if (clouds) {
            text(c, context.getString(R.string.forecast_chart_high_cloud), label/2, 72f, 10f)
            text(c, context.getString(R.string.forecast_chart_mid_cloud), label/2, 101f, 10f)
            text(c, context.getString(R.string.forecast_chart_low_cloud), label/2, 130f, 10f)
            text(c, "mm", label/2, 157f, 10f)
            text(c, "cm", label/2, 180f, 10f)
        } else {
            text(c, "°C", label/2, 92f)
            text(c, "mm", label/2, 132f, 10f)
            text(c, "cm", label/2, 151f, 10f)
            text(c, "m/s", label/2, 173f, 10f)
            text(c, context.getString(R.string.forecast_chart_gust), label/2, 195f, 10f)
        }
    }

    private fun drawBasic(c: Canvas, first: Int, last: Int) {
        // Temperature area: same -25..40 C vertical/color mapping for every model.
        val path = Path(); var segment = false; var endX = 0f
        fun finish() {
            if (!segment) return
            path.lineTo(endX, 116*d); path.close()
            p.shader = LinearGradient(0f,50*d,0f,116*d,
                intArrayOf(color("#ffd5bc"),color("#f6efaf"),color("#d9ffd4"),color("#c5f2ff"),color("#dbd3f5")),null,Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL; c.drawPath(path,p); p.shader=null; segment=false
        }
        for (i in first..last) {
            val x=label+(i+.5f)*column-offset; val temp=sample("temperature_2m",time(i))
            if (temp == null) { finish(); continue }
            val y=(108-(temp.coerceIn(-25.0,40.0)+25)/65*53).toFloat()*d
            if (!segment) { path.reset(); path.moveTo(x-column/2,116*d); path.lineTo(x-column/2,y); segment=true }
            path.lineTo(x,y); endX=x+column/2
        }
        finish()
        for (i in first..last) {
            val t=time(i); val x=label+i*column-offset; val center=x+column/2
            weatherIcon(c,center,61*d,sample("weather_code",t),if(intervalHours == 24) 1.0 else value("is_day",t))
            val temperature = if (intervalHours == 24) day(t)?.let { "${number(it.high)}° / ${number(it.low)}°" } ?: ForecastComparisonValues.temperatureRange(forecast,t) else number(sample("temperature_2m",t)) + if(sample("temperature_2m",t)!=null) "°" else ""
            text(c,temperature,center,93f,if(intervalHours == 24) 11f else 14f,Color.BLACK)
            val rain=sample("precipitation",t)
            val snow=sample("snowfall",t)
            if (rain != null && rain > 0) rect(c,x+2*d,138f-min(18f,rain.toFloat()*3),column-4*d,min(18f,rain.toFloat()*3),color("#bbdefb"))
            text(c,number(rain,1),center,132f,11f,color("#2579b4"))
            text(c,ForecastComparisonValues.snowText(snow),center,151f,10f,color("#9865b5"))
            val wind=sample("wind_speed_10m",t)
            rect(c,x,158f,column,22f,windColor(wind))
            if(intervalHours != 24) arrow(c,x+7*d,169*d,value("wind_direction_10m",t))
            text(c,number(wind),x+column*.69f,173f,12f,Color.BLACK)
            val gust=sample("wind_gusts_10m",t)
            rect(c,x,180f,column,22f,windColor(gust))
            text(c,number(gust),center,195f,11f,Color.BLACK)
        }
    }

    private fun drawClouds(c: Canvas, first: Int, last: Int) {
        val levels=listOf("cloud_cover_high","cloud_cover_mid","cloud_cover_low")
        for ((row,name) in levels.withIndex()) {
            val y=51f+row*29
            for (i in first..last) {
                val x=label+i*column-offset; val cloud=sample(name,time(i))
                if (cloud != null) {
                    p.color=Color.argb((cloud.coerceIn(0.0,100.0)*1.8).toInt(),115,120,125)
                    val h=3+cloud.toFloat().coerceIn(0f,100f)/100*22
                    c.drawRoundRect(x,y*d+(25-h)*d/2,x+column,(y+25)*d-(25-h)*d/2,7*d,7*d,p)
                } else text(c,"—",x+column/2,y+18,10f,color("#9c9c9c"))
            }
            p.color=color("#d4d4da");p.strokeWidth=d;p.pathEffect=DashPathEffect(floatArrayOf(4*d,4*d),0f)
            c.drawLine(label,(y+27)*d,width.toFloat(),(y+27)*d,p);p.pathEffect=null
        }
        val pressure=Path();var connected=false
        for(i in first..last) {
            val t=time(i);val x=label+(i+.5f)*column-offset;val v=sample("pressure_msl",t)
            if(v==null){connected=false;continue}
            val y=(131-(v.coerceIn(970.0,1050.0)-970)/80*76).toFloat()*d
            if(connected)pressure.lineTo(x,y) else pressure.moveTo(x,y);connected=true
            if(i%8==0)text(c,"${number(v)} hPa",x,y/d-3,9f,color("#7191b2"))
        }
        p.color=color("#94b5d3");p.strokeWidth=d;p.style=Paint.Style.STROKE;c.drawPath(pressure,p);p.style=Paint.Style.FILL
        for(i in first..last){
            val x=label+(i+.5f)*column-offset;val t=time(i)
            text(c,number(sample("precipitation",t),1),x,157f,12f,color("#2579b4"))
            text(c,ForecastComparisonValues.snowText(sample("snowfall",t)),x,180f,12f,color("#9865b5"))
        }
    }

    private fun windColor(speed: Double?): Int {
        if(speed==null)return color("#eeeeef")
        val palette=intArrayOf(color("#f1f1f1"),color("#9cedf4"),color("#00dcde"),color("#08e50a"),color("#b7ea00"),color("#ffe000"),color("#ffad25"),color("#f8735c"),color("#ca72db"))
        val v=(speed/3).coerceIn(0.0,(palette.size-1).toDouble());val a=v.toInt();val b=min(a+1,palette.lastIndex);val t=v-a
        fun channel(fn:(Int)->Int)=(fn(palette[a])*(1-t)+fn(palette[b])*t).toInt()
        return Color.rgb(channel(Color::red),channel(Color::green),channel(Color::blue))
    }
    private fun arrow(c:Canvas,x:Float,y:Float,direction:Double?) {
        if(direction==null)return
        c.save();c.rotate(direction.toFloat()+180,x,y)
        val path=Path().apply{moveTo(x,y-5*d);lineTo(x-3*d,y+4*d);lineTo(x,y+2*d);lineTo(x+3*d,y+4*d);close()}
        p.color=color("#33554e");p.style=Paint.Style.STROKE;p.strokeWidth=d;c.drawPath(path,p);p.style=Paint.Style.FILL;c.restore()
    }
    private fun weatherIcon(c:Canvas,x:Float,y:Float,code:Double?,day:Double?) {
        if(code==null){text(c,"—",x,y/d+4,12f);return}
        val v=code.toInt();val cloudy=v>=2;val snow=v in 71..77 || v in 85..86;val rain=v in 51..67 || v in 80..82 || v>=95
        if(v<=2){
            p.color=if(day==0.0)color("#96cde7") else color("#ffb800")
            c.drawCircle(x-2*d,y,6*d,p)
            if(day==0.0){p.color=color("#e9e9f3");c.drawCircle(x-4*d,y-2*d,5*d,p)}else{
                p.strokeWidth=d
                for(n in 0..7){val a=n*Math.PI/4;c.drawLine(x-2*d+cos(a).toFloat()*8*d,y+sin(a).toFloat()*8*d,x-2*d+cos(a).toFloat()*10*d,y+sin(a).toFloat()*10*d,p)}
            }
        }
        if(cloudy){p.color=color("#a1a5a8");c.drawCircle(x-4*d,y+2*d,5*d,p);c.drawCircle(x+2*d,y,6*d,p);c.drawRoundRect(x-9*d,y+2*d,x+9*d,y+7*d,3*d,3*d,p)}
        if(rain||snow){p.color=if(snow)color("#9168b8") else color("#369cda");p.strokeWidth=d;for(n in -1..1){val a=x+n*5*d;if(snow){c.drawLine(a-2*d,y+11*d,a+2*d,y+11*d,p);c.drawLine(a,y+9*d,a,y+13*d,p)}else c.drawLine(a,y+9*d,a-2*d,y+13*d,p)}}
        if(v in 45..48){p.color=Color.GRAY;p.strokeWidth=d;c.drawLine(x-8*d,y+10*d,x+8*d,y+10*d,p)}
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.x;lastX=event.x;downY=event.y;dragging=false;return true}
            MotionEvent.ACTION_MOVE->{
                val dx=event.x-downX;val dy=event.y-downY
                if(!dragging&&abs(dx)>slop&&abs(dx)>abs(dy)){dragging=true;parent.requestDisallowInterceptTouchEvent(true)}
                if(dragging)moved((offset+lastX-event.x).coerceIn(0f,maxOffset()))
                lastX=event.x;return true
            }
            MotionEvent.ACTION_UP->{
                parent.requestDisallowInterceptTouchEvent(false)
                if(!dragging&&abs(event.y-downY)<slop&&event.x>=label){
                    val index=((event.x-label+offset)/column).toInt().coerceIn(0,times.size-1)
                    selected(time(index));performClick()
                };return true
            }
            MotionEvent.ACTION_CANCEL->{parent.requestDisallowInterceptTouchEvent(false);return true}
        };return super.onTouchEvent(event)
    }
    override fun performClick():Boolean {super.performClick();return true}
    companion object {const val STEP=10800000L}
}

object ForecastComparisonValues {
    private const val HOUR = 3600000L
    @JvmStatic fun timeline(start: Long, interval: Int): List<Long> {
        require(interval in listOf(1,3,24))
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis=start; set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0)
            set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
        }
        val end = (calendar.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH,7) }.timeInMillis
        if(interval == 24) return (0..6).map { val t=calendar.timeInMillis; calendar.add(java.util.Calendar.DAY_OF_MONTH,1); t }
        return generateSequence(start / (interval*HOUR) * (interval*HOUR)) { it+interval*HOUR }.takeWhile { it<end }.toList()
    }
    private fun samples(f: OpenForecastData.Forecast?, name: String, t: Long, interval: Int): List<Double>? {
        val times = if(interval == 24) {
            val end=java.util.Calendar.getInstance().apply {timeInMillis=t;add(java.util.Calendar.DAY_OF_MONTH,1)}.timeInMillis
            generateSequence(t) {it+HOUR}.takeWhile {it<end}.toList()
        } else (0 until interval).map {t-it*HOUR}
        return times.map { f?.value(name,it) ?: return null }
    }
    @JvmStatic fun sample(f: OpenForecastData.Forecast?, name: String, t: Long, interval: Int): Double? {
        val values=samples(f,name,t,interval) ?: return null
        return when(name) {
            "precipitation", "snowfall" -> values.sum()
            "wind_gusts_10m", "weather_code" -> values.maxOrNull()
            else -> if(interval == 24) values.average() else f?.value(name,t)
        }
    }
    @JvmStatic fun temperatureRange(f: OpenForecastData.Forecast?, t: Long): String {
        val values=samples(f,"temperature_2m",t,24) ?: return "—"
        return String.format(Locale.getDefault(),"%.0f° / %.0f°",values.maxOrNull(),values.minOrNull())
    }
    @JvmStatic fun sum(forecast:OpenForecastData.Forecast?,variable:String,time:Long):Double? {
        val values=(0..2).map{forecast?.value(variable,time-it*3600000L) ?: return null}
        return values.sum()
    }
    @JvmStatic fun maximum(forecast:OpenForecastData.Forecast?,variable:String,time:Long):Double? {
        val values=(0..2).map{forecast?.value(variable,time-it*3600000L) ?: return null}
        return values.maxOrNull()
    }
    @JvmStatic fun snowText(value:Double?):String = when {
        value==null -> "—"
        value>0&&value<.1 -> "<0.1"
        else -> String.format(Locale.getDefault(),"%.1f",value)
    }
}
