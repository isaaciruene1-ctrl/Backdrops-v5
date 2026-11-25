package com.example.engine.overlay

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.catalog.WallpaperCatalog
import com.example.data.media.WallpaperMediaService
import com.example.data.model.LockScreenSetDoc
import com.example.data.model.StudioWidgetItem
import com.example.data.model.UiStyleMode
import com.example.data.model.UserPreferencesDoc
import com.example.data.model.Wallpaper
import com.example.data.repository.GuestLocalUserStore
import com.example.data.weather.OpenMeteoWeatherRepository
import com.example.engine.notifications.LockNotificationGroup
import com.example.engine.notifications.LockNotificationItem
import com.example.ui.components.ClockBoxRect
import com.example.ui.components.ClockDesignConfig
import com.example.ui.components.ClockWidgetTelemetry
import com.example.ui.components.HyperOsLockClock
import com.example.ui.components.SharedLockScreenRenderer
import com.example.ui.components.WallpaperAsyncImage
import com.example.ui.screens.CanvasLookFilter
import com.example.ui.theme.LocalUiStyleMode
import com.example.widget.BackdropWidgetContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground wake & keyguard monitor service for Backdrop Lock Screen Mode.
 *
 * Replaces fragile `WindowManager` overlay attachment with reliable screen-off -> screen-on wake
 * tracking that presents [BackdropLockActivity] only after the display actually turns off, and
 * dismisses it immediately upon `ACTION_USER_PRESENT`.
 */
class LockScreenOverlayService : Service() {

    private var receiverRegistered = false
    private val screenWasOff = AtomicBoolean(false)
    @Volatile
    private var lastLaunchElapsedMs: Long = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenWasOff.set(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    handleScreenWake()
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenWasOff.set(false)
                    sendBroadcast(Intent(BackdropLockActivity.ACTION_DISMISS_LOCK_UI).setPackage(packageName))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        screenWasOff.set(pm?.isInteractive == false)
        ensureNotificationChannel()
        registerScreenLifecycleReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            screenWasOff.set(false)
            sendBroadcast(Intent(BackdropLockActivity.ACTION_DISMISS_LOCK_UI).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val store = GuestLocalUserStore.getInstance(this)
        val prefs = store.getCurrentPreferencesSync()
        if (!prefs.lockScreenOverlayEnabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_PREVIEW_TEST) {
            launchLockScreenActivity(previewMode = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerScreenLifecycleReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    internal fun handleScreenWake() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val prefs = GuestLocalUserStore.getInstance(this).getCurrentPreferencesSync()
        if (!prefs.lockScreenOverlayEnabled) {
            stopSelf()
            return
        }
        // Only activate when transitioning from a genuinely off display state to avoid mid-session popups
        if (!screenWasOff.getAndSet(false)) return

        val kgm = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val shouldPresent = kgm == null || kgm.isKeyguardLocked || !kgm.isKeyguardSecure
        if (!shouldPresent) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLaunchElapsedMs < 650L) return
        lastLaunchElapsedMs = now

        launchLockScreenActivity(previewMode = false)
    }

    private fun launchLockScreenActivity(previewMode: Boolean) {
        try {
            startActivity(BackdropLockActivity.createLaunchIntent(this, previewMode = previewMode))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to present BackdropLockActivity", e)
        }
    }

    private fun ensureNotificationChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Backdrop Lock Screen Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while Backdrop custom lock screen wake monitor is enabled"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, LockScreenOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentTitle("Backdrop Lock Screen Ready")
            .setContentText("Shows your custom clock & notifications on wake")
            .setContentIntent(openIntent)
            .addAction(0, "Turn Off", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "BackdropOverlaySvc"
        private const val CHANNEL_ID = "backdrop_lockscreen_overlay"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.engine.overlay.ACTION_START"
        const val ACTION_STOP = "com.example.engine.overlay.ACTION_STOP"
        const val ACTION_PREVIEW_TEST = "com.example.engine.overlay.ACTION_PREVIEW_TEST"

        fun start(context: Context, action: String = ACTION_START) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context.applicationContext, LockScreenOverlayService::class.java).setAction(action)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, LockScreenOverlayService::class.java).setAction(ACTION_STOP)
            runCatching {
                context.applicationContext.startService(intent)
            }
        }
    }
}

