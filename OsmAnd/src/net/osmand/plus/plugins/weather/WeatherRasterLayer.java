package net.osmand.plus.plugins.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.StateChangedListener;
import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.BandIndexList;
import net.osmand.core.jni.MapLayerConfiguration;
import net.osmand.core.jni.WeatherRasterLayerProvider;
import net.osmand.core.jni.WeatherTileResourcesManager;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.BaseMapLayer;
import net.osmand.util.Algorithms;

import java.util.ArrayList;
import java.util.List;

public class WeatherRasterLayer extends BaseMapLayer {
	public static final int FORECAST_ANIMATION_DURATION_HOURS = 6;
	private static final long MINUTE_IN_MILLISECONDS = 60 * 1000;
	private static final long HOUR_IN_MILLISECONDS = 60 * 60 * 1000;
	private static final long DAY_IN_MILLISECONDS = 24 * HOUR_IN_MILLISECONDS;
	private final WeatherHelper weatherHelper;
	private final WeatherSettings weatherSettings;
	private final WeatherPlugin plugin;
	private final WeatherLayer weatherLayer;

	private WeatherRasterLayerProvider provider;

	private boolean weatherEnabledCached;
	private List<WeatherBand> enabledBandsCached;
	private int bandsSettingsVersionCached;
	private boolean requireTimePeriodChange;
	private long timePeriodStart;
	private long timePeriodEnd;
	private long timePeriodStep;
	private long dateTime;
	private long cachedDateTime;
	private long renderedTime;
	private boolean localDataCached;
	private int cachedTimesVersion = -1;
	private final android.graphics.Paint timeLabel = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

	private final List<StateChangedListener<Float>> alphaChangeListeners = new ArrayList<>();
	private StateChangedListener<String> weatherSourceChangeListener;

	public enum WeatherLayer {
		LOW,
		HIGH
	}

	public WeatherRasterLayer(@NonNull Context context, @NonNull WeatherLayer weatherLayer) {
		super(context);
		OsmandApplication app = getApplication();
		this.weatherHelper = app.getWeatherHelper();
		this.weatherSettings = weatherHelper.getWeatherSettings();
		this.weatherLayer = weatherLayer;
		this.plugin = PluginsHelper.getPlugin(WeatherPlugin.class);
		setDateTime(System.currentTimeMillis(), false, false);
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);

		for (WeatherBand weatherBand : weatherHelper.getWeatherBands()) {
			CommonPreference<Float> preference = weatherBand.getAlphaPreference();
			if (preference != null) {
				StateChangedListener<Float> listener = change -> weatherHelper.updateBandsSettings();
				preference.addListener(listener);
				alphaChangeListeners.add(listener);
			}
		}

