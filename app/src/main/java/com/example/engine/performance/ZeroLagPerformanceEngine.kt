package com.example.engine.performance

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.LruCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.catalog.WallpaperCatalog
import com.example.data.media.BackdropImageLoader
import com.example.data.model.DiscoveryCollection
import com.example.data.model.LiquidGlassTier
import com.example.data.model.LockScreenSetDoc
import com.example.data.model.UserPreferencesDoc
import com.example.data.model.Wallpaper
import com.example.engine.crash.ZeroCrashEngine
import com.example.engine.depth.OnlineVisionDepthSolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class PerformanceTier {
    LOW_RAM,
    BALANCED,
    HIGH_END
}

data class PreloadedTabMetadata(
    val route: String,
    val titleList: List<String>,
    val wallpaperIds: List<String>,
    val preparedAtMs: Long
)

data class InstantLockWarmState(
    val activeSetId: String,
    val wallpaperId: String,
    val clockConfigJson: String,
    val wallpaperBitmap: Bitmap?,
    val cutoutBitmap: Bitmap?,
    val warmedAtMs: Long
)

/**
 * Hardware-adaptive Zero Lag & Preloading Catcher Engine V5 (Backdrops 1.5):
 * - Sub-Engine A (Predictive Carousel, Tab & Neighbor Telemetry Warmer)
 * - Sub-Engine B (Tier-Guarded RAM & In-Flight Queue Cap)
 * - Instant Lock Screen Engine (pre-warms active lock wallpaper, cutout & configuration)
 * - Memory pressure listener releasing unnecessary caches cleanly
 */
object ZeroLagPerformanceEngine {

    private val initialized = AtomicBoolean(false)
    private val inFlightPreloads = ConcurrentHashMap.newKeySet<String>()
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lowRamSemaphore = Semaphore(2)
    private val balancedSemaphore = Semaphore(3)
    private val highEndSemaphore = Semaphore(4)

    @Volatile
    private var cachedTier: PerformanceTier? = null

    private fun semaphoreForTier(tier: PerformanceTier): Semaphore = when (tier) {
        PerformanceTier.LOW_RAM -> lowRamSemaphore
        PerformanceTier.BALANCED -> balancedSemaphore
        PerformanceTier.HIGH_END -> highEndSemaphore
    }