/**
 * Resolves the exact active lock-screen wallpaper + optional custom bitmap path without silently
 * snapping back to catalog index 0 when a custom edit or lock wallpaper has been set.
 */
internal data class ResolvedLockArtwork(
    val wallpaper: Wallpaper,
    val customBitmapPath: String,
    val customPhotoUri: String
)

internal fun resolveActiveLockArtwork(
    context: Context,
    preferences: UserPreferencesDoc,
    selectedSet: LockScreenSetDoc?
): ResolvedLockArtwork {
    val setWallpaper = selectedSet?.wallpaperId?.takeIf { it.isNotBlank() }?.let { WallpaperCatalog.getById(it) }
    val activeLockWp = preferences.activeLockWallpaperId.takeIf { it.isNotBlank() }?.let { WallpaperCatalog.getById(it) }
    val widgetWp = BackdropWidgetContract.getCurrentWallpaperId(context).let { WallpaperCatalog.getById(it) }
    val fallbackWp = WallpaperCatalog.allWallpapers.first()
    val resolvedWallpaper = setWallpaper ?: activeLockWp ?: widgetWp ?: fallbackWp

    val setPhotoUri = selectedSet?.customPhotoUri.orEmpty()
    val savedBitmapPath = preferences.activeLockCustomBitmapPath
        .takeIf { it.isNotBlank() && File(it).exists() }
        .orEmpty()

    return ResolvedLockArtwork(
        wallpaper = resolvedWallpaper,
        customBitmapPath = if (setPhotoUri.isBlank()) savedBitmapPath else "",
        customPhotoUri = setPhotoUri
    )
}

