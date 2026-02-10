package io.relimus.zflow.xposed.ui.window

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import com.qauxv.ui.CommonContextWrapper
import dev.rikka.tools.refine.Refine
import io.github.kyuubiran.ezxhelper.core.misc.paramTypes
import io.github.kyuubiran.ezxhelper.core.misc.params
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.R
import io.relimus.zflow.databinding.ViewFreeformFlymeBinding
import io.relimus.zflow.hook.utils.XLog
import io.relimus.zflow.ui.freeform.FreeformHelper
import io.relimus.zflow.xposed.services.FreeformManager
import io.relimus.zflow.xposed.utils.Instances
import java.lang.reflect.Field
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * FreeformWindow runs in system_server with elevated privileges.
 * Creates VirtualDisplay and floating overlay window with higher z-order priority.
 * This solves the issue of window disappearing when system UI components appear.
 */
@SuppressLint("ClickableViewAccessibility")
class FreeformWindow(
    baseContext: Context,
    val componentName: ComponentName?,
    private val userId: Int,
    private val taskId: Int,
    private val config: FreeformWindowConfig = FreeformWindowConfig()
) : TextureView.SurfaceTextureListener {
    companion object {
        private const val TAG = "FreeformWindow"
        private const val WIDTH_HEIGHT_RATIO = 20f / 35f

        // 虚拟显示屏方向常量
        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0
    }

    private val context: Context = CommonContextWrapper.createAppCompatContext(baseContext)
    private lateinit var binding: ViewFreeformFlymeBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var backgroundView: View

    var displayId: Int = -1
        private set

    private var windowLayoutParams = WindowManager.LayoutParams()
    private var backgroundLayoutParams = WindowManager.LayoutParams()

    // 屏幕方向 - 使用 Surface.ROTATION_* 常量
    private var screenRotation: Int = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    // 虚拟显示屏方向 - 小窗内应用的方向
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // 屏幕方向变化监听
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val newRotation = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
                if (newRotation != screenRotation) {
                    screenRotation = newRotation
                    mainHandler.post {
                        onScreenOrientationChanged()
                    }
                }
            }
        }
    }

    // 屏幕尺寸 - 根据方向返回正确的宽高
    private val realScreenWidth: Int
        get() {
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            return if (screenIsPortrait()) min(width, height) else max(width, height)
        }
    private val realScreenHeight: Int
        get() {
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            return if (screenIsPortrait()) max(width, height) else min(width, height)
        }

    // 窗口尺寸 (用于显示) - 动态计算，考虑小窗内应用方向
    private val rootWidth: Int
        get() {
            var tmp = if (screenIsPortrait()) realScreenWidth else realScreenHeight
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = realScreenWidth
            }
            return tmp
        }
    private val rootHeight: Int
        get() {
            var tmp = if (screenIsPortrait()) realScreenHeight else realScreenWidth
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = ((rootWidth * WIDTH_HEIGHT_RATIO) + cardHeightMargin).roundToInt()
                if (!screenIsPortrait()) {
                    tmp = realScreenHeight
                }
            }
            return tmp
        }

    // Margins - 根据屏幕方向动态计算
    private val cardHeightMargin: Float
        get() = if (screenIsPortrait()) (barHeight + freeformShadow) else 0f
    private val cardWidthMargin: Float
        get() = if (screenIsPortrait()) 0f else barHeight

    // 小窗内部尺寸 (VirtualDisplay)
    private var freeformScreenWidth = 0
    private var freeformScreenHeight = 0
    private var freeformDpi = 320

    // 当前窗口缩放后的尺寸
    private var freeformWidth = 0
    private var freeformHeight = 0

    // 缩放比例
    private var mScaleX = 1f
        set(value) {
            field = value
            binding.freeformRoot.scaleX = value
        }
    private var mScaleY = 1f
        set(value) {
            field = value
            binding.freeformRoot.scaleY = value
        }

    // 触摸坐标缩放
    private var scaleX = 1f
    private var scaleY = 1f

    // 缩放阈值
    private var goFloatScale = 0.6f
    private var goFullScale = 0.9f

    // 悬浮模式尺寸
    private var hangUpViewWidth = 0
    private var hangUpViewHeight = 0

    // Bar 高度
    private val barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private val freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private val cardCornerRadius: Float = context.resources.getDimension(R.dimen.card_corner_radius)

    // 侧边栏按钮尺寸 - 缓存避免在动画回调中访问资源导致崩溃
    private val floatingButtonWidth: Int = context.resources.getDimension(R.dimen.floating_button_width).toInt()
    private val floatingButtonHeight: Int = context.resources.getDimension(R.dimen.floating_button_height).toInt()

    // 屏幕边距
    private val screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private val screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    // 状态
    var isDestroyed = false
        private set
    private var updateFrameCount = 0
    private var initFinish = false
    var isFloating = false
        private set
    private var isZoomOut = false
    var isHidden = false
        private set
    private var isAnimating = false

    // 挂起位置，0：是否在左，1：是否在上
    private val hangUpPosition = booleanArrayOf(false, true)
    // 存储上一次的悬浮位置
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)

    // 侧边栏视图
    private lateinit var hiddenView: View

    // 触摸状态
    private var lastX = -1f
    private var lastY = -1f
    private var touchId = -1

    // 手势检测器 - 背景点击关闭
    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isFloating) {
                destroyWithAnimation()
            }
            return true
        }
    })

    // 手势检测器 - bar 双击切换小窗内应用方向
    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (config.manualAdjustFreeformRotation) {
                virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                    VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                } else {
                    VIRTUAL_DISPLAY_ROTATION_PORTRAIT
                }
                onFreeFormRotationChanged()
            }
            return false
        }
    })

    init {
        XLog.d("$TAG: FreeformWindow constructor called, componentName=$componentName, userId=$userId, taskId=$taskId")
        try {
            initConfig()
            initWindow()
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to initialize FreeformWindow", e)
        }
    }

    /**
     * 判断屏幕是否是竖屏
     */
    private fun screenIsPortrait(): Boolean {
        return screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180
    }

    private fun initConfig() {
        // 从配置读取 DPI
        freeformDpi = if (config.freeformDpi > 50) config.freeformDpi else getScreenDpi()

        // 小窗内部尺寸
        freeformScreenHeight = (min(realScreenHeight, realScreenWidth) / WIDTH_HEIGHT_RATIO).roundToInt()
        freeformScreenWidth = (freeformScreenHeight * WIDTH_HEIGHT_RATIO).roundToInt()

        // 初始化悬浮模式尺寸
        initFloatViewSize()

        // 初始化小窗显示尺寸
        refreshFreeformSize()

        // 缩放阈值
        refreshActionScale()

        XLog.d("$TAG: Config - dpi=$freeformDpi, freeformSize=${freeformWidth}x${freeformHeight}, screenSize=${freeformScreenWidth}x${freeformScreenHeight}, portrait=${screenIsPortrait()}")
    }

    /**
     * 初始化悬浮模式尺寸 - 参考 FreeformView.initFloatViewSize
     * 支持小窗内应用横屏时的尺寸计算
     * mini 状态下 cardViewMarginAnim 会将 margin 动画到 0，故使用简化公式
     */
    private fun initFloatViewSize() {
        hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
        hangUpViewWidth = (hangUpViewHeight * WIDTH_HEIGHT_RATIO).roundToInt()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            hangUpViewWidth = (realScreenHeight * config.floatViewSize).roundToInt()
            hangUpViewHeight = (hangUpViewWidth * WIDTH_HEIGHT_RATIO).roundToInt()
            if (!screenIsPortrait()) {
                hangUpViewWidth = (realScreenWidth * config.floatViewSize).roundToInt()
                hangUpViewHeight = (hangUpViewWidth * WIDTH_HEIGHT_RATIO).roundToInt()
            }
        }
    }

    /**
     * 刷新小窗显示尺寸 - 参考 FreeformView.refreshFreeformSize
     * 修复宽高比计算问题
     */
    private fun refreshFreeformSize() {
        if (screenIsPortrait()) {
            // 竖屏状态，以宽度为基准计算，确保宽高比正确
            freeformWidth = (rootWidth * config.freeformSize).roundToInt()
            val contentHeight = (freeformWidth - (freeformShadow * 2)) / WIDTH_HEIGHT_RATIO
            freeformHeight = (contentHeight + cardHeightMargin).roundToInt()
        } else {
            // 横屏状态
            freeformHeight = (rootWidth * config.freeformSizeLand).roundToInt()
            freeformHeight += cardHeightMargin.roundToInt()
            freeformWidth = ((freeformHeight + cardWidthMargin) * WIDTH_HEIGHT_RATIO).roundToInt()
        }
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            // 内部应用横屏时，小窗变成宽扁形态 - 移植自 FreeformView.kt
            if (screenIsPortrait()) {
                freeformWidth = (rootWidth - (rootWidth * 0.05)).roundToInt()
                freeformHeight = ((freeformWidth + (cardHeightMargin * 2)) * WIDTH_HEIGHT_RATIO) .roundToInt()
            } else {
                freeformWidth = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                freeformHeight = ((freeformWidth * WIDTH_HEIGHT_RATIO) * 0.95).roundToInt()
            }
        }
    }

    /**
     * 刷新缩放阈值 - 完全复刻 FreeformView.refreshActionScale
     * goFloatScale 基于 freeformHeight * 0.8
     */
    private fun refreshActionScale() {
        goFloatScale = (freeformHeight * 0.8f) / rootHeight
        goFullScale = (freeformHeight * 1.1f) / rootHeight
    }

    private fun getScreenDpi(): Int {
        return context.resources.displayMetrics.densityDpi
    }

    private fun refreshScale() {
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
    }

    private fun refreshTouchScale() {
        scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
        scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
    }

    private fun initWindow() {
        XLog.d("$TAG: initWindow: Creating context wrapper")
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
        XLog.d("$TAG: initWindow: Context wrapper created, inflating layout")
        binding = ViewFreeformFlymeBinding.inflate(LayoutInflater.from(wrappedContext))
        XLog.d("$TAG: initWindow: Layout inflated")

        // 创建背景 View (点击关闭)
        backgroundView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        backgroundGestureDetector.onTouchEvent(event)
                    }
                }
                true
            }
        }

        // 横屏时调整布局
        if (!screenIsPortrait()) {
            hangUpPosition[0] = true
            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                topMargin = 0
                bottomMargin = 0
                rightMargin = barHeight.roundToInt()
            }
        }

        // 背景 LayoutParams
        backgroundLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = config.dimAmount
        }

        // 窗口 LayoutParams
        windowLayoutParams = WindowManager.LayoutParams(
            rootWidth,
            rootHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        // 横屏时调整窗口位置
        if (!screenIsPortrait()) {
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
        }

        // 添加视图
        Instances.windowManager.addView(backgroundView, backgroundLayoutParams)
        Instances.windowManager.addView(binding.root, windowLayoutParams)

        // 设置 TextureView
        binding.textureView.surfaceTextureListener = this
        binding.textureView.alpha = 0f

        // 初始化缩放
        refreshScale()
        refreshTouchScale()

        // 初始化控制栏（根据屏幕方向）
        initFloatBar()

        // 设置触摸处理
        setupTouchHandlers()
        setupControlBar()

        // 注册屏幕方向变化监听
        Instances.displayManager.registerDisplayListener(displayListener, mainHandler)

        // 禁用窗口移动动画
        setWindowNoUpdateAnimation()

        XLog.d("$TAG: FreeformWindow initialized, waiting for surface")
    }

    /**
     * 生成居中位置 - 参考 FreeformView.genCenterLocation
     * 支持小窗内应用横屏时的位置计算
     */
    private fun genCenterLocation(): IntArray {
        val center = intArrayOf(0, 0)
        if (!screenIsPortrait()) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
            if (!hangUpPosition[0]) {
                center[0] = (freeformWidth - rootHeight + screenPaddingX) / -2
            }
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / 2
                if (!hangUpPosition[0]) {
                    center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / -2
                }
            }
        }
        return center
    }

    /**
     * 初始化控制栏 - 参考 FreeformView.initFloatBar
     */
    private fun initFloatBar() {
        if (screenIsPortrait()) {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    barHeight.roundToInt(),
                ).apply {
                    topToBottom = R.id.cardRoot
                    startToEnd = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.VISIBLE
                sideView.visibility = View.GONE
            }
        } else {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    barHeight.roundToInt(),
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToEnd = R.id.cardRoot
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.GONE
                sideView.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 屏幕方向变化处理 - 参考 FreeformView.onScreenOrientationChanged
     */
    private fun onScreenOrientationChanged() {
        if (isDestroyed) return

        initFloatViewSize()
        refreshFreeformSize()
        initFloatBar()

        val location = genFloatViewLocation()
        lastFloatViewLocation = location

        refreshTouchScale()
        refreshActionScale()

        if (isFloating && !isHidden) {
            moveFloatViewLocation(location, true)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply {
                height = rootHeight
                width = rootWidth
            }
            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
            if (!screenIsPortrait()) {
                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
            resetScale()
            Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    /**
     * 重置缩放
     */
    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
        refreshActionScale()
    }

    /**
     * 调整虚拟显示屏尺寸
     */
    private fun resizeVirtualDisplay() {
        if (!::virtualDisplay.isInitialized || isDestroyed) return
        try {
            virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, freeformDpi)
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to resize VirtualDisplay", e)
        }
    }

    /**
     * 小窗内应用方向变化处理 - 参考 FreeformView.onFreeFormRotationChanged
     * 当小窗内应用从竖屏切换到横屏（或反之）时调用
     * 添加渐变过渡动画以平滑视觉切换
     */
    private fun onFreeFormRotationChanged() {
        if (isDestroyed || isAnimating) return
        isAnimating = true

        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        // 淡出动画
        ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 1f, 0f).apply {
            duration = 100
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 在淡出完成后执行尺寸变更
                    initFloatViewSize()
                    if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                        freeformScreenHeight = tempHeight
                        freeformScreenWidth = tempWidth
                    } else {
                        freeformScreenHeight = tempWidth
                        freeformScreenWidth = tempHeight
                    }
                    refreshFreeformSize()
                    resetScale()
                    resizeVirtualDisplay()
                    Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        width = rootWidth
                        height = rootHeight
                        x = genCenterLocation()[0]
                        y = genCenterLocation()[1]
                    })

                    // 淡入动画
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 0f, 1f).apply {
                        duration = 150
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    /**
     * 公开方法：设置虚拟显示屏方向
     * 供 FreeformManager 的 TaskStackListener 调用
     */
    fun setVirtualDisplayRotation(rotation: Int) {
        if (isDestroyed) return
        var tempRotation = rotation
        // 某些应用可能会有非标准的方向值，将其转为竖屏
        if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        }
        if (tempRotation != virtualDisplayRotation) {
            virtualDisplayRotation = tempRotation
            mainHandler.post {
                onFreeFormRotationChanged()
            }
        }
    }

    /**
     * 禁用更新过渡动画
     */
    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnimFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnimFlag.inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setupTouchHandlers() {
        // TextureView 触摸 - 转发到虚拟显示屏
        binding.textureView.setOnTouchListener { _, event ->
            forwardMotionEvent(event)
            true
        }

        // root 触摸 - 背景点击
        binding.root.setOnTouchListener { _, event ->
            backgroundGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupControlBar() {
        // middleView 触摸处理 - 竖屏拖拽缩放（垂直方向）
        binding.bottomBar.middleView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownEvent(v, event)
                }
                MotionEvent.ACTION_MOVE -> {
                    handleMoveEvent(v, event)
                }
                MotionEvent.ACTION_UP -> {
                    handleUpEvent(v, event)
                }
            }
            true
        }

        // sideView 触摸处理 - 横屏拖拽缩放（水平方向）
        binding.bottomBar.sideView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownEvent(v, event)
                }
                MotionEvent.ACTION_MOVE -> {
                    handleMoveEvent(v, event)
                }
                MotionEvent.ACTION_UP -> {
                    handleUpEvent(v, event)
                }
            }
            true
        }

        // 长按关闭
        binding.bottomBar.middleView.setOnLongClickListener {
            destroy()
            true
        }
        binding.bottomBar.sideView.setOnLongClickListener {
            destroy()
            true
        }
    }

    private fun handleDownEvent(v: View, event: MotionEvent) {
        if (touchId == -1) touchId = v.id
        lastX = event.rawX
        lastY = event.rawY
        middleGestureDetector.onTouchEvent(event)
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    val dy = event.rawY - lastY
                    handleToFloatScale(0f, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.sideView -> {
                if (touchId == R.id.sideView) {
                    val dx = event.rawX - lastX
                    handleToFloatScale(dx, 0f)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
                notifyToFloat()
            }
            R.id.sideView -> {
                notifyToFloat()
                middleGestureDetector.onTouchEvent(event)
            }
        }
        touchId = -1
    }

    /**
     * 处理拖拽缩放
     * 根据小窗内应用方向(virtualDisplayRotation)选择乘/除，保持宽高比一致性
     * dy < 0 (向上拖动) -> 高度减少 -> 缩小 (竖屏)
     * dy > 0 (向下拖动) -> 高度增加 -> 放大 (竖屏)
     * dx < 0 (向左拖动) -> 宽度减少 -> 缩小 (横屏)
     * dx > 0 (向右拖动) -> 宽度增加 -> 放大 (横屏)
     */
    private fun handleToFloatScale(dx: Float, dy: Float) {
        if (isFloating || isAnimating) return


        val ratio =
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                1 / WIDTH_HEIGHT_RATIO
        } else {
            WIDTH_HEIGHT_RATIO
        }

        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            if (tempHeight >= hangUpViewHeight && tempHeight <= rootHeight * 0.9) {
                freeformHeight += dy.roundToInt()

                val contentHeight = freeformHeight - cardHeightMargin
                val contentWidth = contentHeight * ratio
                if (FreeformHelper.screenIsPortrait(screenRotation)) {
                    freeformWidth = (contentWidth + (freeformShadow * 2)).roundToInt()
                } else {
                    freeformWidth = (contentWidth + cardWidthMargin).roundToInt()
                }

                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()

                isZoomOut = true
            }
        } else if (dx != 0f) {
            val tempWidth = freeformWidth + dx
            if (tempWidth >= hangUpViewWidth && tempWidth <= rootWidth * 0.9) {
                freeformWidth += dx.roundToInt()

                val contentWidth = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                    freeformWidth - (freeformShadow * 2)
                } else {
                    freeformWidth - cardWidthMargin
                }
                val contentHeight = contentWidth / ratio
                freeformHeight = (contentHeight + cardHeightMargin).roundToInt()

                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()

                isZoomOut = true
            }
        }
    }

    /**
     * 判断缩放后的操作
     * 向上拖动缩小 -> mini 悬浮状态
     * 向下拖动放大 -> 移动到主屏幕
     * 完全复刻 FreeformView.notifyToFloat 的时序
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun notifyToFloat() {
        if (isZoomOut && !isAnimating) {
            val scaleX: Float = hangUpViewWidth / rootWidth.toFloat()
            val scaleY: Float = hangUpViewHeight / rootHeight.toFloat()

            // 进入 mini 模式时，关闭其他 mini 窗口
            if (mScaleY <= goFloatScale) {
                FreeformManager.destroyAllMiniWindows()
            }

            when {
                // 缩小到悬浮模式阈值 -> 进入 mini 悬浮状态
                mScaleY <= goFloatScale -> {
                    isAnimating = true
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, scaleX),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, scaleY),
                            ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                            cardViewMarginAnim(
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                                0, 0, 0
                            )
                        )
                        addListener(
                            onStart = {
                                val windowCoordinate = intArrayOf(
                                    windowLayoutParams.x,
                                    windowLayoutParams.y
                                )

                                var location = genFloatViewLocation()
                                if (lastFloatViewLocation[0] != -1) {
                                    location = lastFloatViewLocation
                                }

                                AnimatorSet().apply {
                                    playTogether(
                                        moveViewAnim(windowCoordinate, location),
                                        ValueAnimator.ofFloat(config.dimAmount, 0f).apply {
                                            addUpdateListener {
                                                Instances.windowManager.updateViewLayout(
                                                    backgroundView,
                                                    backgroundLayoutParams.apply {
                                                        dimAmount = it.animatedValue as Float
                                                    }
                                                )
                                            }
                                        }
                                    )
                                    startDelay = 125
                                    duration = 400
                                    interpolator = OvershootInterpolator(0.5f)
                                    addListener(
                                        onStart = {
                                            backgroundView.visibility = View.GONE
                                            binding.textureView.setOnTouchListener(null)
                                            // 延迟动画：在移动动画进行中更新窗口尺寸
                                            AnimatorSet().apply {
                                                duration = 100
                                                startDelay = 200
                                                addListener(
                                                    onEnd = {
                                                        mScaleX = scaleX
                                                        mScaleY = scaleY
                                                        binding.cardRoot.radius = cardCornerRadius * (hangUpViewWidth / rootWidth.toFloat())
                                                        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                                            height = hangUpViewHeight
                                                            width = hangUpViewWidth
                                                        })
                                                        binding.freeformRoot.scaleY = 1f
                                                        binding.freeformRoot.scaleX = 1f
                                                    }
                                                )
                                                start()
                                            }
                                            isFloating = true
                                        },
                                        onEnd = {
                                            binding.textureView.setOnTouchListener(floatViewTouchListener)
                                            setWindowEnableUpdateAnimation()
                                            isAnimating = false
                                        }
                                    )
                                    start()
                                }
                            }
                        )
                        duration = 200
                        start()
                    }
                }
                // 放大到全屏阈值 -> 移动到主屏幕
                mScaleY >= goFullScale -> {
                    isAnimating = true
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, 1f),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, 1f),
                            ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                            cardViewMarginAnim(
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                                0, 0, 0
                            )
                        )
                        duration = 300
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                                // 移动应用到主屏幕
                                moveToDefaultDisplay()
                                destroy()
                            }
                        })
                        start()
                    }
                }
                // 恢复原始大小
                else -> {
                    isAnimating = true
                    // 重新计算正确的尺寸
                    refreshFreeformSize()

                    val targetScaleX = freeformWidth / rootWidth.toFloat()
                    val targetScaleY = freeformHeight / rootHeight.toFloat()

                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, targetScaleX),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, targetScaleY),
                        )
                        duration = 300
                        interpolator = OvershootInterpolator(1.5f)
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                mScaleX = targetScaleX
                                mScaleY = targetScaleY
                                isAnimating = false
                            }
                        })
                        start()
                    }
                }
            }
            isZoomOut = false
        }
    }

    /**
     * 移动应用到主屏幕
     */
    private fun moveToDefaultDisplay() {
        if (componentName != null) {
            FreeformManager.startActivityOnDisplay(componentName, userId, 0)
        }
    }

    /**
     * 悬浮模式触摸监听 - 完整移植自 FreeformView.kt
     * 支持：拖动、点击恢复、拖到侧边变侧边栏、拖到顶部关闭
     */
    private inner class FloatViewTouchListener : View.OnTouchListener {
        private var moveStartX = 0f
        private var moveStartY = 0f
        private var movedX = 0f
        private var movedY = 0f

        private var minLong = 1.1
        private var isMoved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            // 侧边栏视图的触摸处理
            if (v?.id == R.id.root && isHidden) {
                hideGestureDetector.onTouchEvent(event)
                return true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    hangUpGestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    movedX = event.rawX - moveStartX
                    movedY = event.rawY - moveStartY

                    if (abs(movedX) > minLong || abs(movedY) > minLong) {
                        isMoved = true

                        windowLayoutParams.x += movedX.toInt()
                        windowLayoutParams.y += movedY.toInt()
                        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)

                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        event.rawX
                        val nowY = event.rawY

                        val windowCoordinate = intArrayOf(
                            windowLayoutParams.x,
                            windowLayoutParams.y
                        )

                        // 拖到顶部关闭
                        if (nowY <= realScreenHeight * 0.1f) {
                            destroyWithAnimation()
                            isMoved = false
                            return true
                        }

                        // 更新挂起位置
                        hangUpPosition[0] = windowCoordinate[0] <= 0
                        hangUpPosition[1] = windowCoordinate[1] <= 0

                        val location = genFloatViewLocation()
                        location[1] = windowLayoutParams.y

                        // 限制 Y 范围
                        if (nowY < realScreenHeight * 0.1f) {
                            location[1] = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                        }
                        if (nowY > realScreenHeight - realScreenHeight * 0.1f) {
                            location[1] = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                        }

                        var position = 0
                        // 拖到左边 -> 变成侧边栏
                        if (windowCoordinate[0] <= (realScreenWidth - screenPaddingX / 2) / -2) {
                            location[0] -= (hangUpViewWidth + screenPaddingX)
                            position = -1
                        }
                        // 拖到右边 -> 变成侧边栏
                        else if (windowCoordinate[0] >= (realScreenWidth - screenPaddingX / 2) / 2) {
                            location[0] += (hangUpViewWidth + screenPaddingX)
                            position = 1
                        }

                        AnimatorSet().apply {
                            playTogether(moveViewAnim(windowCoordinate, location))
                            addListener(
                                onStart = {
                                    if (position != 0) {
                                        // 进入侧边栏模式
                                        isHidden = true
                                        val inflateContext = try {
                                            val moduleContext = context.createPackageContext(
                                                BuildConfig.APPLICATION_ID,
                                                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                                            )
                                            CommonContextWrapper.createAppCompatContext(moduleContext)
                                        } catch (e: Exception) {
                                            XLog.e("$TAG: Failed to create module context for inflation", e)
                                            context
                                        }

                                        hiddenView = LayoutInflater.from(inflateContext).inflate(R.layout.view_floating_button, null, false)
                                        hiddenView.setOnTouchListener(this@FloatViewTouchListener)
                                        if (position == 1) {
                                            hiddenView.findViewById<View>(R.id.backgroundView).background =
                                                ContextCompat.getDrawable(inflateContext, R.drawable.floating_button_bg_right)
                                        }

                                        Instances.windowManager.addView(hiddenView, WindowManager.LayoutParams().apply {
                                            x = (realScreenWidth - floatingButtonWidth) / 2 * position
                                            y = location[1]
                                            width = floatingButtonWidth
                                            height = floatingButtonHeight
                                            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                            format = PixelFormat.TRANSLUCENT
                                            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                        })
                                    }
                                },
                                onEnd = {
                                    if (!isHidden) {
                                        lastFloatViewLocation = location
                                    }
                                    isMoved = false
                                }
                            )
                            duration = 300
                            interpolator = OvershootInterpolator(0.4f)
                            start()
                        }
                    } else {
                        hangUpGestureDetector.onTouchEvent(event)
                    }
                }
            }
            return true
        }
    }

    private val floatViewTouchListener = FloatViewTouchListener()

    // 悬浮模式点击手势 - 点击恢复到正常模式
    private val hangUpGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            floatViewToNormalView()
            return true
        }
    })

    // 侧边栏点击手势 - 点击恢复到悬浮模式
    private val hideGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hiddenViewToFloatView(false)
            return true
        }
    })

    /**
     * 生成悬浮视图位置
     */
    private fun genFloatViewLocation(): IntArray {
        return intArrayOf(
            if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
            else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
            if (hangUpPosition[1]) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
            else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
        )
    }

    /**
     * 移动视图动画
     */
    private fun moveViewAnim(startCoordinate: IntArray, endCoordinate: IntArray): Animator {
        val moveAnim = AnimatorSet()
        if (endCoordinate[0] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[0], endCoordinate[0]).apply {
                    addUpdateListener {
                        Instances.windowManager.updateViewLayout(
                            binding.root,
                            windowLayoutParams.apply {
                                x = it.animatedValue as Int
                            }
                        )
                    }
                }
            )
        }
        if (endCoordinate[1] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[1], endCoordinate[1]).apply {
                    addUpdateListener {
                        Instances.windowManager.updateViewLayout(
                            binding.root,
                            windowLayoutParams.apply {
                                y = it.animatedValue as Int
                            }
                        )
                    }
                }
            )
        }
        return moveAnim
    }

    /**
     * CardView margin 动画 - 复刻自 FreeformView
     */
    private fun cardViewMarginAnim(
        topStartMargin: Int, bottomStartMargin: Int, rightStartMargin: Int,
        topEndMargin: Int, bottomEndMargin: Int, rightEndMargin: Int
    ): Animator {
        return AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(topStartMargin, topEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            topMargin = it.animatedValue as Int
                        }
                    }
                },
                ValueAnimator.ofInt(bottomStartMargin, bottomEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            bottomMargin = it.animatedValue as Int
                        }
                    }
                },
                ValueAnimator.ofInt(rightStartMargin, rightEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            rightMargin = it.animatedValue as Int
                        }
                    }
                }
            )
        }
    }

    /**
     * 从侧边栏恢复到悬浮模式
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToFloatView(goNormalView: Boolean) {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y
        )

        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0

        val location = intArrayOf(
            if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
            else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
            -1
        )

        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    hiddenView.setOnTouchListener(null)
                    Instances.windowManager.removeView(hiddenView)
                    isHidden = false
                },
                onEnd = {
                    if (!isHidden) {
                        lastFloatViewLocation = intArrayOf(location[0], windowCoordinate[1])
                    }
                    if (goNormalView) {
                        floatViewToNormalView()
                    }
                }
            )
            duration = 300
            interpolator = OvershootInterpolator(0.4f)
            start()
        }
    }

    /**
     * 移动悬浮视图位置
     */
    private fun moveFloatViewLocation(location: IntArray, reset: Boolean) {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y
        )

        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    if (reset) {
                        binding.freeformRoot.scaleY = 1f
                        binding.freeformRoot.scaleX = 1f
                        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            height = hangUpViewHeight
                            width = hangUpViewWidth
                        })
                    }
                }
            )
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /**
     * 移动侧边栏视图位置
     */
    private fun moveHiddenViewLocation(location: IntArray) {
        if (!::hiddenView.isInitialized) return

        val layoutParams = hiddenView.layoutParams as WindowManager.LayoutParams
        val windowCoordinate = intArrayOf(layoutParams.x, layoutParams.y)

        var position = 0
        // R
        if (layoutParams.x > 0) {
            location[0] += (hangUpViewWidth + screenPaddingX)
            position = 1
        // L
        } else {
            location[0] -= (hangUpViewWidth + screenPaddingX)
            position = -1
        }

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(windowCoordinate[0], (realScreenWidth - floatingButtonWidth) / 2 * position).apply {
                    addUpdateListener {
                        Instances.windowManager.updateViewLayout(
                            hiddenView,
                            layoutParams.apply {
                                x = it.animatedValue as Int
                            }
                        )
                    }
                },
                ValueAnimator.ofInt(windowCoordinate[1], location[1]).apply {
                    addUpdateListener {
                        Instances.windowManager.updateViewLayout(
                            hiddenView,
                            layoutParams.apply {
                                y = it.animatedValue as Int
                            }
                        )
                    }
                },
                moveViewAnim(
                    intArrayOf(windowLayoutParams.x, windowLayoutParams.y),
                    intArrayOf(location[0], location[1])
                )
            )
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /**
     * 从悬浮模式恢复到正常模式 - 完全复刻 FreeformView.floatViewToMiniView
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToNormalView() {
        floatViewToNormalViewInternal()
    }

    /**
     * 公开方法：从 mini/hidden 状态恢复到正常模式
     * 供 FreeformManager 调用
     */
    fun restoreToNormalView() {
        if (isDestroyed) return
        if (isHidden) {
            hiddenViewToFloatView(true)
        } else if (isFloating) {
            floatViewToNormalViewInternal()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToNormalViewInternal() {
        if (isAnimating) return

        // 恢复普通模式时，关闭其他普通窗口
        FreeformManager.destroyAllNormalWindows()

        binding.textureView.setOnTouchListener { _, motionEvent ->
            forwardMotionEvent(motionEvent)
            true
        }

        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y
        )

        // 重新计算正常尺寸
        refreshFreeformSize()

        val restoreScaleX = freeformWidth / rootWidth.toFloat()
        val restoreScaleY = freeformHeight / rootHeight.toFloat()
        val center = genCenterLocation()

        // 快速减速动画组 - 移动窗口 + 恢复背景蒙版
        val fastDecelerateAnims = AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, center),
                ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                    addUpdateListener {
                        Instances.windowManager.updateViewLayout(backgroundView, backgroundLayoutParams.apply {
                            dimAmount = it.animatedValue as Float
                        })
                    }
                }
            )
            duration = 250
            interpolator = DecelerateInterpolator()
        }

        // 根据屏幕方向设置 margin
        var topMargin = 0f
        var bottomMargin = 0f
        var rightMargin = 0f
        if (screenIsPortrait()) {
            topMargin = freeformShadow
            bottomMargin = barHeight
        } else {
            rightMargin = barHeight
        }

        // 过冲动画组 - 恢复缩放 + 恢复 bottomBar + 恢复 margin
        val overshootAnims = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScaleX),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScaleY),
                cardViewMarginAnim(
                    (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                    (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                    (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                    topMargin.roundToInt(),
                    bottomMargin.roundToInt(),
                    rightMargin.roundToInt()
                )
            )
            duration = 300
            interpolator = DecelerateInterpolator()
            startDelay = 100
        }

        // 根动画
        AnimatorSet().apply {
            playTogether(fastDecelerateAnims, overshootAnims)
            addListener(
                onStart = {
                    isAnimating = true
                    backgroundView.visibility = View.VISIBLE
                    Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        height = rootHeight
                        width = rootWidth
                    })
                    binding.freeformRoot.scaleX = mScaleX
                    binding.freeformRoot.scaleY = mScaleY
                    binding.cardRoot.radius = cardCornerRadius
                },
                onEnd = {
                    isAnimating = false
                    // 更新 mScaleX/Y 为恢复后的正常尺寸比例
                    refreshScale()
                }
            )
            start()
        }

        isFloating = false

        setWindowNoUpdateAnimation()
    }

    private fun forwardMotionEvent(event: MotionEvent) {
        if (isDestroyed || displayId < 0) return

        try {
            // 使用 MotionEvent.obtain(event) 复制事件，保留 Android 13+ 的惯性滚动所需的内部状态
            val newEvent = MotionEvent.obtain(event)
            // 使用 Matrix 进行坐标变换
            val matrix = Matrix()
            matrix.setScale(1f / scaleX, 1f / scaleY)
            newEvent.transform(matrix)

            ObjectUtil.invokeMethod(
                obj = newEvent,
                methodName = "setDisplayId",
                paramTypes = paramTypes(Int::class.javaPrimitiveType),
                params = params(displayId)
            )
            Instances.inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to forward motion event", e)
        }
    }

    private fun injectBackKey() {
        if (displayId < 0) return

        try {
            val downTime = SystemClock.uptimeMillis()

            val downEvent = KeyEvent(
                downTime, downTime,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, 0
            )
            ObjectUtil.invokeMethod(
                obj = downEvent,
                methodName = "setSource",
                paramTypes = paramTypes(Int::class.javaPrimitiveType),
                params = params(InputDevice.SOURCE_KEYBOARD)
            )
            ObjectUtil.invokeMethod(
                obj = downEvent,
                methodName = "setDisplayId",
                paramTypes = paramTypes(Int::class.javaPrimitiveType),
                params = params(displayId)
            )
            Instances.inputManager.injectInputEvent(downEvent, 0)

            val upEvent = KeyEvent(
                downTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK, 0
            )
            ObjectUtil.invokeMethod(
                obj = upEvent,
                methodName = "setSource",
                paramTypes = paramTypes(Int::class.javaPrimitiveType),
                params = params(InputDevice.SOURCE_KEYBOARD)
            )
            ObjectUtil.invokeMethod(
                obj = upEvent,
                methodName = "setDisplayId",
                paramTypes = paramTypes(Int::class.javaPrimitiveType),
                params = params(displayId)
            )
            Instances.inputManager.injectInputEvent(upEvent, 0)
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to inject back key", e)
        }
    }

    fun moveToTop() {
        if (isDestroyed || isAnimating) return
        try {
            isAnimating = true
            ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 1f, 0f).apply {
                duration = 175
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        Instances.windowManager.removeView(binding.root)
                        Instances.windowManager.addView(binding.root, windowLayoutParams)
                        FreeformManager.moveToTop(displayId)
                        if (!isFloating && !isHidden) {
                            FreeformManager.bringMiniWindowsToFront()
                        }
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 0f, 1f).apply {
                            duration = 200
                            interpolator = AccelerateDecelerateInterpolator()
                            startDelay = 25
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    isAnimating = false
                                }
                            })
                            start()
                        }
                    }
                })
                start()
            }
        } catch (e: Exception) {
            isAnimating = false
            XLog.e("$TAG: Failed to move to top", e)
        }
    }

    private fun moveToTopIfNeeded() {
        if (!FreeformManager.isTop(displayId)) {
            moveToTop()
        }
    }

    /**
     * 带动画的销毁
     */
    private fun destroyWithAnimation() {
        if (isDestroyed || isAnimating) return

        isAnimating = true
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, mScaleX * 0.9f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, mScaleY * 0.9f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 1f, 0f),
            )
            duration = 200
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    destroy()
                }
            })
            start()
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        try {
            // 取消注册屏幕方向监听
            Instances.displayManager.unregisterDisplayListener(displayListener)

            // 移除侧边栏视图
            if (isHidden && ::hiddenView.isInitialized) {
                Instances.windowManager.removeView(hiddenView)
            }

            FreeformManager.removeWindow(displayId)
            if (::virtualDisplay.isInitialized) {
                virtualDisplay.release()
            }
            Instances.windowManager.removeView(binding.root)
            Instances.windowManager.removeView(backgroundView)
            XLog.d("$TAG: FreeformWindow destroyed, displayId=$displayId")
        } catch (e: Exception) {
            XLog.e("$TAG: Error destroying window", e)
        }
    }

    // TextureView.SurfaceTextureListener

    @SuppressLint("WrongConstant")
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        XLog.d("$TAG: onSurfaceTextureAvailable called, size=${width}x${height}")
        try {
            XLog.d("$TAG: Creating VirtualDisplay with size=${freeformScreenWidth}x${freeformScreenHeight}, dpi=$freeformDpi")
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    (1 shl 10) // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10
            virtualDisplay = Instances.displayManager.createVirtualDisplay(
                "ZFlow@${System.currentTimeMillis()}",
                freeformScreenWidth,
                freeformScreenHeight,
                freeformDpi,
                null,
                flags
            )
            displayId = virtualDisplay.display.displayId
            XLog.d("$TAG: VirtualDisplay created, displayId=$displayId")

            // Configure IME policy
            try {
                val wmHidden = Refine.unsafeCast<WindowManagerHidden>(Instances.windowManager)
                wmHidden.setDisplayImePolicy(
                    displayId,
                    WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
                )
                XLog.d("$TAG: IME policy set")
            } catch (e: Exception) {
                XLog.w("$TAG: Failed to set IME policy", e)
            }

            // Attach surface
            surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            virtualDisplay.surface = Surface(surface)
            XLog.d("$TAG: Surface attached")

            // Register with manager
            FreeformManager.addWindow(this)

            XLog.d("$TAG: VirtualDisplay ready, displayId=$displayId")

            // Start activity on the virtual display
            if (componentName != null && userId >= 0) {
                XLog.d("$TAG: Starting activity $componentName on display $displayId")
                FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
            } else if (taskId > 0) {
                XLog.d("$TAG: Moving task $taskId to display $displayId")
                FreeformManager.moveTaskToDisplay(taskId, displayId)
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to create VirtualDisplay", e)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isDestroyed || !::virtualDisplay.isInitialized) return
        try {
            virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, freeformDpi)
            surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to resize VirtualDisplay", e)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!initFinish) {
            ++updateFrameCount
            if (updateFrameCount > 2) {
                binding.lottieView.cancelAnimation()
                binding.lottieView.animate().alpha(0f).setDuration(200).start()
                binding.textureView.animate().alpha(1f).setDuration(200).start()
                initFinish = true
                XLog.d("$TAG: App started rendering, hiding loading animation")
            }
        }
    }
}

/**
 * 窗口配置类 - 从 SharedPreferences 读取
 */
data class FreeformWindowConfig(
    var freeformDpi: Int = 320,
    var freeformSize: Float = 0.75f,
    var freeformSizeLand: Float = 0.9f,
    var floatViewSize: Float = 0.33f,
    var dimAmount: Float = 0.2f,
    var manualAdjustFreeformRotation: Boolean = false,
)
