package net.osmand.plus.plugins.weather

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AlertDialog
import net.osmand.data.LatLon
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.weather.enums.WeatherSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Vertically stacked model meteograms share one horizontal time axis. */
object ForecastComparisonUi {
    @JvmStatic fun show(activity: MapActivity, lat: Double, lon: Double, name: String?) = showPage(activity,lat,lon,name)

    fun showPlace(activity: MapActivity, place: ForecastPlace, edit: () -> Unit, delete: () -> Unit, hourly: () -> Unit) =
        showPage(activity,place.latitude,place.longitude,place.name,place,edit,delete,hourly)

    private fun showPage(activity: MapActivity, lat: Double, lon: Double, name: String?,
        place: ForecastPlace? = null, edit: (() -> Unit)? = null, delete: (() -> Unit)? = null, hourly: (() -> Unit)? = null) {
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val dark = Color.rgb(58,58,58)
        val pale = Color.rgb(224,218,197)
        val red = Color.rgb(178,8,0)
        val order = if(place != null) listOf(place.source) else listOf(WeatherSource.ECMWF,WeatherSource.GFS,WeatherSource.ICON,WeatherSource.GEM)
        val results = mutableMapOf<WeatherSource,OpenForecastData.Forecast>()
        val errors = mutableMapOf<WeatherSource,String>()
        val prefs = activity.getSharedPreferences("forecast_comparison_ui",0)
        val hidden = if(place != null) mutableSetOf<String>() else prefs.getStringSet("hidden",emptySet())!!.toMutableSet()
        var pinned = prefs.getString("pinned",null)
        var interval = if(place == null) 3 else 24
        var daily: DailyForecast? = null
        var dailyStatus: String? = null
        var clouds = false
        var offset = 0f
        val start = System.currentTimeMillis() / 3600000L * 3600000L
        val charts = mutableMapOf<WeatherSource,ForecastComparisonChart>()
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        fun text(value:String,size:Float=14f,shade:Int=Color.WHITE) = TextView(activity).apply {
            text=value;textSize=size;setTextColor(shade);gravity=Gravity.CENTER_VERTICAL
        }
        fun shape(shade:Int,radius:Int=18) = GradientDrawable().apply {setColor(shade);cornerRadius=dp(radius).toFloat()}
        fun button(label:String,action:()->Unit) = TextView(activity).apply {
            text=label;textSize=13f;setTextColor(pale);gravity=Gravity.CENTER
            setPadding(dp(10),dp(7),dp(10),dp(7));isClickable=true;isFocusable=true;setOnClickListener { action() }
        }
        val root = LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;background=shape(dark,12)
            setPadding(0,dp(4),0,0)
        }
        val closeRow=LinearLayout(activity).apply {gravity=Gravity.RIGHT}
        val close=button("×"){dialog.dismiss()}.apply {textSize=28f;setPadding(0,0,0,0);contentDescription=activity.getString(R.string.shared_string_close)}
        closeRow.addView(View(activity),LinearLayout.LayoutParams(0,dp(30),1f))
        closeRow.addView(close,LinearLayout.LayoutParams(dp(44),dp(40)));root.addView(closeRow)
        root.addView(text(name ?: activity.getString(R.string.forecast_compare_title),30f).apply {
            gravity=Gravity.CENTER;setPadding(dp(16),dp(3),dp(16),0);maxLines=2
        })
        root.addView(text(activity.getString(if(place == null) R.string.forecast_chart_subtitle else R.string.forecast_daily_title),19f,Color.LTGRAY).apply {gravity=Gravity.CENTER;setPadding(0,0,0,dp(16))})
        if(place != null) {
            val actions=LinearLayout(activity).apply {gravity=Gravity.CENTER;setPadding(dp(8),0,dp(8),dp(6))}
            fun action(label:Int, callback:()->Unit) {actions.addView(button(activity.getString(label)){dialog.dismiss();callback()},LinearLayout.LayoutParams(0,dp(40),1f))}
            action(R.string.forecast_places_edit){edit?.invoke()}
            action(R.string.forecast_places_delete){delete?.invoke()}
            action(R.string.forecast_compare_title){show(activity,lat,lon,name)}
            root.addView(actions)
        }
        val tabs=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;background=shape(Color.rgb(78,78,78));setPadding(dp(2),0,dp(2),0)}
        val tabsContainer=LinearLayout(activity).apply {setPadding(dp(15),dp(4),dp(15),dp(8));addView(tabs)}
        root.addView(tabsContainer)
        val controls=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(15),dp(2),dp(10),dp(10))}
        val durations=LinearLayout(activity).apply {background=shape(Color.rgb(78,78,78))};controls.addView(durations)
        val all=button(activity.getString(R.string.forecast_chart_show_all)){}
        controls.addView(all,LinearLayout.LayoutParams(0,dp(36),1f))
        val info=button("ⓘ"){
            AlertDialog.Builder(activity).setTitle(R.string.forecast_compare_title)
                .setMessage(activity.getString(R.string.forecast_chart_note)+"\n\n"+activity.getString(R.string.forecast_compare_note)+"\n"+
                    String.format(Locale.US,"%.5f, %.5f · %s",lat,lon,TimeZone.getDefault().id))
                .setPositiveButton(R.string.shared_string_close,null).show()
        }.apply {contentDescription=activity.getString(R.string.shared_string_description)}
        controls.addView(info);root.addView(controls)
        val vertical=ScrollView(activity).apply {isFillViewport=false;isVerticalScrollBarEnabled=false}
        val panels=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};vertical.addView(panels)
        root.addView(vertical,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        val footer=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(8),0)}
        footer.addView(text("Open-Meteo",10f,Color.LTGRAY),LinearLayout.LayoutParams(0,dp(30),1f))
        val retry=button(activity.getString(R.string.forecast_compare_retry)){}
        footer.addView(retry);root.addView(footer)
        val timeFormat=SimpleDateFormat("MM-dd HH:mm",Locale.getDefault())
        fun detail(time:Long) {
            if(place != null && interval == 24) {
                dialog.dismiss()
                DailyForecastUi.showDetails(activity,place,edit ?: {},delete ?: {},hourly ?: {})
                return
            }
            val message=StringBuilder()
            fun number(v:Double?)=v?.let{String.format(Locale.getDefault(),"%.1f",it)} ?: "—"
            for(source in order){
                val f=results[source]
                message.append(source.name).append("\n")
                message.append(activity.getString(R.string.forecast_chart_detail,
                    if(interval == 24) ForecastComparisonValues.temperatureRange(f,time) else number(f?.value("temperature_2m",time)),number(ForecastComparisonValues.sample(f,"precipitation",time,interval)),
                    ForecastComparisonValues.snowText(ForecastComparisonValues.sample(f,"snowfall",time,interval)),
                    number(ForecastComparisonValues.sample(f,"wind_speed_10m",time,interval)),number(ForecastComparisonValues.sample(f,"wind_gusts_10m",time,interval)),
                    number(ForecastComparisonValues.sample(f,"pressure_msl",time,interval)))).append("\n")
                if(f!=null) message.append(activity.getString(R.string.open_weather_fetched,timeFormat.format(Date(f.fetched)))).append("\n")
                message.append("\n")
            }
            AlertDialog.Builder(activity).setTitle(timeFormat.format(Date(time)))
                .setMessage(message.toString()).setPositiveButton(R.string.shared_string_close,null).show()
        }
        lateinit var render:()->Unit
        render={
            val scrollY=vertical.scrollY
            panels.removeAllViews();charts.clear()
            val sources=order.filter{it.settingValue !in hidden}.sortedBy{if(it.settingValue==pinned)0 else 1}
            all.visibility=if(hidden.isEmpty()) View.INVISIBLE else View.VISIBLE
            if(sources.isEmpty())panels.addView(text(activity.getString(R.string.forecast_chart_none),16f).apply{setPadding(dp(16),dp(30),dp(16),dp(30))})
            dailyStatus?.let {panels.addView(text(it,11f,Color.LTGRAY).apply {setPadding(dp(12),dp(4),dp(12),dp(4))})}
            for(source in sources){
                val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(9),0,dp(6),0)}
                val resolution=when(source){WeatherSource.ECMWF->"25 km";WeatherSource.GFS->"13 km";WeatherSource.ICON->"13 km";WeatherSource.GEM->"15 km"}
                row.addView(text(source.name,15f).apply{setTypeface(typeface,Typeface.NORMAL)},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(32)))
                row.addView(text("  $resolution",10f,Color.LTGRAY),LinearLayout.LayoutParams(0,dp(32),1f))
                if(place == null) row.addView(button(activity.getString(R.string.forecast_chart_hide)){
                    hidden.add(source.settingValue);prefs.edit().putStringSet("hidden",hidden.toSet()).apply();render()
                })
                if(place == null) row.addView(button(activity.getString(if(pinned==source.settingValue)R.string.forecast_chart_unpin else R.string.forecast_chart_pin)){
                    pinned=if(pinned==source.settingValue)null else source.settingValue
                    prefs.edit().putString("pinned",pinned).apply();vertical.scrollTo(0,0);render()
                })
                panels.addView(row)
                if(errors.containsKey(source))panels.addView(text(errors.getValue(source),11f,Color.rgb(255,190,170)).apply{setPadding(dp(12),dp(2),dp(12),dp(3))})
                else if(results[source]==null && daily==null)panels.addView(text(activity.getString(R.string.open_weather_loading),11f,Color.LTGRAY).apply{setPadding(dp(12),0,0,0)})
                val chart=ForecastComparisonChart(activity,start,{x->offset=x;charts.values.forEach{it.offset=x}},::detail).apply{
                    this.intervalHours=interval;this.clouds=clouds;forecast=results[source];dailyForecast=daily
                }
                charts[source]=chart
                panels.addView(chart,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(if(clouds)190 else 202)))
                chart.post { if(dialog.isShowing){ chart.offset=offset } }
            }
            vertical.post { if(dialog.isShowing) vertical.scrollTo(0,scrollY) }
        }
        val basic=button(activity.getString(R.string.forecast_chart_basic)){}
        val cloud=button(activity.getString(R.string.forecast_chart_clouds)){}
        val waves=button(activity.getString(R.string.forecast_chart_waves)){}.apply{alpha=.4f;isEnabled=false}
        tabs.addView(basic);tabs.addView(cloud);tabs.addView(waves)
        val intervals=listOf(1 to R.string.forecast_chart_interval_hour,3 to R.string.forecast_chart_interval_three,24 to R.string.forecast_chart_interval_day)
        val intervalButtons=intervals.associate { (hours,label) -> hours to button(activity.getString(label)){} }
        intervalButtons.values.forEach { durations.addView(it) }
        fun highlight(){
            basic.background=if(!clouds)shape(red) else null;cloud.background=if(clouds)shape(red) else null
            intervalButtons.forEach { (hours,view) -> view.background=if(interval==hours)shape(red) else null }
        }
        basic.setOnClickListener{clouds=false;highlight();render()}
        cloud.setOnClickListener{clouds=true;highlight();render()}
        intervalButtons.forEach { (hours,view) -> view.setOnClickListener {interval=hours;offset=0f;highlight();render()} }
        all.setOnClickListener{hidden.clear();prefs.edit().remove("hidden").apply();render()}
        var generation=0
        fun request(force: Boolean = false){
            val token=++generation;errors.clear();render();retry.isEnabled=false
            var pending=order.size + if(place != null) 1 else 0
            if(place != null) {
                (activity.application as net.osmand.plus.OsmandApplication).weatherHelper.openForecastData
                    .requestDaily(place.source,LatLon(lat,lon),force) callback@{result,cached,error->
                        if(!dialog.isShowing||activity.isDestroyed||activity.isFinishing||token!=generation)return@callback
                        if(result != null) {
                            daily=result
                            dailyStatus=activity.getString(if(cached) R.string.forecast_daily_cached else R.string.forecast_daily_fetched,
                                timeFormat.format(Date(result.fetched)),result.timezone)
                        } else dailyStatus=error
                        pending--;retry.isEnabled=pending==0;render()
                    }
            }
            for(source in order){
                (activity.application as net.osmand.plus.OsmandApplication).weatherHelper.openForecastData
                    .requestComparison(source,LatLon(lat,lon)) callback@{result,error->
                        if(!dialog.isShowing||activity.isDestroyed||activity.isFinishing||token!=generation)return@callback
                        val f=result?.firstOrNull()
                        if(f!=null)results[source]=f else errors[source]=error ?: activity.getString(R.string.open_weather_error)
                        pending--;retry.isEnabled=pending==0;render()
                    }
            }
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener{generation++}
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val height=(activity.resources.displayMetrics.heightPixels*.94).toInt()
        dialog.window?.apply {setLayout(ViewGroup.LayoutParams.MATCH_PARENT,height);setGravity(Gravity.BOTTOM)}
        retry.setOnClickListener{request(true)}
        highlight();request()
    }
}