		weatherSourceChangeListener = change -> {
			WeatherTileResourcesManager resourcesManager = weatherHelper.getWeatherResourcesManager();
			MapRendererView mapRenderer = getMapRenderer();

			if (mapRenderer == null || resourcesManager == null || resourcesManager.getBandSettings().empty()) {
				return;
			}

			resetLayerProvider();
			if (!shouldDrawLayer()) return;
			recreateLayerProvider(mapRenderer, resourcesManager);
		};
		weatherSettings.weatherSource.addListener(weatherSourceChangeListener);
	}

	public long getDateTime() {
		return dateTime;
	}

	public void setDateTime(long dateTime, boolean goForward, boolean resetPeriod) {
		long dayStart = OsmAndFormatter.getStartOfDayForTime(timePeriodStart);
		long dayEnd = dayStart + DAY_IN_MILLISECONDS;
		if (dateTime < dayStart || dateTime > dayEnd) {
			dayStart = OsmAndFormatter.getStartOfDayForTime(dateTime);
			dayEnd = dayStart + DAY_IN_MILLISECONDS;
		}
		long todayStep = HOUR_IN_MILLISECONDS;
		long nextStep = todayStep * 3;
		long startOfToday = OsmAndFormatter.getStartOfToday();
		long step = dayStart == startOfToday ? todayStep : nextStep;
		long switchStepTime = (System.currentTimeMillis() + DAY_IN_MILLISECONDS) / nextStep * nextStep;
		if (switchStepTime > startOfToday && switchStepTime >= dayStart + todayStep && switchStepTime <= dayEnd - nextStep) {
			if (dateTime < switchStepTime) {
				dayEnd = switchStepTime;
				step = todayStep;
			} else
				dayStart = switchStepTime;
		}
		long prevTime = (dateTime - dayStart) / step * step + dayStart;
		long nextTime = prevTime + step;
		if (goForward) {
			if (resetPeriod || timePeriodStep != step
					|| (timePeriodStart > dayStart && prevTime < timePeriodStart)
					|| (timePeriodEnd < dayEnd && nextTime > timePeriodEnd)) {
				timePeriodStart = Math.max(prevTime, dayStart);
				timePeriodEnd = Math.min(nextTime + FORECAST_ANIMATION_DURATION_HOURS * HOUR_IN_MILLISECONDS, dayEnd);
				timePeriodStep = step;
				requireTimePeriodChange = true;
			}
		} else {
			long nearestTime = dateTime - prevTime < nextTime - dateTime ? prevTime : nextTime;
			if (resetPeriod || timePeriodStep != step
					|| (timePeriodStart > dayStart && nearestTime <= timePeriodStart)
					|| (timePeriodEnd < dayEnd && nearestTime >= timePeriodEnd)) {
				timePeriodStart = Math.max(nearestTime - step, dayStart);
				timePeriodEnd = Math.min(nearestTime + step, dayEnd);
				timePeriodStep = step;
				requireTimePeriodChange = true;
			}
		}
		this.dateTime = dateTime;
	}

	@Override
	public void destroyLayer() {
		super.destroyLayer();

		for (StateChangedListener<Float> listener : alphaChangeListeners) {
			for (WeatherBand weatherBand : weatherHelper.getWeatherBands()) {
				CommonPreference<Float> preference = weatherBand.getAlphaPreference();
				if (preference != null) {
					preference.removeListener(listener);
				}
			}
		}
	}

	@Override
	protected void cleanupResources() {
		super.cleanupResources();
		resetLayerProvider();
		if (weatherSourceChangeListener != null) {
			weatherSettings.weatherSource.removeListener(weatherSourceChangeListener);
		}
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	@Override
	public int getMaximumShownMapZoom() {
		return 22;
	}

	@Override
	public int getMinimumShownMapZoom() {
		return 1;
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tilesRect, DrawSettings drawSettings) {
		if (weatherLayer != WeatherLayer.LOW || plugin.getWeatherSource().isExternal()) return;
		long sampleTime = weatherHelper.getOfflineMapWeatherTime(plugin.getWeatherSource(), dateTime);
		if (plugin.isAnyDataVisible() && sampleTime != 0 && sampleTime != dateTime) {
			String time = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
					.format(new java.util.Date(sampleTime));
			String label = getApplication().getString(net.osmand.plus.R.string.weather_cached_sample_time, time);
			float density = getApplication().getResources().getDisplayMetrics().density;
			timeLabel.setTextSize(14 * density);
			timeLabel.setColor(android.graphics.Color.BLACK);
			timeLabel.setShadowLayer(3 * density, 0, 0, android.graphics.Color.WHITE);
			canvas.drawText(label, 16 * density, tilesRect.getPixHeight() * 0.62f, timeLabel);
		}
	}

	private void recreateLayerProvider(@NonNull MapRendererView mapRenderer, @NonNull WeatherTileResourcesManager resourcesManager) {
		renderedTime = weatherHelper.getOfflineMapWeatherTime(plugin.getWeatherSource(), dateTime);
		long start = renderedTime != dateTime ? renderedTime : timePeriodStart;
		long end = renderedTime != dateTime ? renderedTime : timePeriodEnd;
		long step = renderedTime != dateTime ? HOUR_IN_MILLISECONDS : timePeriodStep;
		BandIndexList bands = new BandIndexList();
		// A source can change before the first prepare pass initializes the cache.
		for (WeatherBand weatherBand : getVisibleBands()) {
			short bandIndex = weatherBand.getBandIndex();
			if (bandIndex != WeatherBand.WEATHER_BAND_NOTHING) {
				bands.add(bandIndex);
			}
		}
		if (!bands.isEmpty()) {
			net.osmand.core.jni.WeatherLayer weatherLayer = this.weatherLayer == WeatherLayer.LOW
					? net.osmand.core.jni.WeatherLayer.Low : net.osmand.core.jni.WeatherLayer.High;
			if (provider == null) {
				requireTimePeriodChange = false;
				provider = new WeatherRasterLayerProvider(resourcesManager, weatherLayer,
						start, end, step, bands, weatherHelper.isOfflineWeather());
				mapRenderer.setMapLayerProvider(view.getLayerIndex(this), provider);
				MapLayerConfiguration mapLayerConfiguration = new MapLayerConfiguration();
				mapLayerConfiguration.setOpacityFactor(1.0f);
				mapRenderer.setMapLayerConfiguration(view.getLayerIndex(this), mapLayerConfiguration);
			}
			if (requireTimePeriodChange) {
				requireTimePeriodChange = false;
				provider.setDateTime(start, end, step);
				mapRenderer.changeTimePeriod();
			}
			mapRenderer.setDateTime(renderedTime);
		}
	}

	private void resetLayerProvider() {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			int layerIndex = view.getLayerIndex(this);
			mapRenderer.resetMapLayerProvider(layerIndex);
			provider = null;
		}
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tilesRect, DrawSettings drawSettings) {
		super.onPrepareBufferImage(canvas, tilesRect, drawSettings);
		if (plugin.getWeatherSource().isExternal()) { resetLayerProvider(); return; }

		MapRendererView mapRenderer = getMapRenderer();
		WeatherTileResourcesManager resourcesManager = weatherHelper.getWeatherResourcesManager();
		if (view == null || mapRenderer == null || resourcesManager == null
				|| resourcesManager.getBandSettings().empty()) {
			return;
		}

		if (shouldUpdateLayer() || mapActivityInvalidated || mapRendererChanged) {
			if (shouldDrawLayer()) {
				recreateLayerProvider(mapRenderer, resourcesManager);
			} else {
				resetLayerProvider();
			}
		}
		mapRendererChanged = false;
		mapActivityInvalidated = false;
	}

	public boolean shouldDrawLayer() {
		if (plugin.getWeatherSource().isExternal()) return false;
		if (weatherHelper.getOfflineMapWeatherTime(plugin.getWeatherSource(), dateTime) == 0) return false;
		return (weatherSettings.weatherEnabled.get() || plugin.hasCustomForecast())
				&& !Algorithms.isEmpty(getVisibleBands());
	}

	private boolean shouldUpdateLayer() {
		int timesVersion = weatherHelper.getCachedWeatherTimesVersion();
		boolean timesChanged = cachedTimesVersion != timesVersion;
		cachedTimesVersion = timesVersion;
		boolean localData = weatherHelper.isOfflineWeather();
		boolean localDataChanged = localData != localDataCached;
		localDataCached = localData;
		boolean weatherEnabled = weatherSettings.weatherEnabled.get() || plugin.hasCustomForecast();
		boolean weatherEnabledChanged = weatherEnabled != weatherEnabledCached;
		weatherEnabledCached = weatherEnabled;

		List<WeatherBand> enabledBands = getVisibleBands();
		boolean layersChanged = !Algorithms.objectEquals(enabledBands, enabledBandsCached);
		enabledBandsCached = enabledBands;

		int bandsSettingsVersion = weatherHelper.getBandsSettingsVersion();
		boolean bandsSettingsChanged = bandsSettingsVersion != bandsSettingsVersionCached;
		bandsSettingsVersionCached = bandsSettingsVersion;

		long effectiveTime = weatherHelper.getOfflineMapWeatherTime(plugin.getWeatherSource(), dateTime);
		boolean dateTimeChanged = cachedDateTime != effectiveTime || renderedTime != effectiveTime;
		cachedDateTime = effectiveTime;
		if (dateTimeChanged) requireTimePeriodChange = true;

		if (weatherEnabledChanged || layersChanged || bandsSettingsChanged || localDataChanged || timesChanged)
			resetLayerProvider();

		return weatherEnabledChanged || layersChanged || bandsSettingsChanged || dateTimeChanged || localDataChanged || timesChanged;
	}

	@NonNull
	private List<WeatherBand> getVisibleBands() {
		return plugin.hasCustomForecast() ? weatherHelper.getVisibleForecastBands() : weatherHelper.getVisibleBands();
	}
}
