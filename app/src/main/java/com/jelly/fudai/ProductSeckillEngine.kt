package com.jelly.fudai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout

/** Fast path from a live-room product number to the payment button. */
class ProductSeckillEngine(private val service: FudaiAccessibilityService) {
    companion object {
        private const val DOUYIN = "com.ss.android.ugc.aweme"
        private const val DOUYIN_LITE = "com.ss.android.ugc.aweme.lite"
        // Accessibility content events drive the fast path immediately.
        // Polling is only a fallback for ROMs that occasionally drop events.
        private const val POLL_INTERVAL_MS = 60L
        private const val SEARCH_RESULT_TIMEOUT_MS = 3_000L
        private const val SEARCH_RESULT_SETTLE_MS = 80L
        private const val PRODUCT_TIMEOUT_MS = 20_000L
        private const val CHECKOUT_TIMEOUT_MS = 15_000L
        private const val BUY_RETRY_WINDOW_MS = 1_000L
        private const val BUY_RETRY_INTERVAL_MS = 260L
        private const val MAX_BUY_CLICKS_PER_WINDOW = 2
        private const val PRODUCT_TRANSITION_RECOVERY_MS = 600L
        private const val OLD_SEARCH_INPUT_SUFFIX = ":id/arp"
        private const val NEW_SEARCH_INPUT_SUFFIX = ":id/search_ec_common_input"
    }

    private enum class Phase { SEARCH, PRODUCT, CHECKOUT, DONE }
    private enum class SearchState { IDLE, SEARCHING }
    private enum class BuyNavigationState { ENTERED_NEXT_PAGE, STILL_IN_CART, TRANSITIONING }

    private data class BuyNavigationSnapshot(
        val state: BuyNavigationState,
        val bottomCta: AccessibilityNodeInfo? = null,
        val checkoutCta: AccessibilityNodeInfo? = null
    )

    private data class CheckoutSnapshot(
        val cashierEvidence: Boolean,
        val pay: AccessibilityNodeInfo?,
        val submit: AccessibilityNodeInfo?,
        val staleOrderSheetCta: AccessibilityNodeInfo?
    )

    private val prefs = service.getSharedPreferences("product_seckill", 0)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var scheduled = false
    private var busy = false
    private var phase = Phase.SEARCH
    private var searchState = SearchState.IDLE
    private var searchStateStartTime = 0L
    private var phaseStartTime = 0L
    private var lastScanAt = 0L
    private var lastProductDecisionLog = ""
    private var lastSkuDecisionLog = ""
    private val attemptedSkuNames = linkedSetOf<String>()
    private val completedSkuGroupKeys = linkedSetOf<String>()
    private var missingTargetConfirmations = 0
    private var purchaseNavigationArmed = false
    private var buyTransitionSeenAt = 0L
    private var buyClickAttempts = 0
    private var nextBuyClickAt = 0L
    private var partialResultSeenAt = 0L
    private var sessionGeneration = 0
    private var submitDispatched = false
    private var paymentDispatched = false
    private var submitRetryCount = 0
    private var nextSubmitRetryAt = 0L
    private var orderSheetRetryCount = 0
    private var nextOrderSheetRetryAt = 0L
    private val pendingSkuAttempts = linkedMapOf<String, PendingSkuAttempt>()
    private var productSheetSeen = false
    private var lastSelectedSkuLog = ""
    private var lastPriceDiagnostic = ""
    private var cartOpenedAt = 0L
    private var cartRefreshRounds = 0L
    private var lastCartScrollAt = 0L
    private var cartCloseRequestedAt = 0L
    private var lastUserStatus = ""

    private data class SkuGroup(
        val key: String,
        val options: List<AccessibilityNodeInfo>
    )

    private data class PendingSkuAttempt(
        val name: String,
        val startedAt: Long
    )

    private data class FreshBuyAction(
        val row: AccessibilityNodeInfo,
        val target: AccessibilityNodeInfo,
        val label: String
    )

    private val monitor = object : Runnable {
        override fun run() {
            scheduled = false
            scanOnce()
        }
    }

    private var configDialogView: android.view.View? = null

    fun showConfigDialog() {
        if (service.overlayDialogShowing) return
        service.overlayDialogShowing = true
        val savedNumber = prefs.getInt("product_number", prefs.getInt("index", 1))
        val savedPriceStr = prefs.getString("max_price", "") ?: ""
        val savedValidatePrice = prefs.getBoolean("validate_price", true)

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = makeCardBackground()
        }

