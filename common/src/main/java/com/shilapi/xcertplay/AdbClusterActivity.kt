package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.ColorDrawable
import java.util.UUID
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** A separate task for stream 111. Routing is control traffic; video stays in the app process. */
class AdbClusterActivity : Activity() {
    private var waiting: TextView? = null
    private var surface: Surface? = null
    private var videoTexture: ClusterVideoTexture? = null
    private var overlayRegion: FrameLayout? = null
    private var turnCard: ClusterTurnCardView? = null
    private var safeAreaPreview: SafeAreaEditorView? = null
    internal var snapshotExecutor: (android.content.Context, String, () -> Unit) -> Unit = ClusterSnapshot::captureAsync
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshot5s = Runnable { captureSnapshot("auto5s") }
    private val snapshot30s = Runnable { captureSnapshot("auto30s") }
    internal var routeStatus = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdbClusterRouter.enabled(this)) { finish(); return }
        val token = intent.getStringExtra("cluster_launch_token")
        if (!ClusterActivityOutput.acceptsToken(token)) { finish(); return }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val video = ClusterVideoTexture(this, onPresented = {
            ClusterActivityOutput.countPresented(token)
            ClusterActivityOutput.presented(this)
        }) { next ->
            surface?.let { ClusterActivityOutput.detach(this, it) }
            surface = next
            if (next != null && ClusterActivityOutput.activity.get() === this)
                ClusterActivityOutput.attach(this, next)
        }.also { videoTexture = it }
        val legacy = AirPlayPersistence.loadLegacyClusterEnabled(this)
        val videoRegion = FrameLayout(this)
        videoRegion.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMirrorLayout() }
        videoRegion.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoRegion, FrameLayout.LayoutParams(-1, -1))
        if (legacy) root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            LegacyClusterLayout.plan(root.width, root.height)?.let { plan ->
                val current = videoRegion.layoutParams as FrameLayout.LayoutParams
                if (current.width != plan.width || current.height != plan.height ||
                    current.leftMargin != plan.left || current.topMargin != plan.top) {
                    videoRegion.layoutParams = FrameLayout.LayoutParams(plan.width, plan.height).apply {
                        leftMargin = plan.left; topMargin = plan.top
                    }
                }
            }
        }
        waiting = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        videoRegion.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        val overlays = FrameLayout(this).also { overlayRegion = it }
        turnCard = ClusterTurnCardView(this).apply { visibility = View.GONE }
        overlays.addView(turnCard, FrameLayout.LayoutParams(-1, -1))
        overlays.addView(ClusterMusicView(this), FrameLayout.LayoutParams(-1, -1))
        overlays.addView(ClusterSpeedometerView(this), FrameLayout.LayoutParams(-1, -1))
        root.addView(overlays, FrameLayout.LayoutParams(-1, -1))
        safeAreaPreview = SafeAreaEditorView(this).apply {
            visibility = View.GONE
            interactive = false
            dimOutside = false
        }
        root.addView(safeAreaPreview, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.post {
            val attached = root.display?.displayId ?: -1
            if (attached > 0 && !legacy) confirmDisplay(token, attached)
            else Thread({
                val verified = AdbClusterRouter.verify(applicationContext, taskId)
                runOnUiThread { confirmDisplay(token, verified ?: -1) }
            }, "cluster-display-check").start()
        }
        updateStream()
    }

    internal fun updateStream() {
        val hasPicture = if (ClusterActivityOutput.mirrorEnabled) {
            ClusterActivityOutput.mirrorAttached && ClusterActivityOutput.mirrorPresented
        } else ClusterActivityOutput.streamActive
        waiting?.visibility = if (hasPicture || (ClusterActivityOutput.mirrorEnabled && !ClusterActivityOutput.mirrorAttached))
            View.GONE else View.VISIBLE
        waiting?.text = ""
        videoTexture?.alpha = if (ClusterActivityOutput.mirrorEnabled && !hasPicture) 0f else 1f
        updateMirrorLayout()
        updateTurnCard()
    }

    internal fun updateMirrorLayout() {
        val video = videoTexture ?: return
        if (!ClusterActivityOutput.mirrorEnabled) {
            val current = video.layoutParams as FrameLayout.LayoutParams
            if (current.width != -1 || current.height != -1 || current.leftMargin != 0 || current.topMargin != 0) {
                video.layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            waiting?.let { view ->
                val params = view.layoutParams as FrameLayout.LayoutParams
                if (params.width != -1 || params.height != -1 || params.leftMargin != 0 || params.topMargin != 0)
                    view.layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            video.setContentRect(null)
            ClusterActivityOutput.mirrorRect = null
            ClusterActivityOutput.mirrorCrop = null
            return
        }
        val region = video.parent as? View ?: return
        val viewWidth = region.width
        val viewHeight = region.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val sourceWidth = ClusterActivityOutput.mirrorSourceWidth
        val sourceHeight = ClusterActivityOutput.mirrorSourceHeight
        val keep = ClusterMirrorCrop.keep(sourceWidth, sourceHeight, ClusterActivityOutput.mirrorDockEdge,
            AirPlayPersistence.loadRightHandDrive(this), AirPlayPersistence.loadClusterMirrorDockCrop(this))
        val safe = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        val scaledSafe = com.shilapi.xcertplay.airplay.AirPlaySafeArea.scaleRect(safe,
            DiLink4ClusterDisplay.STREAM_WIDTH, DiLink4ClusterDisplay.STREAM_HEIGHT, viewWidth, viewHeight)
        val size = AirPlayPersistence.loadClusterMirrorSize(this)
        val rect = ClusterMirrorLayout.plan(viewWidth, viewHeight,
            keep.width().roundToInt(), keep.height().roundToInt(),
            scaledSafe, size, AirPlayPersistence.loadClusterMirrorCustom(this))
        val left = rect.left.roundToInt()
        val top = rect.top.roundToInt()
        val width = rect.right.roundToInt() - left
        val height = rect.bottom.roundToInt() - top
        val current = video.layoutParams as FrameLayout.LayoutParams
        if (current.width != width || current.height != height || current.leftMargin != left || current.topMargin != top) {
            video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = left; topMargin = top
            }
        }
        waiting?.let { view ->
            val waitingParams = view.layoutParams as FrameLayout.LayoutParams
            if (waitingParams.width != width || waitingParams.height != height ||
                waitingParams.leftMargin != left || waitingParams.topMargin != top) {
                view.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = left; topMargin = top
                }
            }
        }
        val displayed = if (size == ClusterMirrorSize.CUSTOM && width > 0 && height > 0) {
            val view = AirPlayPersistence.loadClusterMirrorView(this)
            ClusterMirrorCrop.frame(keep, width.toFloat() / height, view.zoom, view.focusX, view.focusY)
        } else keep
        video.setContentRect(RectF(displayed.left / sourceWidth, displayed.top / sourceHeight,
            displayed.right / sourceWidth, displayed.bottom / sourceHeight))
        ClusterActivityOutput.mirrorRect = RectF(rect)
        ClusterActivityOutput.mirrorCrop = RectF(keep)
    }

    internal fun updateTurnCard() {
        turnCard?.setLayout(ClusterActivityOutput.cardX, ClusterActivityOutput.cardY, ClusterActivityOutput.cardSize)
        turnCard?.setOpacity(ClusterActivityOutput.cardOpacity)
        turnCard?.setNightMode(ClusterActivityOutput.cardNight)
        val show = (ClusterActivityOutput.streamActive && !ClusterActivityOutput.mirrorEnabled) ||
            (ClusterActivityOutput.mirrorEnabled && ClusterActivityOutput.mirrorAttached &&
                AirPlayPersistence.loadClusterMirrorTurnCard(this))
        turnCard?.setGuidance(if (show) ClusterActivityOutput.guidance else null)
        turnCard?.setLanes(if (show && AirPlayPersistence.loadClusterLaneStrip(this))
            ClusterActivityOutput.lanes else emptyList())
        updateSafeAreaPreview()
    }

    internal fun updateSafeAreaPreview() {
        val rect = ClusterActivityOutput.previewRect
        safeAreaPreview?.apply {
            visibility = if (rect == null) View.GONE else View.VISIBLE
            if (rect != null) { setRect(rect, 1920, 720); bringToFront() }
        }
    }

    private fun confirmDisplay(token: String?, display: Int) {
        if (isFinishing || isDestroyed || !ClusterActivityOutput.confirm(this, token, display)) {
            if (token != null && !ClusterActivityOutput.hasConfirmedRoute())
                com.shilapi.xcertplay.hud.BydOemClusterNavi.release(this, token)
            finish(); return
        }
        routeStatus = getString(R.string.adb_cluster_routed)
        surface?.let { ClusterActivityOutput.attach(this, it) }
        updateStream()
        snapshotHandler.removeCallbacks(snapshot5s)
        snapshotHandler.removeCallbacks(snapshot30s)
        snapshotHandler.postDelayed(snapshot5s, 5_000L)
        snapshotHandler.postDelayed(snapshot30s, 30_000L)
    }

    private fun captureSnapshot(trigger: String) {
        if (!isFinishing && !isDestroyed && ClusterActivityOutput.activity.get() === this)
            snapshotExecutor(applicationContext, trigger) { }
    }

    internal fun route() { ClusterActivityOutput.retry() }

    // Losing focus must not detach stream 111. The texture owns the rendering lifetime.
    override fun onDestroy() {
        snapshotHandler.removeCallbacks(snapshot5s)
        snapshotHandler.removeCallbacks(snapshot30s)
        videoTexture?.close()
        videoTexture = null
        if (ClusterActivityOutput.activity.get() === this) {
            intent.getStringExtra("cluster_launch_token")?.let {
                com.shilapi.xcertplay.hud.BydOemClusterNavi.release(this, it)
            }
            ClusterActivityOutput.activity.clear()
            ClusterActivityOutput.launchPending = false
            ClusterActivityOutput.retry()
        }
        super.onDestroy()
    }
}

