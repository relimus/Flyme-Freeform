package io.relimus.zflow.xposed.services

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.kyuubiran.ezxhelper.core.misc.paramTypes
import io.github.kyuubiran.ezxhelper.core.misc.params
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.bean.MotionEventBean
import io.relimus.zflow.hook.utils.XLog
import io.relimus.zflow.xposed.IFreeformManager
import io.relimus.zflow.xposed.ui.window.FreeformWindow
import io.relimus.zflow.xposed.ui.window.FreeformWindowConfig
import io.relimus.zflow.xposed.utils.Instances

/**
 * Core FreeformManager service running in system_server.
 * Implements IFreeformManager.Stub for AIDL communication with app.
 * Manages VirtualDisplay windows, input injection, and task management.
 */
@SuppressLint("StaticFieldLeak")
object FreeformManager : IFreeformManager.Stub() {
    private const val TAG = "FreeformManager"

    private val windowList = mutableListOf<FreeformWindow>()
    private val displayIdList = mutableListOf<Int>()
    private var isReady = false

    // 存储 displayId 对应的 taskId 列表
    private val displayTaskMap = mutableMapOf<Int, MutableList<Int>>()

    lateinit var activityManagerService: Any
        internal set

    // TaskStackListener 监听应用方向变化
    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            // 不需要处理，因为窗口创建时会自动关联 task
        }

        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            // 如果是小窗内的任务被移除，关闭对应的窗口
            runOnMainThread {
                displayTaskMap.entries.find { it.value.contains(taskInfo.taskId) }?.let { entry ->
                    val displayId = entry.key
                    entry.value.remove(taskInfo.taskId)
                    // 如果这个 display 上没有任务了，可以关闭窗口
                    if (entry.value.isEmpty()) {
                        getWindow(displayId)?.destroy()
                    }
                }
            }
        }

        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
            runOnMainThread {
                // 更新 task 的 display 映射
                displayTaskMap.values.forEach { it.remove(taskId) }
                if (displayIdList.contains(newDisplayId)) {
                    displayTaskMap.getOrPut(newDisplayId) { mutableListOf() }.add(taskId)
                }
            }
        }

        override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
            runOnMainThread {
                // 找到包含此 task 的 display
                displayTaskMap.entries.find { it.value.contains(taskId) }?.let { entry ->
                    getWindow(entry.key)?.setVirtualDisplayRotation(requestedOrientation)
                }
            }
        }

        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
            runOnMainThread {
                // Android 10 及以上使用此回调
                displayTaskMap.entries.find { it.value.contains(taskId) }?.let { entry ->
                    getWindow(entry.key)?.setVirtualDisplayRotation(requestedOrientation)
                }
            }
        }
    }

    fun systemReady() {
        Instances.init(activityManagerService)
        isReady = true

        // 注册 TaskStackListener
        try {
            Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
            XLog.d("$TAG: TaskStackListener registered")
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to register TaskStackListener", e)
        }

        XLog.d("$TAG: System ready, FreeformManager initialized")
    }

    // Window management

    fun addWindow(window: FreeformWindow) {
        windowList.add(0, window)
        displayIdList.add(0, window.displayId)
        // 初始化 display 对应的 task 列表
        displayTaskMap[window.displayId] = mutableListOf()
    }

    fun removeWindow(displayId: Int) {
        val window = windowList.find { it.displayId == displayId }
        if (window != null) {
            windowList.remove(window)
            displayIdList.remove(displayId)
            // 清理 displayTaskMap
            displayTaskMap.remove(displayId)
        }
    }

    fun isTop(displayId: Int): Boolean = displayIdList.isEmpty() || displayIdList[0] == displayId

    fun moveToTop(displayId: Int) {
        if (displayIdList.remove(displayId)) {
            displayIdList.add(0, displayId)
        }
    }

    fun getWindow(displayId: Int): FreeformWindow? = windowList.find { it.displayId == displayId }

    /**
     * 查找指定应用的窗口（mini 或 hidden 状态）
     */
    fun findFloatingWindow(packageName: String?): FreeformWindow? {
        if (packageName == null) return null
        return windowList.find {
            !it.isDestroyed && it.componentName?.packageName == packageName && (it.isFloating || it.isHidden)
        }
    }

    /**
     * 查找普通状态的窗口（非 mini/hidden）
     */
    fun findNormalWindow(): FreeformWindow? {
        return windowList.find { !it.isDestroyed && !it.isFloating && !it.isHidden }
    }

    /**
     * 查找 mini/hidden 状态的窗口
     */
    fun findMiniWindow(): FreeformWindow? {
        return windowList.find { !it.isDestroyed && (it.isFloating || it.isHidden) }
    }

    /**
     * 将所有 mini/hidden 窗口提升到最顶层，确保其在普通窗口之上
     */
    fun bringMiniWindowsToFront() {
        // 使用 toList() 创建快照避免并发修改异常
        // 使用 reversed() 保持正确的 Z-order（后添加的在上层）
        windowList.filter { it.isFloating || it.isHidden }.toList().reversed().forEach { it.moveToTop() }
    }

    /**
     * 关闭所有 mini/hidden 状态的窗口
     */
    fun destroyAllMiniWindows() {
        windowList.filter { it.isFloating || it.isHidden }.forEach { it.destroy() }
    }

    /**
     * 关闭所有普通状态的窗口
     */
    fun destroyAllNormalWindows() {
        windowList.filter { !it.isFloating && !it.isHidden }.forEach { it.destroy() }
    }

    // IFreeformManager implementation

    override fun getVersionName(): String = BuildConfig.VERSION_NAME

    override fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    override fun getUid(): Int = Process.myUid()

    override fun createWindow(componentName: ComponentName?, userId: Int, taskId: Int, freeformDpi: Int, freeformSize: Int, floatViewSize: Int, dimAmount: Int) {
        if (!isReady) {
            XLog.e("$TAG: Service not ready")
            return
        }
        runOnMainThread {
            try {
                Instances.iStatusBarService.collapsePanels()

                // 检查是否有同应用的 mini/hidden 窗口
                val existingFloatingWindow = findFloatingWindow(componentName?.packageName)
                if (existingFloatingWindow != null) {
                    // 同应用：关闭当前普通状态窗口，将 mini 窗口恢复到普通状态
                    destroyAllNormalWindows()
                    existingFloatingWindow.restoreToNormalView()
                    XLog.d("$TAG: Restored existing floating window for ${componentName?.packageName}")
                    return@runOnMainThread
                }

                // 不同应用：关闭当前普通状态窗口，创建新窗口
                destroyAllNormalWindows()

                // Build config from parameters
                val config = FreeformWindowConfig(
                    freeformDpi = freeformDpi,
                    freeformSize = freeformSize / 100f,
                    floatViewSize = floatViewSize / 100f,
                    dimAmount = dimAmount / 100f
                )
                // Pass componentName, userId, taskId to FreeformWindow
                // Activity will be started in onSurfaceTextureAvailable after VirtualDisplay is created
                FreeformWindow(Instances.systemUiContext, componentName, userId, taskId, config)

                // 将 mini 窗口提升到最顶层
                bringMiniWindowsToFront()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create window", e)
            }
        }
    }

    override fun destroyWindow(displayId: Int) {
        runOnMainThread {
            getWindow(displayId)?.destroy()
        }
    }

    override fun destroyAllWindows() {
        runOnMainThread {
            windowList.toList().forEach { it.destroy() }
        }
    }

    override fun moveWindowToTop(displayId: Int) {
        runOnMainThread {
            getWindow(displayId)?.moveToTop()
        }
    }

    override fun injectMotionEvent(event: MotionEventBean?, displayId: Int) {
        if (event == null) return
        runOnMainThread {
            try {
                val count = event.xArray.size
                val pointerProperties = Array(count) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val pointerCoords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = event.xArray[i]
                        y = event.yArray[i]
                        pressure = 1f
                        size = 1f
                    }
                }

                val motionEvent = MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    event.action,
                    count,
                    pointerProperties,
                    pointerCoords,
                    0, 0, 1f, 1f,
                    -1, 0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                ObjectUtil.invokeMethod(
                    obj = motionEvent,
                    methodName = "setDisplayId",
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(displayId)
                )
                Instances.inputManager.injectInputEvent(motionEvent, 0)
                motionEvent.recycle()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to inject motion event", e)
            }
        }
    }

    override fun injectKeyEvent(keyCode: Int, displayId: Int) {
        runOnMainThread {
            try {
                val downTime = SystemClock.uptimeMillis()
                val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
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

                val upEvent = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
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
                XLog.e("$TAG: Failed to inject key event", e)
            }
        }
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int) {
        runOnMainThread {
            try {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, displayId)
                Instances.activityManager.moveTaskToFront(taskId, 0)
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to move task $taskId", e)
            }
        }
    }

    override fun startActivityOnDisplay(componentName: ComponentName?, userId: Int, displayId: Int) {
        if (componentName == null) return
        runOnMainThread {
            try {
                val intent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    component = componentName
                    `package` = componentName.packageName
                    action = Intent.ACTION_VIEW
                }
                val options = ActivityOptions.makeBasic().apply {
                    launchDisplayId = displayId
                    ObjectUtil.invokeMethod(
                        obj = this,
                        methodName = "setCallerDisplayId",
                        paramTypes = paramTypes(Int::class.javaPrimitiveType),
                        params = params(displayId)
                    )
                }.toBundle()
                val userHandle = ClassUtil.newInstance(
                    clz = UserHandle::class.java,
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(userId)
                ) as UserHandle

                ObjectUtil.invokeMethod(
                    obj = Instances.systemContext,
                    methodName = "startActivityAsUser",
                    paramTypes = paramTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java),
                    params = params(intent, options, userHandle)
                )
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to start activity $componentName", e)
            }
        }
    }

    override fun collapseStatusBarPanel() {
        runOnMainThread {
            try {
                Instances.iStatusBarService.collapsePanels()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to collapse status bar", e)
            }
        }
    }

    override fun getOpenWindowCount(): Int = windowList.size

    override fun isServiceReady(): Boolean = isReady

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }
}