@Composable
internal fun LockScreenOverlaySurface(
    selectedSet: LockScreenSetDoc?,
    clockConfig: ClockDesignConfig,
    telemetry: ClockWidgetTelemetry,
    onDismissToUnlock: () -> Unit,
    preferences: UserPreferencesDoc = UserPreferencesDoc(),
    notificationGroups: List<LockNotificationGroup> = emptyList(),
    hideSensitiveNotifications: Boolean = false,
    isPreviewMode: Boolean = false,
    previewChargingOverlay: Boolean = false,
    onOpenNotification: (LockNotificationItem) -> Unit = {},
    onDismissNotification: (String) -> Unit = {},
    onClearAllNotifications: () -> Unit = {},
    onUpdateWidgetItem: ((StudioWidgetItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val resolved = remember(selectedSet, preferences.activeLockWallpaperId, preferences.activeLockCustomBitmapPath) {
        resolveActiveLockArtwork(context, preferences, selectedSet)
    }
    val lookFilter = remember(selectedSet?.lookFilterName) {
        runCatching {
            CanvasLookFilter.valueOf(selectedSet?.lookFilterName ?: CanvasLookFilter.ORIGINAL.name)
        }.getOrDefault(CanvasLookFilter.ORIGINAL)
    }
    val dimFraction = (selectedSet?.dimFraction ?: 0f).coerceIn(0f, 0.72f)
    val customBitmapState = produceState<Bitmap?>(
        initialValue = null,
        key1 = resolved.customBitmapPath,
        key2 = resolved.customPhotoUri
    ) {
        value = withContext(Dispatchers.IO) {
            when {
                resolved.customBitmapPath.isNotBlank() ->
                    runCatching { BitmapFactory.decodeFile(resolved.customBitmapPath) }.getOrNull()
                resolved.customPhotoUri.isNotBlank() ->
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(resolved.customPhotoUri))?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }.getOrNull()
                else -> null
            }
        }
    }
    val resolvedBackdropBitmap by produceState<Bitmap?>(
        initialValue = customBitmapState.value,
        key1 = customBitmapState.value,
        key2 = resolved.wallpaper.id,
        key3 = resolved.wallpaper.fallbackResId
    ) {
        val custom = customBitmapState.value
        if (custom != null) {
            value = custom
            return@produceState
        }
        val localRes = if (resolved.wallpaper.isBundledAsset && resolved.wallpaper.fallbackResId != 0) {
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeResource(context.resources, resolved.wallpaper.fallbackResId)
                }.getOrNull()
            }
        } else null
        if (localRes != null) {
            value = localRes
        }
        if (!resolved.wallpaper.isBundledAsset) {
            val remote = runCatching {
                WallpaperMediaService(context.applicationContext).loadFullBitmap(resolved.wallpaper)
            }.getOrNull()
            if (remote != null) {
                value = remote
            }
        }
    }

    val primaryColor = clockConfig.customPrimaryArgb?.let { Color(it.toInt()) } ?: Color(0xFFF6F8FC)
    val secondaryColor = clockConfig.customSecondaryArgb?.let { Color(it.toInt()) }
        ?: resolved.wallpaper.dominantColors.firstOrNull()?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primary

    val uiStyle = LocalUiStyleMode.current
    val isGlass = uiStyle == UiStyleMode.FROSTED_GLASS_OS

    var liveBatteryPercent by remember {
        mutableIntStateOf(
            if (telemetry.batteryPercent > 0) telemetry.batteryPercent
            else OpenMeteoWeatherRepository.readBatteryPercent(context)
        )
    }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    liveBatteryPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.getOrNull()
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                liveBatteryPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            }
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val effectiveTelemetry = remember(telemetry, liveBatteryPercent) {
        telemetry.copy(batteryPercent = liveBatteryPercent)
    }

    var showCredentialGate by remember(preferences.hasLockCredential) {
        mutableStateOf(false)
    }

    BackHandler(enabled = showCredentialGate) {
        showCredentialGate = false
    }

    fun requestUnlockOrChallenge() {
        if (preferences.hasLockCredential) {
            showCredentialGate = true
        } else {
            onDismissToUnlock()
        }
    }

    var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C12))
            .pointerInput(preferences.hasLockCredential, showCredentialGate) {
                if (!showCredentialGate) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumulatedPx = 0f },
                        onDragEnd = {
                            if (dragAccumulatedPx < -160f) {
                                requestUnlockOrChallenge()
                            }
                            dragAccumulatedPx = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            dragAccumulatedPx += dragAmount
                        }
                    )
                }
            }
            .testTag("lockscreen_overlay_root")
    ) {
        val applySetTransform = selectedSet != null && resolved.customBitmapPath.isBlank()
        val lockZoom = if (applySetTransform) selectedSet!!.zoomScale.coerceIn(0.85f, 4.0f) else 1.0f
        val lockPan = if (applySetTransform) Offset(selectedSet!!.panOffsetX, selectedSet.panOffsetY) else Offset.Zero
        val lockRotation = if (applySetTransform) selectedSet!!.rotationDegrees else 0f

        SharedLockScreenRenderer(
            wallpaper = resolved.wallpaper,
            customBitmap = customBitmapState.value?.takeIf { !it.isRecycled },
            backdropBitmap = resolvedBackdropBitmap?.takeIf { !it.isRecycled },
            clockConfig = clockConfig,
            telemetry = effectiveTelemetry,
            preferences = preferences,
            zoomScale = lockZoom,
            panOffset = lockPan,
            rotationDegrees = lockRotation,
            dimFraction = dimFraction,
            lookFilter = lookFilter,
            notificationGroups = if (showCredentialGate) emptyList() else notificationGroups,
            expandedNotificationKeys = expandedKeys,
            hideSensitiveNotifications = hideSensitiveNotifications,
            isCharging = isCharging,
            previewChargingOverlay = previewChargingOverlay,
            showChromeControls = !showCredentialGate,
            isPreviewMode = isPreviewMode,
            useLiveTime = true,
            onUnlockClick = { requestUnlockOrChallenge() },
            onOpenNotification = onOpenNotification,
            onDismissNotification = onDismissNotification,
            onClearAllNotifications = onClearAllNotifications,
            onUpdateWidgetItem = onUpdateWidgetItem
        )

        AnimatedVisibility(
            visible = showCredentialGate,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.64f))
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                LockScreenCredentialGate(
                    preferences = preferences,
                    onUnlockSuccess = {
                        showCredentialGate = false
                        onDismissToUnlock()
                    },
                    onCancelToClock = { showCredentialGate = false }
                )
            }
        }
    }
}