/** Main-thread handoff, with identity checks so a stale activity cannot clear a newer surface. */
internal object ClusterActivityOutput {
    var activity = WeakReference<AdbClusterActivity>(null)
    var launchPending = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var launchToken: String? = null
    @Volatile private var expectedDisplay = -1
    @Volatile var framesPresented: Long = 0
        private set
    var mirrorEnabled = false
        private set
    var mirrorAttached = false
        private set
    var mirrorPresented = false
        private set
    var mirrorSourceWidth = 16
        private set
    var mirrorSourceHeight = 9
        private set
    var mirrorDockEdge: Int? = null
        private set
    var mirrorNavigating = false
        private set
    var mirrorRect: RectF? = null
    var mirrorCrop: RectF? = null
    private var launchHost = WeakReference<Activity>(null)
    private var legacyPresented = false
    private val retryTick = Runnable {
        launchHost.get()?.let { host ->
            ensure(host)
            if (AirPlayPersistence.loadLegacyClusterEnabled(host) && !legacyPresented) retry()
        }
    }

    /** Texture updates confirm an output frame, rather than only a successful shell reply. */
    fun presented(window: AdbClusterActivity) {
        if (legacyPresented || activity.get() !== window || !AirPlayPersistence.loadLegacyClusterEnabled(window) ||
            !streamActive || surfaceOwner !== window || surface?.isValid != true || !hasConfirmedRoute()) return
        legacyPresented = true
        main.removeCallbacks(retryTick)
    }

