package com.jelly.fudai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.Comparator
import java.util.Locale
import java.util.function.Consumer
import java.util.regex.Matcher
import java.util.regex.Pattern

class FudaiEngine(
    private val service: FudaiAccessibilityService,
    private val host: Host,
    private val ocr: OfflineOcr,
    private val templateMatcher: SuperFudaiTemplateMatcher,
    private val log: AppLog
) {
    // OpenCV returns distance=(1-normalized correlation)*100. Entry discovery
    // is image-only; ML Kit confirms "超级福袋" after the candidate opens.
    companion object {
        private const val VISUAL_ONLY_MAX_SCORE = 34f
        private val LIVE_COUNT = Pattern.compile(".*\\d+\\s*个直播.*")
        private val COUNTDOWN = Pattern.compile(".*?(\\d{1,2})\\s*[:：]\\s*(\\d{2}).*")
        private val COMPACT_COUNTDOWN = Pattern.compile("^[（(]?(\\d{2})(\\d{2})[)）]?\$")
        private val TASK_PROGRESS = Pattern.compile(".*?[（(]?\\s*(\\d+)\\s*/\\s*(\\d+)\\s*[)）]?.*")
        private val WATCH_MINUTES = Pattern.compile(".*?(?:观看|看播).*?(\\d+)\\s*分(?:钟)?.*")
        private val FUDAI_PARTICIPANTS = Pattern.compile("(\\d+(?:\\.\\d+)?)(万)?多?人已参与")
    }

    interface Host {
        fun requestScreenshot(callback: ScreenshotCallback)
        fun tap(x: Int, y: Int, after: Runnable)
        fun back(after: Runnable)
        fun swipeToNextLive(after: Runnable)
        fun matchesSuperFudaiTemplate(screenshot: Bitmap, slot: Int): Boolean
        fun getLastTemplateScore(): Float
        fun getLastTemplateCenterY(): Int
        fun isDouyinForeground(): Boolean
        fun restartDouyin(after: Runnable)
        fun getLiveEntryWaitSeconds(): Int
        fun getMaxFudaiMinutes(): Int
        fun getMinFudaiValue(): Int
        fun getUnfollowedRoomLimit(): Int
        fun getMaxLiveViewerCount(): Int
        fun shouldStartFromFollowing(): Boolean
        fun shouldAutoClaimPrize(): Boolean
        fun getPendingDrawDeadline(): Long
        fun setPendingDrawDeadline(deadlineMs: Long)
        fun isPendingDrawParticipationConfirmed(): Boolean
        fun setPendingDrawParticipationConfirmed(confirmed: Boolean)
        fun setAutomationWanted(wanted: Boolean)
        fun performNodeActionByText(keywords: Array<String>, after: java.util.function.Consumer<Boolean>)
        fun performFudaiTaskActionByNode(after: java.util.function.Consumer<String?>)
        fun notifyLuckyBagWin(recognizedText: String)
        fun performPrizeClaimStep(after: java.util.function.Consumer<String>)
        fun onEngineStateChanged()
    }

    fun interface ScreenshotCallback {
        fun onBitmap(bitmap: Bitmap?)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var cycleBusy = false
    private var state: FsmState = FsmState.INIT
    var frameCount: Long = 0
        private set
    private var navigationBusy = false
    private var automationMode = false
    // Invalidates callbacks that belong to a previous start/stop cycle.
    private var engineEpoch = 0
    // A detail may only cause one close-and-switch transition.
    private var switchBusy = false
    private var detailCloseChecks = 0
    private var lastFudaiX = -1
    private var lastFudaiY = -1
    private var lastFudaiFoundAt = 0L
    private var lastAutomaticAction = ""
    private var cachedDetailAction = ""
    private var cachedDetailActionX = -1
    private var cachedDetailActionY = -1
    private var cachedShoppingFanAction = false
    private var automaticTaskSteps = 0
    private var detailNoActionFrames = 0
    private var lastDetailHasPendingTask = false
    private var detailCountdownMissFrames = 0
    private var lastFollowedLiveX = -1
    private var lastFollowedLiveY = -1
    private var currentBagParticipationClicked = false
    private var currentDrawDeadlineMs = 0L
    private var drawResultAttempts = 0
    private var detailCountdownConfirmed = false
    private var detailOverMaxLimit = false
    private var detailValueThresholdPassed = false
    private var performedTaskActionInCurrentDetail = false
    private var stayInRoomEvidenceSeen = false
    private var lastWatchRemainingSeconds = -1
    private var outsideLiveWaitFrames = 0
    private var detailCountdownSample = -1
    private var detailCountdownSampleAt = 0L
    private var confirmedDrawSeconds = -1
    private var taskFaultCount = 0
    private var lastTaskFault = ""
    private var selfRecovering = false
    private var drawResultOverlayVisible = false
    private var consecutiveScreenshotFailures = 0
    private var switchedToLiveList = false
    private var followPageRefreshes = 0
    private var livePageRefreshes = 0
    private var liveEntryVerifyAttempts = 0
    private var unfollowedRoomsSinceFollowEntry = 0
    private var currentRoomCountedUnfollowed = false
    private var scanMisses = 0
    private var consecutiveRoomSwitchFailures = 0
    private var liveEntryWaitCompleted = false
    private var lastWorkflowPulseAt = android.os.SystemClock.elapsedRealtime()
    private var expectedWorkflowResumeAt = 0L
    private var lastLightMaintenanceAt = 0L
    private var consecutiveGestureCancellations = 0
    private val failedFudaiSlots = IntArray(4)
    private var lastAttemptedFudaiSlot = -1
    private var prizeClaimAttempts = 0
    private var prizeReturnAttempts = 0

    private val healthWatchdog: Runnable = object : Runnable {
        override fun run() {
            if (!running || !automationMode || selfRecovering) return
            val elapsedNow = android.os.SystemClock.elapsedRealtime()
            if (elapsedNow <= expectedWorkflowResumeAt) {
                handler.postDelayed(this, 10_000L)
                return
            }
            val silentMs = elapsedNow - lastWorkflowPulseAt
            if (silentMs >= 40_000L) {
                registerTaskFault("工作流无响应", "[假死监控] 主任务链已${silentMs / 1000}秒没有任何进展")
            }
            if (running && automationMode && !selfRecovering) handler.postDelayed(this, 10_000L)
        }
    }

    private fun pulseWorkflow() {
        lastWorkflowPulseAt = android.os.SystemClock.elapsedRealtime()
        expectedWorkflowResumeAt = 0L
    }

    private fun expectWorkflowDelay(delayMs: Long) {
        pulseWorkflow()
        // Scheduled UI settling is progress, not a deadlock. Keep a modest
        // grace period while still allowing genuinely stuck callbacks to trip
        // the watchdog after their expected return time.
        expectedWorkflowResumeAt = android.os.SystemClock.elapsedRealtime() + delayMs + 15_000L
    }

    private val loop: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (!cycleBusy) inspectOnce()
            handler.postDelayed(this, 1200)
        }
    }

    fun startObservationMode() {
        if (running) return
        running = true
        frameCount = 0
        state = FsmState.INIT
        log.write("[FSM] 启动手机端观察模式；所有动作保持禁用")
        host.onEngineStateChanged()
        handler.post(loop)
    }

    fun startAutomationMode() {
        stop()
        if (!host.isDouyinForeground()) {
            log.write("[自动流程] 请先打开抖音；本次未启动")
            return
        }
        running = true
        automationMode = true
        host.onEngineStateChanged()
        pulseWorkflow()
        handler.postDelayed(healthWatchdog, 10_000L)
        engineEpoch++
        switchBusy = false
        frameCount = 0
        navigationBusy = true
        switchedToLiveList = !host.shouldStartFromFollowing()
        followPageRefreshes = 0
        livePageRefreshes = 0
        unfollowedRoomsSinceFollowEntry = 0
        currentRoomCountedUnfollowed = false
        scanMisses = 0
        consecutiveRoomSwitchFailures = 0
        consecutiveGestureCancellations = 0
        consecutiveScreenshotFailures = 0
        liveEntryWaitCompleted = false
        resetTaskFaults()
        currentDrawDeadlineMs = host.getPendingDrawDeadline()
        drawResultAttempts = 0
        drawResultOverlayVisible = false
        prizeClaimAttempts = 0
        prizeReturnAttempts = 0
        detailCountdownConfirmed = false
        detailCountdownSample = -1
        detailCountdownSampleAt = 0
        confirmedDrawSeconds = -1
        if (currentDrawDeadlineMs > System.currentTimeMillis() &&
            host.isPendingDrawParticipationConfirmed()) {
            navigationBusy = false
            currentBagParticipationClicked = true
            state = FsmState.SCAN_FUDAI_ICON
            log.write("[开奖恢复] 服务重启后恢复正在参与的福袋，禁止扫描和切房")
            waitForDrawResult()
            return
        }
        if (currentDrawDeadlineMs > System.currentTimeMillis()) {
            navigationBusy = false
            state = FsmState.CHECK_FUDAI_INFO
            log.write("[任务恢复] 服务重启时检测到福袋任务尚未全部确认完成，重新识别详情并继续剩余任务")
            handler.postDelayed({ verifyFudaiDetail(1) }, 800L)
            return
        }
        currentDrawDeadlineMs = 0
        host.setPendingDrawDeadline(0)
        host.setPendingDrawParticipationConfirmed(false)
        state = FsmState.PAGE_CHECK_NAV
        log.write(if (switchedToLiveList) "[自动流程] 已启动：跳过关注页，直接进入直播列表" else "[自动流程] 已启动：先从关注列表进入直播间")
        inspectHomeAndNavigate()
    }

    private fun automaticScanOnce() {
        if (!running || !automationMode || state != FsmState.SCAN_FUDAI_ICON || switchBusy) return
        pulseWorkflow()
        if (!host.isDouyinForeground()) {
            log.write("[扫描保护] 抖音不在前台，禁止识别和点击；执行自救拉起")
            registerTaskFault("抖音不在前台", "[自救监控] 扫描前发现抖音不在前台")
            if (!selfRecovering) {
                handler.postDelayed(::automaticScanOnce, 1200)
            }
            return
        }
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isActive(epoch) || state != FsmState.SCAN_FUDAI_ICON || switchBusy) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                consecutiveScreenshotFailures++
                if (consecutiveScreenshotFailures == 1 || consecutiveScreenshotFailures % 5 == 0) {
                    log.write("[截图] 暂时无法获取画面，正在自动重试")
                }
                expectWorkflowDelay(1_200L)
                handler.postDelayed(::automaticScanOnce, 1200)
                return@requestScreenshot
            }
            consecutiveScreenshotFailures = 0
            resetTaskFaults()
            val width = bitmap.width
            val height = bitmap.height
            val bottomLeft = Bitmap.createBitmap(bitmap, 0, (height * 0.86).toInt(),
                maxOf(1, (width * 0.28).toInt()),
                maxOf(1, (height * 0.14).toInt()))
            ocr.recognize(bottomLeft) { bottomResult ->
                if (!isActive(epoch) || state != FsmState.SCAN_FUDAI_ICON || switchBusy) {
                    bottomLeft.recycle()
                    bitmap.recycle()
                    return@recognize
                }
                val bottomText = bottomResult.text.replace(Regex("\\s+"), "")
                bottomLeft.recycle()
                if (bottomText.contains("首页") || bottomText.contains("朋友")) {
                    bitmap.recycle()
                    scanMisses = 0
                    state = FsmState.PAGE_CHECK_NAV
                    navigationBusy = true
                    log.write("[扫袋硬拦截] 左下角检测到包含“首页/朋友”底栏导航（OCR=${bottomText}），确认处于直播间外列表页！绝对严禁点击福袋，强制重启导航流程")
                    inspectHomeAndNavigate()
                    return@recognize
                }

                val topLeft = Bitmap.createBitmap(bitmap, 0, 0,
                    maxOf(1, (width * 0.50).toInt()),
                    maxOf(1, (height * 0.30).toInt()))
                ocr.recognize(topLeft) topScan@{ result ->
                    if (!isActive(epoch) || state != FsmState.SCAN_FUDAI_ICON || switchBusy) {
                        topLeft.recycle()
                        bitmap.recycle()
                        return@topScan
                    }
                    val fullTopText = result.text.replace(Regex("\\s+"), "")
                    val isLiveRoomHeader = hasLiveRoomEvidence(result)
                    if (!isLiveRoomHeader && hasLiveListEvidence(result)) {
                        topLeft.recycle()
                        bitmap.recycle()
                        scanMisses = 0
                        state = FsmState.PAGE_CHECK_NAV
                        navigationBusy = true
                        log.write("[导航纠错] 扫描阶段确认仍在列表；禁止上滑与误操作，重新点击第一直播卡")
                        handler.postDelayed(::enterFirstFollowedLive, 1200)
                        return@topScan
                    }
                val showsHostFollowButton = result.items.stream().anyMatch { item ->
                    val text = item.text.replace(Regex("\\s+"), "")
                    val x = item.bounds.centerX() * 1080 / width
                    val y = item.bounds.centerY() * 2400 / height
                    text == "关注" && x >= 120 && x <= 650 && y >= 40 && y <= 300
                }
                if (showsHostFollowButton) {
                    if (!currentRoomCountedUnfollowed) {
                        currentRoomCountedUnfollowed = true
                        unfollowedRoomsSinceFollowEntry++
                        log.write("[关注优先] 左上角主播名字后识别到“关注”，累计未关注直播间${unfollowedRoomsSinceFollowEntry}/${host.getUnfollowedRoomLimit()}")
                    }
                    if (host.shouldStartFromFollowing() &&
                        unfollowedRoomsSinceFollowEntry >= host.getUnfollowedRoomLimit()) {
                        topLeft.recycle()
                        bitmap.recycle()
                        state = FsmState.SWITCH_LIVE
                        switchBusy = true
                        returnToFollowPageAfterUnfollowedLimit()
                        return@topScan
                    }
                }
                val participationStatus = fullTopText.contains("已参与") ||
                    (currentDrawDeadlineMs > 0 && lastAutomaticAction.isNotEmpty() && fullTopText.contains("参与"))
                if (participationStatus) {
                    topLeft.recycle()
                    bitmap.recycle()
                    currentBagParticipationClicked = true
                    scanMisses = 0
                    state = FsmState.SCAN_FUDAI_ICON
                    log.write("[参与保护] 直播间顶部显示“已参与”，按已记录倒计时等待并验证开奖结果")
                    waitForDrawResult()
                    return@topScan
                }
                val slotCenters = intArrayOf(80, 206, 332, 458)
                var best: OfflineOcr.Item? = null
                var bestSlot = -1
                val slotScores = FloatArray(slotCenters.size)
                val slotMatchY = IntArray(slotCenters.size)
                var visualBestScore = Float.MAX_VALUE
                var visualBestSlot = -1
                for (slot in slotCenters.indices) {
                    host.matchesSuperFudaiTemplate(bitmap, slot)
                    slotScores[slot] = host.getLastTemplateScore()
                    slotMatchY[slot] = host.getLastTemplateCenterY()
                    if (failedFudaiSlots[slot] < 2 && slotScores[slot] < visualBestScore) {
                        visualBestScore = slotScores[slot]
                        visualBestSlot = slot
                    }
                }
                // The user-provided upper-half icon is the primary evidence.
                // Compare every slot first, so a left-side red packet cannot
                // steal priority from a better-matching lucky bag beside it.
                if (visualBestSlot >= 0 && visualBestScore <= VISUAL_ONLY_MAX_SCORE) {
                    val syntheticX = slotCenters[visualBestSlot] * width / 1080
                    val matchY = if (slotMatchY[visualBestSlot] > 0) slotMatchY[visualBestSlot]
                        else 350 * height / 2400
                    best = OfflineOcr.Item("图标相似度=${String.format(Locale.CHINA, "%.1f", visualBestScore)}",
                        Rect(syntheticX - 1, matchY - 1, syntheticX + 1, matchY + 1))
                    bestSlot = visualBestSlot
                    log.write("[图标主识别] 4个槽位中槽位${bestSlot + 1}最接近福袋上半图，score=${String.format(Locale.CHINA, "%.1f", visualBestScore)}")
                } else {
                    // Double fallback: if OpenCV template match was degraded by custom frames or high lighting (e.g. Sun Jian's live room),
                    // rescue via OCR countdown/keyword detection or relaxed visual threshold so we NEVER miss!
                    var ocrBest: OfflineOcr.Item? = null
                    var ocrBestSlot = -1
                    for (item in result.items) {
                        val text = item.text.replace(" ", "")
                        // Strictly filter out overlay logs and floating panel text
                        if (text.contains("未命") || text.contains("扫描") || text.contains("OCR") ||
                            text.contains("运行") || text.contains("自动") || text.contains("命中") ||
                            text.contains("探针") || text.contains("停止") || text.contains("设置") ||
                            text.contains("弹幕") || text.contains("收起") || text.contains("流程") ||
                            text.contains("状态") || text.contains("导航")) continue
                        // Strictly filter out red packets, cash rewards, fan gifts, and coin rain
                        if (text.contains("包") || text.contains("拆") || text.contains("抢") ||
                            text.contains("现金") || text.contains("打赏") || text.contains("抖币")) continue
                        // OCR alone is only a fallback when it explicitly
                        // identifies a super lucky bag. Single characters such
                        // as “福” also occur in normal bags and room copy.
                        val explicitSuperBag = text.contains("超级福袋") ||
                            (text.contains("超级") && text.contains("福袋"))
                        if (!explicitSuperBag) continue
                        val centerYRef = item.bounds.centerY() * 2400 / height
                        if (centerYRef < 100 || centerYRef > 650) continue
                        val centerXRef = item.bounds.centerX() * 1080 / width
                        val slot = nearestSlot(centerXRef, slotCenters)
                        if (failedFudaiSlots[slot] >= 2) continue
                        if (ocrBest == null || slot < ocrBestSlot) {
                            ocrBest = item
                            ocrBestSlot = slot
                        }
                    }
                    if (ocrBest != null) {
                        best = ocrBest
                        bestSlot = ocrBestSlot
                        log.write("[OCR降维补救] 模板匹配未通过，但捕获到槽位${bestSlot + 1}挂件证据“${best.text}”；打入探针点击救回！")
                    }
                }
                topLeft.recycle()
                if (best == null) {
                    bitmap.recycle()
                    scanMisses++
                    if (scanMisses >= 3) {
                        scanMisses = 0
                        state = FsmState.SWITCH_LIVE
                        log.write("[自动流程] 连续3次未发现可参与福袋，上滑切换直播间")
                        // Avoid taking the room-identity screenshot in the
                        // same instant as the just-finished scan screenshot.
                        handler.postDelayed(::switchToNextLive, 650)
                        return@topScan
                    }
                    // Keep the three-attempt rule, but span a wider loading
                    // window so delayed lucky-bag widgets get a fair chance.
                    handler.postDelayed(::automaticScanOnce, 2000)
                    return@topScan
                }
                val clickX = slotCenters[bestSlot] * width / 1080
                val clickY: Int
                if (best.text.startsWith("图标相似度=") || best.text.startsWith("宽松探针=")) {
                    // The template contains only the upper half of the bag.
                    // Move into the clickable body; tapping the match center
                    // itself can hit Douyin's recommendation strip above it.
                    clickY = (best.bounds.centerY() + 48 * height / 2400)
                        .coerceIn(300 * height / 2400, 560 * height / 2400)
                } else {
                    val minY = 260 * height / 2400
                    val maxY = 580 * height / 2400
                    clickY = best.bounds.centerY().coerceIn(minY, maxY)
                }
                bitmap.recycle()
                scanMisses = 0
                state = FsmState.OPEN_FUDAI_DETAIL
                lastAutomaticAction = ""
                cachedDetailAction = ""
                cachedDetailActionX = -1
                cachedDetailActionY = -1
                cachedShoppingFanAction = false
                automaticTaskSteps = 0
                detailNoActionFrames = 0
                detailCountdownMissFrames = 0
                currentBagParticipationClicked = false
                stayInRoomEvidenceSeen = false
                lastWatchRemainingSeconds = -1
                outsideLiveWaitFrames = 0
                updateDrawDeadline(0)
                drawResultAttempts = 0
                drawResultOverlayVisible = false
                detailCountdownConfirmed = false
                detailOverMaxLimit = false
                detailValueThresholdPassed = false
                detailCountdownSample = -1
                detailCountdownSampleAt = 0
                confirmedDrawSeconds = -1
                lastAttemptedFudaiSlot = bestSlot
                host.tap(clickX, clickY) {
                    if (!isActive(epoch) || state != FsmState.OPEN_FUDAI_DETAIL || switchBusy) return@tap
                    log.write("[福袋点击] 系统手势已完成，坐标${clickX},${clickY}；开始验证详情是否真正打开")
                    handler.postDelayed({ verifyFudaiDetail(1) }, 1600)
                }
            }
        }
    }
}

    fun stop() {
        val cancelledRecovery = selfRecovering
        engineEpoch++
        running = false
        automationMode = false
        selfRecovering = false
        cycleBusy = false
        switchBusy = false
        handler.removeCallbacksAndMessages(null)
        taskFaultCount = 0
        lastTaskFault = ""
        log.write(if (cancelledRecovery) "[FSM] 已主动停止，并取消正在进行的终极自救" else "[FSM] 已停止")
        host.onEngineStateChanged()
    }

    fun getState(): FsmState = state

    fun isRunning(): Boolean = running

    fun onGestureCancelled(action: String) {
        if (!running || !automationMode || selfRecovering) return
        val cancelledState = state
        consecutiveGestureCancellations++
        log.write("[手势恢复] 操作被系统中断，稍后自动重试")
        pulseWorkflow()
        if (consecutiveGestureCancellations >= 3) {
            consecutiveGestureCancellations = 0
            beginTerminalRecovery("同类操作连续3次被系统中断：$action")
            return
        }
        switchBusy = false
        when {
            navigationBusy || cancelledState == FsmState.PAGE_CHECK_NAV -> {
            state = FsmState.PAGE_CHECK_NAV
            navigationBusy = true
            handler.postDelayed(::inspectHomeAndNavigate, 1500L)
            }
            cancelledState == FsmState.SWITCH_LIVE -> {
                state = FsmState.SWITCH_LIVE
                switchBusy = true
                handler.postDelayed(::switchToNextLive, 1500L)
            }
            cancelledState == FsmState.CHECK_FUDAI_INFO || cancelledState == FsmState.ATTEND_CHOUJIANG -> {
                state = cancelledState
                handler.postDelayed(::automaticDetailActionScan, 1500L)
            }
            else -> {
                state = FsmState.SCAN_FUDAI_ICON
                handler.postDelayed(::automaticScanOnce, 1500L)
            }
        }
    }

    fun onGestureCompleted() {
        consecutiveGestureCancellations = 0
    }

    fun onDouyinMemoryPressure(pssMb: Int, thresholdMb: Int) {
        if (!running || !automationMode || selfRecovering) return
        beginTerminalRecovery("抖音内存连续超限：${pssMb}MB ≥ ${thresholdMb}MB")
    }

    fun runNavigationTest() {
        if (navigationBusy) {
            log.write("[导航流程] 已在执行，忽略重复点击")
            return
        }
        stop()
        navigationBusy = true
        state = FsmState.PAGE_CHECK_NAV
        log.write("[导航流程] 开始；执行进入直播间导航")
        if (!host.isDouyinForeground()) {
            navigationBusy = false
            log.write("[导航流程] 前台不是抖音，已停止")
            return
        }
        inspectHomeAndNavigate()
    }

    fun runFudaiScanTest() {
        stop()
        state = FsmState.SCAN_FUDAI_ICON
        log.write("[福袋扫描测试] 开始；只识别不点击")
        if (!host.isDouyinForeground()) {
            log.write("[福袋扫描测试] 前台不是抖音，已停止")
            return
        }
        host.requestScreenshot { bitmap ->
            if (bitmap == null) {
                log.write("[福袋扫描测试] 截图失败")
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            val topLeft = Bitmap.createBitmap(bitmap, 0, 0,
                maxOf(1, (width * 0.50).toInt()),
                maxOf(1, (height * 0.30).toInt()))
            ocr.recognize(topLeft) { result ->
                val slotCenters = intArrayOf(80, 206, 332, 458)
                var best: OfflineOcr.Item? = null
                var bestSlot = -1
                for (item in result.items) {
                    val text = item.text.replace(" ", "")
                    if (!isCountdownOrPending(text)) continue
                    val centerYRef = item.bounds.centerY() * 2400 / height
                    if (centerYRef < 110 || centerYRef > 560) continue
                    val centerXRef = item.bounds.centerX() * 1080 / width
                    val slot = nearestSlot(centerXRef, slotCenters)
                    if (!isSuperFudaiIcon(bitmap, slot)) {
                        log.write("[福袋扫描测试] 槽位${slot + 1}读到“${text}”，但缺乏超级福袋专属深紫 (135~175) 特征（判定为钻石红包/优惠券），拒点")
                        continue
                    }
                    if (best == null || slot < bestSlot) {
                        best = item
                        bestSlot = slot
                    }
                }
                if (best == null) {
                    lastFudaiX = -1
                    lastFudaiY = -1
                    log.write("[福袋扫描测试] 未命中；顶部OCR=${result.text.replace('\n', ' ').trim()}")
                } else {
                    val clickX = slotCenters[bestSlot] * width / 1080
                    val clickY = 350 * height / 2400
                    lastFudaiX = clickX
                    lastFudaiY = clickY
                    lastFudaiFoundAt = System.currentTimeMillis()
                    log.write("[福袋扫描测试] 命中槽位${bestSlot + 1}，证据“${best.text}”，候选中心=${clickX},${clickY}；测试模式不点击")
                }
                topLeft.recycle()
                bitmap.recycle()
            }
        }
    }

    fun runOpenDetailTest() {
        stop()
        if (lastFudaiX < 0 || lastFudaiY < 0 || System.currentTimeMillis() - lastFudaiFoundAt > 15000) {
            log.write("[详情确认] 没有15秒内的福袋扫描结果，禁止使用固定坐标")
            return
        }
        if (!host.isDouyinForeground()) {
            log.write("[详情确认] 前台不是抖音，已停止")
            return
        }
        state = FsmState.OPEN_FUDAI_DETAIL
        val x = lastFudaiX
        val y = lastFudaiY
        lastFudaiX = -1
        lastFudaiY = -1
        log.write("[详情确认] 点击刚扫描到的福袋中心=${x},${y}")
        host.tap(x, y) { handler.postDelayed({ verifyFudaiDetail(1) }, 1600) }
    }

    fun runDetailInspectTest() {
        stop()
        if (!host.isDouyinForeground()) {
            log.write("[详情识别] 前台不是抖音，已停止")
            return
        }
        state = FsmState.CHECK_FUDAI_INFO
        log.write("[详情识别] 开始；单帧同时识别倒计时、任务进度和动作按钮")
        inspectFudaiDetail()
    }

    private fun inspectOnce() {
        cycleBusy = true
        host.requestScreenshot { bitmap ->
            if (bitmap == null) {
                cycleBusy = false
                log.write("[截图] 获取失败，稍后重试")
                return@requestScreenshot
            }
            val roi = cropForState(bitmap)
            ocr.recognize(roi) { result ->
                val normalized = result.text.replace('\n', ' ').trim()
                frameCount++
                log.write(String.format(Locale.CHINA, "[%s][第%d帧] OCR=%s",
                    state.name, frameCount,
                    if (normalized.length > 100) normalized.substring(0, 100) else normalized))
                // Observation-only transition. It never performs a gesture.
                if (state == FsmState.INIT) {
                    state = FsmState.PAGE_CHECK_NAV
                } else if (state == FsmState.PAGE_CHECK_NAV) {
                    state = if (normalized.contains("首页")) FsmState.PAGE_CHECK_NAV else FsmState.SCAN_FUDAI_ICON
                } else if (state == FsmState.SCAN_FUDAI_ICON && normalized.contains("首页")) {
                    state = FsmState.PAGE_CHECK_NAV
                }
                roi.recycle()
                bitmap.recycle()
                cycleBusy = false
            }
        }
    }

    private var followedLiveCheckAttempts = 0
    private var cachedFollowTabX = -1
    private var cachedFollowTabY = -1

    private fun inspectHomeAndNavigate() {
        val automated = automationMode
        if (!navigationBusy || state != FsmState.PAGE_CHECK_NAV ||
            (automated && !running)) return
        pulseWorkflow()
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                log.write("[导航流程] 暂时无法获取画面，正在重试")
                if (automated) expectWorkflowDelay(1_200L)
                handler.postDelayed(::inspectHomeAndNavigate, 1_200L)
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            val bottomLeft = Bitmap.createBitmap(bitmap, 0, (height * 0.86).toInt(),
                maxOf(1, (width * 0.28).toInt()),
                maxOf(1, (height * 0.14).toInt()))
            ocr.recognize(bottomLeft) { result ->
                if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                    bottomLeft.recycle()
                    bitmap.recycle()
                    return@recognize
                }
                val text = result.text.replace('\n', ' ').trim()
                bottomLeft.recycle()
                if (hasLiveCommentEntry(text)) {
                    bitmap.recycle()
                    state = FsmState.SCAN_FUDAI_ICON
                    finishNavigation("左下角识别到“说点/什么”，确认已在直播间，直接开始")
                    return@recognize
                }
                val bottomNavigationVisible = text.contains("首页") || text.contains("朋友")
                if (!bottomNavigationVisible) {
                    bitmap.recycle()
                    returnToHomeAndRestartNavigation("左下角未识别到“说点/什么”或“首页/朋友”")
                    return@recognize
                }
                val top = Bitmap.createBitmap(bitmap, 0, 0, width, maxOf(1, (height * 0.38).toInt()))
                bitmap.recycle()
                ocr.recognize(top) navTop@{ topResult ->
                    if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                        top.recycle()
                        return@navTop
                    }
                    val targetTab = if (switchedToLiveList) "直播" else "关注"
                    var follow = findTopTab(topResult, targetTab, top.width, top.height)
                    if (follow == null && !switchedToLiveList) {
                        follow = findTopTab(topResult, "直播", top.width, top.height)
                        if (follow != null) {
                            switchedToLiveList = true
                            log.write("[导航流程] 顶部未找到“关注”，自动转降至“直播”标签页")
                        }
                    }
                    val screenW = top.width
                    val screenH = (top.height / 0.38f).toInt()
                    top.recycle()
                    if (follow == null) {
                        log.write("[导航流程] 顶部OCR=${topResult.text.replace('\n', ' ').trim()}")
                        finishNavigation("顶部未识别到“关注/直播”，禁止盲点")
                        return@navTop
                    }
                    val currentTabName = follow.text.trim()
                    cachedFollowTabX = follow.bounds.centerX()
                    cachedFollowTabY = follow.bounds.centerY()
                    log.write("[导航流程] 点击顶部“${currentTabName}”：${cachedFollowTabX},${cachedFollowTabY}")
                    host.tap(cachedFollowTabX, cachedFollowTabY) {
                        if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) return@tap
                        handler.postDelayed({
                            if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) return@postDelayed
                            if (switchedToLiveList || currentTabName.contains("直播")) {
                                log.write("[导航流程] “直播”列表已切换，等待5.0秒等待页面完全加载渲染稳定...")
                                handler.postDelayed({
                                    tapLivePageCenterDirectly(screenW, screenH)
                                }, 5000)
                            } else {
                                log.write("[导航流程] “关注”页已响应，等待5.0秒彻底加载开播主播列表...")
                                followedLiveCheckAttempts = 0
                                handler.postDelayed(::enterFirstFollowedLive, 5000)
                            }
                        }, 1200)
                    }
                }
            }
        }
    }

    private fun enterFirstFollowedLive() {
        val automated = automationMode
        if (!navigationBusy || state != FsmState.PAGE_CHECK_NAV ||
            (automated && !running)) return
        pulseWorkflow()
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                log.write("[导航流程] 页面仍在加载，正在重新识别")
                if (automated) expectWorkflowDelay(1_200L)
                handler.postDelayed(::enterFirstFollowedLive, 1_200L)
                return@requestScreenshot
            }
            ocr.recognize(bitmap) { result ->
                if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                    bitmap.recycle()
                    return@recognize
                }
                val live = result.items.stream()
                    .filter { item -> item.text.contains("直播中") || item.text.contains("带货中") }
                    .filter { item -> item.bounds.centerY() >= 180 && item.bounds.centerY() <= 650 }
                    .filter { item -> item.bounds.centerX() >= 25 }
                    .min(Comparator.comparingInt { item -> item.bounds.centerX() })
                    .orElse(null)
                if (live != null) {
                    val x = Math.round(bitmap.width * 0.125f)
                    val y = maxOf(200, live.bounds.centerY())
                    lastFollowedLiveX = x
                    lastFollowedLiveY = y
                    bitmap.recycle()
                    unfollowedRoomsSinceFollowEntry = 0
                    currentRoomCountedUnfollowed = false
                    liveEntryVerifyAttempts = 0
                    followedLiveCheckAttempts = 0
                    log.write("[导航流程] “关注”页识别到正在直播的主播，点击第一槽位：${x},${y}（证据“${live.text}”）")
                    host.tap(x, y) {
                        if (!isOperationCurrent(epoch, automated)) return@tap
                        scheduleLiveEntryVerification(epoch, automated)
                    }
                    return@recognize
                }

                // Retry clicking "关注" tab once if no live stream found
                if (!switchedToLiveList && followedLiveCheckAttempts < 1) {
                    followedLiveCheckAttempts++
                    val bitmapWidth = bitmap.width
                    bitmap.recycle()
                    val tapX = if (cachedFollowTabX > 0) cachedFollowTabX else (bitmapWidth * 0.60f).toInt()
                    val tapY = if (cachedFollowTabY > 0) cachedFollowTabY else 140
                    log.write("[导航流程] “关注”页未识别到开播主播，再次点击“关注”刷新（${followedLiveCheckAttempts}/1），等待5.0秒后再次识别...")
                    host.tap(tapX, tapY) {
                        if (!isOperationCurrent(epoch, automated)) return@tap
                        handler.postDelayed({
                            if (isOperationCurrent(epoch, automated)) enterFirstFollowedLive()
                        }, 5000)
                    }
                    return@recognize
                }

                if (switchedToLiveList) {
                    val centerX = bitmap.width / 2
                    val centerY = bitmap.height / 2
                    bitmap.recycle()
                    log.write("[导航流程] 已在“直播”页，连续点击两次屏幕中央进入直播间：${centerX},${centerY}")
                    tapLiveCenterTwice(centerX, centerY, epoch, automated)
                    return@recognize
                }

                val liveTab = findTopTab(result, "直播", bitmap.width, bitmap.height)
                val width = bitmap.width
                val height = bitmap.height
                bitmap.recycle()
                if (liveTab == null) {
                    finishNavigation("二次识别“关注”仍无开播，且未找到“直播”标签页，停止")
                    return@recognize
                }
                log.write("[导航流程] 二次识别“关注”仍无开播主播，切换至顶部“直播”标签页并等待5.0秒稳定...")
                switchedToLiveList = true
                livePageRefreshes = 0
                host.tap(liveTab.bounds.centerX(), liveTab.bounds.centerY()) {
                    if (!isOperationCurrent(epoch, automated)) return@tap
                    handler.postDelayed({
                        if (isOperationCurrent(epoch, automated)) {
                            log.write("[导航流程] “直播”列表加载完成，准备进入直播间")
                            tapLivePageCenterDirectly(width, height)
                        }
                    }, 5000)
                }
            }
        }
    }

    private fun tapLivePageCenterDirectly(width: Int, height: Int) {
        val automated = automationMode
        if (!navigationBusy || state != FsmState.PAGE_CHECK_NAV ||
            (automated && !running)) return
        pulseWorkflow()
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                log.write("[导航流程] 直播列表仍在加载，正在重试")
                if (automated) expectWorkflowDelay(1_200L)
                handler.postDelayed({
                    if (isOperationCurrent(epoch, automated)) tapLivePageCenterDirectly(width, height)
                }, 1_200L)
                return@requestScreenshot
            }
            val centerX = bitmap.width / 2
            val centerY = bitmap.height / 2
            bitmap.recycle()
            liveEntryVerifyAttempts = 0
            log.write("[导航流程] 连续点击两次屏幕中央进入直播间")
            tapLiveCenterTwice(centerX, centerY, epoch, automated)
        }
    }

    private fun tapLiveCenterTwice(
        centerX: Int,
        centerY: Int,
        epoch: Int,
        automated: Boolean
    ) {
        host.tap(centerX, centerY) {
            if (!isOperationCurrent(epoch, automated) || !navigationBusy ||
                state != FsmState.PAGE_CHECK_NAV) return@tap
            handler.postDelayed({
                if (!isOperationCurrent(epoch, automated) || !navigationBusy ||
                    state != FsmState.PAGE_CHECK_NAV) return@postDelayed
                host.tap(centerX, centerY) secondTap@{
                    if (!isOperationCurrent(epoch, automated)) return@secondTap
                    scheduleLiveEntryVerification(epoch, automated)
                }
            }, 1_000L)
        }
    }

    private fun verifyEnteredLive() {
        val automated = automationMode
        if (!navigationBusy || state != FsmState.PAGE_CHECK_NAV ||
            (automated && !running)) return
        pulseWorkflow()
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                log.write("[导航流程] 直播间画面仍在加载，正在重新确认")
                if (automated) expectWorkflowDelay(1_200L)
                handler.postDelayed({
                    if (isOperationCurrent(epoch, automated)) verifyEnteredLive()
                }, 1_200L)
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            val bottomLeft = Bitmap.createBitmap(bitmap, 0, (height * 0.86).toInt(),
                maxOf(1, (width * 0.28).toInt()),
                maxOf(1, (height * 0.14).toInt()))
            ocr.recognize(bottomLeft) { result ->
                if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) {
                    bottomLeft.recycle()
                    bitmap.recycle()
                    return@recognize
                }
                val text = result.text.replace('\n', ' ').trim()
                bottomLeft.recycle()
                val bottomNavigationVisible = text.contains("首页") || text.contains("朋友")
                if (bottomNavigationVisible) {
                    bitmap.recycle()
                    liveEntryVerifyAttempts = 0
                    log.write("[导航硬隔离] 进入验证检测到左下角包含“首页/朋友”底栏导航（OCR=${text}），确认仍在列表页未入直播间！强制重启整套导航流程")
                    returnToHomeAndRestartNavigation("确认仍在列表页，重启导航")
                    return@recognize
                }
                if (hasLiveCommentEntry(text)) {
                    bitmap.recycle()
                    liveEntryVerifyAttempts = 0
                    state = FsmState.SCAN_FUDAI_ICON
                    finishNavigation("左下角无“首页/朋友”底栏且识别到“说点/什么”，确认成功进入直播间")
                    return@recognize
                }

                val header = Bitmap.createBitmap(bitmap, 0, 0, width, maxOf(1, (height * 0.36).toInt()))
                bitmap.recycle()
                ocr.recognize(header) headerScan@{ headerResult ->
                    val liveRoom = hasLiveRoomEvidence(headerResult)
                    header.recycle()
                    if (!isOperationCurrent(epoch, automated) || !navigationBusy || state != FsmState.PAGE_CHECK_NAV) return@headerScan
                    if (liveRoom) {
                        liveEntryVerifyAttempts = 0
                        state = FsmState.SCAN_FUDAI_ICON
                        finishNavigation("顶部识别到本场点赞/榜单等直播间特征且无底栏，确认已进入直播间")
                        return@headerScan
                    }
                    liveEntryVerifyAttempts++
                    if (liveEntryVerifyAttempts <= 2) {
                        if (switchedToLiveList) {
                            val centerX = width / 2
                            val centerY = Math.round(height * 0.48f)
                            log.write("[导航流程] “直播”列表重试连续点击两次屏幕中央，间隔1秒：${centerX},${centerY}（${liveEntryVerifyAttempts}/2）")
                            tapLiveCenterTwice(centerX, centerY, epoch, automated)
                        } else {
                            val tapX = if (lastFollowedLiveX > 0) lastFollowedLiveX else 135
                            val tapY = if (lastFollowedLiveY > 0) lastFollowedLiveY else 400
                            log.write("[导航流程] “关注”第一直播卡坐标重试点击：${tapX},${tapY}（${liveEntryVerifyAttempts}/2）")
                            host.tap(tapX, tapY) {
                                if (!isOperationCurrent(epoch, automated)) return@tap
                                scheduleLiveEntryVerification(epoch, automated)
                            }
                        }
                        return@headerScan
                    }
                    liveEntryVerifyAttempts = 0
                    returnToHomeAndRestartNavigation("重试点击后仍未确认进入直播间")
                }
            }
        }
    }

    private fun hasLiveCommentEntry(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return normalized.contains("说点什么") || normalized.contains("说点")
    }

    private fun returnToHomeAndRestartNavigation(reason: String) {
        log.write("[导航纠错] $reason${if (switchedToLiveList) "；保持直播列表路线重试，不再跳回关注列表" else "；返回首页重新开始"}")
        if (selfRecovering) return
        val epoch = engineEpoch
        val automated = automationMode
        state = FsmState.PAGE_CHECK_NAV
        navigationBusy = true
        liveEntryWaitCompleted = false
        followPageRefreshes = 0
        livePageRefreshes = 0
        host.back {
            if (!isOperationCurrent(epoch, automated)) return@back
            handler.postDelayed({
                if (isOperationCurrent(epoch, automated)) inspectHomeAndNavigate()
            }, 1200)
        }
    }

    private fun hasLiveListEvidence(result: OfflineOcr.Result): Boolean {
        var hasListHeader = false
        var hasBottomNav = false
        for (item in result.items) {
            val text = item.text.replace(Regex("\\s+"), "")
            if (LIVE_COUNT.matcher(text).matches() || text.contains("关注的直播") || text.contains("更多直播") || text.contains("推荐直播")) {
                hasListHeader = true
            }
            if (text.contains("首页") || text.contains("朋友") || text.contains("消息")) {
                hasBottomNav = true
            }
        }
        return hasListHeader || (hasBottomNav && result.text.contains("直播"))
    }

    private fun hasLiveRoomEvidence(result: OfflineOcr.Result): Boolean {
        val text = result.text.replace(Regex("\\s+"), "")
        return text.contains("本场点赞") || text.contains("带货总榜") ||
            text.contains("人气榜") || text.contains("小时榜") || text.contains("挑战榜") ||
            text.contains("直播广场") || text.contains("说点什么") ||
            (text.contains("本场") && text.contains("点赞"))
    }

    private fun verifyFudaiDetail(attempt: Int) {
        val automated = automationMode
        if (switchBusy ||
            (state != FsmState.OPEN_FUDAI_DETAIL && state != FsmState.CHECK_FUDAI_INFO && state != FsmState.ATTEND_CHOUJIANG) ||
            (automated && !running)) return
        pulseWorkflow()
        if (attempt == 1) performedTaskActionInCurrentDetail = false
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isOperationCurrent(epoch, automated) || switchBusy || state == FsmState.SWITCH_LIVE) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                log.write("[详情确认] 画面仍在加载，正在重新确认")
                if (automated) expectWorkflowDelay(1_200L)
                handler.postDelayed({
                    if (isOperationCurrent(epoch, automated)) verifyFudaiDetail(attempt)
                }, 1_200L)
                return@requestScreenshot
            }
            resetTaskFaults()
            val width = bitmap.width
            val height = bitmap.height
            val detailLeft = (width * 0.02).toInt()
            val detailTop = (height * 0.18).toInt()
            val detail = Bitmap.createBitmap(bitmap, detailLeft, detailTop,
                maxOf(1, (width * 0.96).toInt()),
                maxOf(1, (height * 0.78).toInt()))
            bitmap.recycle()
            ocr.recognize(detail) { result ->
                if (!isOperationCurrent(epoch, automated) || switchBusy || state == FsmState.SWITCH_LIVE) {
                    detail.recycle()
                    return@recognize
                }
                val text = result.text.replace('\n', ' ').trim()
                detail.recycle()
                // Strict positive confirmation: entrance icons/countdowns
                // are only hints. The detail must explicitly say
                // "超级福袋" before any task action is allowed.
                val compactDetail = text.replace(Regex("\\s+"), "")
                // Strict positive confirmation: only accept explicit "超级福袋" title or fuzzy match
                val opened = compactDetail.contains("超级福袋") || hasFuzzySuperBagTitle(result)
                val diamondBag = isDiamondBag(text)
                val redPacket = isRedPacket(text)
                val couponPopup = isCouponPopup(text)
                when {
                    couponPopup && automationMode && running -> skipCurrentDetail("[规则排除] 识别到抖音支付/优惠券弹窗，关闭并切房")
                    redPacket && automationMode && running -> skipCurrentDetail("[规则排除] 识别到红包活动，不参与并切房")
                    diamondBag && automationMode && running -> skipCurrentDetail("[规则排除] 识别到钻石/支付礼包福袋，不参与并切房")
                    opened -> {
                        if (lastAttemptedFudaiSlot >= 0) failedFudaiSlots[lastAttemptedFudaiSlot] = 0
                        lastAttemptedFudaiSlot = -1
                        state = FsmState.CHECK_FUDAI_INFO
                        log.write("[详情确认] 弹窗已确认打开；OCR=${trim(text, 220)}")
                        logDetailInfo(result)
                        if (automationMode && running) {
                            handleAutomaticDetailAction(result, detailLeft, detailTop)
                        }
                    }
                    attempt < 2 -> {
                        log.write("[详情确认] 手势已完成但尚未识别到超级福袋详情，短暂复核一次（${attempt}/2）；不代表弹窗已经打开")
                        handler.postDelayed({
                            if (isOperationCurrent(epoch, automated)) verifyFudaiDetail(attempt + 1)
                        }, 1200)
                    }
                    else -> {
                        if (lastAttemptedFudaiSlot >= 0) {
                            val slot = lastAttemptedFudaiSlot
                            failedFudaiSlots[slot]++
                            if (failedFudaiSlots[slot] >= 2) {
                                log.write("[福袋候选排除] 槽位${slot + 1}连续2次未打开详情，本直播间加入黑名单；改试其他候选")
                            } else {
                                log.write("[福袋候选复试] 槽位${slot + 1}首次未打开详情，允许最后复试一次")
                            }
                        }
                        log.write("[详情确认] 连续2帧未出现超级福袋详情，重新截图并跳过已拉黑槽位；OCR=${trim(text, 120)}")
                        state = FsmState.SCAN_FUDAI_ICON
                        handler.postDelayed(::automaticScanOnce, 800)
                    }
                }
            }
        }
    }

    private fun isDiamondBag(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return normalized.matches(Regex("(?s).*总\\d+钻石.*")) ||
            normalized.contains("抖音支付/月付礼包") ||
            (normalized.contains("抖音支付") && normalized.contains("钻石")) ||
            (normalized.contains("此福袋额外附赠") && normalized.contains("钻石"))
    }

    private fun hasFuzzySuperBagTitle(result: OfflineOcr.Result): Boolean {
        for (item in result.items) {
            val value = item.text.replace(Regex("[\\s·:：|丨()（）]+"), "")
            val index = value.indexOf("福袋")
            if (index < 1 || index > 2) continue
            // Exclusions are handled before the positive detail branch, but
            // keep them out here too so a fuzzy title can never override it.
            if (value.contains("钻石") || value.contains("支付") || value.contains("红包")) continue
            log.write("[详情识别] 标题“${item.text}”中“福袋”前有${index}个字，按超级福袋OCR容错确认")
            return true
        }
        return false
    }

    private fun isRedPacket(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return normalized.contains("红包") || normalized.contains("查看红包") || normalized.contains("钻石红") ||
            normalized.contains("拆红包") || normalized.contains("打赏") ||
            normalized.contains("现金") || normalized.contains("抖币") || normalized.contains("分钱")
    }

    private fun isCouponPopup(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return normalized.contains("抖音支付") ||
            normalized.contains("无门槛券") ||
            normalized.contains("绑卡奖励") ||
            (normalized.contains("开心收下") && normalized.contains("券"))
    }

    private fun isShoppingFanClubTask(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return normalized.contains("购物粉丝团") || normalized.contains("购物粉丝") ||
            normalized.contains("加入购物") || normalized.contains("加人购物") ||
            normalized.contains("店铺粉丝团")
    }

    private fun handleAutomaticDetailAction(result: OfflineOcr.Result, offsetX: Int, offsetY: Int) {
        val all = result.text.replace(Regex("\\s+"), "")
        if (hasEndedDetailCountdown(result)) {
            leaveEndedDetail()
            return
        }
        val personal = all.replace(Regex("\\d+人已参与"), "")
        val taskStatus = personal.replace(Regex("完成.{0,4}抽取奖[品。.]?"), "")
        val shoppingFanTask = isShoppingFanClubTask(all)
        var hasPendingTask = taskStatus.contains("未达")
        var hasIncompleteProgress = false
        for (item in result.items) {
            val progress = TASK_PROGRESS.matcher(item.text.replace(" ", ""))
            if (!progress.matches()) continue
            val done = progress.intGroup(1)
            val total = progress.intGroup(2)
            if (total > 0 && done < total) {
                hasPendingTask = true
                hasIncompleteProgress = true
            }
        }
        val requiresFanClub = (all.contains("加入粉丝团") || all.contains("粉丝团任务")) &&
            (hasPendingTask || hasIncompleteProgress || taskStatus.contains("未达成"))
        if (requiresFanClub && !shoppingFanTask && !currentBagParticipationClicked) {
            skipCurrentDetail("[规则跳过] 福袋要求加入粉丝团，本助手不执行入团任务，直接跳过并切房")
            return
        }
        if (shoppingFanTask && !currentBagParticipationClicked) {
            log.write("[任务白名单] 精确识别到“加入购物粉丝团”；允许执行。普通加入粉丝团仍禁止")
        }
        if (currentBagParticipationClicked && !hasPendingTask && !hasIncompleteProgress) {
            log.write("[参与保护] 本直播间福袋任务已成功参与完成！绝对锁死当前直播间等待开奖，禁止一切切房！")
            state = FsmState.SCAN_FUDAI_ICON
            waitForDrawResult()
            return
        }
        val isFudai = all.contains("超级福袋") || all.contains("超级") || all.contains("级福袋") || all.contains("超级福") ||
            all.contains("福袋") || all.contains("福袋任务") || all.contains("福袋奖品") || all.contains("一键发表") ||
            all.contains("一键评论") || all.contains("发表评论") || all.contains("发送评论") || all.contains("已达成") ||
            all.contains("未达成") || all.contains("请勿离开")
        // A merchant may put ordinary promotional words in the required
        // comment text of a genuine super lucky bag. Those words
        // must never override the already-confirmed lucky-bag structure. The
        // incompatible reward type requested by the product rule is identified
        // by its actual reward keyword: diamonds.
        val isRedPacketOrDiamond = all.contains("钻石")
        if (isRedPacketOrDiamond || !isFudai) {
            if (performedTaskActionInCurrentDetail) {
                currentBagParticipationClicked = true
                state = FsmState.SCAN_FUDAI_ICON
                log.write("[参与保护] 曾下发参与评论/任务手势（即使本帧弹窗收起或丢失关键字），坚决保留在当前直播间等待开奖，绝对禁止切房！")
                waitForDrawResult()
                return
            }
            skipCurrentDetail("[误点拦截] 检测到弹窗包含“钻石/抖币/红包”或全文字缺乏“福袋”属性非超级福袋，直接关闭并切房")
            return
        }
        val detailVisible = all.contains("超级福袋") || all.contains("福袋任务") ||
            all.contains("福袋奖品") || all.contains("完成任务") || all.contains("抽取奖品") ||
            all.contains("任务已完成") || all.contains("等待开奖") || all.contains("观看直播") ||
            all.contains("看播") || all.contains("去完成") || all.contains("开始观看") ||
            all.contains("未达成") || all.contains("已达成") || all.contains("一键发表") ||
            all.contains("一键评论") || all.contains("发表评论") || all.contains("发送评论") ||
            all.contains("一键参与") || all.contains("参与抽奖") || all.contains("加入粉丝团")
        if (!detailVisible) {
            if (performedTaskActionInCurrentDetail || currentBagParticipationClicked) {
                currentBagParticipationClicked = true
                state = FsmState.SCAN_FUDAI_ICON
                log.write("[任务全完] 本轮下发任务动作后官方已自动收起弹窗，确认全套福袋任务已全部完成！坚决锁死当前直播间，全静默等待开奖")
                waitForDrawResult()
            } else {
                switchWithoutClosing("[自动流程] 详情消失但本轮没有实际参与动作，禁止按参与成功处理；直接切房")
            }
            return
        }
        rememberDetailAction(result, offsetX, offsetY)
        if (detailOverMaxLimit) {
            skipCurrentDetail("[规则跳过] 开奖倒计时为${confirmedDrawSeconds / 60}分钟（${confirmedDrawSeconds}秒），超过设置上限${host.getMaxFudaiMinutes()}分钟，跳过并切房")
            return
        }
        if (!confirmDetailCountdown(result)) {
            if (selfRecovering) return
            detailCountdownMissFrames++
            if (detailCountdownMissFrames >= 6) {
                if (performedTaskActionInCurrentDetail || currentBagParticipationClicked) {
                    currentBagParticipationClicked = true
                    log.write("[开奖等待] 任务动作已执行但倒计时连续丢失，禁止直接切房；进入保守等待")
                    state = FsmState.SCAN_FUDAI_ICON
                    waitForDrawResult()
                    return
                }
                skipCurrentDetail("[规则跳过] 连续6帧未取得可靠详情倒计时，禁止无限等待；关闭并切房")
                return
            }
            handler.postDelayed(::automaticDetailActionScan, 1200)
            return
        }
        detailCountdownMissFrames = 0
        val completionEvidence = taskStatus.contains("等待开奖") || taskStatus.contains("等开") ||
            taskStatus.contains("待开") || taskStatus.contains("已参与") || taskStatus.contains("参与成功")
        if (!hasPendingTask && !hasIncompleteProgress && (personal.contains("已参") || personal.contains("已加") || personal.contains("成功") || completionEvidence ||
                personal.contains("等待开奖"))) {
            currentBagParticipationClicked = true
            state = FsmState.SCAN_FUDAI_ICON
            if (!performedTaskActionInCurrentDetail) {
                log.write("[标志自愈] 弹窗内未做任务操作（主动复核已完成“请勿离开直播间”）；官方不自动收起，助手主动按Back键关闭弹窗并留存")
                host.back { handler.postDelayed(::waitForDrawResult, 1200) }
            } else {
                log.write("[标志自愈] 本轮弹窗内已完成全部任务操作；依靠官方自动收起弹窗，助手绝对不动Back键，全静默等待开奖")
                handler.postDelayed(::waitForDrawResult, 1500)
            }
            return
        }
        if (hasPendingTask || hasIncompleteProgress) {
            // Continue into the task-action phase below.
        }
        if (!currentBagParticipationClicked && automaticTaskSteps >= 5 && performedTaskActionInCurrentDetail) {
            currentBagParticipationClicked = true
            log.write("[开奖等待] 已执行任务动作但动作计数达到保护上限，按已参与处理，禁止切房")
            state = FsmState.SCAN_FUDAI_ICON
            waitForDrawResult()
            return
        }
        if (!currentBagParticipationClicked && automaticTaskSteps >= 5 && !performedTaskActionInCurrentDetail) {
            skipCurrentDetail("[自动流程] 多任务已处理5步，跳过并切房")
            return
        }

        // Validation and action execution are deliberately split between the
        // two overloads. Continue into the action phase after all guards pass.
        handleAutomaticDetailAction(result, offsetX, offsetY, isSecondPass = false)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleAutomaticDetailAction(result: OfflineOcr.Result, offsetX: Int, offsetY: Int, isSecondPass: Boolean = false) {
        if (automationMode && (!running || switchBusy || state == FsmState.SWITCH_LIVE)) return
        val all = result.text.replace(Regex("\\s+"), "")
        // The configured people limit applies both to live-room viewers and
        // to the stronger number shown in the lucky-bag detail ("N人已参与").
        // Previously only the top live-room header was checked, so a bag with
        // 400+ participants could pass a configured limit of 200.
        val participantCount = findFudaiParticipantCount(all)
        val peopleLimit = host.getMaxLiveViewerCount()
        if (peopleLimit > 0 && participantCount > peopleLimit) {
            skipCurrentDetail("[人数过滤] 当前福袋已有约${participantCount}人参与，超过设置上限${peopleLimit}人；不参与并切换直播间")
            return
        }
        if (!isSecondPass) {
            val shoppingFanTask = isShoppingFanClubTask(all)
            val unavailable = arrayOf(
                "请先加入粉丝团", "加入粉丝团后",
                "粉丝团等级不足", "粉丝等级不足",
                "粉丝团等级不够", "等级不足", "等级不够",
                "未达到等级", "暂不可参与", "无法参与",
                "不能参与", "不满足参与条件", "参与条件不足",
                "不符合参与条件", "尚未满足参与条件", "仅限粉丝团",
                "粉丝团成员可参与", "粉丝团等级需达到", "粉丝等级需达到"
            )
            for (phrase in unavailable) {
                if (shoppingFanTask && (phrase == "请先加入粉丝团" || phrase == "加入粉丝团后")) continue
                if (all.contains(phrase)) {
                    skipCurrentDetail("[规则跳过] 命中参与限制“$phrase”，禁止点击并切房")
                    return
                }
            }

            var drawSeconds = confirmedDrawSeconds
            if (drawSeconds < 0) {
                skipCurrentDetail("[规则跳过] 未取得可靠开奖倒计时，禁止参与并切房")
                return
            }
            val maxMinutes = host.getMaxFudaiMinutes()
            if (drawSeconds > maxMinutes * 60) {
                skipCurrentDetail("[规则跳过] 开奖倒计时超过${maxMinutes}分钟（${drawSeconds}秒），不参与并切房")
                return
            }
            val minFudaiValueLimit = host.getMinFudaiValue()
            if (minFudaiValueLimit > 0 && !detailValueThresholdPassed) {
                val fudaiValue = findFudaiValue(result)
                if (fudaiValue >= minFudaiValueLimit.toDouble()) {
                    detailValueThresholdPassed = true
                    log.write("[门槛锁定] 参考价值￥${String.format(Locale.CHINA, "%.1f", fudaiValue)}已通过最低门槛，后续OCR波动不再反向跳过")
                }
                if (fudaiValue >= 0.0 && fudaiValue < minFudaiValueLimit.toDouble()) {
                    skipCurrentDetail("[规则跳过] 福袋参考价值￥${String.format(Locale.CHINA, "%.1f", fudaiValue)}低于设置门槛￥${minFudaiValueLimit}元，跳过并切房")
                    return
                } else if (fudaiValue < 0.0) {
                    log.write("[门槛放行] 未明确识别到“￥xxx 参考价值”，忽略价值门槛限制，继续参与任务")
                }
            }
            val watchMatcher = WATCH_MINUTES.matcher(all)
            if (watchMatcher.matches()) {
                val watchSeconds = watchMatcher.intGroup(1) * 60
                // 倒计时本身已是当前实时值。不要再强制预留 15 秒，否则像
                // “剩余 5:02 / 观看 5 分钟”这种实际可完成的任务会被误跳过。
                if (drawSeconds < watchSeconds) {
                    skipCurrentDetail("[规则跳过] 开奖剩余${drawSeconds}秒，观看任务需${watchSeconds}秒，时间不足；切房")
                    return
                }
            }
        } else {
            log.write("[第二遍识别] 第一遍已通过约束门槛；本轮全面忽略价格/时长限制，专心识别寻找下一动作按钮")
        }

        val hasPendingTask = all.contains("未达成")
        val hasIncompleteProgress = result.items.any { item ->
            val matcher = TASK_PROGRESS.matcher(item.text.replace(" ", ""))
            matcher.matches() && matcher.intGroup(1) < matcher.intGroup(2)
        }
        lastDetailHasPendingTask = hasPendingTask || hasIncompleteProgress
        val participationEvidence = all.contains("请勿离开直播间")
        if (participationEvidence && !stayInRoomEvidenceSeen) {
            stayInRoomEvidenceSeen = true
            log.write("[参与证据] 识别到“请勿离开直播间”；仅记录候选证据，等待任务动作及完成状态共同确认")
        }
        val watchRemainingSeconds = findWatchRemainingSeconds(result)
        if (watchRemainingSeconds > 0) {
            // 此类福袋里“还需看播 xx:xx”是任务完成后的状态提示，不是
            // 助手需要等待归零的开奖计时。真正的停留时间只取“xx:xx 后开奖”。
            performedTaskActionInCurrentDetail = true
            currentBagParticipationClicked = true
            detailNoActionFrames = 0
            lastWatchRemainingSeconds = watchRemainingSeconds
            state = FsmState.SCAN_FUDAI_ICON
            log.write("[观看任务] 按钮显示“还需看播${watchRemainingSeconds}秒”，按任务已完成处理；不等待该数字，改按福袋开奖倒计时留房等待开奖")
            waitForDrawResult()
            return
        }
        executeTaskActionByNode()
    }

    private fun executeTaskActionByNode() {
        // Douyin Lite does not expose the inner lucky-bag controls reliably.
        // Prefer the exact OCR button rectangle cached from the current frame.
        if (executeStrictCachedTaskAction()) return
        val epoch = engineEpoch
        host.performFudaiTaskActionByNode { label ->
            if (!isActive(epoch) || switchBusy || state == FsmState.SWITCH_LIVE) return@performFudaiTaskActionByNode
            if (!label.isNullOrBlank()) {
                performedTaskActionInCurrentDetail = true
                automaticTaskSteps++
                detailNoActionFrames = 0
                lastAutomaticAction = label
                state = FsmState.ATTEND_CHOUJIANG
                log.write("[任务节点] 下发第${automaticTaskSteps}个原生动作“$label”；等待节点状态刷新")
                handler.postDelayed({
                    if (isActive(epoch)) automaticDetailActionScan()
                }, 1200L)
                return@performFudaiTaskActionByNode
            }

            val cachedLabel = cachedDetailAction.replace(" ", "")
            val safeCachedAction = cachedLabel == "一键发表评论" || cachedLabel == "发表评论" ||
                cachedLabel == "开始观看直播任务" ||
                cachedLabel == "立即观看" || cachedLabel == "去观看" ||
                cachedLabel == "参与抽奖" || cachedLabel == "一键参与" || cachedLabel == "免费参与"
            if (safeCachedAction && cachedDetailActionX > 0 && cachedDetailActionY > 0) {
                val x = cachedDetailActionX
                val y = cachedDetailActionY
                cachedDetailAction = ""
                cachedDetailActionX = -1
                cachedDetailActionY = -1
                cachedShoppingFanAction = false
                performedTaskActionInCurrentDetail = true
                automaticTaskSteps++
                detailNoActionFrames = 0
                lastAutomaticAction = cachedLabel
                state = FsmState.ATTEND_CHOUJIANG
                log.write("[任务兜底] 无障碍树未暴露内部按钮，点击严格OCR按钮“$cachedLabel”坐标$x,$y")
                host.tap(x, y) {
                    if (!isActive(epoch)) return@tap
                    handler.postDelayed({
                        if (isActive(epoch)) automaticDetailActionScan()
                    }, 1200L)
                }
                return@performFudaiTaskActionByNode
            }

            detailNoActionFrames++
            if (performedTaskActionInCurrentDetail && stayInRoomEvidenceSeen &&
                !lastDetailHasPendingTask && detailNoActionFrames >= 3) {
                currentBagParticipationClicked = true
                state = FsmState.SCAN_FUDAI_ICON
                log.write("[任务节点] 已执行参与动作且连续3帧无新动作按钮，确认进入任务计时/开奖等待，禁止切房")
                waitForDrawResult()
                return@performFudaiTaskActionByNode
            }
            if (detailNoActionFrames >= 3 && lastDetailHasPendingTask) {
                skipCurrentDetail("[白名单过滤] 详情仍有未完成任务，但连续3帧没有识别到受支持的白名单按钮；关闭弹窗并切房")
                return@performFudaiTaskActionByNode
            }
            if (detailNoActionFrames >= 5) {
                if (performedTaskActionInCurrentDetail) {
                    currentBagParticipationClicked = true
                    state = FsmState.SCAN_FUDAI_ICON
                    log.write("[任务完成保护] 已执行白名单动作且详情不再显示未完成任务，留在当前直播间等待开奖")
                    waitForDrawResult()
                    return@performFudaiTaskActionByNode
                }
                skipCurrentDetail("[白名单过滤] 连续5帧未找到受支持的任务按钮；关闭弹窗并切房")
                return@performFudaiTaskActionByNode
            }
            handler.postDelayed({
                if (isActive(epoch)) automaticDetailActionScan()
            }, 900L)
        }
    }

    private fun executeStrictCachedTaskAction(): Boolean {
        val rawLabel = cachedDetailAction.replace(" ", "")
        val label = if (cachedShoppingFanAction &&
            (rawLabel == "去完成" || rawLabel == "加入" ||
                rawLabel.contains("加入购物粉丝团"))) {
            "加入购物粉丝团"
        } else fuzzyTaskAction(rawLabel) ?: return false
        if (cachedDetailActionX <= 0 || cachedDetailActionY <= 0) return false

        val x = cachedDetailActionX
        val y = cachedDetailActionY
        cachedDetailAction = ""
        cachedDetailActionX = -1
        cachedDetailActionY = -1
        cachedShoppingFanAction = false
        performedTaskActionInCurrentDetail = true
        automaticTaskSteps++
        detailNoActionFrames = 0
        lastAutomaticAction = label
        state = FsmState.ATTEND_CHOUJIANG
        log.write("[任务按钮] 点击严格OCR缓存按钮“$label”坐标$x,$y")
        val epoch = engineEpoch
        host.tap(x, y) {
            if (!isActive(epoch)) return@tap
            handler.postDelayed({
                if (isActive(epoch)) automaticDetailActionScan()
            }, 1200L)
        }
        return true
    }

    private fun fuzzyTaskAction(raw: String): String? {
        val label = raw.replace("一健", "一键").replace(Regex("[，。！？?、]"), "")
        if (label.isBlank() || label.length > 14 ||
            label.contains("发送评论:") || label.contains("发送评论：") ||
            label.contains("已发表评论") || label.contains("评论已完成") ||
            label.contains("还需看播") || label.contains("未达成") || label.contains("已达成")) return null

        val actions = listOf(
            "一键发表评论", "发表评论", "开始观看直播任务",
            "立即观看", "去观看", "参与抽奖", "一键参与", "免费参与"
        )
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (action in actions) {
            if (kotlin.math.abs(label.length - action.length) > 3) continue
            val distance = editDistance(label, action)
            val allowed = when {
                action.length <= 4 -> 1
                action.length <= 7 -> 2
                else -> 3
            }
            if (distance <= allowed && distance < bestDistance) {
                best = action
                bestDistance = distance
            }
        }
        if (best != null && bestDistance > 0) {
            log.write("[按钮容错] OCR“$raw”按${bestDistance}字差异匹配为“$best”")
        }
        return best
    }

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val replaceCost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + replaceCost
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun triggerSecondPassScan(offsetX: Int, offsetY: Int) {
        if (!automationMode || !running || switchBusy || state == FsmState.SWITCH_LIVE) return
        pulseWorkflow()
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isActive(epoch) || switchBusy || state == FsmState.SWITCH_LIVE) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                log.write("[任务循环] 页面正在刷新，稍后继续确认任务")
                expectWorkflowDelay(1_200L)
                handler.postDelayed({
                    if (isActive(epoch)) automaticDetailActionScan()
                }, 1_200L)
                return@requestScreenshot
            }
            resetTaskFaults()
            // The dimensions returned by AccessibilityService.takeScreenshot
            // can change between the first and second task frames (status bar,
            // transient system UI, rotation settling). offsetX/offsetY belong
            // to the previous frame, so always clamp the crop to this bitmap.
            val cropX = offsetX.coerceIn(0, maxOf(0, bitmap.width - 1))
            val cropY = offsetY.coerceIn(0, maxOf(0, bitmap.height - 1))
            val cropWidth = minOf(maxOf(1, (bitmap.width * 0.96).toInt()), bitmap.width - cropX)
            val cropHeight = minOf(maxOf(1, (bitmap.height * 0.78).toInt()), bitmap.height - cropY)
            if (cropX != offsetX || cropY != offsetY ||
                cropWidth != maxOf(1, (bitmap.width * 0.96).toInt()) ||
                cropHeight != maxOf(1, (bitmap.height * 0.78).toInt())) {
                log.write("[截图边界修正] 多任务第二遍截图尺寸发生变化，已将裁剪区域夹紧为${cropX},${cropY},${cropWidth}x${cropHeight}")
            }
            val detail = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
            bitmap.recycle()
            ocr.recognize(detail) { result ->
                detail.recycle()
                if (!isActive(epoch) || switchBusy || state == FsmState.SWITCH_LIVE) return@recognize
                handleAutomaticDetailAction(result, cropX, cropY, isSecondPass = true)
            }
        }
    }

    private fun isNonActionButtonText(text: String): Boolean {
        val compact = text.replace(" ", "")
        val commentTaskDescription = compact.contains("发送评论:") || compact.contains("发送评论：") ||
            compact.contains("发表评论:") || compact.contains("发表评论：")
        val watchTaskDescription = (compact.contains("观看直播") || compact.contains("看播")) &&
            (compact.contains("分钟") || compact.contains("还需")) &&
            !compact.contains("开始观看直播任务") && !compact.contains("开始任务") &&
            !compact.contains("去完成") && !compact.contains("立即观看")
        if (commentTaskDescription || watchTaskDescription || compact.contains("还需看播") ||
            compact.contains("已发表评论") || compact.contains("评论已完成") ||
            compact.contains("已达成") || compact.contains("未达成")) return true
        return text.contains("人已参与") || text.contains("已参与") || text.contains("后开奖") ||
            text.contains("倒计时") || text.contains("剩余") || text.contains("福袋任务") ||
            text.contains("完成任务") || text.contains("抽取奖品") || text.contains("购物粉丝团") ||
            text.contains("粉丝团后") || text.contains("等级不够") || text.contains("等级不足") ||
            text.contains("暂不可参与") || text.contains("商品信息") || text.contains("参考价值") ||
            text.contains("已达成") || text.contains("未达成") || text.contains("免运费")
    }

    private fun isSafetyNoticeText(text: String): Boolean =
        text.contains("离开直播间") || text.contains("失去抽奖资格") || text.contains("抽奖资格")

    private fun rememberDetailAction(result: OfflineOcr.Result, offsetX: Int, offsetY: Int) {
        val all = result.text.replace(Regex("\\s+"), "")
        val shoppingFanTask = isShoppingFanClubTask(all)
        val candidate = result.items.stream()
            .filter { item ->
                val text = item.text.replace(" ", "")
                if (isNonActionButtonText(text) || isSafetyNoticeText(text)) return@filter false
                text.contains("一键发表评论") || text.contains("参与抽奖") ||
                    text.contains("开始观看") || text.contains("去观看") ||
                text.contains("立即观看") || text.contains("观看直播") ||
                text.contains("看播任务") || text.contains("观看任务") ||
                    text.contains("开始观看直播任务") ||
                    (shoppingFanTask && (text == "去完成" || text == "去加入" ||
                        text == "立即加入" || text == "加入" || text == "加入粉丝团" ||
                        text.contains("加入购物粉丝团")))
            }
            .filter { item -> item.text.replace(" ", "") != lastAutomaticAction }
            .max(Comparator.comparingInt { item -> item.bounds.centerY() })
            .orElse(null)
        if (candidate == null) return
        cachedDetailAction = candidate.text.replace(" ", "")
        cachedShoppingFanAction = shoppingFanTask &&
            (cachedDetailAction == "去完成" || cachedDetailAction == "去加入" ||
                cachedDetailAction == "立即加入" || cachedDetailAction == "加入" ||
                cachedDetailAction == "加入粉丝团" ||
                cachedDetailAction.contains("加入购物粉丝团"))
        cachedDetailActionX = offsetX + candidate.bounds.centerX()
        cachedDetailActionY = offsetY + candidate.bounds.centerY()
        log.write("[多帧按钮] 已缓存“${cachedDetailAction}”坐标，等待倒计时/任务状态确认")
    }

    private fun actionCompletesAllTasks(result: OfflineOcr.Result): Boolean {
        for (item in result.items) {
            val matcher = TASK_PROGRESS.matcher(item.text.replace(" ", ""))
            if (!matcher.matches()) continue
            val done = matcher.intGroup(1)
            val total = matcher.intGroup(2)
            if (total > 0 && total <= 9 && done == total - 1) return true
        }
        return false
    }

    private fun registerTaskFault(key: String, message: String) {
        if (selfRecovering || !running || !automationMode) return
        // Only identical, consecutive faults constitute a recovery loop.
        // Previously unrelated failures accumulated, and the live-entry path
        // even preloaded this counter to 2, so one ordinary miss immediately
        // restarted Douyin.
        if (lastTaskFault == key) {
            taskFaultCount++
        } else {
            lastTaskFault = key
            taskFaultCount = 1
        }
        log.write("$message（任务故障${taskFaultCount}/3）")
        if (taskFaultCount < 3) return
        beginTerminalRecovery("同一任务已连续3次循环/报错：${lastTaskFault}")
    }

    private fun beginTerminalRecovery(reason: String) {
        if (selfRecovering || !running || !automationMode) return
        selfRecovering = true
        val recoveryEpoch = engineEpoch
        running = false
        automationMode = false
        host.onEngineStateChanged()
        handler.removeCallbacksAndMessages(null)
        log.write("[终极自救] $reason；正在强制重启抖音")
        host.restartDouyin {
            if (!selfRecovering || recoveryEpoch != engineEpoch) {
                log.write("[终极自救] 已被用户主动停止，取消恢复和自动重启")
                return@restartDouyin
            }
            taskFaultCount = 0
            lastTaskFault = ""
            waitForDouyinRecovery(1, 1, recoveryEpoch)
        }
    }

    private fun waitForDouyinRecovery(attempt: Int, recoveryRound: Int, recoveryEpoch: Int) {
        if (!selfRecovering || recoveryEpoch != engineEpoch) {
            log.write("[终极自救] 恢复流程已取消，不再拉起或启动任务")
            return
        }
        if (host.isDouyinForeground()) {
            selfRecovering = false
            log.write("[终极自救] 抖音已稳定在前台，重新开始自动任务")
            startAutomationMode()
            return
        }
        if (attempt >= 10) {
            val retryDelayMs = minOf(20_000L, 3_000L + recoveryRound * 2_000L)
            log.write("[终极自救] 第${recoveryRound}轮20秒内未确认抖音/抖音极速版前台，${retryDelayMs / 1000}秒后重新拉起")
            handler.postDelayed({
                if (!selfRecovering || recoveryEpoch != engineEpoch) return@postDelayed
                log.write("[终极自救] 开始第${recoveryRound + 1}轮重新拉起")
                host.restartDouyin {
                    waitForDouyinRecovery(1, recoveryRound + 1, recoveryEpoch)
                }
            }, retryDelayMs)
            return
        }
        log.write("[终极自救] 等待抖音/抖音极速版进入前台（第${recoveryRound}轮，${attempt}/10）")
        handler.postDelayed({ waitForDouyinRecovery(attempt + 1, recoveryRound, recoveryEpoch) }, 2000)
    }

    private fun resetTaskFaults() {
        taskFaultCount = 0
        lastTaskFault = ""
    }

    private fun confirmDetailCountdown(result: OfflineOcr.Result): Boolean {
        if (detailCountdownConfirmed) return true
        if (detailOverMaxLimit) return false
        var seconds = -1
        var overLimitSeconds = -1
        val maxSeconds = host.getMaxFudaiMinutes() * 60
        // Phase 1: High-confidence search for countdown items with "开奖/倒计时/剩余/后"
        for (item in result.items) {
            val text = item.text.replace(" ", "")
            val isCountdownText = text.contains("后开奖") || text.contains("开奖") ||
                text.contains("倒计时") || text.contains("剩余") || text.contains("距开奖")
            if (!isCountdownText) continue
            val colonMatcher = COUNTDOWN.matcher(text)
            val compactMatcher = COMPACT_COUNTDOWN.matcher(text)
            var sec = -1
            if (colonMatcher.matches()) {
                sec = colonMatcher.intGroup(1) * 60 + colonMatcher.intGroup(2)
            } else if (compactMatcher.matches()) {
                sec = compactMatcher.intGroup(1) * 60 + compactMatcher.intGroup(2)
            }
            if (sec > 0) {
                if (maxSeconds > 0 && sec > maxSeconds) {
                    overLimitSeconds = sec
                } else {
                    seconds = sec
                    break
                }
            }
        }
        // Never infer a draw countdown from an unlabeled mm:ss token. Live
        // duration, product video time and comments can contain the same shape.

        if (overLimitSeconds > 0) {
            detailOverMaxLimit = true
            confirmedDrawSeconds = overLimitSeconds
            log.write("[开奖等待] 明确识别到开奖倒计时为${overLimitSeconds / 60}分${overLimitSeconds % 60}秒，超出系统限制${host.getMaxFudaiMinutes()}分钟；拒绝参与")
            return false
        }

        if (seconds <= 0) return false
        val now = System.currentTimeMillis()
        if (detailCountdownSample < 0) {
            if (seconds >= 10) {
                detailCountdownConfirmed = true
                confirmedDrawSeconds = seconds
                updateDrawDeadline(now + seconds * 1000L + 2000L)
                resetTaskFaults()
                log.write("[开奖等待] 首次详情倒计时${seconds}秒（约${seconds / 60}分钟）处于可靠区间，直接采用")
                return true
            }
            detailCountdownSample = seconds
            detailCountdownSampleAt = now
            log.write("[开奖等待] 详情倒计时第1次=${seconds}秒，等待第2次确认")
            return false
        }
        val elapsed = maxOf(1, ((now - detailCountdownSampleAt + 500L) / 1000L).toInt())
        if (detailCountdownSample <= 5 && seconds >= 10) {
            detailCountdownConfirmed = true
            confirmedDrawSeconds = seconds
            observeDrawDeadline(now + seconds * 1000L + 2000L)
            resetTaskFaults()
            log.write("[开奖等待] 首帧为加载占位值${detailCountdownSample}秒，第二帧${seconds}秒处于可靠区间，直接采用")
            return true
        }
        val observedDrop = detailCountdownSample - seconds
        val reasonable = observedDrop >= -2 && Math.abs(observedDrop - elapsed) <= 6
        if (!reasonable) {
            log.write("[开奖等待] 两次倒计时差值异常：${detailCountdownSample}→${seconds}，间隔${elapsed}秒；以本次为新基准")
            detailCountdownSample = seconds
            detailCountdownSampleAt = now
            return false
        }
        detailCountdownConfirmed = true
        confirmedDrawSeconds = seconds
        observeDrawDeadline(now + seconds * 1000L + 2000L)
        resetTaskFaults()
        log.write("[开奖等待] 双帧校验通过：${detailCountdownSample}→${seconds}，采用第2次倒计时")
        return true
    }

    /** The detail panel is already over; do not enter task or draw-result waits. */
    private fun hasEndedDetailCountdown(result: OfflineOcr.Result): Boolean {
        return result.items.any { item ->
            val text = item.text.replace(Regex("\\s+"), "")
            val exactZero = text.matches(Regex("^[（(]?0{1,2}[:：]00[)）]?$"))
            val labeledZero = (text.contains("开奖") || text.contains("倒计时") ||
                text.contains("剩余") || text.contains("距开奖")) &&
                COUNTDOWN.matcher(text).matches() && countdownSeconds(text) == 0
            exactZero || labeledZero
        }
    }

    private fun leaveEndedDetail() {
        if (!running || !automationMode || switchBusy) return
        log.write("[福袋结束] 详情倒计时已为00:00，立即关闭并切换下一直播间")
        currentBagParticipationClicked = false
        performedTaskActionInCurrentDetail = false
        updateDrawDeadline(0L)
        detailCountdownConfirmed = false
        confirmedDrawSeconds = 0
        switchBusy = true
        detailCloseChecks = 0
        state = FsmState.SWITCH_LIVE
        dismissDetailPopupSafely {
            if (!running || !automationMode) return@dismissDetailPopupSafely
            switchToNextLive()
        }
    }

    private fun updateDrawDeadline(deadlineMs: Long) {
        currentDrawDeadlineMs = deadlineMs
        host.setPendingDrawDeadline(deadlineMs)
        // A countdown alone only means that a bag is being processed. The
        // completion flag is written separately by waitForDrawResult after
        // every task has been confirmed complete.
        host.setPendingDrawParticipationConfirmed(false)
    }

    private fun observeDrawDeadline(deadlineMs: Long) {
        // A verified countdown is only an observation. Do not persist it as
        // an active draw until participation has actually been confirmed.
        // Otherwise a skipped/over-limit bag is incorrectly restored after
        // an accessibility-service restart.
        currentDrawDeadlineMs = deadlineMs
    }

    private fun waitForDrawResult() {
        if (!running || !automationMode) return
        pulseWorkflow()
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        if (elapsedNow - lastLightMaintenanceAt >= 60_000L) {
            lastLightMaintenanceAt = elapsedNow
            service.triggerPeriodicMemoryClean()
        }
        val now = System.currentTimeMillis()
        if (currentDrawDeadlineMs == 0L) {
            updateDrawDeadline(now + 5000L)
            log.write("[开奖等待] 未取得倒计时，5秒后直接验证开奖结果")
        }
        if (currentBagParticipationClicked) {
            host.setPendingDrawDeadline(currentDrawDeadlineMs)
            host.setPendingDrawParticipationConfirmed(true)
        }
        val remaining = currentDrawDeadlineMs - now
        if (remaining > 0) {
            verifyDrawWaitPage(remaining)
            return
        }
        log.write("[开奖等待] 倒计时归零，等待3秒让开奖结果弹窗完整展示...")
        handler.postDelayed(::verifyDrawResult, 3000L)
    }

    private fun verifyDrawWaitPage(remaining: Long) {
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isActive(epoch) || bitmap == null) {
                bitmap?.recycle()
                handler.postDelayed(::waitForDrawResult, minOf(30000L, maxOf(1000L, remaining)))
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            val bottom = Bitmap.createBitmap(bitmap, 0, (height * 0.80).toInt(), width, maxOf(1, (height * 0.20).toInt()))
            bitmap.recycle()
            ocr.recognize(bottom) { result ->
                val text = result.text.replace(Regex("\\s+"), "")
                bottom.recycle()
                if (!isActive(epoch)) return@recognize
                val outsideLive = text.contains("首页") &&
                    (text.contains("朋友") || text.contains("消息") || text.contains("我"))
                if (outsideLive) {
                    outsideLiveWaitFrames++
                    if (outsideLiveWaitFrames < 2) {
                        log.write("[开奖纠错] 首次识别到首页底栏，1秒后复核，避免单帧OCR误判")
                        handler.postDelayed(::waitForDrawResult, 1000L)
                        return@recognize
                    }
                    log.write("[开奖纠错] 连续2帧识别到抖音首页底栏，确认已离开直播间；取消无效倒计时并重新导航")
                    currentBagParticipationClicked = false
                    stayInRoomEvidenceSeen = false
                    outsideLiveWaitFrames = 0
                    updateDrawDeadline(0L)
                    drawResultAttempts = 0
                    navigationBusy = true
                    switchedToLiveList = !host.shouldStartFromFollowing()
                    state = FsmState.PAGE_CHECK_NAV
                    inspectHomeAndNavigate()
                    return@recognize
                }
                outsideLiveWaitFrames = 0
                val latestRemaining = currentDrawDeadlineMs - System.currentTimeMillis()
                val next = minOf(30000L, maxOf(1000L, latestRemaining))
                handler.postDelayed(::waitForDrawResult, next)
            }
        }
    }

    private fun verifyDrawResult() {
        if (!running || !automationMode) return
        pulseWorkflow()
        host.requestScreenshot { bitmap ->
            if (bitmap == null) {
                retryDrawResult("[开奖结果] 截图失败")
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            // The official draw-result card is centered. Exclude the top live
            // title, side controls, comments and bottom navigation so ordinary
            // room copy cannot become false winning evidence.
            val left = (width * 0.12).toInt()
            val top = (height * 0.20).toInt()
            val center = Bitmap.createBitmap(bitmap, left, top,
                maxOf(1, (width * 0.76).toInt()),
                maxOf(1, (height * 0.58).toInt()))
            ocr.recognize(center) { result ->
                val all = result.text.replace(Regex("\\s+"), "")
                val negativeEvidence = all.contains("未中奖") || all.contains("好运在路上") ||
                    all.contains("遗憾") || all.contains("未抽中") || all.contains("没中") ||
                    all.contains("擦肩而过") || all.contains("未获得") || all.contains("下次再来") ||
                    all.contains("谢谢参与") || all.contains("未中") || all.contains("没抽中") || all.contains("差一点")
                val explicitWinTitle = all.contains("恭喜中奖") || all.contains("中奖啦") ||
                    all.contains("恭喜你中奖") || all.contains("恭喜抽中")
                val winTitleAndReward = (all.contains("恭喜") || all.contains("喜提")) &&
                    (all.contains("奖品") || all.contains("奖励") || all.contains("抽中") ||
                        all.contains("获得") || all.contains("收下"))
                val resultAction = all.contains("领取奖励") || all.contains("收下奖励") ||
                    all.contains("开心收下") || all.contains("查看奖品") || all.contains("我的奖品")
                val won = !negativeEvidence && (explicitWinTitle || winTitleAndReward || resultAction)
                val lost = negativeEvidence
                drawResultOverlayVisible = lost
                center.recycle()
                bitmap.recycle()
                if (won) {
                    log.write("[开奖结果] 恭喜！识别到【中奖】记录！标题与内容：${trim(result.text, 160)}")
                    // The notification is deliberately queued before any claim UI
                    // action. Claim failures, navigation and engine stop must never
                    // suppress the independent email task.
                    host.notifyLuckyBagWin(result.text)
                    log.write("[中奖通知] 已将中奖邮件任务提交后台；领奖流程不会阻塞邮件发送")
                    currentBagParticipationClicked = false
                    updateDrawDeadline(0)
                    drawResultAttempts = 0
                    drawResultOverlayVisible = false
                    detailCountdownConfirmed = false
                    state = FsmState.SWITCH_LIVE
                    switchBusy = true
                    if (host.shouldAutoClaimPrize()) {
                        prizeClaimAttempts = 0
                        prizeReturnAttempts = 0
                        log.write("[自动领奖] 已开启：勾选协议后进入订单；仅在底部应付总额明确为¥0.00时领取")
                        handler.postDelayed(::continuePrizeClaim, 350L)
                    } else {
                        log.write("[开奖结果] 自动领奖未开启；中奖页面停留5秒后关闭并继续抽奖")
                        handler.postDelayed({
                            host.back {
                                handler.postDelayed(afterPrizeClose@{
                                    if (!running || !automationMode) return@afterPrizeClose
                                    switchToNextLive()
                                }, 1000L)
                            }
                        }, 5000L)
                    }
                    return@recognize
                }
                if (lost) {
                    log.write("[开奖结果] 明确识别到“未中奖/遗憾”；按Back键关闭浮层并切换下一房")
                    finishDrawAsLoss()
                    return@recognize
                }
                retryDrawResult("[开奖结果] 结果动画/文字复核中")
            }
        }
    }

    private fun finishDrawAsLoss() {
        currentBagParticipationClicked = false
        updateDrawDeadline(0)
        drawResultAttempts = 0
        drawResultOverlayVisible = false
        detailCountdownConfirmed = false
        state = FsmState.SWITCH_LIVE
        switchBusy = true
        log.write("[开奖结果] 判定为未中奖；智能检测浮窗卡片，仅在存在弹窗时收起...")
        dismissDetailPopupSafely {
            if (!running || !automationMode) return@dismissDetailPopupSafely
            switchToNextLive()
        }
    }

    private fun retryDrawResult(message: String) {
        drawResultAttempts++
        log.write("$message（${drawResultAttempts}/5）")
        if (drawResultAttempts >= 5) {
            log.write("[开奖结果] 连续5次未精确定位结果；按未中奖处理，智能检测浮窗卡片...")
            currentBagParticipationClicked = false
            updateDrawDeadline(0)
            drawResultAttempts = 0
            drawResultOverlayVisible = false
            detailCountdownConfirmed = false
            state = FsmState.SWITCH_LIVE
            switchBusy = true
            dismissDetailPopupSafely {
                if (!running || !automationMode) return@dismissDetailPopupSafely
                switchToNextLive()
            }
            return
        }
        handler.postDelayed(::verifyDrawResult, 1000L)
    }

    private fun findWatchRemainingSeconds(result: OfflineOcr.Result): Int {
        for (item in result.items) {
            val text = item.text.replace(" ", "")
            if (!text.contains("还需看播") && !text.contains("还需观看")) continue
            val matcher = COUNTDOWN.matcher(text)
            if (matcher.matches()) {
                return matcher.intGroup(1) * 60 + matcher.intGroup(2)
            }
            val minuteSecond = Regex(".*?还需(?:看播|观看).*?(\\d+)分(?:(\\d+)秒)?.*").matchEntire(text)
            if (minuteSecond != null) {
                return minuteSecond.groupValues[1].toInt() * 60 +
                    minuteSecond.groupValues[2].toIntOrNull().orZero()
            }
            val seconds = Regex(".*?还需(?:看播|观看).*?(\\d+)秒.*").matchEntire(text)
            if (seconds != null) return seconds.groupValues[1].toInt()
        }
        return 0
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun dismissDetailPopupSafely(onFinished: () -> Unit) {
        if (automationMode && !running) {
            onFinished()
            return
        }
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (automationMode && !isActive(epoch)) {
                bitmap?.recycle()
                onFinished()
                return@requestScreenshot
            }
            if (bitmap == null) {
                onFinished()
                return@requestScreenshot
            }
            ocr.recognize(bitmap) { result ->
                bitmap.recycle()
                val text = result.text.replace(Regex("\\s+"), "")
                val hasPopup = text.contains("超级福袋") || text.contains("福袋任务") ||
                    text.contains("福袋奖品") || text.contains("完成任务") || text.contains("抽取奖品") ||
                    text.contains("等待开奖") || text.contains("请勿离开") || text.contains("已达成") ||
                    text.contains("未达成") || text.contains("抖音支付") || text.contains("优惠券")
                if (hasPopup) {
                    log.write("[安全关闭] 确认画面仍存残余弹窗，按 Back 键收起卡片...")
                    host.back { handler.postDelayed(onFinished, 800) }
                } else {
                    log.write("[安全防护] 确认画面已无卡片弹窗（已自动收起），跳过 Back 键，防止误退出直播间！")
                    onFinished()
                }
            }
        }
    }

    private fun skipCurrentDetail(reason: String) {
        if (currentBagParticipationClicked) {
            log.write("[参与保护熔断] 尝试触发切房，但检测到本直播间任务已参与成功，强烈拦停！绝对留存等待开奖：$reason")
            state = FsmState.SCAN_FUDAI_ICON
            waitForDrawResult()
            return
        }
        log.write(reason)
        if (!running || !automationMode || switchBusy) {
            if (switchBusy) log.write("[切换保护] 已在关闭详情并切房，忽略重复请求")
            return
        }
        currentBagParticipationClicked = false
        currentDrawDeadlineMs = 0L
        host.setPendingDrawDeadline(0L)
        detailCountdownConfirmed = false
        confirmedDrawSeconds = -1
        switchBusy = true
        detailCloseChecks = 0
        state = FsmState.SWITCH_LIVE

        log.write("[切房流程] 智能检测卡片状态，仅当有残余卡片时收起...")
        dismissDetailPopupSafely {
            if (!running || !automationMode) return@dismissDetailPopupSafely
            switchToNextLive()
        }
    }

    private fun switchWithoutClosing(reason: String) {
        log.write(reason)
        if (!running || !automationMode || switchBusy) return
        switchBusy = true
        state = FsmState.SWITCH_LIVE
        switchToNextLive()
    }

    private fun switchToNextLive() {
        if (currentBagParticipationClicked) {
            log.write("[参与保护终极熔断] 误调 swipeToNextLive！但检测到本直播间任务已参与成功，强制取消上滑手势！安稳留存等待开奖")
            switchBusy = false
            state = FsmState.SCAN_FUDAI_ICON
            waitForDrawResult()
            return
        }
        if (!running || !automationMode) return
        failedFudaiSlots.fill(0)
        lastAttemptedFudaiSlot = -1
        if (!switchBusy) switchBusy = true
        val epoch = engineEpoch
        readRoomIdentity { before ->
            host.swipeToNextLive {
                handler.postDelayed({
                    readRoomIdentity afterIdentity@{ after ->
                        if (!isActive(epoch) || !switchBusy) return@afterIdentity
                        val confirmedSameRoom = before.isNotEmpty() && after.isNotEmpty() && isSameRoomIdentity(before, after)
                        if (confirmedSameRoom) {
                            consecutiveRoomSwitchFailures++
                            log.write("[切换直播间] 确认上滑后仍停留在同一房间（${consecutiveRoomSwitchFailures}/3），重试物理切房")
                            if (consecutiveRoomSwitchFailures >= 3) {
                                beginTerminalRecovery("连续3轮上滑均未切换直播间，判定抖音页面或触控链路假死")
                                return@afterIdentity
                            }
                            host.swipeToNextLive {
                                handler.postDelayed(retrySameRoom@{
                                    if (!isActive(epoch) || !switchBusy) return@retrySameRoom
                                    scanMisses = 0
                                    switchBusy = false
                                    state = FsmState.SCAN_FUDAI_ICON
                                    automaticScanOnce()
                                }, 2000)
                            }
                            return@afterIdentity
                        }
                        consecutiveRoomSwitchFailures = 0
                        log.write("[切换直播间] 已进入下一房，继续等待稳定；房间特征=${trim(before, 24)} -> ${trim(after, 24)}")
                        currentRoomCountedUnfollowed = false
                        resetTaskFaults()
                        handler.postDelayed(nextRoomSettled@{
                            if (!isActive(epoch) || !switchBusy) return@nextRoomSettled
                            scanMisses = 0
                            switchBusy = false
                            state = FsmState.SCAN_FUDAI_ICON
                            automaticScanOnce()
                        }, 7500)
                    }
                }, 2500)
            }
        }
    }

    private fun refreshLiveListAfterFailedSwipe() {
        host.back {
            handler.postDelayed({
                if (!running || !automationMode) return@postDelayed
                val epoch = engineEpoch
                host.requestScreenshot { bitmap ->
                    if (!isActive(epoch) || bitmap == null) {
                        bitmap?.recycle()
                        switchBusy = false
                        navigationBusy = true
                        state = FsmState.PAGE_CHECK_NAV
                        inspectHomeAndNavigate()
                        return@requestScreenshot
                    }
                    ocr.recognize(bitmap) { result ->
                        if (!isActive(epoch)) {
                            bitmap.recycle()
                            return@recognize
                        }
                        val liveTab = findTopTab(result, "直播", bitmap.width, bitmap.height)
                        val centerX = bitmap.width / 2
                        val centerY = Math.round(bitmap.height * 0.88f)
                        bitmap.recycle()
                        if (liveTab == null) {
                            switchBusy = false
                            navigationBusy = true
                            state = FsmState.PAGE_CHECK_NAV
                            inspectHomeAndNavigate()
                            return@recognize
                        }
                        log.write("[直播列表] 跑完或滑动失败，再点一次“直播”刷新列表")
                        host.tap(liveTab.bounds.centerX(), liveTab.bounds.centerY()) {
                            handler.postDelayed(liveListRefresh@{
                                if (!isActive(epoch)) return@liveListRefresh
                                switchBusy = false
                                navigationBusy = true
                                switchedToLiveList = true
                                liveEntryVerifyAttempts = 0
                                state = FsmState.PAGE_CHECK_NAV
                                log.write("[直播列表] 刷新完成，点击屏幕中央进入直播间：${centerX},${centerY}")
                                host.tap(centerX, centerY) {
                                    handler.postDelayed(::verifyEnteredLive, 5000)
                                }
                            }, 1800)
                        }
                    }
                }
            }, 1000)
        }
    }

    private fun returnToFollowPageAfterUnfollowedLimit() {
        log.write("[关注优先] 已累计${host.getUnfollowedRoomLimit()}个未关注直播间，返回关注页并从第一个关注直播间重新开始")
        host.back {
            handler.postDelayed({
                if (!running || !automationMode) return@postDelayed
                unfollowedRoomsSinceFollowEntry = 0
                currentRoomCountedUnfollowed = false
                switchBusy = false
                navigationBusy = true
                switchedToLiveList = !host.shouldStartFromFollowing()
                followPageRefreshes = 0
                livePageRefreshes = 0
                state = FsmState.PAGE_CHECK_NAV
                inspectHomeAndNavigate()
            }, 1000)
        }
    }

    private fun readRoomIdentity(callback: Consumer<String>) {
        host.requestScreenshot { bitmap ->
            if (bitmap == null) {
                callback.accept("")
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            val header = Bitmap.createBitmap(bitmap, 0, 0,
                maxOf(1, (width * 0.40).toInt()),
                maxOf(1, (height * 0.09).toInt()))
            bitmap.recycle()
            ocr.recognize(header) { result ->
                var identity = result.text.replace(Regex("[\\s\\d:：·.]+"), "")
                if (identity.length > 48) identity = identity.substring(0, 48)
                header.recycle()
                callback.accept(identity)
            }
        }
    }

    private fun isSameRoomIdentity(before: String, after: String): Boolean {
        if (before == after) return true
        val maxLength = maxOf(before.length, after.length)
        if (maxLength < 4) return false
        val distance = levenshteinDistance(before, after)
        val similarity = 1f - distance.toFloat() / maxLength
        // OCR changes by one or two characters as likes/rank animations move.
        // A swipe is only accepted when the stable header changes materially.
        if (similarity >= 0.72f) {
            log.write("[切换保护] 房间顶部仅有OCR轻微变化，相似度=${String.format(Locale.CHINA, "%.0f%%", similarity * 100f)}，仍判定为同一直播间")
            return true
        }
        return false
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val replace = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(minOf(previous[j] + 1, current[j - 1] + 1), replace)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun automaticDetailActionScan() {
        if (!automationMode || !running) return
        pulseWorkflow()
        val epoch = engineEpoch
        host.requestScreenshot { bitmap ->
            if (!isActive(epoch) || switchBusy || state == FsmState.SWITCH_LIVE) {
                bitmap?.recycle()
                return@requestScreenshot
            }
            if (bitmap == null) {
                expectWorkflowDelay(1_000L)
                handler.postDelayed({
                    if (isActive(epoch)) automaticDetailActionScan()
                }, 1_000L)
                return@requestScreenshot
            }
            resetTaskFaults()
            val width = bitmap.width
            val height = bitmap.height
            val left = (width * 0.03).toInt()
            val top = (height * 0.25).toInt()
            val detail = Bitmap.createBitmap(bitmap, left, top,
                maxOf(1, (width * 0.94).toInt()),
                maxOf(1, (height * 0.73).toInt()))
            bitmap.recycle()
            ocr.recognize(detail) { result ->
                detail.recycle()
                if (!isActive(epoch) || switchBusy || state == FsmState.SWITCH_LIVE) return@recognize
                handleAutomaticDetailAction(result, left, top)
            }
        }
    }

    private fun inspectFudaiDetail() {
        host.requestScreenshot { bitmap ->
            if (bitmap == null) {
                log.write("[详情识别] 截图失败")
                return@requestScreenshot
            }
            val width = bitmap.width
            val height = bitmap.height
            val detail = Bitmap.createBitmap(bitmap, (width * 0.03).toInt(), (height * 0.25).toInt(),
                maxOf(1, (width * 0.94).toInt()),
                maxOf(1, (height * 0.73).toInt()))
            bitmap.recycle()
            ocr.recognize(detail) { result ->
                val text = result.text.replace('\n', ' ').trim()
                val opened = text.contains("超级福袋") || text.contains("福袋任务") ||
                    text.contains("福袋奖品") || text.contains("完成任务抽取奖品")
                if (!opened) {
                    state = FsmState.SCAN_FUDAI_ICON
                    log.write("[详情识别] 当前不是福袋详情；不执行任何点击；OCR=${trim(text, 180)}")
                } else {
                    logDetailInfo(result)
                }
                detail.recycle()
            }
        }
    }

    private fun logDetailInfo(result: OfflineOcr.Result) {
        var countdown = "未识别"
        var progress = "未识别"
        var action = "未识别"
        var task = "未识别"
        for (item in result.items) {
            val text = item.text.replace(" ", "")
            val countdownMatcher = COUNTDOWN.matcher(text)
            if (countdownMatcher.matches() && countdownMatcher.intGroup(2) < 60) {
                countdown = "${countdownMatcher.group(1)}:${countdownMatcher.group(2)}"
            }
            val progressMatcher = TASK_PROGRESS.matcher(text)
            if (progressMatcher.matches()) {
                val done = progressMatcher.intGroup(1)
                val total = progressMatcher.intGroup(2)
                if (total > 0 && total <= 9 && done <= total) {
                    progress = "$done/$total"
                }
            }
            when {
                text.contains("一键发表评论") -> action = "一键发表评论"
                text.contains("参与抽奖") -> action = "参与抽奖"
                text.contains("还需看播") || (text.contains("观看") && text.contains("分钟")) -> action = item.text.trim()
            }
            when {
                text.contains("发送评论") -> task = item.text.trim()
                text.contains("观看") && text.contains("分钟") -> task = item.text.trim()
                text.contains("加入") && text.contains("粉丝团") -> task = item.text.trim()
            }
        }
        val valueVal = findFudaiValue(result)
        val valueStr = if (valueVal >= 0) "￥${String.format(Locale.CHINA, "%.1f", valueVal)}" else "未识别"
        log.write("[详情识别] 成功：倒计时=$countdown，参考价值=$valueStr，任务进度=$progress，参与任务=${trim(task, 60)}，动作按钮=${trim(action, 40)}；解析阶段只记录，自动流程确认后执行")
    }

    private fun findFudaiValue(result: OfflineOcr.Result): Double {
        val p1 = Pattern.compile("[￥¥]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元)?\\s*参考(?:价值|价)?")
        val p2 = Pattern.compile("参考(?:价值|价)?\\s*[：:]?\\s*[￥¥]?\\s*(\\d+(?:\\.\\d+)?)")

        for (item in result.items) {
            val text = item.text.replace(" ", "")
            val m1 = p1.matcher(text)
            if (m1.find()) {
                m1.doubleGroupOrNull(1)?.let { return it }
            }
            val m2 = p2.matcher(text)
            if (m2.find()) {
                m2.doubleGroupOrNull(1)?.let { return it }
            }
        }

        val full = result.text.replace(Regex("\\s+"), "")
        val mFull1 = p1.matcher(full)
        if (mFull1.find()) {
            return mFull1.doubleGroupOrNull(1) ?: -1.0
        }
        val mFull2 = p2.matcher(full)
        if (mFull2.find()) {
            return mFull2.doubleGroupOrNull(1) ?: -1.0
        }

        if (full.contains("参考") || full.contains("价值")) {
            val mRmb = Pattern.compile("[￥¥](\\d+(?:\\.\\d+)?)").matcher(full)
            if (mRmb.find()) {
                return mRmb.doubleGroupOrNull(1) ?: -1.0
            }
        }
        return -1.0
    }

    private fun findTopTab(result: OfflineOcr.Result, wanted: String, width: Int, height: Int): OfflineOcr.Item? {
        var fallback: OfflineOcr.Item? = null
        val maxTabY = maxOf(450, (height * 0.45f).toInt())
        for (item in result.items) {
            val text = item.text.replace(" ", "")
            if (!text.contains(wanted) || item.bounds.centerY() > maxTabY ||
                item.bounds.centerX() < width * 0.02 || item.bounds.centerX() > width * 0.98) {
                continue
            }
            if (wanted == text) return item
            if (fallback == null) {
                val index = text.indexOf(wanted)
                val unit = item.bounds.width().toFloat() / maxOf(1, text.length)
                val left = item.bounds.left + Math.round(index * unit)
                val right = left + Math.round(wanted.length * unit)
                fallback = OfflineOcr.Item(wanted, Rect(left, item.bounds.top, right, item.bounds.bottom))
            }
        }
        return fallback
    }

    private fun finishNavigation(message: String) {
        navigationBusy = false
        log.write("[导航流程] $message")
        if (automationMode && running && state == FsmState.SCAN_FUDAI_ICON) {
            log.write("[自动流程] 已进入直播间，开始查找超级福袋")
            expectWorkflowDelay(700L)
            handler.postDelayed(::automaticScanOnce, 700L)
        } else if (automationMode && running) {
            registerTaskFault("导航失败", "[自救监控] 导航未完成")
            if (selfRecovering) return
            log.write("[自动流程] 导航未完成，3秒后自动重新开始导航")
            navigationBusy = true
            switchedToLiveList = !host.shouldStartFromFollowing()
            followPageRefreshes = 0
            livePageRefreshes = 0
            state = FsmState.PAGE_CHECK_NAV
            handler.postDelayed(::inspectHomeAndNavigate, 3000)
        }
    }

    private fun center(rect: Rect): String = "${rect.centerX()},${rect.centerY()}"

    private fun isActive(epoch: Int): Boolean = running && automationMode && epoch == engineEpoch

    private fun isOperationCurrent(epoch: Int, automated: Boolean): Boolean {
        if (epoch != engineEpoch) return false
        return !automated || (running && automationMode)
    }

    private fun continuePrizeClaim() {
        if (!running || !automationMode || !switchBusy) return
        pulseWorkflow()
        host.performPrizeClaimStep { result ->
            if (!running || !automationMode || !switchBusy) return@performPrizeClaimStep
            when (result) {
                "AGREEMENT_CLICKED" -> {
                    log.write("[自动领奖] 已勾选用户协议与隐私政策")
                    handler.postDelayed(::continuePrizeClaim, 250L)
                }
                "OPENED_ORDER" -> {
                    prizeClaimAttempts++
                    log.write("[自动领奖] 已点击“填写地址 领取奖品”，等待确认订单页")
                    if (prizeClaimAttempts >= 10) {
                        log.write("[自动领奖] 连续10次仍未进入确认订单页，停止重复点击并保留页面供人工处理")
                        switchBusy = false
                        state = FsmState.SCAN_FUDAI_ICON
                    } else {
                        handler.postDelayed(::continuePrizeClaim, 600L)
                    }
                }
                "CLAIM_SUBMITTED" -> {
                    log.write("[自动领奖] 已确认订单底部总额为¥0.00并点击“领取奖品”")
                    handler.postDelayed(::continuePrizeClaim, 700L)
                }
                "SUCCESS" -> {
                    log.write("[自动领奖] 检测到购买成功，开始返回直播间继续抽奖")
                    prizeReturnAttempts = 0
                    returnFromPrizeClaim()
                }
                "UNSAFE_PRICE" -> {
                    log.write("[自动领奖-安全停止] 确认订单底部总额不是明确的¥0.00，绝不提交；保留页面供人工检查")
                    switchBusy = false
                    state = FsmState.SCAN_FUDAI_ICON
                }
                else -> {
                    prizeClaimAttempts++
                    if (prizeClaimAttempts >= 30) {
                        log.write("[自动领奖] 30秒内未完成领奖，保留当前页面供人工处理；中奖邮件任务仍继续执行")
                        switchBusy = false
                        state = FsmState.SCAN_FUDAI_ICON
                    } else {
                        handler.postDelayed(::continuePrizeClaim, 1000L)
                    }
                }
            }
        }
    }

    private fun returnFromPrizeClaim() {
        if (!running || !automationMode) return
        host.performPrizeClaimStep { result ->
            if (!running || !automationMode) return@performPrizeClaimStep
            if (result == "LIVE_ROOM") {
                log.write("[自动领奖] 已返回直播间，继续扫描下一轮福袋")
                switchToNextLive()
                return@performPrizeClaimStep
            }
            if (prizeReturnAttempts >= 4) {
                log.write("[自动领奖] 返回4次仍未确认直播间，交由页面导航重新进入直播列表")
                switchBusy = false
                navigationBusy = true
                state = FsmState.PAGE_CHECK_NAV
                inspectHomeAndNavigate()
                return@performPrizeClaimStep
            }
            prizeReturnAttempts++
            host.back { handler.postDelayed(::returnFromPrizeClaim, 500L) }
        }
    }

    private fun scheduleLiveEntryVerification(epoch: Int, automated: Boolean) {
        if (!isOperationCurrent(epoch, automated)) return
        val waitMs = maxOf(12, host.getLiveEntryWaitSeconds()) * 1_000L
        if (automated) expectWorkflowDelay(waitMs)
        handler.postDelayed({
            if (isOperationCurrent(epoch, automated) && navigationBusy &&
                state == FsmState.PAGE_CHECK_NAV) verifyEnteredLive()
        }, waitMs)
    }

    private fun trim(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max)

    private fun Matcher.intGroup(index: Int, fallback: Int = -1): Int =
        group(index)?.toIntOrNull() ?: fallback

    private fun Matcher.doubleGroupOrNull(index: Int): Double? =
        group(index)?.toDoubleOrNull()

    private fun isCountdownOrPending(text: String): Boolean {
        if (text.contains("待参与")) return true
        val colon = COUNTDOWN.matcher(text)
        if (colon.matches()) return colon.intGroup(2) < 60
        val compact = COMPACT_COUNTDOWN.matcher(text)
        return compact.matches() && compact.intGroup(2) < 60
    }

    private fun countdownSeconds(text: String): Int {
        val colon = COUNTDOWN.matcher(text)
        if (colon.matches()) return colon.intGroup(1) * 60 + colon.intGroup(2)
        val compact = COMPACT_COUNTDOWN.matcher(text)
        if (compact.matches()) return compact.intGroup(1) * 60 + compact.intGroup(2)
        return -1
    }

    private fun findFudaiParticipantCount(text: String): Int {
        val matcher = FUDAI_PARTICIPANTS.matcher(text)
        if (!matcher.find()) return -1
        return try {
            var value = matcher.doubleGroupOrNull(1) ?: return -1
            if (matcher.group(2) != null) value *= 10000.0
            minOf(Int.MAX_VALUE.toLong(), Math.round(value)).toInt()
        } catch (ignored: Exception) {
            -1
        }
    }

    private fun nearestSlot(x: Int, centers: IntArray): Int {
        var best = 0
        var distance = Int.MAX_VALUE
        for (i in centers.indices) {
            val current = Math.abs(x - centers[i])
            if (current < distance) {
                distance = current
                best = i
            }
        }
        return best
    }

    private fun isSuperFudaiIcon(bitmap: Bitmap, slot: Int): Boolean {
        return host.matchesSuperFudaiTemplate(bitmap, slot)
    }

    private fun cropForState(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (state == FsmState.PAGE_CHECK_NAV) {
            // Same safety premise as the PC version: only inspect bottom-left
            // for "首页" when determining whether the app is in a live room.
            return Bitmap.createBitmap(source, 0, (height * 0.86).toInt(),
                maxOf(1, (width * 0.28).toInt()),
                maxOf(1, (height * 0.14).toInt()))
        }
        return Bitmap.createBitmap(source, 0, 0, width, height)
    }
}
