package com.jelly.fudai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Debug
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class FudaiAccessibilityService : AccessibilityService(), FudaiEngine.Host {
    internal fun writeRuntimeLog(message: String) {
        log?.write(message)
    }

    internal fun dispatchRootTripleTap(x: Int, y: Int): Boolean {
        if (!rootAvailable) return false
        return try {
            val command = "input tap $x $y; sleep 0.06; input tap $x $y; sleep 0.06; input tap $x $y"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            Thread({
                val completed = process.waitFor(3, TimeUnit.SECONDS)
                if (!completed) process.destroy()
                log?.write(if (completed && process.exitValue() == 0)
                    "[抢购点击确认] Root三连点命令执行成功"
                else
                    "[抢购点击异常] Root三连点命令失败")
            }, "seckill-root-triple-tap").start()
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun dispatchRootTap(x: Int, y: Int, label: String): Boolean {
        if (!rootAvailable) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
            Thread({
                val completed = process.waitFor(3, TimeUnit.SECONDS)
                if (!completed) process.destroy()
                log?.write(if (completed && process.exitValue() == 0)
                    "[抢购点击确认] Root点击成功：$label@$x,$y"
                else
                    "[抢购点击异常] Root点击失败：$label@$x,$y")
            }, "seckill-root-tap").start()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val DOUYIN = "com.ss.android.ugc.aweme"
        private const val DOUYIN_LITE = "com.ss.android.ugc.aweme.lite"
        const val ACTION_SHOW_PANEL = "com.jelly.fudai.SHOW_PANEL"
        const val ACTION_TEST_EMAIL = "com.jelly.fudai.TEST_EMAIL"

        @JvmField
        var instance: FudaiAccessibilityService? = null

        private const val SETTINGS = "fudai_settings"
        private const val AUTOMATION_WANTED = "automation_wanted"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }
    private val backgroundExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { task -> Thread(task, "fudai-background").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var barrageDialogView: View? = null
    private var settingsDialogView: View? = null
    private var stateView: TextView? = null
    private var logView: TextView? = null
    private var startBtn: Button? = null
    private var seckillBtn: Button? = null
    private var barrageBtn: Button? = null
    private var prizeAgreementFallbackClicked = false
    private var prizeAgreementClickAttempts = 0
    private var log: AppLog? = null
    private var ocr: OfflineOcr? = null
    var engine: FudaiEngine? = null
    private var productSeckillEngine: ProductSeckillEngine? = null
    private var templateMatcher: SuperFudaiTemplateMatcher? = null

    @Volatile private var lastDouyinWindowClass = ""
    @Volatile private var activeDouyinPackage = DOUYIN_LITE
    @Volatile private var rootAvailable = false

    private var screenshotBusy = false
    private var lastScreenshotRequestAt = 0L
    private var screenshotGeneration = 0
    private var panelCollapsed = false
    var overlayDialogShowing = false
    private var lastPanelClickAt = 0L
    private var panelAddRetries = 0
    private var receiverRegistered = false
    private var barrageRunning = false
    private var barrageInfinite = false
    private var barrageRemaining = 0
    private var barrageIntervalMs = 500L
    private var barrageText = ""
    private var barrageGeneration = 0
    private var barrageEditorNode: AccessibilityNodeInfo? = null
    private var barrageSendNode: AccessibilityNodeInfo? = null
    private var liveEntryWaitSeconds = 12
    private var maxFudaiMinutes = 11
    private var minFudaiValue = 50
    private var unfollowedRoomLimit = 5
    private var maxLiveViewerCount = 0
    private var highDouyinMemorySamples = 0
    private var screenshotMemoryCooldownUntil = 0L
    private var unattendedRecoveryActive = false
    private var unattendedRecoveryGeneration = 0

    private val douyinMemoryWatchdog: Runnable = object : Runnable {
        override fun run() {
            if (engine?.isRunning() == true) checkDouyinMemory()
            handler.postDelayed(this, 30_000L)
        }
    }

    private val rootAccessibilityWatchdog: Runnable = object : Runnable {
        override fun run() {
            if (instance != null && instance !== this@FudaiAccessibilityService) return
            repairAccessibilityIfNeeded()
            handler.postDelayed(this, 20_000L)
        }
    }

    private val recoveryHeartbeat: Runnable = object : Runnable {
        override fun run() {
            AccessibilityRecoveryService.writeHeartbeat(this@FudaiAccessibilityService)
            handler.postDelayed(this, 10_000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_SHOW_PANEL == intent.action) showPanel()
        }
    }

    override fun onServiceConnected() {
        instance = this
        AccessibilityRecoveryService.start(this)
        handler.removeCallbacks(recoveryHeartbeat)
        handler.post(recoveryHeartbeat)
        handler.removeCallbacks(douyinMemoryWatchdog)
        handler.postDelayed(douyinMemoryWatchdog, 30_000L)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        log = AppLog(this)
        ocr = OfflineOcr()
        templateMatcher = SuperFudaiTemplateMatcher(this)
        liveEntryWaitSeconds = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("live_entry_wait_seconds", 12)
        maxFudaiMinutes = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("max_fudai_minutes", 11)
        minFudaiValue = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("min_fudai_value", 50)
        unfollowedRoomLimit = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("unfollowed_room_limit", 5)
        maxLiveViewerCount = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("max_live_viewer_count", 0)
        engine = FudaiEngine(this, this, ocr!!, templateMatcher!!, log!!)
        productSeckillEngine = ProductSeckillEngine(this)
        log!!.setListener { line ->
            handler.post {
                // Avoid invalidating the panel underneath a focusable overlay;
                // repeated redraws are visible as flicker on some ROMs.
                if (overlayDialogShowing) return@post
                // The file keeps full timestamps for 24-hour diagnostics, but
                // the tiny floating panel should spend every pixel on content.
                val cleanLine = line.replace(
                    Regex("^(?:\\d{4}-\\d{2}-\\d{2}\\s+)?\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s*"),
                    ""
                )
                val displayLine = if (cleanLine.length > 70) cleanLine.take(69) + "…" else cleanLine
                if (logView?.text?.toString() != displayLine) logView?.text = displayLine
                updateFloatButtonsState()
            }
        }
        if (!receiverRegistered) {
            registerReceiver(receiver, IntentFilter(ACTION_SHOW_PANEL), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        showPanel()
        updateFloatButtonsState()
        log!!.write("[服务] 无障碍服务已连接")
        // Do not probe/request Magisk on service connection. Repeated `su`
        // invocations produced intrusive permission Toasts during purchasing.
        rootAvailable = false
        if (getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)) {
            log!!.write("[无人值守] 检测到上次任务应继续运行，准备自动恢复")
            handler.postDelayed(::resumeAutomation, 1500)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == null) return
        if (isDouyinPackage(event.packageName) && event.className != null &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activeDouyinPackage = event.packageName.toString()
            lastDouyinWindowClass = event.className.toString()
        }
        productSeckillEngine?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        // Android may call this for a transient accessibility interruption.
        // It is not a user request to stop the automation.
        log?.write("[服务中断] 无障碍连接短暂中断，保留任务并等待系统恢复")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ask Android to recreate the hosting process when it is reclaimed.
        // AccessibilityService remains system-bound, so this is best-effort
        // on ROMs that aggressively disable accessibility services.
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        engine?.stop()
        productSeckillEngine?.stop()
        stopBarrage(false)
        ocr?.close()
        templateMatcher?.close()
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
        log?.setListener(null)
        log?.close()
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (ignored: Exception) {}
            receiverRegistered = false
        }
        removePanel()
        removeBarrageDialog()
        removeSettingsDialog()
        super.onDestroy()
    }

    override fun requestScreenshot(callback: FudaiEngine.ScreenshotCallback) {
        val requestNow = android.os.SystemClock.elapsedRealtime()
        if (requestNow < screenshotMemoryCooldownUntil) {
            callback.onBitmap(null)
            return
        }
        if (screenshotBusy) {
            callback.onBitmap(null)
            return
        }
        screenshotBusy = true
        val generation = ++screenshotGeneration
        val now = android.os.SystemClock.elapsedRealtime()
        // Some MediaTek/MIUI builds enforce a noticeably longer screenshot
        // interval than AOSP. 600 ms repeatedly produced
        // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (3) on Redmi 9.
        val delay = maxOf(0L, 1_200L - (now - lastScreenshotRequestAt))
        handler.postDelayed({
            lastScreenshotRequestAt = android.os.SystemClock.elapsedRealtime()
            panel?.alpha = 0f
            handler.postDelayed({
                captureScreenshot(callback, true, generation)
            }, 40)
        }, delay)
        handler.postDelayed({
            if (screenshotBusy && generation == screenshotGeneration) {
                screenshotBusy = false
                screenshotGeneration++
                panel?.alpha = 1f
                log?.write("[截图看门狗] 系统截图12秒未回调，已解除忙锁并交给任务自救")
                callback.onBitmap(null)
            }
        }, 12_000L)
    }

    private fun captureScreenshot(callback: FudaiEngine.ScreenshotCallback, restorePanel: Boolean, generation: Int) {
        try {
            takeScreenshot(0, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                if (generation != screenshotGeneration) {
                    result.hardwareBuffer.close()
                    return
                }
                screenshotBusy = false
                if (restorePanel) panel?.alpha = 1f
                val buffer: HardwareBuffer = result.hardwareBuffer
                var wrapped: Bitmap? = null
                val copy: Bitmap? = try {
                    wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    if (wrapped == null) null else wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    if (wrapped != null && !wrapped.isRecycled) wrapped.recycle()
                    buffer.close()
                }
                callback.onBitmap(copy)
            }

            override fun onFailure(errorCode: Int) {
                if (generation != screenshotGeneration) return
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    log?.write("[截图] 系统限制截图频率，延迟1.2秒后自动重试；不计为任务失败")
                    handler.postDelayed({
                        if (generation != screenshotGeneration || !screenshotBusy) return@postDelayed
                        lastScreenshotRequestAt = android.os.SystemClock.elapsedRealtime()
                        captureScreenshot(callback, restorePanel, generation)
                    }, 1_200L)
                } else {
                    screenshotBusy = false
                    if (restorePanel) panel?.alpha = 1f
                    log?.write("[截图] 错误码=$errorCode")
                    callback.onBitmap(null)
                }
            }
            })
        } catch (error: Throwable) {
            if (generation != screenshotGeneration) return
            screenshotBusy = false
            if (restorePanel) panel?.alpha = 1f
            log?.write("[截图] 调用系统截图接口异常：${error.javaClass.simpleName}")
            callback.onBitmap(null)
        }
    }

    override fun performNodeActionByText(keywords: Array<String>, after: Consumer<Boolean>) {
        handler.post {
            val root = findDouyinRoot()
            if (root == null) {
                after.accept(false)
                return@post
            }
            val target = findNode(root) { node ->
                val txt = textOf(node)
                txt.isNotEmpty() && keywords.any { kw -> txt.contains(kw) }
            }
            if (target != null) {
                val name = textOf(target)
                val rect = Rect()
                target.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                log?.write("[无障碍Node节点] 成功找到原生粉色任务控件“${name}”，物理Bounds屏幕中心=(${cx},${cy})；直击下按！")
                val clicked = clickNodeOrParent(target)
                if (clicked) {
                    after.accept(true)
                } else if (cx > 0 && cy > 0) {
                    tap(cx, cy) { after.accept(true) }
                } else {
                    after.accept(false)
                }
            } else {
                after.accept(false)
            }
        }
    }

    override fun tap(x: Int, y: Int, after: Runnable) {
        panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
                .build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription) {
                    engine?.onGestureCompleted()
                    handler.postDelayed({
                        panel?.visibility = View.VISIBLE
                        after.run()
                    }, 220)
                }

                override fun onCancelled(description: GestureDescription) {
                    panel?.visibility = View.VISIBLE
                    log?.write("[点击] 手势被系统取消")
                    engine?.onGestureCancelled("点击")
                }
            }
            val accepted = try { dispatchGesture(gesture, callback, handler) } catch (_: Throwable) { false }
            if (!accepted) {
                panel?.visibility = View.VISIBLE
                log?.write("[点击] 系统拒绝接收手势")
                engine?.onGestureCancelled("点击被拒绝")
            }
        }, 120)
    }

    override fun back(after: Runnable) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ after.run() }, 1200L)
    }

    override fun swipeToNextLive(after: Runnable) {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val path = Path()
        // Keep the gesture away from the product card and right-side actions.
        path.moveTo(width * 0.45f, height * 0.72f)
        path.lineTo(width * 0.45f, height * 0.26f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription) {
                engine?.onGestureCompleted()
                after.run()
            }

            override fun onCancelled(description: GestureDescription) {
                log?.write("[切换直播间] 上滑手势被系统取消")
                engine?.onGestureCancelled("上滑切换直播间")
            }
        }
        val accepted = try { dispatchGesture(gesture, callback, handler) } catch (_: Throwable) { false }
        if (!accepted) {
            log?.write("[切换直播间] 系统拒绝接收上滑手势")
            engine?.onGestureCancelled("上滑手势被拒绝")
        }
    }

    override fun isDouyinForeground(): Boolean {
        // Application-overlay settings temporarily become the focused
        // window. The underlying Douyin activity is still alive and must not
        // trigger the unattended self-recovery/relaunch loop while the user
        // is editing settings or barrage content.
        if (overlayDialogShowing && isDouyinPackage(activeDouyinPackage)) {
            return true
        }
        // Prefer the active root when Douyin exposes one.
        rootInActiveWindow?.packageName?.let { packageName ->
            if (isDouyinPackage(packageName)) {
                activeDouyinPackage = packageName.toString()
                return true
            }
        }
        val douyinWindowTitles = setOf("抖音", "抖音极速版")
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root
            if (root?.packageName != null && isDouyinPackage(root.packageName)) {
                activeDouyinPackage = root.packageName.toString()
                // Douyin Lite keeps using a class named SplashActivity even
                // after its real home/live UI is fully rendered. A visible
                // application window from the target package is authoritative;
                // UI readiness is verified separately by navigation OCR.
                return true
            }
            // SplashActivity can temporarily expose an active application
            // window with a null accessibility root. Android still provides
            // the trusted application label as its window title; accept that
            // title only for the active/focused application window.
            val title = window.title?.toString()?.trim().orEmpty()
            if ((window.isActive || window.isFocused) && title in douyinWindowTitles) {
                activeDouyinPackage = if (title == "抖音极速版") DOUYIN_LITE else DOUYIN
                return true
            }
        }
        return false
    }

    override fun matchesSuperFudaiTemplate(screenshot: Bitmap, slot: Int): Boolean {
        return templateMatcher?.matches(screenshot, slot) ?: false
    }

    override fun getLastTemplateScore(): Float {
        return templateMatcher?.lastScore ?: Float.MAX_VALUE
    }

    override fun getLastTemplateCenterY(): Int {
        return templateMatcher?.lastCenterY ?: -1
    }

    override fun getLiveEntryWaitSeconds(): Int = liveEntryWaitSeconds

    override fun getMaxFudaiMinutes(): Int = maxFudaiMinutes

    override fun getMinFudaiValue(): Int = minFudaiValue

    override fun getUnfollowedRoomLimit(): Int = unfollowedRoomLimit

    override fun getMaxLiveViewerCount(): Int = maxLiveViewerCount

    override fun shouldStartFromFollowing(): Boolean =
        getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean("start_from_following", true)

    override fun shouldAutoClaimPrize(): Boolean =
        getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean("auto_claim_prize", true)

    override fun performPrizeClaimStep(after: Consumer<String>) {
        handler.post {
            val root = findDouyinRoot()
            if (root == null) {
                after.accept("WAIT")
                return@post
            }
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collectVisibleNodes(root, nodes)
            val texts = nodes.map { textOf(it).replace(Regex("\\s+"), "") }.filter { it.isNotEmpty() }
            val all = texts.joinToString("")
            val screenHeight = resources.displayMetrics.heightPixels

            if (all.contains("购买成功")) {
                prizeAgreementFallbackClicked = false
                prizeAgreementClickAttempts = 0
                after.accept("SUCCESS")
                return@post
            }
            if (all.contains("说点什么") || all.contains("说点啥") ||
                (all.contains("直播间") && all.contains("购物车"))) {
                after.accept("LIVE_ROOM")
                return@post
            }

            if (all.contains("确认订单")) {
                prizeAgreementFallbackClicked = false
                val claimButton = nodes.firstOrNull { node ->
                    val text = textOf(node).replace(Regex("\\s+"), "")
                    val bounds = Rect().also(node::getBoundsInScreen)
                    text == "领取奖品" && bounds.centerY() > screenHeight * 0.70f
                }
                if (claimButton == null) {
                    after.accept("WAIT")
                    return@post
                }
                val bottomMoney = nodes.mapNotNull { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.centerY() <= screenHeight * 0.72f) null
                    else textOf(node).replace(" ", "").replace('￥', '¥')
                        .takeIf { it.matches(Regex(".*¥\\d+(?:\\.\\d{1,2})?.*")) }
                }
                val exactZero = bottomMoney.any { it.contains(Regex("¥0(?:\\.0{1,2})?(?!\\d)")) }
                if (!exactZero) {
                    // Once a payable amount is visible, absence of an exact zero
                    // is a hard safety failure. While the page is still loading,
                    // wait instead of guessing.
                    after.accept(if (bottomMoney.isNotEmpty()) "UNSAFE_PRICE" else "WAIT")
                    return@post
                }
                clickPrizeNode(claimButton) { after.accept(if (it) "CLAIM_SUBMITTED" else "WAIT") }
                return@post
            }

            val winButton = nodes.firstOrNull { node ->
                val text = textOf(node).replace(Regex("\\s+"), "")
                text.contains("填写地址") && text.contains("领取奖品")
            }
            if (winButton != null || all.contains("恭喜抽中福袋")) {
                val uncheckedAgreement = nodes.firstOrNull { node ->
                    node.isCheckable && !node.isChecked && run {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        bounds.centerY() > screenHeight * 0.62f
                    }
                }
                if (uncheckedAgreement != null) {
                    clickPrizeNode(uncheckedAgreement) { after.accept(if (it) "AGREEMENT_CLICKED" else "WAIT") }
                    return@post
                }
                // Some Douyin WebView versions expose the agreement sentence but
                // not the circle as a checkable node. Use a tightly bounded
                // fallback immediately to the left of that sentence, once only,
                // never a broad screen coordinate.
                val agreementText = nodes.firstOrNull { node ->
                    val text = textOf(node).replace(Regex("\\s+"), "")
                    text.contains("已阅读并同意") || text.contains("阅读并同意") ||
                        text.contains("用户协议") || text.contains("隐私政策")
                }
                if (agreementText != null && !prizeAgreementFallbackClicked) {
                    val bounds = Rect().also(agreementText::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        prizeAgreementFallbackClicked = true
                        val x = maxOf(dp(12), bounds.left - dp(18))
                        tap(x, bounds.centerY()) { after.accept("AGREEMENT_CLICKED") }
                        return@post
                    }
                }
                if (winButton != null) {
                    // In current Douyin WebViews the claim button is exposed as an
                    // accessibility node, while the agreement row is often drawn
                    // only on the WebView canvas. Do not click the button first:
                    // an unchecked agreement makes Douyin silently ignore it.
                    if (!prizeAgreementFallbackClicked && agreementText == null) {
                        log?.write("[自动领奖] 节点未暴露协议勾选框，先用OCR确认并勾选协议")
                        performPrizeClaimWithOcr(after)
                        return@post
                    }
                    clickPrizeNode(winButton) { after.accept(if (it) "OPENED_ORDER" else "WAIT") }
                    return@post
                }
            }
            performPrizeClaimWithOcr(after)
        }
    }

    /** Douyin's prize WebView often renders text without accessibility nodes. */
    private fun performPrizeClaimWithOcr(after: Consumer<String>) {
        requestScreenshot { bitmap ->
            if (bitmap == null) {
                after.accept("WAIT")
                return@requestScreenshot
            }
            ocr?.recognize(bitmap) { result ->
                try {
                    val normalized = result.text.replace(Regex("\\s+"), "")
                    val screenHeight = bitmap.height
                    if (normalized.contains("购买成功")) {
                        prizeAgreementFallbackClicked = false
                        after.accept("SUCCESS")
                        return@recognize
                    }
                    if (normalized.contains("确认订单")) {
                        val zeroAtBottom = result.items.any { item ->
                            item.bounds.centerY() > screenHeight * 0.70f &&
                                item.text.replace(" ", "").replace('￥', '¥')
                                    .contains(Regex("¥0(?:\\.0{1,2})?(?!\\d)"))
                        }
                        val claim = result.items.firstOrNull { item ->
                            item.bounds.centerY() > screenHeight * 0.70f &&
                                item.text.replace(Regex("\\s+"), "").contains("领取奖品")
                        }
                        if (claim != null && zeroAtBottom) {
                            log?.write("[自动领奖OCR] 无障碍节点不可见；已确认订单底部为0元，点击领取奖品")
                            tap(claim.bounds.centerX(), claim.bounds.centerY()) { after.accept("CLAIM_SUBMITTED") }
                        } else if (result.items.any { it.bounds.centerY() > screenHeight * 0.70f && it.text.contains(Regex("[¥￥]\\s*[1-9]")) }) {
                            after.accept("UNSAFE_PRICE")
                        } else {
                            after.accept("WAIT")
                        }
                        return@recognize
                    }
                    val agreement = result.items.firstOrNull {
                        val text = it.text.replace(Regex("\\s+"), "")
                        text.contains("已阅读并同意") || text.contains("阅读并同意") ||
                            text.contains("用户协议") || text.contains("隐私政策")
                    }
                    if (agreement != null) {
                        val agreementText = agreement.text.replace(Regex("\\s+"), "")
                        val circle = result.items
                            .filter {
                                val text = it.text.trim()
                                (text == "O" || text == "0" || text == "○" || text == "〇") &&
                                    kotlin.math.abs(it.bounds.centerY() - agreement.bounds.centerY()) <= dp(28) &&
                                    it.bounds.centerX() <= agreement.bounds.left + dp(40)
                            }
                            .minByOrNull { kotlin.math.abs(it.bounds.centerY() - agreement.bounds.centerY()) }
                        val circleInsideAgreement = agreementText.startsWith("O") ||
                            agreementText.startsWith("0") || agreementText.startsWith("○") ||
                            agreementText.startsWith("〇")
                        val visiblyUnchecked = circle != null || circleInsideAgreement
                        if (visiblyUnchecked && prizeAgreementClickAttempts >= 3) {
                            log?.write("[自动领奖OCR] 协议圆圈连续3次仍显示未勾选，停止点击领奖按钮并等待人工处理")
                            after.accept("WAIT")
                            return@recognize
                        }
                        if (!prizeAgreementFallbackClicked || visiblyUnchecked) {
                            val tapX = when {
                                circle != null -> circle.bounds.centerX()
                                circleInsideAgreement -> agreement.bounds.left + minOf(dp(12), agreement.bounds.width() / 4)
                                else -> maxOf(dp(12), agreement.bounds.left - dp(18))
                            }
                            prizeAgreementClickAttempts++
                            prizeAgreementFallbackClicked = true
                            log?.write("[自动领奖OCR] 协议未勾选，点击圆圈坐标($tapX,${agreement.bounds.centerY()})，第${prizeAgreementClickAttempts}/3次")
                            tap(tapX, agreement.bounds.centerY()) {
                                after.accept("AGREEMENT_CLICKED")
                            }
                            return@recognize
                        }
                    }
                    val openOrderCandidates = result.items.filter {
                        val text = it.text.replace(Regex("\\s+"), "")
                        text.contains("填写地址") || text.contains("领取奖品")
                    }
                    val openOrder = openOrderCandidates.firstOrNull {
                        val text = it.text.replace(Regex("\\s+"), "")
                        text.contains("填写地址") && text.contains("领取奖品")
                    } ?: openOrderCandidates.firstOrNull {
                        it.text.replace(Regex("\\s+"), "").contains("填写地址")
                    }
                    if (openOrder != null) {
                        log?.write("[自动领奖OCR] 已定位“填写地址 领取奖品”按钮；点击按钮文字中心")
                        tap(openOrder.bounds.centerX(), openOrder.bounds.centerY()) { after.accept("OPENED_ORDER") }
                    } else {
                        after.accept("WAIT")
                    }
                } finally {
                    bitmap.recycle()
                }
            } ?: run {
                bitmap.recycle()
                after.accept("WAIT")
            }
        }
    }

    private fun collectVisibleNodes(node: AccessibilityNodeInfo?, output: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser) output.add(node)
        for (index in 0 until node.childCount) collectVisibleNodes(node.getChild(index), output)
    }

    private fun clickPrizeNode(node: AccessibilityNodeInfo, after: (Boolean) -> Unit) {
        val label = textOf(node).replace(Regex("\\s+"), "")
        val bounds = Rect().also(node::getBoundsInScreen)
        if (clickNodeOrParent(node)) {
            log?.write("[自动领奖节点] ACTION_CLICK命中“$label”，Bounds=$bounds")
            handler.postDelayed({ after(true) }, 120L)
            return
        }
        if (bounds.isEmpty) {
            log?.write("[自动领奖节点] “$label”没有有效Bounds，未执行点击")
            after(false)
            return
        }
        log?.write("[自动领奖节点] 节点不可直接点击，严格使用“$label”Bounds中心(${bounds.centerX()},${bounds.centerY()})")
        tap(bounds.centerX(), bounds.centerY()) { after(true) }
    }

    override fun getPendingDrawDeadline(): Long {
        return getSharedPreferences(SETTINGS, MODE_PRIVATE).getLong("pending_draw_deadline_ms", 0L)
    }

    override fun setPendingDrawDeadline(deadlineMs: Long) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
            .putLong("pending_draw_deadline_ms", deadlineMs).apply()
    }

    override fun performFudaiTaskActionByNode(after: Consumer<String?>) {
        handler.post {
            val root = findDouyinRoot()
            if (root == null) {
                after.accept(null)
                return@post
            }
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            val allVisibleText = StringBuilder()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null || !node.isVisibleToUser) return
                val label = textOf(node).replace(" ", "")
                if (label.isNotEmpty()) allVisibleText.append(label)
                for (i in 0 until node.childCount) collect(node.getChild(i))
            }
            collect(root)
            val pageText = allVisibleText.toString()
            val shoppingFanTask = pageText.contains("购物粉丝团") || pageText.contains("购物粉丝") ||
                pageText.contains("加入购物") || pageText.contains("加人购物") ||
                pageText.contains("店铺粉丝团")
            fun collectActions(node: AccessibilityNodeInfo?) {
                if (node == null || !node.isVisibleToUser) return
                val label = textOf(node).replace(" ", "")
                val exactAction = label.contains("一键发表评论") || label == "发表评论" ||
                    label.contains("开始观看直播任务") ||
                    label == "立即观看" || label == "去观看" ||
                    label.contains("参与抽奖") || label.contains("一键参与") ||
                    label == "免费参与" || label == "立即参与" ||
                    (shoppingFanTask && (label == "去完成" || label == "去加入" ||
                        label == "立即加入" || label == "加入" || label == "加入粉丝团" ||
                        label.contains("加入购物粉丝团")))
                if (exactAction && label.length <= 30 && node.isEnabled) {
                    candidates += node
                }
                for (i in 0 until node.childCount) collectActions(node.getChild(i))
            }
            collectActions(root)
            val target = candidates.minByOrNull {
                val label = textOf(it).replace(" ", "")
                when (label) {
                    "一键发表评论", "发表评论" -> 0
                    "开始观看直播任务", "立即观看", "去观看" -> 1
                    else -> 2
                }
            }
            if (target == null) {
                after.accept(null)
                return@post
            }
            val label = textOf(target).replace(" ", "")
            val bounds = Rect().also(target::getBoundsInScreen)
            if (bounds.width() > 0 && bounds.height() > 0) {
                // Douyin's WebView may return true from ACTION_CLICK without
                // dispatching a real touch or changing task state. A gesture at
                // the exposed button bounds is reliable and uses the current
                // device's native coordinate space.
                log?.write("[任务节点] 对“$label”下发Bounds中心手势(${bounds.centerX()},${bounds.centerY()})，等待下一帧状态复核" +
                    if (shoppingFanTask) "；购物粉丝团精确白名单已命中" else "")
                tap(bounds.centerX(), bounds.centerY()) { after.accept(label) }
            } else {
                log?.write("[任务节点] 动作节点“$label”无有效点击区域")
                after.accept(null)
            }
        }
    }

    override fun isPendingDrawParticipationConfirmed(): Boolean {
        return getSharedPreferences(SETTINGS, MODE_PRIVATE)
            .getBoolean("pending_draw_participation_confirmed", false)
    }

    override fun setPendingDrawParticipationConfirmed(confirmed: Boolean) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
            .putBoolean("pending_draw_participation_confirmed", confirmed).apply()
    }

    override fun setAutomationWanted(wanted: Boolean) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit().putBoolean(AUTOMATION_WANTED, wanted).apply()
        if (wanted) {
            AccessibilityRecoveryService.start(this)
        } else {
            unattendedRecoveryGeneration++
            unattendedRecoveryActive = false
        }
        updateFloatButtonsState()
    }

    private fun resumeAutomation() {
        if (engine == null || engine!!.isRunning()) return
        if (!getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)) return
        if (unattendedRecoveryActive) return
        unattendedRecoveryActive = true
        val generation = ++unattendedRecoveryGeneration
        continueUnattendedRecovery(1, generation)
    }

    private fun continueUnattendedRecovery(round: Int, generation: Int) {
        if (generation != unattendedRecoveryGeneration ||
            !getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)) {
            unattendedRecoveryActive = false
            return
        }
        if (isDouyinForeground()) {
            unattendedRecoveryActive = false
            log?.write("[无人值守] 抖音在前台，自动恢复任务")
            engine!!.startAutomationMode()
            return
        }
        log?.write("[无人值守] 抖音不在前台，执行第${round}轮重新拉起")
        restartDouyin {
            waitForUnattendedForeground(1, round, generation)
        }
    }

    private fun waitForUnattendedForeground(check: Int, round: Int, generation: Int) {
        if (generation != unattendedRecoveryGeneration ||
            !getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)) {
            unattendedRecoveryActive = false
            return
        }
        if (isDouyinForeground()) {
            unattendedRecoveryActive = false
            log?.write("[无人值守] 已确认抖音/抖音极速版进入前台，自动恢复任务")
            engine?.startAutomationMode()
            return
        }
        if (check >= 10) {
            val delayMs = minOf(20_000L, 3_000L + round * 2_000L)
            log?.write("[无人值守] 第${round}轮20秒内未进入前台，${delayMs / 1000}秒后重新执行拉起")
            handler.postDelayed({ continueUnattendedRecovery(round + 1, generation) }, delayMs)
            return
        }
        log?.write("[无人值守] 等待抖音/抖音极速版进入前台（${check}/10）")
        handler.postDelayed({ waitForUnattendedForeground(check + 1, round, generation) }, 2_000L)
    }

    override fun restartDouyin(after: Runnable) {
        val packageName = getTargetDouyinPackage()
        log?.write("[终极自救] 本轮目标包：$packageName")
        Thread({
            var rootRestarted = false
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("su", "-c",
                        "am force-stop $packageName; sleep 1; monkey -p $packageName -c android.intent.category.LAUNCHER 1"))
                val finished = process.waitFor(8, TimeUnit.SECONDS)
                if (!finished) process.destroy()
                rootRestarted = finished && process.exitValue() == 0
                if (rootRestarted) rootAvailable = true
            } catch (ignored: Exception) {}
            val usedRoot = rootRestarted
            handler.post {
                if (!usedRoot) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    log?.write("[终极自救] 无Root权限：先回桌面、清理抖音后台和旧任务栈，再重新拉起")
                    handler.postDelayed({
                        try {
                            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                            manager?.killBackgroundProcesses(packageName)
                        } catch (ignored: Exception) {}
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            try {
                                startActivity(launch)
                            } catch (error: Throwable) {
                                log?.write("[终极自救] 无Root拉起抖音失败：${error.javaClass.simpleName}，2秒后重试")
                                handler.postDelayed({
                                    try { startActivity(launch) } catch (retry: Throwable) {
                                        log?.write("[终极自救] 无Root重试仍失败：${retry.javaClass.simpleName}")
                                    }
                                }, 2000L)
                            }
                        } else {
                            log?.write("[终极自救] 未找到可启动的抖音或抖音极速版")
                        }
                    }, 900)
                } else {
                    log?.write("[终极自救] 已发送强制停止并重新启动命令：$packageName")
                }
                handler.postDelayed({ after.run() }, if (usedRoot) 9000L else 7000L)
            }
        }, "douyin-self-recovery").start()
    }

    private fun detectRootAndApplyOptimizations() {
        Thread({
            var available = false
            try {
                val probe = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val finished = probe.waitFor(5, TimeUnit.SECONDS)
                if (!finished) probe.destroy()
                available = finished && probe.exitValue() == 0
            } catch (ignored: Exception) {}
            rootAvailable = available
            if (!available) {
                log?.write("[Root优化] 未检测到Root授权，继续使用未Root兼容模式")
                return@Thread
            }
            handler.removeCallbacks(rootAccessibilityWatchdog)
            handler.post(rootAccessibilityWatchdog)
            val own = packageName
            val ownPid = android.os.Process.myPid()
            try {
                val command = rootOptimizationCommand(DOUYIN) +
                    "; " + rootOptimizationCommand(DOUYIN_LITE) +
                    "; cmd deviceidle whitelist +$own" +
                    "; am set-standby-bucket $own active" +
                    "; appops set $own RUN_IN_BACKGROUND allow" +
                    "; appops set $own RUN_ANY_IN_BACKGROUND allow" +
                    // Accessibility alone only gives this process a
                    // perceptible adj on this ROM. Under memory pressure the
                    // system repeatedly killed it and then reconnected the
                    // service. The device is rooted, so protect this specific
                    // process without touching global LMK settings.
                    "; echo -700 > /proc/$ownPid/oom_score_adj" +
                    "; renice -5 -p $ownPid"
                val optimize = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val finished = optimize.waitFor(8, TimeUnit.SECONDS)
                if (!finished) optimize.destroy()
                log?.write("[Root优化] 已启用后台白名单并提高无障碍进程存活优先级；未Root路径仍保留")
            } catch (error: Exception) {
                log?.write("[Root优化] Root已授权，但部分优化执行失败；自动回退兼容模式")
            }
        }, "douyin-root-optimizer").start()
    }

    private fun repairAccessibilityIfNeeded() {
        if (!rootAvailable) return
        val component = "$packageName/.FudaiAccessibilityService"
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (enabled == 1 && services != null && services.contains(component)) return
        Thread({
            try {
                val command =
                    "current=\$(settings get secure enabled_accessibility_services); " +
                    "if [ \"\$current\" = null ] || [ -z \"\$current\" ]; then " +
                    "current='$component'; " +
                    "elif ! echo \"\$current\" | grep -q '$component'; then current=\"\$current:$component\"; fi; " +
                    "settings put secure enabled_accessibility_services \"\$current\"; " +
                    "settings put secure accessibility_enabled 1"
                val repair = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val finished = repair.waitFor(6, TimeUnit.SECONDS)
                if (!finished) repair.destroy()
                if (finished && repair.exitValue() == 0) {
                    log?.write("[Root守护] 检测到无障碍授权被系统关闭，已自动恢复")
                }
            } catch (error: Exception) {
                log?.write("[Root守护] 无障碍自动恢复失败，等待下轮重试")
            }
        }, "accessibility-root-watchdog").start()
    }

    private fun isDouyinPackage(packageName: CharSequence?): Boolean {
        return packageName != null && (DOUYIN.contentEquals(packageName) || DOUYIN_LITE.contentEquals(packageName))
    }

    private fun getTargetDouyinPackage(): String {
        if (packageManager.getLaunchIntentForPackage(activeDouyinPackage) != null) {
            return activeDouyinPackage
        }
        if (packageManager.getLaunchIntentForPackage(DOUYIN_LITE) != null) {
            activeDouyinPackage = DOUYIN_LITE
            return DOUYIN_LITE
        }
        activeDouyinPackage = DOUYIN
        return DOUYIN
    }

    private fun rootOptimizationCommand(packageName: String): String {
        if (packageManager.getLaunchIntentForPackage(packageName) == null) return "true"
        return "cmd deviceidle whitelist +$packageName" +
            "; am set-standby-bucket $packageName active" +
            "; appops set $packageName RUN_IN_BACKGROUND allow" +
            "; appops set $packageName RUN_ANY_IN_BACKGROUND allow"
    }

    private fun showPanel() {
        if (panel != null || windowManager == null) return
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.minimumWidth = dp(96)
        root.setPadding(dp(2), dp(2), dp(2), dp(2))
        root.background = panelBackground(218, 14)

        stateView = TextView(this).also { tv ->
            tv.text = "● 未开始"
            tv.setTextColor(Color.rgb(255, 92, 92))
            tv.textSize = 9.5f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(2), dp(1), dp(2), dp(1))
            root.addView(tv)
        }

        logView = TextView(this).also { tv ->
            tv.text = "已停止"
            tv.setTextColor(Color.LTGRAY)
            tv.textSize = 8.5f
            tv.maxWidth = dp(90)
            tv.minLines = 2
            tv.maxLines = 2
            tv.setPadding(0, 1, 0, 1)
            val lp = LinearLayout.LayoutParams(dp(90), dp(26))
            lp.setMargins(0, 1, 0, 1)
            tv.layoutParams = lp
            root.addView(tv)
        }

        val row1 = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = Gravity.CENTER_HORIZONTAL }
        val row2 = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = Gravity.CENTER_HORIZONTAL }
        val row3 = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = Gravity.CENTER_HORIZONTAL }

        val start = smallButton("开始").also { startBtn = it }
        val settings = smallButton("设置")
        val seckill = smallButton("抢购").also { seckillBtn = it }
        val barrage = smallButton("弹幕").also { barrageBtn = it }
        val fold = smallButton("收起")

        row1.addView(start)
        row1.addView(settings)

        row2.addView(seckill)
        row2.addView(barrage)

        row3.addView(fold)

        root.addView(row1)
        root.addView(row2)
        root.addView(row3)

        start.setOnClickListener {
            val currentlyWanted = getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)
            if (engine?.isRunning() == true || currentlyWanted) {
                setAutomationWanted(false)
                setPendingDrawDeadline(0L)
                engine?.stop()
                cleanupAfterManualStop()
                stateView?.text = "● 已停止"
                stateView?.setTextColor(Color.rgb(255, 92, 92))
                updateFloatButtonsState()
                Toast.makeText(this, "抢福袋挂机已停止", Toast.LENGTH_SHORT).show()
            } else {
                stopBarrage(false)
                productSeckillEngine?.stop()
                setAutomationWanted(true)
                if (isDouyinForeground()) {
                    engine?.startAutomationMode()
                } else {
                    resumeAutomation()
                }
                updateFloatButtonsState()
                Toast.makeText(this, "抢福袋已开始", Toast.LENGTH_SHORT).show()
            }
        }
        barrage.setOnClickListener {
            if (barrageRunning) {
                stopBarrage(true)
                updateFloatButtonsState()
            } else {
                runPanelClick("弹幕", ::showBarrageDialog)
            }
        }
        settings.setOnClickListener { runPanelClick("设置", ::showUnifiedSettingsDialog) }
        seckill.setOnClickListener {
            if (productSeckillEngine?.isRunning() == true) {
                productSeckillEngine?.stop()
                log?.write("[抢购] 已从悬浮窗主界面紧急停止抢购")
                Toast.makeText(this, "抢购已停止", Toast.LENGTH_SHORT).show()
                updateFloatButtonsState()
            } else {
                runPanelClick("抢购") { productSeckillEngine?.showConfigDialog() }
            }
        }
        fold.setOnClickListener {
            if (!acceptPanelClick()) return@setOnClickListener
            panelCollapsed = !panelCollapsed
            logView?.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
            row1.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
            row2.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
            stateView?.visibility = View.VISIBLE
            fold.text = if (panelCollapsed) "展开" else "收起"
            root.minimumWidth = if (panelCollapsed) dp(50) else dp(96)
            root.setPadding(dp(3), if (panelCollapsed) dp(2) else dp(3), dp(3), if (panelCollapsed) dp(2) else dp(3))
            updateFloatButtonsState()
        }

        val drag = FloatArray(4)
        val dragListener = View.OnTouchListener { _, event ->
            val params = panel!!.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    drag[0] = event.rawX
                    drag[1] = event.rawY
                    drag[2] = params.x.toFloat()
                    drag[3] = params.y.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (drag[2] - (event.rawX - drag[0])).toInt()
                    params.y = (drag[3] + (event.rawY - drag[1])).toInt()
                    windowManager!!.updateViewLayout(panel, params)
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
        stateView?.setOnTouchListener(dragListener)
        logView?.setOnTouchListener(dragListener)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp(8)
        params.y = dp(160)
        panel = root
        try {
            windowManager!!.addView(panel, params)
            panelAddRetries = 0
        } catch (error: WindowManager.BadTokenException) {
            panel = null
            log?.write("[悬浮窗] 系统窗口令牌尚未就绪，稍后自动重试")
            if (panelAddRetries++ < 3) handler.postDelayed(::showPanel, 700L)
        } catch (error: SecurityException) {
            panel = null
            log?.write("[悬浮窗] 系统窗口令牌尚未就绪，稍后自动重试")
            if (panelAddRetries++ < 3) handler.postDelayed(::showPanel, 700L)
        }
    }

    private fun acceptPanelClick(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPanelClickAt < 350L) return false
        lastPanelClickAt = now
        return true
    }

    private fun runPanelClick(name: String, action: () -> Unit) {
        if (!acceptPanelClick()) return
        try {
            action()
        } catch (error: Throwable) {
            overlayDialogShowing = false
            log?.write("[悬浮窗] ${name}打开失败：${error.javaClass.simpleName}")
            Toast.makeText(this, "${name}暂时无法打开，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupAfterManualStop() {
        // Give any screenshot/OCR callback already in flight enough time to
        // observe the invalidated engine epoch and recycle its Bitmap first.
        handler.postDelayed({
            // Never run GC or trim Douyin while either automation is active.
            // Keeping the shopping process and its network/UI caches hot is
            // materially faster than trying to "free memory" at seckill start.
            if (engine?.isRunning() == true || productSeckillEngine?.isRunning() == true) {
                return@postDelayed
            }
            log?.maintain()
            log?.write("[手动清理] 已保留24小时日志并回收截图/OCR临时内存")
            Runtime.getRuntime().gc()
            if (!rootAvailable) return@postDelayed
            executeBackground("manual-root-trim") {
                try {
                    val trim = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "am send-trim-memory ${getTargetDouyinPackage()} RUNNING_LOW"))
                    val finished = trim.waitFor(5, TimeUnit.SECONDS)
                    if (!finished) trim.destroy()
                    log?.write("[Root清理] 已请求系统释放抖音可回收缓存")
                } catch (ignored: Exception) {
                    log?.write("[Root清理] 系统缓存回收请求失败，不影响未Root兼容运行")
                }
            }
        }, 800)
    }

    fun triggerPeriodicMemoryClean() {
        // Do not force a full GC or send RUNNING_LOW to the foreground live
        // app. Both operations create visible pauses and cache rebuilding.
        // Frame bitmaps and OpenCV Mats are explicitly released instead. Only
        // rotate assistant-owned logs and remove stale log artifacts here.
        log?.maintain()
    }

    private fun checkDouyinMemory() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        checkAssistantMemory()
        val target = getTargetDouyinPackage()
        val pid = manager.runningAppProcesses?.firstOrNull { it.processName == target }?.pid ?: run {
            highDouyinMemorySamples = 0
            return
        }
        val visiblePssMb = try {
            manager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()?.totalPss?.div(1024) ?: 0
        } catch (_: Throwable) { 0 }
        val deviceMb = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem / (1024L * 1024L)
        // Scale to the phone, while keeping useful bounds for 4-16 GB devices.
        val thresholdMb = (deviceMb * 35L / 100L).coerceIn(900L, 1800L).toInt()
        // Android 10+ may hide another UID's PSS. Rooted devices can still
        // obtain the real figure from dumpsys without weakening app sandboxing.
        if (visiblePssMb <= 0 && rootAvailable) {
            executeBackground("douyin-memory-check") {
                val pssMb = readDouyinPssWithRoot(target) ?: return@executeBackground
                handler.post { recordDouyinMemorySample(pssMb, thresholdMb) }
            }
            return
        }
        recordDouyinMemorySample(visiblePssMb, thresholdMb)
    }

    private fun readDouyinPssWithRoot(target: String): Int? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys meminfo --local $target"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            val kb = Regex("TOTAL PSS:\\s*([0-9,]+)").find(output)?.groupValues?.get(1)
                ?: Regex("(?m)^\\s*TOTAL\\s+([0-9,]+)").find(output)?.groupValues?.get(1)
                ?: return null
            kb.replace(",", "").toLongOrNull()?.div(1024L)?.toInt()
        } catch (_: Throwable) { null }
    }

    private fun recordDouyinMemorySample(pssMb: Int, thresholdMb: Int) {
        if (pssMb >= thresholdMb) highDouyinMemorySamples++ else highDouyinMemorySamples = 0
        if (highDouyinMemorySamples == 1) {
            log?.write("[内存监控] 抖音占用${pssMb}MB，超过阈值${thresholdMb}MB；等待连续确认，避免峰值误杀")
        }
        if (highDouyinMemorySamples >= 3) {
            highDouyinMemorySamples = 0
            log?.write("[内存监控] 连续3次超限，准备重启抖音释放内存")
            engine?.onDouyinMemoryPressure(pssMb, thresholdMb)
        }
    }

    private fun checkAssistantMemory() {
        val ownPssMb = Debug.getPss() / 1024
        if (ownPssMb < 320) return
        screenshotMemoryCooldownUntil = android.os.SystemClock.elapsedRealtime() + 8_000L
        screenshotGeneration++
        screenshotBusy = false
        panel?.alpha = 1f
        log?.write("[自身内存保护] 助手PSS=${ownPssMb}MB，暂停截图8秒并回收临时对象")
        Runtime.getRuntime().gc()
    }

    private fun smallButton(label: String): Button {
        val button = Button(this)
        button.text = label
        button.textSize = 12.5f
        button.setTextColor(Color.BLACK)
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.gravity = Gravity.CENTER
        button.setPadding(dp(1), 0, dp(1), 0)
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(4).toFloat()
        button.background = bg
        val params = LinearLayout.LayoutParams(dp(44), dp(28))
        params.setMargins(dp(1), dp(1), dp(1), dp(1))
        button.layoutParams = params
        return button
    }

    private fun styleBarrageInput(input: EditText) {
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.rgb(166, 184, 198))
        input.textSize = 16f
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.minHeight = dp(48)
        input.minimumHeight = dp(48)
        input.setPadding(dp(10), dp(7), dp(10), dp(7))
        input.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(75, 205, 222))
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, dp(2), 0, dp(4))
        input.layoutParams = params
    }

    private fun barrageButton(label: String, backgroundColor: Int): Button {
        val button = Button(this)
        button.text = label
        button.setTextColor(Color.WHITE)
        button.textSize = 15f
        button.isAllCaps = false
        button.gravity = Gravity.CENTER
        button.minHeight = dp(46)
        button.minimumHeight = dp(46)
        button.setPadding(dp(10), 0, dp(10), 0)
        val background = GradientDrawable()
        background.setColor(backgroundColor)
        background.cornerRadius = dp(10).toFloat()
        background.setStroke(dp(1), Color.argb(140, 180, 220, 230))
        button.background = background
        val params = LinearLayout.LayoutParams(0, dp(46), 1f)
        params.setMargins(dp(4), 0, dp(4), 0)
        button.layoutParams = params
        return button
    }

    private fun showUnifiedSettingsDialog() {
        if (overlayDialogShowing) return
        overlayDialogShowing = true
        val prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16), dp(16), dp(16), dp(16))

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(0, dp(4), 0, dp(8))
        val runtimeTab = settingsTabButton("运行设置")
        val reminderTab = settingsTabButton("提醒设置")
        tabs.addView(runtimeTab)
        tabs.addView(reminderTab)
        container.addView(tabs)

        val scroll = ScrollView(this)
        val pages = LinearLayout(this)
        pages.orientation = LinearLayout.VERTICAL
        scroll.addView(pages)
        container.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val runtimePage = LinearLayout(this)
        runtimePage.orientation = LinearLayout.VERTICAL
        runtimePage.addView(settingsHelp("调整自动流程的等待时间和跳过规则"))
        val startFromFollowing = CheckBox(this).apply {
            text = "从关注列表开始（取消后直接进入直播列表）"
            setTextColor(Color.rgb(255, 225, 155))
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    Color.rgb(255, 55, 100),
                    Color.rgb(225, 235, 242)
                )
            )
            isChecked = prefs.getBoolean("start_from_following", true)
        }
        runtimePage.addView(startFromFollowing)
        val autoClaimPrize = CheckBox(this).apply {
            text = "中奖后自动领取奖品（仅限总额为 0 元）"
            setTextColor(Color.rgb(255, 225, 155))
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.rgb(255, 55, 100), Color.rgb(225, 235, 242))
            )
            isChecked = prefs.getBoolean("auto_claim_prize", true)
        }
        runtimePage.addView(autoClaimPrize)
        runtimePage.addView(settingsLabel("进入直播间等待时间"))
        val waitSeconds = settingNumberField("请输入 3–60 秒", liveEntryWaitSeconds.toString())
        runtimePage.addView(waitSeconds)
        runtimePage.addView(settingsLabel("福袋最长倒计时"))
        val maxMinutes = settingNumberField("请输入 1–60 分钟", maxFudaiMinutes.toString())
        runtimePage.addView(maxMinutes)
        runtimePage.addView(settingsLabel("福袋最低参考价值（低于不参与，默认 50）"))
        val minFudaiValueField = settingNumberField("默认 50，低于此价格跳过", minFudaiValue.toString())
        runtimePage.addView(minFudaiValueField)
        runtimePage.addView(settingsLabel("未关注直播间累计上限"))
        val roomLimit = settingNumberField("请输入 1–50 个", unfollowedRoomLimit.toString())
        runtimePage.addView(roomLimit)
        runtimePage.addView(settingsLabel("福袋参与人数上限"))
        val viewerLimit = settingNumberField("输入 0 表示不限制", maxLiveViewerCount.toString())
        runtimePage.addView(viewerLimit)

        val reminderPage = LinearLayout(this)
        reminderPage.orientation = LinearLayout.VERTICAL
        reminderPage.visibility = View.GONE
        reminderPage.addView(settingsHelp("中奖后自动发送邮件，不会阻塞切换直播间"))
        val emailEnabled = CheckBox(this)
        emailEnabled.text = "启用中奖邮件提醒"
        emailEnabled.setTextColor(Color.WHITE)
        emailEnabled.isChecked = prefs.getBoolean("email_enabled", false)
        reminderPage.addView(emailEnabled)
        reminderPage.addView(settingsLabel("发件邮箱"))
        val sender = emailField("例如：123456@qq.com", prefs.getString("email_sender", "") ?: "")
        reminderPage.addView(sender)
        reminderPage.addView(settingsLabel("SMTP 授权码"))
        val authCode = emailField("邮箱生成的授权码，不是登录密码", prefs.getString("email_auth_code", "") ?: "")
        reminderPage.addView(authCode)
        reminderPage.addView(settingsLabel("收件邮箱"))
        val recipient = emailField("用于接收中奖通知", prefs.getString("email_recipient", "") ?: "")
        reminderPage.addView(recipient)
        reminderPage.addView(settingsHelp("支持 QQ、139、163、126 和 Gmail 邮箱"))
        val testEmailButton = Button(this).apply {
            text = "保存配置并发送测试邮件"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = panelBackground(255, 10).apply { setColor(Color.rgb(20, 158, 182)) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
        }
        reminderPage.addView(testEmailButton)

        pages.addView(runtimePage)
        pages.addView(reminderPage)
        setSettingsTab(runtimeTab, reminderTab, true)
        runtimeTab.setOnClickListener {
            runtimePage.visibility = View.VISIBLE
            reminderPage.visibility = View.GONE
            scroll.scrollTo(0, 0)
            setSettingsTab(runtimeTab, reminderTab, true)
        }
        reminderTab.setOnClickListener {
            runtimePage.visibility = View.GONE
            reminderPage.visibility = View.VISIBLE
            scroll.scrollTo(0, 0)
            setSettingsTab(runtimeTab, reminderTab, false)
        }

        val settingsTitle = TextView(this)
        settingsTitle.text = "设置"
        settingsTitle.setTextColor(Color.WHITE)
        settingsTitle.textSize = 19f
        settingsTitle.setPadding(0, 0, 0, dp(8))
        container.addView(settingsTitle, 0)

        val actionsRow = LinearLayout(this)
        actionsRow.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        actionsRow.setPadding(0, dp(12), 0, 0)
        val cancelButton = barrageButton("取消", Color.rgb(72, 91, 106))
        val saveButton = barrageButton("保存", Color.rgb(20, 158, 182))
        actionsRow.addView(cancelButton)
        actionsRow.addView(saveButton)
        container.addView(actionsRow)
        container.background = panelBackground(248, 18)
        container.setPadding(dp(18), dp(18), dp(18), dp(18))

        cancelButton.setOnClickListener { removeSettingsDialog() }
        testEmailButton.setOnClickListener {
            val from = sender.text.toString().trim()
            val code = authCode.text.toString().trim()
            val to = recipient.text.toString().trim()
            if (!emailEnabled.isChecked || !isEmail(from) || code.isEmpty() || !isEmail(to)) {
                Toast.makeText(this, "请先开启邮件提醒并完整填写邮箱和 SMTP 授权码", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putBoolean("email_enabled", true)
                .putString("email_sender", from)
                .putString("email_auth_code", code)
                .putString("email_recipient", to)
                .apply()
            log?.write("[邮件测试] 已保存当前配置，开始发送测试邮件")
            testEmailButton.isEnabled = false
            testEmailButton.text = "正在发送测试邮件…"
            Toast.makeText(this, "正在连接邮箱服务器…", Toast.LENGTH_SHORT).show()
            var testFinished = false
            val restoreTestButton = {
                testEmailButton.isEnabled = true
                testEmailButton.text = "保存配置并发送测试邮件"
            }
            // UI recovery must not depend solely on a socket/background callback.
            // Both SMTP connection attempts normally finish within 12 seconds.
            testEmailButton.postDelayed({
                if (!testFinished) {
                    restoreTestButton()
                    Toast.makeText(this, "邮箱服务器连接超时，请切换网络后重试", Toast.LENGTH_LONG).show()
                }
            }, 15_000L)
            sendTestEmail { sent, error ->
                testFinished = true
                restoreTestButton()
                if (!sent && error?.contains("535") == true) {
                    Toast.makeText(this, "认证失败（SMTP 535），请检查或重新生成授权码", Toast.LENGTH_LONG).show()
                }
            }
        }
        saveButton.setOnClickListener {
            val from = sender.text.toString().trim()
            val code = authCode.text.toString().trim()
            val to = recipient.text.toString().trim()
            if (emailEnabled.isChecked && (!isEmail(from) || code.isEmpty() || !isEmail(to))) {
                reminderTab.performClick()
                Toast.makeText(this, "请完整填写邮件提醒信息", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val seconds = parseBoundedInt(waitSeconds.text.toString(), 3, 60, 12)
            val minutes = parseBoundedInt(maxMinutes.text.toString(), 1, 60, 11)
            val minVal = parseBoundedInt(minFudaiValueField.text.toString(), 0, 100000, 50)
            val limit = parseBoundedInt(roomLimit.text.toString(), 1, 50, 5)
            val viewers = parseBoundedInt(viewerLimit.text.toString(), 0, 10000000, 0)
            liveEntryWaitSeconds = seconds
            maxFudaiMinutes = minutes
            minFudaiValue = minVal
            unfollowedRoomLimit = limit
            maxLiveViewerCount = viewers
            prefs.edit()
                .putInt("live_entry_wait_seconds", seconds)
                .putInt("max_fudai_minutes", minutes)
                .putInt("min_fudai_value", minVal)
                .putInt("unfollowed_room_limit", limit)
                .putInt("max_live_viewer_count", viewers)
                .putBoolean("start_from_following", startFromFollowing.isChecked)
                .putBoolean("auto_claim_prize", autoClaimPrize.isChecked)
                .putBoolean("email_enabled", emailEnabled.isChecked)
                .putString("email_sender", from)
                .putString("email_auth_code", code)
                .putString("email_recipient", to)
                .apply()
            log?.write("[设置] 运行设置与提醒设置已保存")
            Toast.makeText(this, "设置已保存并立即生效", Toast.LENGTH_SHORT).show()
            removeSettingsDialog()
        }

        val params = WindowManager.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(24), dp(440)),
            minOf(resources.displayMetrics.heightPixels - dp(80), dp(520)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        settingsDialogView = container
        try {
            windowManager!!.addView(settingsDialogView, params)
        } catch (error: Throwable) {
            settingsDialogView = null
            overlayDialogShowing = false
            log?.write("[悬浮窗] 面板打开失败：${error.javaClass.simpleName}，已清理状态，可重试")
        }
    }

    private fun removeSettingsDialog() {
        if (settingsDialogView != null && windowManager != null) {
            try { windowManager!!.removeView(settingsDialogView) } catch (ignored: Exception) {}
        }
        settingsDialogView = null
        overlayDialogShowing = false
    }

    private fun settingsTabButton(text: String): Button {
        val button = Button(this)
        button.text = text
        button.textSize = 13f
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(dp(8), 0, dp(8), 0)
        val params = LinearLayout.LayoutParams(0, dp(40), 1f)
        params.setMargins(dp(3), 0, dp(3), 0)
        button.layoutParams = params
        return button
    }

    private fun setSettingsTab(runtime: Button, reminder: Button, runtimeActive: Boolean) {
        styleSettingsTab(runtime, runtimeActive)
        styleSettingsTab(reminder, !runtimeActive)
    }

    private fun styleSettingsTab(button: Button, active: Boolean) {
        val background = GradientDrawable()
        background.cornerRadius = dp(10).toFloat()
        background.setColor(if (active) Color.argb(210, 35, 112, 128) else Color.argb(110, 20, 31, 41))
        background.setStroke(dp(1), if (active) Color.rgb(64, 221, 232) else Color.argb(80, 130, 170, 185))
        button.background = background
        button.setTextColor(if (active) Color.WHITE else Color.LTGRAY)
    }

    private fun settingNumberField(hint: String, value: String): EditText {
        val field = emailField(hint, value)
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.setSelectAllOnFocus(true)
        return field
    }

    private fun settingsHelp(text: String): TextView {
        val help = TextView(this)
        help.text = text
        help.textSize = 11f
        help.setTextColor(Color.rgb(155, 180, 190))
        help.setPadding(0, dp(5), 0, dp(5))
        return help
    }

    private fun showSettingsMenu() {
        if (overlayDialogShowing) return
        overlayDialogShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(arrayOf("运行设置", "提醒设置")) { _, which ->
                overlayDialogShowing = false
                handler.post(if (which == 0) ::showRuntimeSettingsDialog else ::showReminderSettingsDialog)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { overlayDialogShowing = false }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(panelBackground(232, 16))
            dialog.listView?.dividerHeight = 0
        }
        try {
            dialog.show()
        } catch (error: Throwable) {
            overlayDialogShowing = false
            log?.write("[悬浮窗] 菜单打开失败：${error.javaClass.simpleName}，已清理状态，可重试")
        }
    }

    private fun showRuntimeSettingsDialog() {
        if (overlayDialogShowing) return
        overlayDialogShowing = true
        val fields = LinearLayout(this)
        fields.orientation = LinearLayout.VERTICAL
        val padding = dp(18)
        fields.setPadding(padding, dp(6), padding, 0)
        fields.setBackgroundColor(Color.TRANSPARENT)

        val tabs = settingsTabs(true)
        fields.addView(tabs)

        val dialogTitle = TextView(this)
        dialogTitle.text = "运行设置"
        dialogTitle.setTextColor(Color.WHITE)
        dialogTitle.textSize = 19f
        dialogTitle.setPadding(padding, dp(18), padding, dp(10))
        dialogTitle.setBackgroundColor(Color.TRANSPARENT)

        fields.addView(settingsLabel("进入直播间等待时间（秒）"))
        val waitSeconds = EditText(this).also { et ->
            et.hint = "范围：3–60"
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(liveEntryWaitSeconds.toString())
            et.setTextColor(Color.WHITE)
            et.setHintTextColor(Color.LTGRAY)
            et.setSelectAllOnFocus(true)
            et.setPadding(padding, dp(8), padding, dp(8))
        }
        fields.addView(waitSeconds)

        fields.addView(settingsLabel("福袋最长倒计时（分钟）"))
        val maxMinutes = EditText(this).also { et ->
            et.hint = "范围：1–60"
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(maxFudaiMinutes.toString())
            et.setTextColor(Color.WHITE)
            et.setHintTextColor(Color.LTGRAY)
            et.setSelectAllOnFocus(true)
            et.setPadding(padding, dp(8), padding, dp(8))
        }
        fields.addView(maxMinutes)

        fields.addView(settingsLabel("福袋最低参考价值（低于不参与，默认50）"))
        val minValField = EditText(this).also { et ->
            et.hint = "默认：50（0为不限制）"
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(minFudaiValue.toString())
            et.setTextColor(Color.WHITE)
            et.setHintTextColor(Color.LTGRAY)
            et.setSelectAllOnFocus(true)
            et.setPadding(padding, dp(8), padding, dp(8))
        }
        fields.addView(minValField)

        fields.addView(settingsLabel("未关注直播间累计上限（个）"))
        val unfollowedLimit = EditText(this).also { et ->
            et.hint = "范围：1–50"
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(unfollowedRoomLimit.toString())
            et.setTextColor(Color.WHITE)
            et.setHintTextColor(Color.LTGRAY)
            et.setSelectAllOnFocus(true)
            et.setPadding(padding, dp(8), padding, dp(8))
        }
        fields.addView(unfollowedLimit)

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle)
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        tabs.getChildAt(1).setOnClickListener {
            dialog.dismiss()
            handler.post(::showReminderSettingsDialog)
        }
        dialog.setOnDismissListener { overlayDialogShowing = false }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(panelBackground(232, 16))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(64, 221, 232))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(64, 221, 232))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = parseBoundedInt(waitSeconds.text.toString(), 3, 60, 12)
                val minutes = parseBoundedInt(maxMinutes.text.toString(), 1, 60, 11)
                val minVal = parseBoundedInt(minValField.text.toString(), 0, 100000, 50)
                val roomLimitVal = parseBoundedInt(unfollowedLimit.text.toString(), 1, 50, 5)
                liveEntryWaitSeconds = seconds
                maxFudaiMinutes = minutes
                minFudaiValue = minVal
                unfollowedRoomLimit = roomLimitVal
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                    .putInt("live_entry_wait_seconds", seconds)
                    .putInt("max_fudai_minutes", minutes)
                    .putInt("min_fudai_value", minVal)
                    .putInt("unfollowed_room_limit", roomLimitVal)
                    .apply()
                log?.write("[设置] 已保存：进房等待${seconds}秒，最长${minutes}分钟，最低价值￥${minVal}，未关注上限${roomLimitVal}个")
                Toast.makeText(this, "设置已保存并立即生效", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        try {
            dialog.show()
        } catch (error: Throwable) {
            overlayDialogShowing = false
            log?.write("[悬浮窗] 设置打开失败：${error.javaClass.simpleName}，已清理状态，可重试")
        }
    }

    private fun showReminderSettingsDialog() {
        if (overlayDialogShowing) return
        overlayDialogShowing = true
        val prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        val fields = LinearLayout(this)
        fields.orientation = LinearLayout.VERTICAL
        val padding = dp(18)
        fields.setPadding(padding, dp(6), padding, 0)

        val tabs = settingsTabs(false)
        fields.addView(tabs)

        val enabled = CheckBox(this)
        enabled.text = "中奖后发送邮件提醒"
        enabled.setTextColor(Color.WHITE)
        enabled.isChecked = prefs.getBoolean("email_enabled", false)
        fields.addView(enabled)

        fields.addView(settingsLabel("发件邮箱"))
        val sender = emailField("例如：123456@qq.com", prefs.getString("email_sender", "") ?: "")
        fields.addView(sender)

        fields.addView(settingsLabel("SMTP 授权码（不是邮箱密码）"))
        val authCode = emailField("邮箱后台生成的授权码", prefs.getString("email_auth_code", "") ?: "")
        fields.addView(authCode)

        fields.addView(settingsLabel("收件邮箱"))
        val recipient = emailField("中奖通知发送到这里", prefs.getString("email_recipient", "") ?: "")
        fields.addView(recipient)

        val dialog = AlertDialog.Builder(this)
            .setTitle("提醒设置")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        tabs.getChildAt(0).setOnClickListener {
            dialog.dismiss()
            handler.post(::showRuntimeSettingsDialog)
        }
        dialog.setOnDismissListener { overlayDialogShowing = false }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(panelBackground(232, 16))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(64, 221, 232))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(64, 221, 232))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = sender.text.toString().trim()
                val code = authCode.text.toString().trim()
                val to = recipient.text.toString().trim()
                if (enabled.isChecked && (!isEmail(from) || code.isEmpty() || !isEmail(to))) {
                    Toast.makeText(this, "请填写有效邮箱和 SMTP 授权码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putBoolean("email_enabled", enabled.isChecked)
                    .putString("email_sender", from)
                    .putString("email_auth_code", code)
                    .putString("email_recipient", to)
                    .apply()
                log?.write("[提醒设置] 邮件提醒已${if (enabled.isChecked) "开启" else "关闭"}")
                Toast.makeText(this, "提醒设置已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        try {
            dialog.show()
        } catch (error: Throwable) {
            overlayDialogShowing = false
            log?.write("[悬浮窗] 弹窗打开失败：${error.javaClass.simpleName}，已清理状态，可重试")
        }
    }

    private fun emailField(hint: String, value: String): EditText {
        val field = EditText(this)
        field.hint = hint
        field.setText(value)
        field.isSingleLine = true
        field.isFocusable = true
        field.isFocusableInTouchMode = true
        field.minHeight = dp(48)
        field.minimumHeight = dp(48)
        field.setTextColor(Color.WHITE)
        field.setHintTextColor(Color.argb(135, 190, 200, 205))
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        field.setPadding(dp(18), dp(8), dp(18), dp(8))
        field.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { setMargins(0, dp(1), 0, dp(4)) }
        return field
    }

    private fun settingsTabs(runtimeActive: Boolean): LinearLayout {
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val runtime = smallButton("运行设置")
        val reminder = smallButton("提醒设置")
        runtime.setTextColor(if (runtimeActive) Color.rgb(64, 221, 232) else Color.LTGRAY)
        reminder.setTextColor(if (runtimeActive) Color.LTGRAY else Color.rgb(64, 221, 232))
        runtime.isEnabled = !runtimeActive
        reminder.isEnabled = runtimeActive
        tabs.addView(runtime)
        tabs.addView(reminder)
        return tabs
    }

    private fun isEmail(value: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()
    }

    override fun notifyLuckyBagWin(recognizedText: String) {
        // Every prize popup starts a new consent flow. Never carry the one-shot
        // OCR fallback state over from a previous win or interrupted claim.
        prizeAgreementFallbackClicked = false
        prizeAgreementClickAttempts = 0
        sendConfiguredEmail(
            "应用状态通知",
            "应用检测到需要你及时处理的新状态，请立即打开手机查看。\n\n检测时间：${formatNow()}\n此邮件由应用自动发送，请勿直接回复。",
            false
        )
    }

    fun sendTestEmail(onComplete: ((Boolean, String?) -> Unit)? = null) {
        sendConfiguredEmail(
            "应用状态通知",
            "应用检测到需要你及时处理的新状态，请立即打开手机查看。\n\n检测时间：${formatNow()}\n此邮件由应用自动发送，请勿直接回复。",
            true,
            onComplete
        )
    }

    private fun formatNow(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
    }

    private fun sendConfiguredEmail(
        subject: String,
        body: String,
        test: Boolean,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val prefs = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        if (!prefs.getBoolean("email_enabled", false)) {
            if (test) Toast.makeText(this, "邮件提醒尚未开启", Toast.LENGTH_SHORT).show()
            return
        }
        val sender = prefs.getString("email_sender", "") ?: ""
        val authCode = prefs.getString("email_auth_code", "") ?: ""
        val recipient = prefs.getString("email_recipient", "") ?: ""
        val emailTask = {
            val retryDelays = if (test) longArrayOf(0L) else longArrayOf(0L, 10_000L, 30_000L)
            var sent = false
            var lastError: Exception? = null
            for (attempt in retryDelays.indices) {
                if (retryDelays[attempt] > 0) {
                    try { Thread.sleep(retryDelays[attempt]) } catch (_: InterruptedException) { break }
                }
                try {
                    EmailNotifier.send(sender, authCode, recipient, subject, body)
                    sent = true
                    log?.write(if (test) "[邮件测试] 测试邮件发送成功"
                        else "[邮件提醒] 中奖邮件发送成功（${attempt + 1}/${retryDelays.size}）")
                    break
                } catch (error: Exception) {
                    lastError = error
                    val detail = (error.message ?: "无详细信息").take(220)
                    log?.write(if (test) "[邮件测试] 发送失败：${error.javaClass.simpleName}，$detail"
                        else "[邮件提醒] 第${attempt + 1}/${retryDelays.size}次发送失败：${error.javaClass.simpleName}，$detail")
                    if (error is SmtpPermanentException) {
                        log?.write("[邮件提醒] 服务器已明确拒绝本封邮件，停止切换端口和重复投递，避免提高反垃圾评分")
                        break
                    }
                }
            }
            if (test) handler.post {
                val detail = lastError?.message
                Toast.makeText(this, if (sent) "测试邮件发送成功" else "测试邮件发送失败：${detail ?: "请查看日志"}", Toast.LENGTH_LONG).show()
                onComplete?.invoke(sent, detail)
            }
            if (!sent && !test && lastError != null) log?.write("[邮件提醒] 本次中奖通知未送达")
        }
        if (test) {
            executeBackground("test-email", emailTask)
        } else {
            executeCriticalBackground("lucky-bag-email", emailTask)
        }
    }

    private fun executeCriticalBackground(name: String, action: () -> Unit) {
        try {
            backgroundExecutor.execute(action)
        } catch (_: RejectedExecutionException) {
            // A win notification is rare and must not be discarded just because
            // maintenance jobs temporarily filled the bounded queue.
            log?.write("[后台任务] $name 队列已满，启用中奖通知专用兜底线程")
            Thread(action, "fudai-critical-$name").apply { isDaemon = true }.start()
        }
    }

    private fun executeBackground(name: String, action: () -> Unit) {
        try {
            backgroundExecutor.execute(action)
        } catch (_: RejectedExecutionException) {
            log?.write("[后台任务] $name 暂未执行：后台队列已满")
        }
    }

    private fun settingsLabel(text: String): TextView {
        val label = TextView(this)
        label.text = text
        label.textSize = 13f
        label.setTextColor(Color.WHITE)
        label.setPadding(0, dp(6), 0, 0)
        return label
    }

    private fun panelBackground(alpha: Int, radiusDp: Int): GradientDrawable {
        val background = GradientDrawable()
        background.setColor(Color.argb(alpha, 22, 39, 52))
        background.cornerRadius = dp(radiusDp).toFloat()
        background.setStroke(dp(1), Color.argb(90, 90, 180, 200))
        return background
    }

    private fun showBarrageDialog() {
        if (overlayDialogShowing) return
        overlayDialogShowing = true
        val fields = LinearLayout(this)
        fields.orientation = LinearLayout.VERTICAL
        val padding = dp(18)
        fields.setPadding(padding, padding, padding, padding)
        fields.background = panelBackground(248, 18)

        val title = TextView(this)
        title.text = if (barrageRunning) "弹幕发送中" else "弹幕设置"
        title.setTextColor(Color.WHITE)
        title.textSize = 18f
        title.setPadding(0, 0, 0, dp(8))
        fields.addView(title)

        val content = EditText(this)
        content.hint = "弹幕内容"
        content.isSingleLine = false
        content.maxLines = 3
        content.setText(barrageText)
        styleBarrageInput(content)
        fields.addView(content)

        val interval = EditText(this)
        interval.hint = "发送间隔（秒，最少0.5秒）"
        interval.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        interval.setText((barrageIntervalMs / 1000.0).toString())
        styleBarrageInput(interval)
        fields.addView(interval)

        val count = EditText(this)
        count.hint = "发送次数（0=无限次）"
        count.inputType = InputType.TYPE_CLASS_NUMBER
        count.setText((if (barrageRunning && barrageInfinite) 0 else if (barrageRunning) barrageRemaining else 0).toString())
        styleBarrageInput(count)
        fields.addView(count)

        val actionsRow = LinearLayout(this)
        actionsRow.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        actionsRow.setPadding(0, dp(12), 0, 0)
        val stopBtn = barrageButton("停止发送", Color.rgb(170, 68, 78))
        val cancelBtn = barrageButton("取消", Color.rgb(72, 91, 106))
        val startBtn = barrageButton("开始发送", Color.rgb(20, 158, 182))
        actionsRow.addView(stopBtn)
        actionsRow.addView(cancelBtn)
        actionsRow.addView(startBtn)
        fields.addView(actionsRow)

        stopBtn.setOnClickListener { stopBarrage(true); removeBarrageDialog() }
        cancelBtn.setOnClickListener { removeBarrageDialog() }
        startBtn.setOnClickListener {
            val text = content.text.toString().trim()
            val seconds = parseBoundedDouble(interval.text.toString(), 0.5, 3600.0, 0.5)
            val times = parseBoundedInt(count.text.toString(), 0, 1000000, 0)
            if (text.isEmpty()) {
                content.error = "请输入弹幕内容"
                return@setOnClickListener
            }
            startBarrage(text, seconds, times)
            removeBarrageDialog()
        }

        val params = WindowManager.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(24), dp(420)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        barrageDialogView = fields
        try {
            windowManager!!.addView(barrageDialogView, params)
            content.requestFocus()
        } catch (error: Throwable) {
            barrageDialogView = null
            overlayDialogShowing = false
            log?.write("[悬浮窗] 弹窗打开失败：${error.javaClass.simpleName}，已清理状态，可重试")
        }
    }

    private fun removeBarrageDialog() {
        if (barrageDialogView != null && windowManager != null) {
            try { windowManager!!.removeView(barrageDialogView) } catch (ignored: Exception) {}
        }
        barrageDialogView = null
        overlayDialogShowing = false
    }

    private fun parseBoundedInt(value: String, min: Int, max: Int, fallback: Int): Int {
        return try {
            maxOf(min, minOf(max, value.trim().toInt()))
        } catch (ignored: Exception) {
            fallback
        }
    }

    private fun parseBoundedDouble(value: String, min: Double, max: Double, fallback: Double): Double {
        return try {
            maxOf(min, minOf(max, value.trim().toDouble()))
        } catch (ignored: Exception) {
            fallback
        }
    }

    override fun onEngineStateChanged() {
        updateFloatButtonsState()
    }

    fun updateFloatButtonsState() {
        handler.post {
            val seckillRunning = productSeckillEngine?.isRunning() == true
            if (seckillRunning) {
                if (engine?.isRunning() == true) engine?.stop()
                if (barrageRunning) stopBarrage(false)
                if (getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)) {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putBoolean(AUTOMATION_WANTED, false).apply()
                }
            }
            val wanted = getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)
            val fudaiRunning = (engine?.isRunning() == true) || wanted
            startBtn?.let { button ->
                val expectedText = if (fudaiRunning) "停止" else "开始"
                if (button.text.toString() != expectedText) button.text = expectedText
                button.setTextColor(if (fudaiRunning) Color.rgb(220, 38, 38) else Color.BLACK)
            }

            seckillBtn?.text = if (seckillRunning) "停止" else "抢购"
            seckillBtn?.setTextColor(if (seckillRunning) Color.rgb(220, 38, 38) else Color.BLACK)

            barrageBtn?.text = if (barrageRunning) "停止" else "弹幕"
            barrageBtn?.setTextColor(if (barrageRunning) Color.rgb(220, 38, 38) else Color.BLACK)

            val active = fudaiRunning || seckillRunning || barrageRunning
            stateView?.text = when {
                panelCollapsed -> if (active) "●" else "○"
                fudaiRunning -> "● 抢福袋运行中"
                seckillRunning -> "● 抢购中"
                barrageRunning -> "● 弹幕发送中"
                else -> "● 已停止"
            }
            stateView?.setTextColor(if (active) Color.rgb(80, 232, 167) else Color.rgb(255, 92, 92))
        }
    }

    private fun startBarrage(text: String, seconds: Double, times: Int) {
        stopBarrage(false)
        productSeckillEngine?.stop()
        if (engine?.isRunning() == true ||
            getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(AUTOMATION_WANTED, false)) {
            getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(AUTOMATION_WANTED, false).apply()
            engine?.stop()
        }
        barrageText = text
        barrageIntervalMs = maxOf(500L, Math.round(seconds * 1000.0))
        barrageInfinite = times == 0
        barrageRemaining = times
        barrageRunning = true
        barrageEditorNode = null
        barrageSendNode = null
        val generation = ++barrageGeneration
        log?.write("[弹幕] 已启动：间隔${seconds}秒，${if (barrageInfinite) "无限次" else "共${times}次"}")
        updateFloatButtonsState()
        sendBarrageOnce(generation)
    }

    fun stopBarrage(notify: Boolean) {
        val wasRunning = barrageRunning
        barrageRunning = false
        barrageInfinite = false
        barrageRemaining = 0
        barrageEditorNode = null
        barrageSendNode = null
        barrageGeneration++
        updateFloatButtonsState()
        if (wasRunning) log?.write("[弹幕] 已停止")
        if (notify && wasRunning) {
            Toast.makeText(this, "弹幕发送已停止", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBarrageOnce(generation: Int) {
        if (!barrageRunning || generation != barrageGeneration) return
        if (!barrageInfinite && barrageRemaining <= 0) {
            stopBarrage(false)
            log?.write("[弹幕] 已按设定次数发送完成")
            return
        }
        if (!isDouyinForeground()) {
            log?.write("[弹幕] 抖音不在前台，已停止发送")
            stopBarrage(false)
            return
        }
        val root = findDouyinRoot()
        if (root == null) {
            log?.write("[弹幕] 未找到直播间页面，请进入直播间后重试")
            stopBarrage(false)
            return
        }
        val editor = findNode(root) { node ->
            node.isEditable || "android.widget.EditText".contentEquals(node.className ?: "")
        }
        if (editor != null) {
            writeBarrageText(generation, 1)
            return
        }
        val entry = findNode(root) { node ->
            val value = textOf(node)
            value.contains("说点什么") || value.contains("聊点什么") || value.contains("发表评论")
        }
        val afterOpen = Runnable {
            handler.postDelayed(barrageVerify@{
                if (!barrageRunning || generation != barrageGeneration) return@barrageVerify
                writeBarrageText(generation, 1)
            }, 120L)
        }
        if (entry != null) {
            tapNode(entry, afterOpen)
        } else {
            // Current Douyin Lite visually renders "说点什么…" but exposes
            // no text/contentDescription/clickable accessibility node for it.
            // Tap the stable bottom-left comment area relative to the active
            // Douyin root, then verify that a real editable control appears.
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (!rootBounds.isEmpty) {
                val x = rootBounds.left + rootBounds.width() * 18 / 100
                val y = rootBounds.bottom - dp(26)
                log?.write("[弹幕] 极速版未暴露评论入口节点，改用底部评论区坐标打开")
                dispatchTap(x.toFloat(), y.toFloat(), afterOpen)
            } else {
                log?.write("[弹幕] 未找到弹幕输入框，请确认当前直播间支持评论")
                stopBarrage(false)
            }
        }
    }

    private fun clickBarrageSend(generation: Int) {
        if (!barrageRunning || generation != barrageGeneration) return
        val root = findDouyinRoot()
        val minY = resources.displayMetrics.heightPixels / 2
        val send = findNode(root) { node ->
            val text = textOf(node)
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val isSendText = "发送" == text || "发送弹幕" == text || "发送" == desc || "发送弹幕" == desc
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            isSendText && bounds.centerY() > minY
        }
        // The current Lite editor may keep the submitted text instead of
        // clearing/closing. Gesture completion is the only stable signal;
        // never stop infinite mode waiting for a UI-clear animation.
        val afterSend = Runnable { onBarrageSendDispatched(generation) }
        if (send != null) {
            barrageSendNode = send
            tapNode(send, afterSend)
        } else {
            log?.write("[弹幕] 未找到发送按钮，本次已停止")
            stopBarrage(false)
        }
    }

    private fun onBarrageSendDispatched(generation: Int) {
        if (!barrageRunning || generation != barrageGeneration) return
        if (!barrageInfinite) barrageRemaining--
        log?.write(if (barrageInfinite) "[弹幕] 已点击发送，继续无限循环" else
            "[弹幕] 已点击发送，剩余${barrageRemaining}次")
        handler.postDelayed({ continueBarrageFast(generation) }, barrageIntervalMs)
    }

    private fun continueBarrageFast(generation: Int) {
        if (!barrageRunning || generation != barrageGeneration) return
        if (!barrageInfinite && barrageRemaining <= 0) {
            stopBarrage(false)
            log?.write("[弹幕] 已按设定次数发送完成")
            return
        }
        val editor = barrageEditorNode
        val send = barrageSendNode
        val editorAlive = try { editor != null && editor.refresh() && editor.isVisibleToUser } catch (_: Throwable) { false }
        val sendAlive = try { send != null && send.refresh() && send.isVisibleToUser && send.isEnabled } catch (_: Throwable) { false }
        if (!editorAlive || !sendAlive || editor == null || send == null) {
            barrageEditorNode = null
            barrageSendNode = null
            sendBarrageOnce(generation)
            return
        }
        val clickCachedSend = Runnable {
            if (barrageRunning && generation == barrageGeneration) {
                tapNode(send, Runnable { onBarrageSendDispatched(generation) })
            }
        }
        if (editor.text?.toString().orEmpty() == barrageText) {
            clickCachedSend.run()
            return
        }
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, barrageText)
        }
        val written = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (written) handler.postDelayed(clickCachedSend, 40L) else {
            barrageEditorNode = null
            barrageSendNode = null
            sendBarrageOnce(generation)
        }
    }

    private fun writeBarrageText(generation: Int, attempt: Int) {
        if (!barrageRunning || generation != barrageGeneration) return
        val inputRoot = findDouyinRoot()
        val editor = findNode(inputRoot) { node ->
            node.isEditable || "android.widget.EditText".contentEquals(node.className ?: "")
        }
        if (editor == null) {
            if (attempt < 16) {
                handler.postDelayed({ writeBarrageText(generation, attempt + 1) }, 100L)
            } else {
                log?.write("[弹幕] 输入框展开后仍未找到可编辑控件，已停止")
                stopBarrage(false)
            }
            return
        }
        barrageEditorNode = editor
        editor.refresh()
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, barrageText)
        val accepted = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        handler.postDelayed({
            if (!barrageRunning || generation != barrageGeneration) return@postDelayed
            editor.refresh()
            if (accepted && editor.text?.toString().orEmpty() == barrageText) {
                handler.postDelayed({ clickBarrageSend(generation) }, 60L)
                return@postDelayed
            }
            if (attempt < 8) {
                handler.postDelayed({ writeBarrageText(generation, attempt + 1) }, 100L)
                return@postDelayed
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                log?.write("[弹幕] 无法填写弹幕内容，已停止")
                stopBarrage(false)
                return@postDelayed
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("弹幕", barrageText))
            editor.refresh()
            editor.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            handler.postDelayed(barragePasteCheck@{
                if (!barrageRunning || generation != barrageGeneration) return@barragePasteCheck
                editor.refresh()
                if (editor.text?.toString().orEmpty() == barrageText) {
                    clickBarrageSend(generation)
                } else {
                    log?.write("[弹幕] 无法填写弹幕内容，已停止")
                    stopBarrage(false)
                }
            }, 250L)
        }, 60L)
    }

    private fun findDouyinRoot(): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        var fallbackLayer = Int.MIN_VALUE
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName != null && isDouyinPackage(root.packageName)) {
                activeDouyinPackage = root.packageName.toString()
                if (window.isActive || window.isFocused) return root
                if (window.layer > fallbackLayer) {
                    fallback = root
                    fallbackLayer = window.layer
                }
            }
        }
        return fallback
    }

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    private fun textOf(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        var text: CharSequence? = node.text
        if (text == null) text = node.contentDescription
        return text?.toString()?.trim() ?: ""
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun tapNode(node: AccessibilityNodeInfo, after: Runnable) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            log?.write("[弹幕] 按钮暂时不可点击，已停止")
            stopBarrage(false)
            return
        }
        dispatchTap(bounds.exactCenterX(), bounds.exactCenterY(), after)
    }

    private fun tapNode(node: AccessibilityNodeInfo, after: () -> Unit) = tapNode(node, Runnable { after() })

    private fun dispatchTap(x: Float, y: Float, after: Runnable) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription) {
                after.run()
            }

            override fun onCancelled(description: GestureDescription) {
                log?.write("[弹幕] 点击手势被取消，已停止")
                stopBarrage(false)
            }
        }, handler)
        if (!accepted) {
            log?.write("[弹幕] 系统未接受点击操作，已停止")
            stopBarrage(false)
        }
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun removePanel() {
        if (panel == null || windowManager == null) return
        try { windowManager!!.removeView(panel) } catch (ignored: Exception) {}
        panel = null
        stateView = null
        logView = null
    }
}