    fun acceptsToken(token: String?): Boolean = token != null && token == launchToken && (hostOwner != null || previewHost.get() != null)
    fun countPresented(token: String?) {
        if (acceptsToken(token)) {
            framesPresented++
            if (mirrorAttached && !mirrorPresented) {
                mirrorPresented = true
                activity.get()?.updateStream()
            }
        }
    }

    fun setMirrorState(enabled: Boolean, attached: Boolean, sourceWidth: Int, sourceHeight: Int,
        dockEdge: Int? = null, navigating: Boolean = false) {
        val width = sourceWidth.takeIf { it > 0 } ?: 16
        val height = sourceHeight.takeIf { it > 0 } ?: 9
        if (mirrorEnabled == enabled && mirrorAttached == attached &&
            mirrorSourceWidth == width && mirrorSourceHeight == height &&
            mirrorDockEdge == dockEdge && mirrorNavigating == navigating) return
        val newAttachment = attached && (!mirrorAttached || !mirrorEnabled)
        mirrorEnabled = enabled
        mirrorAttached = attached
        if (!attached || newAttachment) mirrorPresented = false
        mirrorSourceWidth = width
        mirrorSourceHeight = height
        mirrorDockEdge = dockEdge
        mirrorNavigating = navigating
        activity.get()?.updateStream()
    }