        val title = android.widget.TextView(service).apply {
            text = if (running) "抢购进行中" else "商品抢购设置"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 19f
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(title)

        val hint = android.widget.TextView(service).apply {
            text = "请先进入有小黄车的直播间。按商品序号定位，价格符合后才会下单。"
            textSize = 11.5f
            setTextColor(android.graphics.Color.rgb(155, 180, 190))
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(hint)

        val label1 = android.widget.TextView(service).apply {
            text = "商品序号"
            setTextColor(android.graphics.Color.rgb(180, 205, 215))
            textSize = 12.5f
            setPadding(0, dp(2), 0, dp(1))
        }
        container.addView(label1)

        val numberInput = EditText(service).also { et ->
            et.hint = "例如：11"
            et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            et.setText(savedNumber.toString())
            styleInput(et)
        }
        container.addView(numberInput)

        val label2 = android.widget.TextView(service).apply {
            text = "最高价格上限（元）"
            setTextColor(android.graphics.Color.rgb(180, 205, 215))
            textSize = 12.5f
            setPadding(0, dp(2), 0, dp(1))
        }
        container.addView(label2)

        val priceInput = EditText(service).also { et ->
            et.hint = "例如：99.0"
            et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            et.setText(savedPriceStr)
            styleInput(et)
        }
        container.addView(priceInput)

        val validatePrice = android.widget.CheckBox(service).apply {
            text = "校验价格（取消后发现可抢按钮立即进入）"
            setTextColor(android.graphics.Color.rgb(255, 225, 155))
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    android.graphics.Color.rgb(255, 55, 100),
                    android.graphics.Color.rgb(225, 235, 242)
                )
            )
            textSize = 12.5f
            isChecked = savedValidatePrice
            minHeight = dp(48)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(1), 0, 0) }
        }
        container.addView(validatePrice)
        priceInput.isEnabled = validatePrice.isChecked
        priceInput.alpha = if (validatePrice.isChecked) 1f else 0.45f
        validatePrice.setOnCheckedChangeListener { _, checked ->
            priceInput.isEnabled = checked
            priceInput.alpha = if (checked) 1f else 0.45f
        }

        val autoPay = android.widget.CheckBox(service).apply {
            text = "自动支付（不勾选时遇到支付按钮立即停止）"
            setTextColor(android.graphics.Color.rgb(255, 225, 155))
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    android.graphics.Color.rgb(255, 55, 100),
                    android.graphics.Color.rgb(225, 235, 242)
                )
            )
            textSize = 12.5f
            isChecked = prefs.getBoolean("auto_pay", false)
            minHeight = dp(48)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 0) }
        }
        container.addView(autoPay)

        val actionsRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val saveAndStart: () -> Unit = saveAndStart@{
            val numberText = numberInput.text.toString().trim()
            val number = numberText.toIntOrNull()
            val priceText = priceInput.text.toString().trim()
            val maxPrice = priceText.toDoubleOrNull()
            var valid = true
            if (number == null || number !in 1..999) {
                numberInput.error = "请输入1至999之间的商品序号"
                valid = false
            }
            if (validatePrice.isChecked && (maxPrice == null || !maxPrice.isFinite() || maxPrice <= 0.0)) {
                priceInput.error = "请输入大于0的最高价格"
                valid = false
            }
            if (!valid) return@saveAndStart

            prefs.edit()
                .putInt("product_number", number!!)
                .remove("product_keyword")
                .putString("max_price", priceText)
                .putBoolean("validate_price", validatePrice.isChecked)
                .putBoolean("auto_pay", autoPay.isChecked)
                .apply()
            removeConfigDialog()
            // The overlay marks the live-room nodes underneath it invisible.
            // Close it first, then inspect the real active Douyin window.
            handler.postDelayed(::start, 300L)
        }

        if (running) {
            val stopBtn = cardButton("停止抢购", android.graphics.Color.rgb(170, 68, 78))
            val startBtn = cardButton("更新参数并重启", android.graphics.Color.rgb(20, 158, 182))
            actionsRow.addView(stopBtn)
            actionsRow.addView(startBtn)

            stopBtn.setOnClickListener {
                stop()
                removeConfigDialog()
            }
            startBtn.setOnClickListener {
                saveAndStart()
            }
        } else {
            val cancelBtn = cardButton("取消", android.graphics.Color.rgb(72, 91, 106))
            val startBtn = cardButton("保存并开始", android.graphics.Color.rgb(20, 158, 182))
            actionsRow.addView(cancelBtn)
            actionsRow.addView(startBtn)

            cancelBtn.setOnClickListener { removeConfigDialog() }
            startBtn.setOnClickListener {
                saveAndStart()
            }
        }
        container.addView(actionsRow)

        val params = WindowManager.LayoutParams(
            minOf(service.resources.displayMetrics.widthPixels - dp(24), dp(420)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.CENTER
        // Resizing a centered overlay between ACTION_DOWN/ACTION_UP can move
        // the second field under the following checkbox. Pan the window to the
        // focused field instead, keeping every control's hit box stable.
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        configDialogView = container
        try {
            (service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).addView(configDialogView, params)
            numberInput.requestFocus()
        } catch (error: Throwable) {
            configDialogView = null
            service.overlayDialogShowing = false
            throw error
        }
    }

    private fun removeConfigDialog() {
        if (configDialogView != null) {
            try {
                (service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).removeView(configDialogView)
            } catch (ignored: Exception) {}
        }
        configDialogView = null
        service.overlayDialogShowing = false
    }

    private fun styleInput(input: EditText) {
        input.setTextColor(android.graphics.Color.WHITE)
        input.setHintTextColor(android.graphics.Color.rgb(166, 184, 198))
        input.textSize = 15f
        input.isSingleLine = true
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.minHeight = dp(48)
        input.minimumHeight = dp(48)
        input.setPadding(dp(10), dp(7), dp(10), dp(7))
        input.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(75, 205, 222))
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        params.setMargins(0, dp(1), 0, dp(4))
        input.layoutParams = params
    }

    private fun cardButton(label: String, backgroundColor: Int): android.widget.Button {
        val button = android.widget.Button(service)
        button.text = label
        button.setTextColor(android.graphics.Color.WHITE)
        button.textSize = 14f
        button.isAllCaps = false
        button.gravity = android.view.Gravity.CENTER
        button.minHeight = dp(42)
        button.minimumHeight = dp(42)
        button.setPadding(dp(10), 0, dp(10), 0)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(backgroundColor)
        bg.cornerRadius = dp(10).toFloat()
        bg.setStroke(dp(1), android.graphics.Color.argb(140, 180, 220, 230))
        button.background = bg
        val params = LinearLayout.LayoutParams(0, dp(42), 1f)
        params.setMargins(dp(4), 0, dp(4), 0)
        button.layoutParams = params
        return button
    }

    private fun makeCardBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.argb(248, 18, 28, 36))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), android.graphics.Color.argb(140, 64, 221, 232))
        }
    }

    fun isRunning(): Boolean = running

    fun stop(reason: String? = null) {
        if (running) userStatus(reason ?: "已停止", force = true)
        sessionGeneration++
        running = false
        scheduled = false
        busy = false
        phase = Phase.DONE
        handler.removeCallbacksAndMessages(null)
        service.updateFloatButtonsState()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running) return
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != DOUYIN && packageName != DOUYIN_LITE) return

        // Douyin's cashier is a dedicated Activity whose first accessibility
        // frame may contain no readable cashier text. Treat the Activity
        // transition itself as authoritative evidence that the order has
        // been submitted/locked. With "stop before payment" enabled, stop
        // immediately so checkout retries cannot leak onto the payment page.
        val className = event.className?.toString().orEmpty()
        val enteredCashierActivity = className.contains("DyPayETActivity") ||
            className.contains("H5PayActivity")
        if (phase == Phase.CHECKOUT && enteredCashierActivity &&
            !prefs.getBoolean("auto_pay", false)) {
            stop("订单已锁定并进入支付页，已按设置停止自动操作")
            return
        }

        // A content-change event is fresher than the pending polling tick.
        // Preempt it so price/button changes are consumed almost immediately.
        handler.removeCallbacks(monitor)
        scheduled = false
        schedule(0L)
    }

    private fun start() {
        val number = prefs.getInt("product_number", 0)
        val maxPrice = prefs.getString("max_price", "")?.toDoubleOrNull()
        val validatePrice = prefs.getBoolean("validate_price", true)
        if (number !in 1..999 || (validatePrice && (maxPrice == null ||
            !maxPrice.isFinite() || maxPrice <= 0.0))) {
            userStatus("设置无效，请重新填写商品序号和最高价格", force = true)
            return
        }
        val liveRoot = findDouyinRoot(requireActive = true)
        if (liveRoot == null) {
            userStatus("请保持抖音直播间在前台", force = true)
            return
        }
        service.stopBarrage(false)
        service.engine?.stop()
        handler.removeCallbacksAndMessages(null)
        sessionGeneration++
        running = true
        scheduled = false
        busy = false
        phase = Phase.SEARCH
        phaseStartTime = SystemClock.elapsedRealtime()
        lastScanAt = 0L
        searchState = SearchState.IDLE
        searchStateStartTime = 0L
        lastProductDecisionLog = ""
        lastSkuDecisionLog = ""
        attemptedSkuNames.clear()
        completedSkuGroupKeys.clear()
        missingTargetConfirmations = 0
        purchaseNavigationArmed = false
        buyClickAttempts = 0
        nextBuyClickAt = 0L
        partialResultSeenAt = 0L
        submitDispatched = false
        paymentDispatched = false
        submitRetryCount = 0
        nextSubmitRetryAt = 0L
        orderSheetRetryCount = 0
        nextOrderSheetRetryAt = 0L
        pendingSkuAttempts.clear()
        productSheetSeen = false
        lastSelectedSkuLog = ""
        cartOpenedAt = 0L
        cartRefreshRounds = 0L
        lastCartScrollAt = 0L
        cartCloseRequestedAt = 0L
        lastUserStatus = ""
        service.updateFloatButtonsState()
        userStatus(
            "已开始：第${number}件，" +
                (if (validatePrice) "限价￥${formatPrice(maxPrice!!)}，" else "不校验价格，") +
                if (prefs.getBoolean("auto_pay", false)) "自动支付" else "支付前停止",
            force = true
        )
        schedule(10L)
    }

    private fun schedule(delayMs: Long = POLL_INTERVAL_MS) {
        if (!running || scheduled) return
        scheduled = true
        handler.postDelayed(monitor, delayMs)
    }

    private fun scanOnce() {
        if (!running || busy) return
        val now = SystemClock.elapsedRealtime()
        val remaining = POLL_INTERVAL_MS - (now - lastScanAt)
        if (remaining > 0) {
            schedule(remaining)
            return
        }
        lastScanAt = now
        busy = true
        try {
            val activeRoot = service.rootInActiveWindow
            if (phase == Phase.PRODUCT && activeRoot != null && isAddressManager(activeRoot)) {
                userStatus("误入地址页面，正在返回订单")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                phaseStartTime = now
                return
            }
            val root = findDouyinRoot(requireActive = true)
            if (root == null) {
                userStatus("请保持抖音直播间在前台")
                return
            }

            // A shopping-plugin transition can arrive late, even after the
            // cart refresh path has started or the cart has been reopened.
            // This check has priority over every SEARCH action. Do not require
            // the old cart panel to disappear: Douyin can keep that tree under
            // the new sheet. Any SKU/order/checkout CTA from the armed attempt
            // immediately cancels the preceding refresh path and takes over.
            if (phase == Phase.SEARCH && purchaseNavigationArmed) {
                val navigation = inspectBuyNavigation(root)
                if (navigation.state == BuyNavigationState.ENTERED_NEXT_PAGE) {
                    purchaseNavigationArmed = false
                    cartCloseRequestedAt = 0L
                    if (navigation.checkoutCta != null) {
                        phase = Phase.CHECKOUT
                        phaseStartTime = now
                        userStatus("监听到下单页，已取消小黄车刷新")
                        scanCheckout(root)
                    } else {
                        phase = Phase.PRODUCT
                        phaseStartTime = now
                        userStatus("监听到商品页，已取消小黄车刷新")
                        scanProductPage(root, navigation)
                    }
                    return
                }
            }

            // Hard phase isolation: SEARCH can only open the yellow cart and
            // search. Live-room advertising text must never enter product or
            // checkout handling before we click a verified buy button.
            if (phase == Phase.PRODUCT && now - phaseStartTime >= PRODUCT_TIMEOUT_MS) {
                if (productSheetSeen) {
                    // Never abandon an opened order/SKU sheet. Returning here
                    // throws away the scarce purchase opportunity.
                    phaseStartTime = now
                    userStatus("正在等待可用规格")
                } else {
                    stop("未能打开商品页，请确认商品仍可购买")
                }
                return
            }
            if (phase == Phase.CHECKOUT && now - phaseStartTime >= CHECKOUT_TIMEOUT_MS) {
                stop("结算页面响应超时，请人工检查")
                return
            }
            when (phase) {
                Phase.SEARCH -> scanCartRefresh(root, now)
                Phase.PRODUCT -> scanProductPage(root)
                Phase.CHECKOUT -> scanCheckout(root)
                Phase.DONE -> Unit
            }

        } finally {
            busy = false
            if (running) schedule()
        }
    }

    /**
     * Refreshes availability by reopening the live-room product list. Product
     * numbers are read from each card's own accessibility description (for
     * example "2号商品…"), never inferred from its visible list position.
     */
    private fun scanCartRefresh(root: AccessibilityNodeInfo, now: Long) {
        val number = prefs.getInt("product_number", 1)
        val cartPanel = findCartPanel(root)

        if (cartPanel == null) {
            if (cartCloseRequestedAt != 0L && now - cartCloseRequestedAt < 400L) return
            cartCloseRequestedAt = 0L
            // Opening the plugin produces several transient roots without the
            // panel. Do not interpret those events as another closed cart.
            // The product sheet is already animating during this window, but
            // its accessibility description may not be attached yet. Never
            // toggle the cart again while that first tree is settling.
            if (cartOpenedAt != 0L && now - cartOpenedAt < 700L) return
            val cart = findNode(root) { node ->
                val desc = node.contentDescription?.toString().orEmpty().trim()
                val text = node.text?.toString().orEmpty().trim()
                val bounds = Rect().also(node::getBoundsInScreen)
                val semanticMatch = desc == "商品列表" || desc == "小黄车" ||
                    desc == "购物车" || text == "商品列表" || text == "小黄车" || text == "购物车"
                semanticMatch && node.isVisibleToUser && node.isEnabled &&
                    bounds.centerY() > service.resources.displayMetrics.heightPixels * 2 / 3 &&
                    bounds.width() in dp(32)..dp(280) && bounds.height() in dp(28)..dp(280)
            }
            if (cart == null) {
                showWaitingHint("正在精准寻找直播间小黄车（商品列表）图标...")
                return
            }
            val target = clickableAncestor(cart)
            val bounds = Rect().also(target::getBoundsInScreen)
            var clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                clicked = dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
            if (!clicked) return
            cartOpenedAt = now
            lastCartScrollAt = 0L
            missingTargetConfirmations = 0
            return
        }

        // GLOBAL_ACTION_BACK is asynchronous. Do not consume the same stale
        // product-list tree and issue a second back while its close animation
        // is still running.
        if (cartCloseRequestedAt != 0L) {
            if (now - cartCloseRequestedAt < 400L) return
            cartCloseRequestedAt = 0L
        }

        val row = findProductRow(cartPanel, number)
        if (row != null) {
            missingTargetConfirmations = 0
            val bounds = Rect().also(row::getBoundsInScreen)
            if (row.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
                // The requested numbered card is already present. Never move
                // the list after a match; operate that exact card immediately.
                handleFirstSearchResult(row)
                return
            }
        }

        // A numbered card may exist off-screen. Scroll only the actual product
        // list and still require its explicit "N号商品" label after scrolling.
        if (now - cartOpenedAt >= 80L && now - lastCartScrollAt >= 160L) {
            missingTargetConfirmations++
            if (missingTargetConfirmations < 4) return
            val scrollable = findNode(cartPanel) { it.isScrollable && it.isVisibleToUser }
            if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
                lastCartScrollAt = now
                missingTargetConfirmations = 0
                return
            }
        }
        // The target may still be attaching or may be farther down the
        // virtualized list. Keep the cart open and continue observing its
        // accessibility tree; absence from the current snapshot is never a
        // reason to close/reopen the yellow cart.
        showWaitingHint("正在无障碍列表中寻找${number}号商品...")
    }

    private fun scanSearch(root: AccessibilityNodeInfo, now: Long) {
        val number = prefs.getInt("product_number", 1)
        val query = number.toString()

        // Step 1: NAVIGATION FIRST - Check if we are inside secondary search page with a real EditText input (id/arp)
        val input = findNode(root) { node ->
            val text = textOf(node)
            val hint = node.hintText?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val bounds = Rect().also(node::getBoundsInScreen)
            
            (node.isEditable || node.className?.toString()?.contains("EditText") == true) &&
                (id.endsWith(OLD_SEARCH_INPUT_SUFFIX) || id.endsWith(NEW_SEARCH_INPUT_SUFFIX) ||
                    text.contains("搜索商品") || hint.contains("搜索商品")) &&
                bounds.centerY() in 500..750
        }

        // Search-result pages may hide the input completely. Once submitted,
        // keep polling the first result instead of reopening/retyping search.
        if (searchState == SearchState.SEARCHING) {
            val resultRow = if (now - searchStateStartTime >= SEARCH_RESULT_SETTLE_MS)
                findProductRow(root, number) else null
            if (resultRow != null) {
                if (partialResultSeenAt == 0L) partialResultSeenAt = now
                handleFirstSearchResult(resultRow)
                return
            }
            if (now - searchStateStartTime < SEARCH_RESULT_TIMEOUT_MS) return
            searchState = SearchState.IDLE
            partialResultSeenAt = 0L
            service.writeRuntimeLog("[抢购] 搜索3秒后仍未出现第一商品，重新发起搜索")
        }

        // If secondary search input is NOT present, navigate to cart/search.
        if (input == null) {
            searchState = SearchState.IDLE // Reset state lock when not in search page!
            val inCart = hasShoppingContext(root)

            val topSearchEntry = if (inCart) findNode(root) { node ->
                val text = textOf(node)
                val id = node.viewIdResourceName.orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                
                (text.contains("搜商品") || text.contains("序号") || text.contains("搜索") ||
                    id.endsWith(":id/ngs") || id.endsWith(":id/kvs") ||
                    node.contentDescription?.toString()?.contains("搜索") == true) &&
                    bounds.centerY() in 400..800
            } else null

            if (topSearchEntry != null) {
                val clickable = clickableAncestor(topSearchEntry)
                if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val bounds = Rect().also(topSearchEntry::getBoundsInScreen)
                    dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
                showWaitingHint("已自动点击小黄车顶部搜索框(搜商品/序号)，进入二级搜索...")
                return
            }

            // A shopping/search page can expose its card tree before the top
            // input is attached. Never fall through to live-room cart lookup:
            // broad shopping ids here would click a product card and open its
            // detail page during startup.
            if (inCart) {
                showWaitingHint("商城页面加载中，等待搜索框节点...")
                return
            }

            val yellowCartButton = findNode(root) { node ->
                val desc = node.contentDescription?.toString().orEmpty()
                val text = textOf(node)
                val id = node.viewIdResourceName.orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                
                val explicitCart = desc.contains("商品列表") || desc.contains("小黄车") ||
                    desc.contains("购物车") || text.contains("小黄车") ||
                    text.contains("商品列表") || text == "商品" || id.contains("yellow_bag")
                explicitCart && bounds.centerY() > service.resources.displayMetrics.heightPixels * 2 / 3 &&
                    bounds.width() <= dp(260) && bounds.height() <= dp(260)
            }

            if (yellowCartButton != null) {
                val target = clickableAncestor(yellowCartButton)
                if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val bounds = Rect().also(yellowCartButton::getBoundsInScreen)
                    dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
                showWaitingHint("已自动点击小黄车图标，正在打开商品列表...")
            } else {
                showWaitingHint("正在寻找直播间小黄车图标...")
            }
            return
        }

        // Step 2: Now that we are inside secondary search page (input != null), execute Search State Machine!
        // 1. Safety Timeout: Reset state machine if 2-second fallback timer expires
        // 2. Check search result & condition assertions
        // Only consume a product row after this run has actually submitted a
        // search. This prevents stale cart rows from being treated as results.
        val row = if (searchState == SearchState.SEARCHING &&
            now - searchStateStartTime >= SEARCH_RESULT_SETTLE_MS) findProductRow(root, number) else null
        if (row != null) {
            handleFirstSearchResult(row)
            return
        }

        // 3. Trigger search ONLY when in IDLE state inside secondary search page
        if (searchState == SearchState.IDLE) {
            val currentText = input.text?.toString()?.trim().orEmpty()
            if (currentText != query) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val written = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                service.writeRuntimeLog(if (written) "[抢购] 已输入商品序号$query" else "[抢购异常] 商品序号$query 写入失败")
                showWaitingHint(if (written) "已输入商品序号$query，等待搜索按钮刷新..." else "商品序号写入失败，正在重试...")
                // The search button is often created only after the text
                // change event. Never search the stale accessibility tree.
                handler.postDelayed({ if (running) scanOnce() }, 30L)
                return
            }

            val imeSubmitted = input.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            val inputId = input.viewIdResourceName.orEmpty()
            // On both observed layouts IME_ENTER may report success without
            // submitting. A verified physical tap in the trailing action area
            // is required; this is the path that produced real result cards
            // on the new layout as well.
            val bounds = Rect().also(input::getBoundsInScreen)
            val x = minOf(service.resources.displayMetrics.widthPixels - dp(18), bounds.right + dp(52))
            val rootTap = dispatchTap(x.toFloat(), bounds.centerY().toFloat())
            searchState = SearchState.SEARCHING
            searchStateStartTime = now
            partialResultSeenAt = 0L
            val layoutName = if (inputId.endsWith(NEW_SEARCH_INPUT_SUFFIX)) "新版" else "旧版"
            service.writeRuntimeLog(if (rootTap) {
                "[抢购] $layoutName 搜索页已启动Root点击右侧搜索按钮，500ms后接收新结果"
            } else if (imeSubmitted) {
                "[抢购] Root不可用，$layoutName 搜索页已触发IME并回退无障碍搜索点击"
            } else {
                "[抢购] $layoutName 搜索页已物理点击右侧搜索动作区"
            })
            showWaitingHint("已发起搜索，等待第一个商品显示...")
        }
    }

    private fun hasShoppingContext(root: AccessibilityNodeInfo): Boolean =
        findNode(root) {
            val id = it.viewIdResourceName.orEmpty()
            val text = textOf(it)
            id.contains("shopping", true) || it.contentDescription?.toString() == "商品列表" ||
                text.contains("直播间未找到相关商品") || text.contains("搜索商品关键词")
        } != null

    private fun hasLiveProductSearchEvidence(root: AccessibilityNodeInfo): Boolean =
        findNode(root) {
            val text = textOf(it)
            text.contains("直播间未找到相关商品") || text.contains("搜索商品关键词")
        } != null

    private fun isCtaAction(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.contains("立即支付") || trimmed.contains("免密支付") ||
            trimmed.contains("先用后付") || trimmed.contains("提交订单") ||
            trimmed.contains("立即购买") || trimmed.contains("马上抢") ||
            trimmed.contains("确认购买")
    }

    /** Single source of truth for the result of tapping "去抢购". */
    private fun inspectBuyNavigation(root: AccessibilityNodeInfo): BuyNavigationSnapshot {
        val screenHeight = service.resources.displayMetrics.heightPixels
        var checkoutCta: AccessibilityNodeInfo? = null
        val bottomMatches = mutableListOf<AccessibilityNodeInfo>()
        var productSheetEvidence = false
        walk(root) { node ->
            val label = textOf(node)
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!node.isVisibleToUser) return@walk
            if (id.endsWith(":id/fl_sku_select_container") ||
                (node.className?.toString() == "android.widget.Button" &&
                    (desc.startsWith("未选择") || desc.startsWith("已选择")))) {
                productSheetEvidence = true
            }
            if (bounds.centerY() <= screenHeight * 3 / 4) return@walk
            if (checkoutCta == null && node.isEnabled &&
                (label.contains("立即支付") || label.contains("免密支付") ||
                    label.contains("先用后付"))) {
                checkoutCta = node
            }
            if (label.contains("请选择") || isCtaAction(label)) bottomMatches += node
        }
        val bottomCta = bottomMatches.minByOrNull {
            val bounds = Rect().also(it::getBoundsInScreen)
            bounds.width().toLong() * bounds.height().toLong()
        }
        if (checkoutCta != null || bottomCta != null || productSheetEvidence) {
            return BuyNavigationSnapshot(
                BuyNavigationState.ENTERED_NEXT_PAGE,
                bottomCta = bottomCta,
                checkoutCta = checkoutCta
            )
        }

        // A cart row can remain underneath an opened overlay, therefore this
        // evidence is considered only after all next-page evidence above.
        val staleRow = findProductRow(root, prefs.getInt("product_number", 1))
        val staleBuy = staleRow?.let { row ->
            findNode(row) { node ->
                isTargetBuyAction(textOf(node)) && node.isVisibleToUser && node.isEnabled
            }
        }?.let(::preciseActionTarget)
        return BuyNavigationSnapshot(
            if (staleBuy != null) BuyNavigationState.STILL_IN_CART
            else BuyNavigationState.TRANSITIONING
        )
    }

    private fun scanProductPage(root: AccessibilityNodeInfo, knownNavigation: BuyNavigationSnapshot? = null) {
        if (isAddressManager(root)) {
            pendingSkuAttempts.clear()
            userStatus("页面跳转异常，正在返回商品页")
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val navigation = knownNavigation ?: inspectBuyNavigation(root)
        if (navigation.state == BuyNavigationState.STILL_IN_CART) {
            buyTransitionSeenAt = 0L
            if (now - phaseStartTime < BUY_RETRY_WINDOW_MS) {
                // Retry only while a fresh scan still proves that the buy
                // action exists. Cap and space requests to prevent click storms.
                if (buyClickAttempts < MAX_BUY_CLICKS_PER_WINDOW && now >= nextBuyClickAt) {
                    val freshAction = resolveFreshBuyAction(prefs.getInt("product_number", 1))
                    if (freshAction != null) {
                        val accepted = freshAction.target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        buyClickAttempts++
                        nextBuyClickAt = now + BUY_RETRY_INTERVAL_MS
                        userStatus(
                            if (accepted) "商品页尚未出现，限频补点去抢购（$buyClickAttempts/$MAX_BUY_CLICKS_PER_WINDOW）"
                            else "补点未被系统确认，停止密集重试（$buyClickAttempts/$MAX_BUY_CLICKS_PER_WINDOW）"
                        )
                    }
                }
                return
            }
            userStatus("限频补点后仍未进入商品页，重新打开小黄车")
            phase = Phase.SEARCH
            phaseStartTime = now
            forceRefreshSearch("去抢购连续补点1秒未生效", root)
            return
        }
        if (navigation.state == BuyNavigationState.TRANSITIONING) {
            // The original buy action has disappeared, but no actionable
            // product control is attached yet. Keep consuming fresh events;
            // if the transition produces nothing quickly, resume cart search
            // instead of sacrificing the purchase window to a 20-second wait.
            if (buyTransitionSeenAt == 0L) buyTransitionSeenAt = now
            if (purchaseNavigationArmed &&
                now - buyTransitionSeenAt >= PRODUCT_TRANSITION_RECOVERY_MS) {
                phase = Phase.SEARCH
                phaseStartTime = now
                buyTransitionSeenAt = 0L
                userStatus("抢购按钮已消失但商品页未就绪，立即恢复小黄车监听")
            }
            return
        }
        buyTransitionSeenAt = 0L
        purchaseNavigationArmed = false
        buyTransitionSeenAt = 0L

        if (navigation.checkoutCta != null) {
            phase = Phase.CHECKOUT
            phaseStartTime = SystemClock.elapsedRealtime()
            scanCheckout(root)
            return
        }

        // The bottom CTA is the only branch point on the order sheet.
        productSheetSeen = true
        val bottomCta = navigation.bottomCta ?: return
        val bottomText = textOf(bottomCta)
        // The bottom CTA text is the fastest authoritative state signal. Once
        // it changes from "请选择" to a real purchase action, click it in this
        // same scan. Never wait for the slower SKU selected semantics to catch
        // up: those nodes can lag behind the already-enabled lock button.
        if (!bottomText.contains("请选择") && isCtaAction(bottomText)) {
            if (pendingSkuAttempts.isNotEmpty()) {
                val selectedNames = pendingSkuAttempts.values.joinToString("、") { it.name.take(24) }
                userStatus("规格已选：$selectedNames")
                pendingSkuAttempts.clear()
            }
            // Click the CTA's own verified bounds. Climbing to a large
            // clickable ancestor can include the address block and route the
            // action there on some shopping-plugin versions.
            // The shopping plugin often reports ACTION_CLICK=true without
            // actually submitting. A real coordinate gesture on the first
            // attempt avoids losing a scarce purchase window to a fake
            // semantic success and a later retry.
            val bounds = Rect().also(bottomCta::getBoundsInScreen)
            val clicked = dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            if (!clicked) {
                userStatus("提交按钮暂未响应，正在重试")
                return
            }
            phase = Phase.CHECKOUT
            phaseStartTime = SystemClock.elapsedRealtime()
            userStatus("价格已确认，正在提交订单")
            return
        }

        // Only inspect SKU structure while the bottom CTA still asks for a
        // selection. Product-specific titles and option names are deliberately
        // not used here.
        val skuGroups = structuralSkuGroups(root, bottomCta)

        // Every group advances independently in the same fresh page scan.
        // This lets color/size/etc. start together without one group's state
        // confirmation blocking the others. The lock CTA above always wins.
        var unresolvedGroups = 0
        var groupsWaitingForState = 0
        val startedNames = mutableListOf<String>()
        for (group in skuGroups) {
            if (group.options.any(::isSkuSelected)) {
                completedSkuGroupKeys += group.key
                pendingSkuAttempts.remove(group.key)?.let { confirmed ->
                    if (confirmed.name != lastSelectedSkuLog) {
                        lastSelectedSkuLog = confirmed.name
                        userStatus("规格已选：${confirmed.name.take(24)}")
                    }
                }
                continue
            }
            unresolvedGroups++

            val pending = pendingSkuAttempts[group.key]
            if (pending != null && now - pending.startedAt < 100L) {
                groupsWaitingForState++
                continue
            }
            // The last accepted click did not produce selected state within
            // the fast verification window. Keep it attempted and move this
            // group to its next genuinely clickable option.
            if (pending != null) pendingSkuAttempts.remove(group.key)

            val candidate = group.options.firstOrNull { option ->
                isSkuAvailable(option) && skuClickableTarget(option) != null &&
                    skuAttemptKey(group.key, option) !in attemptedSkuNames
            } ?: continue
            val actionNode = skuClickableTarget(candidate) ?: continue
            val attemptKey = skuAttemptKey(group.key, candidate)
            attemptedSkuNames += attemptKey
            val skuName = skuDisplayName(candidate)
            if (actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                pendingSkuAttempts[group.key] = PendingSkuAttempt(skuName, now)
                groupsWaitingForState++
                startedNames += skuName.take(24)
                lastSkuDecisionLog = ""
            }
        }
        if (startedNames.isNotEmpty()) {
            userStatus("正在同时选择规格：${startedNames.joinToString("、")}")
            return
        }
        if (unresolvedGroups > 0 && groupsWaitingForState == 0) {
            stop("商品页没有可点击规格，未能锁住订单")
            return
        }
        if (unresolvedGroups > 0) return
        val emptyLog = "暂未找到可用规格，继续等待"
        if (emptyLog != lastSkuDecisionLog) {
            lastSkuDecisionLog = emptyLog
            userStatus(emptyLog)
        }

    }

    private fun isAddressManager(root: AccessibilityNodeInfo): Boolean {
        val hasTitle = findNode(root) { node ->
            val label = textOf(node).trim()
            label == "收货地址" || label == "地址管理"
        } != null
        if (!hasTitle) return false
        return findNode(root) { node ->
            val label = textOf(node)
            label.contains("新建地址") || label.contains("添加收货地址") ||
                label.contains("编辑地址")
        } != null
    }

    /** One tree traversal supplies every checkout decision for this frame. */
    private fun inspectCheckoutPage(root: AccessibilityNodeInfo): CheckoutSnapshot {
        val screenHeight = service.resources.displayMetrics.heightPixels
        var cashierEvidence = false
        var pay: AccessibilityNodeInfo? = null
        var submit: AccessibilityNodeInfo? = null
        val bottomMatches = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            val label = textOf(node)
            if (!cashierEvidence &&
                (label.contains("收银台") || label.contains("选择支付方式") ||
                    label.contains("支付密码") || label.contains("剩余支付时间"))) {
                cashierEvidence = true
            }
            if (!node.isVisibleToUser || !node.isEnabled) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.centerY() <= screenHeight * 3 / 4) return@walk
            if (pay == null && (label.contains("立即支付") ||
                    label.contains("免密支付") || label.contains("先用后付"))) {
                pay = node
            }
            if (submit == null && label.contains("提交订单")) submit = node
            if (label.contains("请选择") || isCtaAction(label)) bottomMatches += node
        }
        val staleOrderSheetCta = bottomMatches.minByOrNull {
            val bounds = Rect().also(it::getBoundsInScreen)
            bounds.width().toLong() * bounds.height().toLong()
        }
        return CheckoutSnapshot(cashierEvidence, pay, submit, staleOrderSheetCta)
    }

    private fun scanCheckout(root: AccessibilityNodeInfo) {
        if (!running) return
        val snapshot = inspectCheckoutPage(root)
        val cashierEvidence = snapshot.cashierEvidence
        val pay = snapshot.pay
        if (pay != null) {
            // Some order-confirmation layouts label the submit CTA as
            // "立即支付". It is only the final payment button when the page
            // also contains reliable cashier evidence.
            // A cashier can only be reached after this session has actually
            // dispatched the order-submission action. Payment-method labels
            // alone also occur on the order-confirmation page.
            val confirmedCashier = submitDispatched && cashierEvidence
            if (!confirmedCashier) {
                val target = preciseActionTarget(pay)
                if (!running || target == null) return
                val now = SystemClock.elapsedRealtime()
                if (submitDispatched && now < nextSubmitRetryAt) return
                // The visible label itself is not clickable on current order
                // pages; its smallest clickable parent owns the submit action.
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) return
                submitDispatched = true
                submitRetryCount++
                // Three fast attempts on the order-confirmation CTA, but each
                // attempt re-scans the page first. If the cashier appears, the
                // branch above changes immediately and no final-pay tap leaks.
                nextSubmitRetryAt = now + 60L
                phaseStartTime = now
                userStatus(if (submitRetryCount == 1)
                    "已点击立即支付提交订单，正在等待收银台"
                else "订单确认页仍在，立即支付补点$submitRetryCount")
                return
            }
            if (!prefs.getBoolean("auto_pay", false)) {
                stop("已到收银台；按设置未点击支付，请手动确认")
                return
            }
            if (paymentDispatched) return
            val target = preciseActionTarget(pay)
            if (!running || target == null) return
            var clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                val bounds = Rect().also(pay::getBoundsInScreen)
                clicked = dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
            if (!clicked) return
            paymentDispatched = true
            stop("已点击支付按钮，请确认支付结果")
            return
        }
        val submit = snapshot.submit
        if (submit != null) {
            val target = preciseActionTarget(submit)
            if (!running || target == null) return
            val now = SystemClock.elapsedRealtime()
            if (submitDispatched && now < nextSubmitRetryAt) return
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) return
            submitDispatched = true
            submitRetryCount++
            // The shopping plugin can acknowledge a gesture while keeping the
            // confirmation page unchanged. Re-scan before every retry so a
            // cashier transition is consumed before another coordinate tap.
            nextSubmitRetryAt = now + 60L
            phaseStartTime = now
            userStatus(if (submitRetryCount == 1)
                "已点击提交订单，正在等待收银台"
            else "订单确认页仍在，提交订单补点$submitRetryCount")
            return
        }

        // dispatchGesture=true only means Android accepted the gesture. The
        // shopping plugin may still keep the SKU/order sheet on screen. Do
        // not wait for the checkout timeout: recover the still-visible CTA
        // and retry it until the page itself proves that navigation happened.
        val staleOrderSheetCta = snapshot.staleOrderSheetCta
        if (staleOrderSheetCta != null) {
            val label = textOf(staleOrderSheetCta)
            if (label.contains("请选择")) {
                phase = Phase.PRODUCT
                phaseStartTime = SystemClock.elapsedRealtime()
                userStatus("规格状态尚未生效，重新确认规格")
                return
            }
            if (label.contains("立即购买") || label.contains("马上抢") ||
                label.contains("确认购买")) {
                val now = SystemClock.elapsedRealtime()
                if (now < nextOrderSheetRetryAt) return
                val target = preciseActionTarget(staleOrderSheetCta) ?: return
                if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                orderSheetRetryCount++
                nextOrderSheetRetryAt = now + 60L
                phaseStartTime = now
                userStatus("商品弹层仍在，锁单按钮补点$orderSheetRetryCount")
            }
        }
    }

    private fun findProductRow(root: AccessibilityNodeInfo, number: Int): AccessibilityNodeInfo? {
        // Only an actual product card starts with "N号商品". Do not accept a
        // loose "N号" match: the cart header has a rolling notification such
        // as "某某抢购了 / 1号", which previously made us climb to the whole
        // list and tap another row's CTA.
        val exactPrefix = Regex("^\\s*${number}\\s*号商品(?:\\D|$)")
        val exactCards = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            val desc = node.contentDescription?.toString().orEmpty().trim()
            if (exactPrefix.containsMatchIn(desc)) {
                exactCards += node
            }
        }
        val expandedCards = exactCards.map(::expandProductCard)
        expandedCards.firstOrNull { node ->
            val b = Rect().also(node::getBoundsInScreen)
            node.isVisibleToUser && b.width() > 0 && b.height() > 0
        }?.let { return it }
        return expandedCards.firstOrNull()
    }

    /**
     * Main Douyin exposes "N号商品" as an empty semantic marker whose price
     * and CTA are siblings. Douyin Lite exposes the whole card as that node.
     * Climb only until the smallest ancestor containing a real buy action so
     * both layouts yield the complete single-product card.
     */
    private fun expandProductCard(marker: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = marker
        repeat(7) {
            val buy = findNode(current) { node ->
                isTargetBuyAction(textOf(node)) && node.isVisibleToUser && node.isEnabled
            }
            if (buy != null) return current
            val parent = current.parent ?: return marker
            // A parent containing another numbered product is already the
            // list/container, never a single product card.
            val containsOtherProduct = findNode(parent) { node ->
                val desc = node.contentDescription?.toString().orEmpty().trim()
                Regex("^\\s*\\d+\\s*号商品(?:\\D|$)").containsMatchIn(desc) &&
                    node !== marker && desc != marker.contentDescription?.toString().orEmpty().trim()
            } != null
            if (containsOtherProduct) return marker
            current = parent
        }
        return marker
    }

    private fun handleFirstSearchResult(row: AccessibilityNodeInfo) {
        // A second content-change callback may carry the same stale result
        // row. Once one candidate has won, SEARCH is permanently frozen until
        // PRODUCT explicitly times out and restores it.
        if (phase != Phase.SEARCH) return
        // Price safety must follow what is visibly rendered. A product card's
        // contentDescription is a synthesized readout and may lag behind the
        // promotional price shown by its TextViews.
        val validatePrice = prefs.getBoolean("validate_price", true)
        val maxPrice = if (validatePrice) prefs.getString("max_price", "")?.toDoubleOrNull() else null
        // Accessibility content events often carry the first card before its
        // price child is attached. Re-read the active-window card instead of
        // keeping that stale event snapshot when the first pass has no price.
        // A masked/pending price is also the visual disabled state of the CTA.
        // It must always block purchasing even when numeric price validation
        // is disabled in settings.
        val eventPendingPrice = validatePrice && hasPendingPrice(row)
        val eventPrice = if (!validatePrice || eventPendingPrice) null else extractVisiblePrice(row)
        val freshRoot = if (eventPrice == null) service.rootInActiveWindow else null
        val freshRow = freshRoot?.let {
            findProductRow(it, prefs.getInt("product_number", 1))
        }
        val decisionRow = freshRow ?: row
        val pendingPrice = validatePrice && hasPendingPrice(decisionRow)
        val visiblePrice = if (!validatePrice || pendingPrice) null else eventPrice
            ?: freshRow?.let(::extractVisiblePrice)
        val buyNode = findNode(decisionRow) { node ->
            val combined = textOf(node) + node.contentDescription?.toString().orEmpty()
            isTargetBuyAction(combined) && node.isVisibleToUser && node.isEnabled
        }
        val buyTarget = buyNode?.let(::preciseActionTarget)
            ?.takeIf { it.isEnabled && it.isClickable }
        val buttonText = buyNode?.let(::textOf)?.trim()?.ifBlank { "抢购按钮" } ?: "无抢购文字"
        val decision = when {
            pendingPrice -> "仍待开价，继续刷新小黄车"
            !validatePrice && (buyNode == null || buyTarget == null) -> "不校验价格，等待可抢按钮"
            !validatePrice -> "不校验价格，立即点击"
            maxPrice != null && visiblePrice == null -> "未读到页面价格，继续刷新小黄车"
            maxPrice != null && visiblePrice != null && visiblePrice > maxPrice -> "价格超限，继续刷新小黄车"
            buyNode == null || buyTarget == null -> "按钮不可点击，继续刷新小黄车"
            else -> "条件通过，立即点击"
        }
        val decisionLog = "${prefs.getInt("product_number", 1)}号商品：$decision"
        if (decisionLog != lastProductDecisionLog) {
            lastProductDecisionLog = decisionLog
            userStatus(decisionLog)
        }

        if (pendingPrice || (validatePrice && maxPrice != null && visiblePrice == null)) {
            forceRefreshSearch(if (pendingPrice) "页面显示￥??/待开价" else "仍待开价")
            return
        }
        if (maxPrice != null && visiblePrice != null && visiblePrice > maxPrice) {
            forceRefreshSearch("当前价￥$visiblePrice，高于上限￥$maxPrice")
            showWaitingHint("第一个商品价格未通过￥$maxPrice 上限，继续监测...")
            return
        }
        if (buyNode == null || buyTarget == null) {
            // An eligible price without a CTA means this cart snapshot is not
            // purchasable yet. Never wait on the stale open list: reopening is
            // the refresh operation that can expose the newly enabled button.
            forceRefreshSearch("价格已符合但抢购按钮尚未出现")
            return
        }
        // Re-read from the current active window immediately before touching
        // the screen. Event nodes can already be stale while a cart row is
        // animating or refreshing.
        val productNumber = prefs.getInt("product_number", 1)
        val freshAction = resolveFreshBuyAction(productNumber) ?: run {
            lastProductDecisionLog = ""
            showWaitingHint("抢购按钮正在刷新，等待新节点")
            return
        }
        val freshPendingPrice = validatePrice && hasPendingPrice(freshAction.row)
        val freshPrice = if (validatePrice && !freshPendingPrice) {
            extractVisiblePrice(freshAction.row)
        } else null
        if (freshPendingPrice || (validatePrice && maxPrice != null && freshPrice == null)) {
            forceRefreshSearch(if (freshPendingPrice) "页面显示￥??/待开价" else "仍待开价")
            return
        }
        if (maxPrice != null && freshPrice != null && freshPrice > maxPrice) {
            forceRefreshSearch("当前价￥$freshPrice，高于上限￥$maxPrice")
            return
        }
        val bounds = Rect().also(freshAction.target::getBoundsInScreen)
        val rowBounds = Rect().also(freshAction.row::getBoundsInScreen)
        userStatus("已锁定${productNumber}号商品：按钮${freshAction.label.ifBlank { buttonText }}，卡片[${rowBounds.left},${rowBounds.top}][${rowBounds.right},${rowBounds.bottom}]，点击${bounds.centerX()},${bounds.centerY()}")
        // Freeze first, click second. ACTION_CLICK can synchronously cause a
        // content event; changing phase afterwards allowed that event to run
        // SEARCH once more and overwrite the attempted navigation.
        phase = Phase.PRODUCT
        phaseStartTime = SystemClock.elapsedRealtime()
        searchState = SearchState.IDLE
        handler.removeCallbacksAndMessages(null)
        scheduled = false
        purchaseNavigationArmed = true
        buyTransitionSeenAt = 0L
        // Start with one click. PRODUCT scans may add at most one state-driven
        // retry, spaced apart, and stop immediately on transition evidence.
        // Operate the freshly refreshed, explicitly clickable accessibility
        // node. Page transition is verified separately in PRODUCT phase; a
        // reported click success is never treated as proof of navigation.
        val firstClicked = freshAction.target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        buyClickAttempts = 1
        nextBuyClickAt = SystemClock.elapsedRealtime() + BUY_RETRY_INTERVAL_MS
        phaseStartTime = SystemClock.elapsedRealtime()
        userStatus(
            if (firstClicked)
                "价格符合，已单击去抢购；仅在按钮仍存在时限频补点"
            else "首次点击未被系统确认；仅在按钮仍存在时限频补点"
        )
    }

    private fun forceRefreshSearch(reason: String, currentRoot: AccessibilityNodeInfo? = null) {
        if (!running || phase != Phase.SEARCH) return
        val root = currentRoot ?: findDouyinRoot(requireActive = true) ?: return
        if (findCartPanel(root) == null) {
            schedule(400L)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (cartCloseRequestedAt != 0L && now - cartCloseRequestedAt < 1_200L) return
        cartRefreshRounds++
        userStatus("${reason}，正在重新打开小黄车")
        searchState = SearchState.IDLE
        partialResultSeenAt = 0L
        lastCartScrollAt = 0L
        val closing = service.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        )
        if (closing) {
            cartCloseRequestedAt = now
            cartOpenedAt = 0L
            schedule(400L)
        } else {
            userStatus("小黄车刷新失败，请保持直播间在前台")
            schedule(800L)
        }
    }

    private fun skuCandidates(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val skuModule = findSkuModule(root) ?: return emptyList()
        walk(skuModule) { node ->
            val desc = node.contentDescription?.toString().orEmpty()
            val subtreeText = collectText(node)
            val bounds = Rect().also(node::getBoundsInScreen)
            val isSkuButton = node.className?.toString() == "android.widget.Button" &&
                desc.startsWith("未选择")
            val unavailable = subtreeText.contains("缺货") || subtreeText.contains("已售罄") ||
                subtreeText.contains("不可选") || subtreeText.contains("无货")
            if (isSkuButton && node.isVisibleToUser && node.isEnabled &&
                !node.isSelected && !unavailable && bounds.width() > 0 && bounds.height() > 0) {
                out += node
            }
        }
        return out.distinctBy { Rect().also(it::getBoundsInScreen) }
            .sortedWith(compareBy(
                { Rect().also(it::getBoundsInScreen).top },
                { Rect().also(it::getBoundsInScreen).left }
            ))
    }

    private fun structuralSkuCandidates(
        root: AccessibilityNodeInfo,
        bottomCta: AccessibilityNodeInfo
    ): List<AccessibilityNodeInfo> {
        val ctaTop = Rect().also(bottomCta::getBoundsInScreen).top
        val panel = findNode(root) {
            it.viewIdResourceName.orEmpty().endsWith(":id/fl_sku_select_container")
        } ?: return emptyList()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walk(panel) { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = collectText(node)
            val unavailable = label.contains("缺货") || label.contains("已售罄") ||
                label.contains("不可选") || label.contains("无货")
            if (node.isVisibleToUser && node.isEnabled && node.isClickable && node.isCheckable &&
                !unavailable && label.isNotBlank() && bounds.width() > dp(40) &&
                bounds.height() > dp(30) && bounds.bottom <= ctaTop) {
                candidates += node
            }
        }
        return candidates.distinctBy { Rect().also(it::getBoundsInScreen) }
            .sortedWith(compareBy({ Rect().also(it::getBoundsInScreen).top },
                { Rect().also(it::getBoundsInScreen).left }))
    }

    private fun findSkuModule(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val chooseCta = findNode(root) { node ->
            val label = textOf(node)
            val bounds = Rect().also(node::getBoundsInScreen)
            label.contains("请选择") && bounds.centerY() > service.resources.displayMetrics.heightPixels * 3 / 4
        }
        val ctaTop = chooseCta?.let { Rect().also(it::getBoundsInScreen).top }
            ?: service.resources.displayMetrics.heightPixels

        // Across the observed templates (small chips, image cards and full
        // width rows), the stable boundary is the RecyclerView containing
        // semantic "未选择..." SKU buttons. Use the whole recycler as module.
        val skuRecycler = findNode(root) { node ->
            node.className?.toString()?.contains("RecyclerView") == true &&
                findNode(node) { child ->
                    val desc = child.contentDescription?.toString().orEmpty()
                    val bounds = Rect().also(child::getBoundsInScreen)
                    child.className?.toString() == "android.widget.Button" &&
                        desc.startsWith("未选择") && bounds.top < ctaTop
                } != null
        }
        if (skuRecycler != null) return skuRecycler

        // SKU names and section titles vary by product. The stable semantic
        // feature is the selectable Button whose accessibility description is
        // "未选择 + option name". Derive its module from the hierarchy.
        val anchors = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            val desc = node.contentDescription?.toString().orEmpty()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.className?.toString() == "android.widget.Button" &&
                desc.startsWith("未选择") && node.isVisibleToUser &&
                skuActionTarget(node) != null && node.isEnabled && bounds.bottom < ctaTop &&
                bounds.centerY() > service.resources.displayMetrics.heightPixels / 3) {
                anchors += node
            }
        }
        // Bottom-up: the option group nearest the bottom CTA wins.
        val anchor = anchors.maxByOrNull { Rect().also(it::getBoundsInScreen).bottom }
            ?: return null

        var item = anchor
        repeat(10) {
            val parent = item.parent ?: return root
            if (parent.className?.toString()?.contains("RecyclerView") == true) {
                return parent
            }
            item = parent
        }
        // Image-card SKU layouts (for example instrument tone choices) use
        // ordinary ViewGroups instead of a RecyclerView. The semantic
        // "未选择 + option" buttons are still reliable; scan the page root
        // and let skuCandidates keep only visible/enabled SKU anchors.
        return root
    }

    private fun skuActionTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isClickable && node.isEnabled) return node
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        var target = node
        repeat(4) {
            val bounds = Rect().also(target::getBoundsInScreen)
            if (target.isVisibleToUser && target.isClickable && target.isEnabled &&
                bounds.width() in dp(20)..screenWidth &&
                bounds.height() in dp(20)..(screenHeight / 3)) {
                return target
            }
            target = target.parent ?: return null
        }
        return null
    }

    /** A SKU is actionable only when Android exposes a real click action. */
    private fun skuClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        repeat(5) {
            val current = target ?: return null
            val hasClickAction = current.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }
            if (current.isVisibleToUser && current.isEnabled && current.isClickable && hasClickAction) {
                return current
            }
            target = current.parent
        }
        return null
    }

    private fun structuralSkuGroups(
        root: AccessibilityNodeInfo,
        bottomCta: AccessibilityNodeInfo
    ): List<SkuGroup> {
        val screenHeight = service.resources.displayMetrics.heightPixels
        val ctaTop = Rect().also(bottomCta::getBoundsInScreen).top
        val options = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            val desc = node.contentDescription?.toString().orEmpty()
            val stateSemantic = desc.startsWith("未选择") || desc.startsWith("已选择")
            val structuralSemantic = node.isCheckable || node.isChecked || node.isSelected
            if (node.className?.toString() == "android.widget.Button" &&
                node.isVisibleToUser && node.isEnabled && bounds.width() > 0 &&
                bounds.height() > 0 && bounds.bottom <= ctaTop &&
                bounds.centerY() > screenHeight / 3 &&
                (stateSemantic || structuralSemantic)) {
                options += node
            }
        }
        if (options.isEmpty()) return emptyList()

        val sorted = options.distinctBy { Rect().also(it::getBoundsInScreen) }
            .sortedWith(compareBy(
                { Rect().also(it::getBoundsInScreen).top },
                { Rect().also(it::getBoundsInScreen).left }
            ))
        val bands = mutableListOf<MutableList<AccessibilityNodeInfo>>()
        sorted.forEach { option ->
            val bounds = Rect().also(option::getBoundsInScreen)
            val band = bands.lastOrNull()
            if (band == null) {
                bands += mutableListOf(option)
            } else {
                val previousBounds = Rect().also(band.last()::getBoundsInScreen)
                val rowStep = bounds.top - previousBounds.top
                val sameGroupLimit = maxOf(
                    dp(48),
                    maxOf(bounds.height(), previousBounds.height()) * 3 / 2
                )
                if (rowStep <= sameGroupLimit) band += option
                else bands += mutableListOf(option)
            }
        }
        return bands.mapIndexed { index, groupOptions ->
            SkuGroup("sku-group-$index", groupOptions)
        }
    }

    private fun isSkuSelected(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString().orEmpty()
        return node.isSelected || node.isChecked || desc.startsWith("已选择")
    }

    private fun isSkuAvailable(node: AccessibilityNodeInfo): Boolean {
        val label = collectText(node)
        return node.isVisibleToUser && node.isEnabled && !isSkuSelected(node) &&
            !label.contains("缺货") && !label.contains("已售罄") &&
            !label.contains("不可选") && !label.contains("无货")
    }

    private fun skuAttemptKey(groupKey: String, node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "$groupKey|${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}"
    }

    private fun skuDisplayName(node: AccessibilityNodeInfo): String {
        val label = textOf(node).removePrefix("未选择").removePrefix("已选择").trim()
        return label.ifBlank { "可用规格" }
    }

    private fun userStatus(message: String, force: Boolean = false) {
        val clean = message.trim().removePrefix("[抢购]").trim()
        if (!force && clean == lastUserStatus) return
        lastUserStatus = clean
        service.writeRuntimeLog("[抢购] $clean")
    }

    private fun formatPrice(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

    private fun isDouyinPackage(packageName: CharSequence?): Boolean {
        val value = packageName?.toString().orEmpty()
        return value == DOUYIN || value == DOUYIN_LITE
    }

    /**
     * Running actions require an active/focused Douyin window. The relaxed
     * variant is used only while our focusable settings overlay is visible.
     */
    private fun findDouyinRoot(requireActive: Boolean): AccessibilityNodeInfo? {
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null && isDouyinPackage(activeRoot.packageName)) return activeRoot

        var fallback: AccessibilityNodeInfo? = null
        var fallbackLayer = Int.MIN_VALUE
        for (window in service.windows) {
            val root = window.root ?: continue
            if (!isDouyinPackage(root.packageName)) continue
            if (window.isActive || window.isFocused) return root
            if (!requireActive && window.layer > fallbackLayer) {
                fallback = root
                fallbackLayer = window.layer
            }
        }
        return fallback
    }

    private fun findCartPanel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val exactPanel = findNode(root) { node ->
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val bounds = Rect().also(node::getBoundsInScreen)
            desc == "商品列表" && node.isVisibleToUser &&
                bounds.width() >= service.resources.displayMetrics.widthPixels * 3 / 4 &&
                bounds.height() >= service.resources.displayMetrics.heightPixels / 2
        }
        if (exactPanel != null) return exactPanel

        // During the opening animation the large panel has no description,
        // while numbered product cards are already present. Derive the panel
        // from that stable card structure instead of clicking the cart again.
        val card = findNode(root) { node ->
            val desc = node.contentDescription?.toString().orEmpty().trim()
            node.isVisibleToUser && Regex("^\\d+号商品(?:\\s|[^0-9]|$)").containsMatchIn(desc)
        } ?: return null
        var container = card
        repeat(8) {
            val parent = container.parent ?: return container
            val bounds = Rect().also(parent::getBoundsInScreen)
            if (bounds.width() >= service.resources.displayMetrics.widthPixels * 3 / 4 &&
                bounds.height() >= service.resources.displayMetrics.heightPixels / 3) {
                return parent
            }
            container = parent
        }
        return container
    }

    private fun hasLiveRoomProductUi(root: AccessibilityNodeInfo): Boolean {
        if (!isDouyinPackage(root.packageName)) return false
        if (findCartPanel(root) != null) return true
        return findNode(root) { node ->
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val text = node.text?.toString().orEmpty().trim()
            val bounds = Rect().also(node::getBoundsInScreen)
            val semantic = desc == "商品列表" || desc == "小黄车" ||
                desc == "购物车" || text == "商品列表" || text == "小黄车" || text == "购物车"
            semantic && node.isVisibleToUser && node.isEnabled &&
                bounds.centerY() > service.resources.displayMetrics.heightPixels * 2 / 3 &&
                bounds.width() in dp(28)..dp(280) && bounds.height() in dp(28)..dp(280)
        } != null
    }

    private fun extractProductTitle(row: AccessibilityNodeInfo): String {
        val titleNode = findNode(row) { node ->
            val id = node.viewIdResourceName.orEmpty()
            node.isVisibleToUser &&
                (id.endsWith(":id/tv_title") || id.endsWith(":id/product_title")) &&
                textOf(node).isNotBlank()
        }
        return titleNode?.let(::textOf)?.trim()
            ?: row.contentDescription?.toString().orEmpty().trim()
    }

    private data class ParsedPrice(val value: Double, val priority: Int, val top: Int)

    private fun extractPreferredPrice(root: AccessibilityNodeInfo, allowStartingPrice: Boolean): Double? {
        val candidates = mutableListOf<ParsedPrice>()
        val labelled = Regex(
            "(?:实付款|应付金额|应付款|合计|订单金额|商品金额|到手价|现价|价格)[^0-9]{0,10}(\\d+(?:\\.\\d+)?)"
        )
        val currency = Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)")
        val cardPrice = Regex("(\\d+(?:\\.\\d+)?)\\s*元(?:起)?(?:原价|$)")
        // Main Douyin exposes a card price as separate sibling nodes, e.g.
        // "¥", "13.8", "起". Parse the card's combined visible text before
        // examining individual nodes (Douyin Lite usually keeps it together).
        if (allowStartingPrice) {
            val combined = collectVisibleText(root).replace(",", "")
            currency.findAll(combined).mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .filter { it.isFinite() && it >= 0.0 }
                .forEach { candidates += ParsedPrice(it, 3, 0) }
        }
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            val id = node.viewIdResourceName.orEmpty()
            val values = listOfNotNull(
                node.text?.toString()?.trim(),
                node.contentDescription?.toString()?.trim()
            ).filter(String::isNotBlank).distinct()
            for (rawValue in values) {
                val raw = rawValue.replace(",", "")
                val finalLabel = raw.contains("实付款") || raw.contains("应付") ||
                    raw.contains("合计") || raw.contains("订单金额") || raw.contains("商品金额")
                // Product cards often expose only one synthesized sentence:
                // "...48元" or "...2588元起原价3688元". Parse the
                // current price before applying the original-price exclusion;
                // the required 元 suffix prevents viewer/add-to-cart counts
                // from being interpreted as prices.
                if (allowStartingPrice) {
                    val currentPart = raw.substringBefore("原价")
                    val cardMatch = cardPrice.find(currentPart)
                    val cardValue = cardMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    if (cardValue != null && cardValue.isFinite() && cardValue >= 0.0) {
                        candidates += ParsedPrice(cardValue, 2, bounds.top)
                        continue
                    }
                }
                val excluded = !finalLabel && (
                    raw.contains("原价") || raw.contains("划线价") || raw.contains("市场价") ||
                        raw.contains("吊牌价") || raw.contains("优惠券") || raw.contains("满减") ||
                        raw.contains("立减") || raw.contains("补贴") || raw.contains("定金")
                    )
                if (excluded || (!allowStartingPrice && raw.contains("起"))) continue

                val labelledMatches = labelled.findAll(raw).mapNotNull {
                    it.groupValues[1].toDoubleOrNull()
                }.filter { it.isFinite() && it >= 0.0 }.toList()
                if (labelledMatches.isNotEmpty()) {
                    val priority = when {
                        finalLabel -> 0
                        id.endsWith(":id/a2y") -> 1
                        else -> 2
                    }
                    labelledMatches.forEach { candidates += ParsedPrice(it, priority, bounds.top) }
                    continue
                }
                currency.findAll(raw).mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .filter { it.isFinite() && it >= 0.0 }
                    .forEach { candidates += ParsedPrice(it, 3, bounds.top) }
            }
        }
        return candidates.minWithOrNull(compareBy<ParsedPrice>({ it.priority }, { it.top }))?.value
    }

    private fun priceAllowed(text: String, max: Double): Boolean {
        if (max <= 0) return true
        // Extract exact numbers directly following the ¥ or ￥ currency symbol (e.g. ¥ 988, ¥129, ¥29.90)
        val currencyPrices = Regex("[\\u00a5\\uffe5]\\s*(\\d+(?:\\.\\d+)?)")
            .findAll(text.replace(",", ""))
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()
        val describedPrices = Regex("(?:现价|到手价|价格)\\s*(\\d+(?:\\.\\d+)?)\\s*元")
            .findAll(text.replace(",", ""))
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()
        val prices = if (describedPrices.isNotEmpty()) describedPrices else currencyPrices

        return if (prices.isNotEmpty()) {
            val actualPrice = prices.minOrNull() ?: Double.MAX_VALUE
            actualPrice <= max
        } else {
            // STRICT SAFETY: If max price is specified, BUT no ¥ price number could be parsed from row text,
            // we MUST return false to prevent accidentally bypassing price protection!
            false
        }
    }

    private fun isTargetBuyAction(text: String): Boolean {
        // “找同类/找同款”是无库存后的替代入口，任何包含它们的
        // 节点或聚合父节点都不能被当成抢购按钮。
        if (text.contains("找同类") || text.contains("找同款")) return false
        return text.contains("\u53bb\u62a2\u8d2d") || text.contains("\u9886\u5238\u62a2\u8d2d") ||
            text.contains("\u7acb\u5373\u62a2\u8d2d") || text.contains("\u9a6c\u4e0a\u62a2") ||
            text.contains("\u7acb\u5373\u8d2d\u4e70")
    }

    private fun isBuyAction(text: String): Boolean =
        isTargetBuyAction(text) || text.contains("找同款")

    private fun showWaitingHint(@Suppress("UNUSED_PARAMETER") message: String) {
        // Polling/refresh hints are intentionally silent. They can run several
        // times per second and must not perform main-thread file I/O.
    }

    private fun extractVisiblePrices(text: String): List<Double> =
        Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)")
            .findAll(text.replace(",", ""))
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()

    private fun extractVisiblePrice(row: AccessibilityNodeInfo): Double? =
        extractPreferredPrice(row, allowStartingPrice = true)

    private fun textOf(node: AccessibilityNodeInfo): String =
        node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()

    private fun collectText(node: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        walk(node) {
            it.text?.let { value -> out.append(' ').append(value) }
            it.contentDescription?.let { value -> out.append(' ').append(value) }
        }
        return out.toString()
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        walk(node) {
            if (it.isVisibleToUser) {
                it.text?.let { value -> out.append(' ').append(value) }
            }
        }
        return out.toString()
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        repeat(5) {
            if (target.isClickable) return target
            target = target.parent ?: return target
        }
        return target
    }

    private fun preciseActionTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenWidth = service.resources.displayMetrics.widthPixels
        var target = node
        repeat(4) {
            val bounds = Rect().also(target::getBoundsInScreen)
            if (target.isVisibleToUser && target.isEnabled && target.isClickable &&
                bounds.width() in dp(28)..screenWidth &&
                bounds.height() in dp(24)..dp(260)) {
                return target
            }
            target = target.parent ?: return null
        }
        return null
    }

    private fun resolveFreshBuyAction(number: Int): FreshBuyAction? {
        val root = findDouyinRoot(requireActive = true) ?: return null
        val row = findProductRow(root, number) ?: return null
        val buyNode = findNode(row) { node ->
            val label = textOf(node) + node.contentDescription?.toString().orEmpty()
            isTargetBuyAction(label) && node.isVisibleToUser && node.isEnabled
        } ?: return null
        val target = preciseActionTarget(buyNode) ?: return null
        if (!runCatching { target.refresh() }.getOrDefault(false)) return null
        val supportsClick = target.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_CLICK
        }
        if (!target.isVisibleToUser || !target.isEnabled || !target.isClickable || !supportsClick) {
            return null
        }
        val rowBounds = Rect().also(row::getBoundsInScreen)
        val targetBounds = Rect().also(target::getBoundsInScreen)
        if (rowBounds.width() <= 0 || rowBounds.height() <= 0 ||
            targetBounds.width() <= 0 || targetBounds.height() <= 0 ||
            !rowBounds.contains(targetBounds.centerX(), targetBounds.centerY())) {
            return null
        }
        val label = (textOf(buyNode) + buyNode.contentDescription?.toString().orEmpty()).trim()
        if (!isTargetBuyAction(label)) return null
        return FreshBuyAction(row, target, label)
    }

    private fun findNode(node: AccessibilityNodeInfo,
                         predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    private inline fun walk(node: AccessibilityNodeInfo,
                            visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            visit(current)
            for (i in current.childCount - 1 downTo 0) current.getChild(i)?.let(stack::add)
        }
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (!running) return false
        val expectedPhase = phase
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        if (x < 0f || y < 0f || x >= screenWidth || y >= screenHeight) return false
        if (findDouyinRoot(requireActive = true) == null || phase != expectedPhase) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 20))
            .build()
        val generation = sessionGeneration
        return if (running && generation == sessionGeneration && phase == expectedPhase &&
            findDouyinRoot(requireActive = true) != null) {
            service.dispatchGesture(gesture, null, null)
        } else false
    }

    /** Visible masked prices override hidden/stale numeric accessibility data. */
    private fun hasPendingPrice(row: AccessibilityNodeInfo): Boolean {
        var pending = false
        walk(row) { node ->
            if (pending || !node.isVisibleToUser) return@walk
            val visibleText = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
            val normalized = visibleText.replace(" ", "")
            pending = normalized.contains("待开价") || normalized.contains("即将开价") ||
                Regex("[¥￥][?？﹖*＊]+\\d*").containsMatchIn(normalized)
        }
        return pending
    }

    private fun dp(value: Int): Int =
        Math.round(value * service.resources.displayMetrics.density)
}