    internal fun maxQueueBudgetForTier(tier: PerformanceTier): Int = when (tier) {
        PerformanceTier.LOW_RAM -> 8
        PerformanceTier.BALANCED -> 16
        PerformanceTier.HIGH_END -> 28
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        classifyTier(appContext)
        if (initialized.compareAndSet(false, true)) {
            ZeroCrashEngine.registerMemoryCleanupHook("ZeroLagPerformanceEngine") {
                handleTrimMemory(appContext, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
            appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    handleTrimMemory(appContext, level)
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Android API")
                override fun onLowMemory() {
                    handleTrimMemory(appContext, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }
            })
        }
    }

    fun classifyTier(context: Context): PerformanceTier {
        cachedTier?.let { return it }
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        val memClass = am?.memoryClass ?: 128
        val cores = Runtime.getRuntime().availableProcessors()
        val tier = when {
            isLowRam || memClass <= 128 || cores <= 4 -> PerformanceTier.LOW_RAM
            memClass >= 384 && cores >= 8 -> PerformanceTier.HIGH_END
            else -> PerformanceTier.BALANCED
        }
        cachedTier = tier
        return tier
    }

    fun recommendLiquidGlassTier(context: Context): LiquidGlassTier {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        val memClass = am?.memoryClass ?: 192
        val tier = LiquidGlassTier.recommendForDevice(
            ramMb = memClass * 16,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            isLowRamDevice = isLowRam
        )
        return tier
    }

    fun resolveEffectiveLiquidGlassTier(context: Context, requested: LiquidGlassTier): LiquidGlassTier =
        if (requested == LiquidGlassTier.AUTO_RECOMMENDED) recommendLiquidGlassTier(context) else requested

    fun memoryCachePercent(tier: PerformanceTier): Double = when (tier) {
        PerformanceTier.LOW_RAM -> 0.12
        PerformanceTier.BALANCED -> 0.18
        PerformanceTier.HIGH_END -> 0.24
    }

    fun diskCacheLimitBytes(tier: PerformanceTier): Long = when (tier) {
        PerformanceTier.LOW_RAM -> 80L * 1024L * 1024L
        PerformanceTier.BALANCED -> 150L * 1024L * 1024L
        PerformanceTier.HIGH_END -> 240L * 1024L * 1024L
    }

    fun preferredThumbnailBitmapConfig(context: Context): Bitmap.Config =
        when (classifyTier(context)) {
            PerformanceTier.LOW_RAM -> Bitmap.Config.RGB_565
            PerformanceTier.BALANCED, PerformanceTier.HIGH_END -> Bitmap.Config.ARGB_8888
        }

    fun handleTrimMemory(context: Context, level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                runCatching { BackdropImageLoader.get(context).memoryCache?.clear() }
                inFlightPreloads.clear()
                TabPreloadingEngine.trimAll()
                InstantLockScreenEngine.trimBitmaps()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                runCatching {
                    BackdropImageLoader.get(context).memoryCache?.let { mc ->
                        mc.trimMemory(mc.maxSize / 2)
                    }
                }
                TabPreloadingEngine.trimToBudget(maxTabs = 2)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                runCatching {
                    BackdropImageLoader.get(context).memoryCache?.let { mc ->
                        mc.trimMemory((mc.maxSize * 3) / 4)
                    }
                }
            }
        }
    }

    /**
     * V4 Preloading Catcher: warms upcoming neighbor items around the current scroll/detail index.
     */
    fun prewarmNeighbors(context: Context, wallpapers: List<Wallpaper>, centerIndex: Int, radius: Int = 3) {
        if (wallpapers.isEmpty()) return
        val start = (centerIndex - radius).coerceAtLeast(0)
        val end = (centerIndex + radius).coerceAtMost(wallpapers.lastIndex)
        if (start > end) return
        preloadThumbnails(context, wallpapers.subList(start, end + 1), maxCount = (radius * 2 + 1).coerceAtMost(8))
    }

    /**
     * Warm up visible + ahead-of-scroll thumbnails without saturating the UI thread or network.
     */
    fun preloadThumbnails(context: Context, wallpapers: List<Wallpaper>, maxCount: Int = 8) {
        if (wallpapers.isEmpty()) return
        val appContext = context.applicationContext
        init(appContext)
        val tier = classifyTier(appContext)
        val semaphore = semaphoreForTier(tier)
        val count = when (tier) {
            PerformanceTier.LOW_RAM -> maxCount.coerceAtMost(4)
            PerformanceTier.BALANCED -> maxCount.coerceAtMost(8)
            PerformanceTier.HIGH_END -> maxCount.coerceAtMost(14)
        }
        val maxQueue = maxQueueBudgetForTier(tier)
        if (inFlightPreloads.size >= maxQueue) return
        val isOnline = OnlineVisionDepthSolver.isOnlineNetworkAvailable(appContext)
        val candidates = wallpapers.take(count).filter { wp ->
            !wp.isBundledAsset && wp.thumbnailUrl.isNotBlank() && isOnline
        }
        if (candidates.isEmpty()) return
        preloadScope.launch {
            val loader = BackdropImageLoader.get(appContext)
            val useRgb565 = tier == PerformanceTier.LOW_RAM
            candidates.forEach { wp ->
                if (inFlightPreloads.size >= maxQueue) return@forEach
                val cacheKey = "${wp.id}_thumb"
                if (!inFlightPreloads.add(cacheKey)) return@forEach
                launch {
                    semaphore.withPermit {
                        try {
                            val request = ImageRequest.Builder(appContext)
                                .data(wp.thumbnailUrl)
                                .memoryCacheKey(cacheKey)
                                .diskCacheKey(cacheKey)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .allowRgb565(useRgb565)
                                .size(540, 840)
                                .build()
                            loader.execute(request)
                        } catch (_: Exception) {
                        } finally {
                            inFlightPreloads.remove(cacheKey)
                        }
                    }
                }
            }
        }
    }

    /**
     * Intelligent Tab Preloading Engine:
     * Preloads lightweight UI metadata & prioritized thumbnails across Home, Explore & Favorites
     * based on current location and user navigation affinity.
     */
    object TabPreloadingEngine {
        private val metadataCache = LruCache<String, PreloadedTabMetadata>(6)
        private val _preparedRoutes = MutableStateFlow<Set<String>>(emptySet())
        val preparedRoutes: StateFlow<Set<String>> = _preparedRoutes.asStateFlow()

        fun preloadLikelyDestinations(
            context: Context,
            currentRoute: String,
            favoriteWallpapers: List<Wallpaper> = emptyList(),
            exploreResults: List<Wallpaper> = WallpaperCatalog.allWallpapers
        ) {
            val appContext = context.applicationContext
            val priorityOrder = when (currentRoute) {
                "home" -> listOf("explore", "favorites", "collections", "profile")
                "explore" -> listOf("home", "favorites", "collections", "profile")
                "favorites" -> listOf("explore", "home", "collections")
                "collections" -> listOf("favorites", "explore", "home")
                else -> listOf("home", "explore", "favorites")
            }
            priorityOrder.take(3).forEach { target ->
                prepareDestination(appContext, target, favoriteWallpapers, exploreResults)
            }
        }

        fun prepareDestination(
            context: Context,
            route: String,
            favoriteWallpapers: List<Wallpaper> = emptyList(),
            exploreResults: List<Wallpaper> = WallpaperCatalog.allWallpapers
        ): PreloadedTabMetadata {
            val existing = metadataCache.get(route)
            val now = System.currentTimeMillis()
            if (existing != null && (now - existing.preparedAtMs) < 30_000L) {
                return existing
            }
            val list = when (route) {
                "home" -> WallpaperCatalog.getForCollection(DiscoveryCollection.FEATURED).take(12) +
                    WallpaperCatalog.getForCollection(DiscoveryCollection.TRENDING).take(8)
                "explore" -> exploreResults.take(16)
                "favorites" -> favoriteWallpapers.take(16).ifEmpty {
                    WallpaperCatalog.getForCollection(DiscoveryCollection.EDITORS_PICKS).take(8)
                }
                "collections" -> WallpaperCatalog.getForCollection(DiscoveryCollection.DEPTH_STUDIO).take(8)
                else -> WallpaperCatalog.getForCollection(DiscoveryCollection.FEATURED).take(6)
            }
            val meta = PreloadedTabMetadata(
                route = route,
                titleList = list.map { it.title },
                wallpaperIds = list.map { it.id },
                preparedAtMs = now
            )
            metadataCache.put(route, meta)
            _preparedRoutes.value = (_preparedRoutes.value + route)
            preloadThumbnails(context, list, maxCount = 8)
            return meta
        }

        fun getCachedMetadata(route: String): PreloadedTabMetadata? = metadataCache.get(route)

        fun trimToBudget(maxTabs: Int) {
            metadataCache.trimToSize(maxTabs.coerceAtLeast(1))
        }

        fun trimAll() {
            metadataCache.evictAll()
            _preparedRoutes.value = emptySet()
        }
    }

    /**
     * Predictive Floating Pill Navigation Engine:
     * Hooks directly into Frosted OS floating pill press/drag gestures so the hovered tab
     * begins preparing before finger release.
     */
    object PredictivePillNavigationEngine {
        private val _activePreloadRoute = MutableStateFlow<String?>(null)
        val activePreloadRoute: StateFlow<String?> = _activePreloadRoute.asStateFlow()

        private val _preloadTriggerCount = MutableStateFlow(0)
        val preloadTriggerCount: StateFlow<Int> = _preloadTriggerCount.asStateFlow()

        @Volatile
        private var lastHoveredRoute: String? = null

        fun onPillHoverChanged(
            context: Context,
            hoveredRoute: String,
            favoriteWallpapers: List<Wallpaper> = emptyList(),
            exploreResults: List<Wallpaper> = WallpaperCatalog.allWallpapers
        ) {
            if (hoveredRoute.isBlank() || hoveredRoute == lastHoveredRoute) return
            lastHoveredRoute = hoveredRoute
            _activePreloadRoute.value = hoveredRoute
            _preloadTriggerCount.value += 1
            TabPreloadingEngine.prepareDestination(
                context = context,
                route = hoveredRoute,
                favoriteWallpapers = favoriteWallpapers,
                exploreResults = exploreResults
            )
        }

        fun onPillDragEnded() {
            lastHoveredRoute = null
            _activePreloadRoute.value = null
        }
    }

    /**
     * Instant Lock Screen Engine:
     * Pre-warms the active lock wallpaper, subject cutout, and clock/notification state so
     * waking Lock Screen Mode or opening Preview displays without blank flash or lag.
     */
    object InstantLockScreenEngine {
        private val _warmState = MutableStateFlow<InstantLockWarmState?>(null)
        val warmState: StateFlow<InstantLockWarmState?> = _warmState.asStateFlow()

        fun warmLockConfiguration(
            context: Context,
            preferences: UserPreferencesDoc,
            activeSet: LockScreenSetDoc?
        ) {
            val wallpaperId = activeSet?.wallpaperId?.ifBlank { preferences.activeLockWallpaperId }
                ?: preferences.activeLockWallpaperId.ifBlank { preferences.activeHomeWallpaperId }
            val wallpaper = WallpaperCatalog.getById(wallpaperId)
            val current = _warmState.value
            val configJson = activeSet?.clockConfigJson.orEmpty()
            if (current != null &&
                current.activeSetId == (activeSet?.id.orEmpty()) &&
                current.wallpaperId == wallpaperId &&
                current.clockConfigJson == configJson
            ) {
                return
            }
            if (wallpaper != null) {
                preloadThumbnails(context, listOf(wallpaper), maxCount = 2)
            }
            _warmState.value = InstantLockWarmState(
                activeSetId = activeSet?.id.orEmpty(),
                wallpaperId = wallpaperId,
                clockConfigJson = configJson,
                wallpaperBitmap = current?.takeIf { it.wallpaperId == wallpaperId }?.wallpaperBitmap,
                cutoutBitmap = current?.takeIf { it.wallpaperId == wallpaperId }?.cutoutBitmap,
                warmedAtMs = System.currentTimeMillis()
            )
        }

        fun updateWarmBitmaps(wallpaperId: String, wallpaperBitmap: Bitmap?, cutoutBitmap: Bitmap?) {
            val existing = _warmState.value
            val validNewWallpaper = wallpaperBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            val validNewCutout = cutoutBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            val fallbackWallpaper = existing?.takeIf { it.wallpaperId == wallpaperId }?.wallpaperBitmap
                ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            val fallbackCutout = existing?.takeIf { it.wallpaperId == wallpaperId }?.cutoutBitmap
                ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            _warmState.value = (existing ?: InstantLockWarmState(
                activeSetId = "",
                wallpaperId = wallpaperId,
                clockConfigJson = "",
                wallpaperBitmap = null,
                cutoutBitmap = null,
                warmedAtMs = System.currentTimeMillis()
            )).copy(
                wallpaperId = wallpaperId,
                wallpaperBitmap = validNewWallpaper ?: fallbackWallpaper,
                cutoutBitmap = validNewCutout ?: fallbackCutout,
                warmedAtMs = System.currentTimeMillis()
            )
        }

        fun trimBitmaps() {
            val existing = _warmState.value ?: return
            _warmState.value = existing.copy(wallpaperBitmap = null, cutoutBitmap = null)
        }
    }
}