    fun refreshMirrorLayout() { activity.get()?.updateMirrorLayout() }
    fun hasConfirmedRoute(): Boolean = activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true &&
        expectedDisplay > 0
    fun diagnosticDisplay(): Int? = expectedDisplay.takeIf { hasConfirmedRoute() }
    /** A launch is running, or the shell accepted it and the window has not confirmed its display yet. */
    fun routeSettling(): Boolean = !hasConfirmedRoute() && (launchPending || (launchToken != null && expectedDisplay > 0))
    fun confirm(window: AdbClusterActivity, token: String?, display: Int): Boolean {
        if (!acceptsToken(token) || display <= 0 || display != expectedDisplay ||
            !AdbClusterRouter.enabled(window)) return false
        activity.get()?.takeIf { it !== window }?.finish()
        activity = WeakReference(window)
        launchPending = false
        main.removeCallbacks(retryTick)
        if (AirPlayPersistence.loadLegacyClusterEnabled(window) && !legacyPresented) retry()
        return true
    }
    fun retry(force: Boolean = false) {
        if (force) legacyPresented = false
        main.removeCallbacks(retryTick)
        val host = launchHost.get() ?: return
        if (AirPlayPersistence.loadLegacyClusterEnabled(host) && legacyPresented) return
        if (hostOwner != null || previewHost.get() != null) main.postDelayed(retryTick, 5_000L)
    }
    var mainTaskId = -1
        private set
    var surface: Surface? = null
        private set
    private var surfaceOwner: Any? = null
    private var hostOwner: Any? = null
    private var onSurface: ((Surface?) -> Unit)? = null
    var streamActive = false
        private set

    var guidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
        private set
    @Volatile var lanes: List<LaneGuidanceLane> = emptyList()
        private set
    var onLanesChanged: ((List<LaneGuidanceLane>) -> Unit)? = null
    var cardX = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        private set
    var cardY = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
        private set
    var cardSize = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        private set
    var cardOpacity = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT
        private set
    var cardNight = false
        private set

    fun setLanes(next: List<LaneGuidanceLane>) {
        val copy = next.toList()
        main.post {
            if (lanes != copy) {
                lanes = copy
                activity.get()?.updateTurnCard()
                onLanesChanged?.invoke(copy)
            }
        }
    }

    private fun clearLanes() {
        if (lanes.isEmpty()) return
        lanes = emptyList()
        onLanesChanged?.invoke(lanes)
    }

    fun setTurnCard(next: com.shilapi.xcertplay.hud.ClusterTurnGuidance?, x: Int, y: Int,
        size: Int, opacity: Int, night: Boolean) {
        guidance = next
        if (next == null) clearLanes()
        cardX = x
        cardY = y
        cardSize = size
        cardOpacity = opacity
        cardNight = night
        activity.get()?.updateTurnCard()
    }

    private var previewOwner: Any? = null
    private var previewHost = WeakReference<Activity>(null)
    var previewRect: com.shilapi.xcertplay.airplay.SafeAreaRect? = null
        private set

    fun beginSafeAreaPreview(owner: Any, rect: com.shilapi.xcertplay.airplay.SafeAreaRect, host: Activity? = null) {
        previewOwner = owner
        previewHost = WeakReference(host)
        updateSafeAreaPreview(owner, rect)
        // Calibration owns a window, not a CarPlay session or decoder callback.
        if (hostOwner == null && host != null) ensure(host)
    }

    fun updateSafeAreaPreview(owner: Any, rect: com.shilapi.xcertplay.airplay.SafeAreaRect) {
        if (previewOwner !== owner) return
        previewRect = rect.clampTo(1920, 720)
        activity.get()?.updateSafeAreaPreview()
    }

    fun endSafeAreaPreview(owner: Any) {
        if (previewOwner !== owner) return
        previewOwner = null
        previewHost.clear()
        previewRect = null
        activity.get()?.updateSafeAreaPreview()
        if (hostOwner == null) closeRoute()
    }

    fun bind(owner: Any, taskId: Int, callback: (Surface?) -> Unit) {
        if (hostOwner !== owner && !hasConfirmedRoute() && previewHost.get() == null) {
            ++generation
            launchToken = null
            expectedDisplay = -1
            launchPending = false
        }
        hostOwner = owner
        if (owner is Activity) launchHost = WeakReference(owner)
        mainTaskId = taskId
        onSurface = callback
        callback(surface)
    }

    fun ensure(host: Activity) {
        if (!AdbClusterRouter.enabled(host) || host.isFinishing || host.isDestroyed) return
        if (AirPlayPersistence.loadLegacyClusterEnabled(host) && legacyPresented &&
            activity.get()?.let { !it.isFinishing && !it.isDestroyed } != true) return
        if (hostOwner !== host && !(hostOwner == null && previewHost.get() === host)) return
        launchHost = WeakReference(host)
        if (activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true || launchPending) return
        launchPending = true
        val epoch = ++generation
        val token = UUID.randomUUID().toString()
        launchToken = token
        framesPresented = 0
        expectedDisplay = -1
        val app = host.applicationContext
        val holdStockMap = hostOwner != null
        Thread({
            val result = AdbClusterRouter.launch(app, token, holdStockMap = holdStockMap) { display ->
                if (generation != epoch || launchToken != token) false
                else { expectedDisplay = display; true }
            }
            main.post {
                completeLaunch(token, generation == epoch, result.success) {
                    com.shilapi.xcertplay.hud.BydOemClusterNavi.release(app, token)
                }
            }
        }, "adb-cluster-launch").start()
    }

    /** Actual display confirmation overrides an inconclusive shell reply. Main thread only. */
    internal fun completeLaunch(token: String, current: Boolean, accepted: Boolean, release: () -> Unit) {
        if (!current) { release(); return }
        launchPending = false
        if (!hasConfirmedRoute()) {
            if (!accepted) {
                // Invalidate admission before releasing OEM state: a delayed Activity cannot attach.
                if (launchToken == token) { launchToken = null; expectedDisplay = -1 }
                release()
            }
            retry()
        }
    }

    fun attach(owner: Any, next: Surface) {
        surfaceOwner = owner
        surface = next
        onSurface?.invoke(next)
    }

    fun detach(owner: Any, old: Surface) {
        if (surfaceOwner !== owner || surface !== old) return
        surfaceOwner = null
        surface = null
        onSurface?.invoke(null)
    }

    fun setStreamActive(active: Boolean) {
        streamActive = active
        activity.get()?.updateStream()
    }

    /** Settings may change while the projection host is paused. Stop its old lease immediately. */
    fun stopForSettings() { closeRoute() }

    fun stop(owner: Any) {
        if (hostOwner !== owner) return
        closeRoute()
    }

    private fun closeRoute() {
        val releaseContext = launchHost.get()?.applicationContext
        val releaseLease = launchToken
        ++generation
        legacyPresented = false
        launchToken = null
        expectedDisplay = -1
        launchHost.clear()
        if (releaseContext != null)
            com.shilapi.xcertplay.hud.BydOemClusterNavi.release(releaseContext, releaseLease)
        main.removeCallbacks(retryTick)
        onSurface?.invoke(null)
        onSurface = null
        hostOwner = null
        mainTaskId = -1
        surface = null
        surfaceOwner = null
        launchPending = false
        guidance = null
        clearLanes()
        previewOwner = null
        previewHost.clear()
        previewRect = null
        setMirrorState(false, false, 16, 9)
        mirrorRect = null
        mirrorCrop = null
        setStreamActive(false)
        val oldActivity = activity.get()
        activity.clear()
        oldActivity?.finish()
    }
}
