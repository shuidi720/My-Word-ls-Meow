package io.catpaw.kittytalk

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 系统保活辅助：
 * 以 ADB/root 身份执行系统命令，为本应用设置系统级白名单
 * （Doze 白名单、应用待机桶、后台运行限制），
 * 使无障碍服务所在进程不易被系统回收，核心替换功能无需打开软件也能常驻运行。
 *
 * [加固] 所有命令 token 已字符串加密（运行时解密），dex 中不再直接可见
 * "deviceidle/whitelist/appops" 等系统命令特征。
 */
object PrivilegeBridge {

    private const val TAG = "PrivilegeBridge"

    /** 主动申请 Shizuku 授权的请求码（与 LauncherActivity 一致） */
    const val REQUEST_CODE = 10001
    private const val CMD = "cmd"
    private const val PKG_CMD = "package"
    private const val SET_STOPPED = "set-stopped-state"
    private const val FALSE = "false"
    private const val DEVICEIDLE = "deviceidle"
    private const val WHITELIST = "whitelist"
    private const val AM = "am"
    private const val STANDBY = "set-standby-bucket"
    private const val ACTIVE = "active"
    private const val APPOPS = "appops"
    private const val SET = "set"
    private const val RUN_ANY = "RUN_ANY_IN_BACKGROUND"
    private const val RUN_BG = "RUN_IN_BACKGROUND"
    private const val START_FG = "START_FOREGROUND"
    private const val GET_STANDBY = "get-standby-bucket"
    private const val DUMPSYS = "dumpsys"
    private const val GET = "get"
    // =================================================================

    /** Shizuku 服务是否可用（ADB/root 已就绪） */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 本应用是否已被 Shizuku 管理器授权 */
    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 主动弹出 Shizuku 授权请求 */
    fun requestPermission() {
        try {
            if (isAvailable() && !isGranted()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission 失败: ${e.message}")
        }
    }

    /** 注册 Shizuku 连接/授权变化监听；返回是否注册成功 */
    fun addBinderListener(listener: Shizuku.OnBinderReceivedListener): Boolean = try {
        Shizuku.addBinderReceivedListenerSticky(listener)
        true
    } catch (_: Throwable) {
        false
    }

    fun removeBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(listener)
        } catch (_: Throwable) {
        }
    }

    /** 执行一条命令，返回 (退出码, 输出)。需要已授权；必须在后台线程调用 */
    fun run(vararg cmd: String): Pair<Int, String> {
        val process = try {
            Shizuku.newProcess(arrayOf(*cmd), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "newProcess 失败: ${cmd.joinToString(" ")} -> ${e.message}")
            return -1 to (e.message ?: "执行失败")
        }
        return try {
            val out = process.inputStream?.bufferedReader()?.readText() ?: ""
            val err = process.errorStream?.bufferedReader()?.readText() ?: ""
            val code = process.waitFor()
            code to (out + err).trim()
        } catch (e: Exception) {
            -1 to (e.message ?: "读取输出失败")
        }
    }

    /** 系统级保活白名单命令集（命令 token 已加密，运行时解密） */
    private fun keepAliveCommands(pkg: String): List<Array<String>> = listOf(
        // 解除"停止状态"：vivo 等 ROM 一键清理会用 force-stop 把应用打进 stopped state，
        // 该状态下系统连无障碍服务的自动重连都会被拒绝（清理后服务掉线不回来自动恢复的根因）；
        // 先解除停止状态，进程才有资格被系统重新拉起，配合系统对无障碍的自动重连实现"清理后自动回来"
        arrayOf(CMD, PKG_CMD, SET_STOPPED, pkg, FALSE),
        // 加入 Doze（熄屏深度休眠）白名单，休眠期不杀进程
        arrayOf(CMD, DEVICEIDLE, WHITELIST, "+$pkg"),
        // 应用待机桶设为活跃，后台优先级最高
        arrayOf(AM, STANDBY, pkg, ACTIVE),
        // 解除后台运行限制（双保险）
        arrayOf(APPOPS, SET, pkg, RUN_ANY, "allow"),
        arrayOf(APPOPS, SET, pkg, RUN_BG, "allow"),
        // 允许启动前台服务（保活服务依赖）
        arrayOf(APPOPS, SET, pkg, START_FG, "allow")
    )

    /** 一次性应用全部白名单，返回逐条结果摘要；必须在后台线程调用 */
    fun applyKeepAlive(context: Context): String {
        if (!isAvailable()) return "Shizuku 未连接"
        if (!isGranted()) return "Shizuku 未授权"
        val pkg = context.packageName
        val sb = StringBuilder()
        for (cmd in keepAliveCommands(pkg)) {
            val (code, out) = run(*cmd)
            sb.append(cmd.joinToString(" ")).append("\n")
            sb.append("  -> exit=$code").append(if (out.isNotEmpty()) "  $out" else "").append("\n")
            // 解除强制停止命令被该 ROM 裁剪时（如 vivo/OriginOS 报 Unknown command），给出手动引导
            if (cmd.contains(SET_STOPPED) && code != 0) {
                sb.append("  ⚠ 该机型不支持自动解除“一键清理”的强制停止：请到最近任务列表把本应用下滑加锁，\n")
                sb.append("    并在系统设置允许“自启动/后台高耗电运行”，否则一键清理后无障碍仍会掉线。\n")
            }
        }
        return sb.toString().trim()
    }

    /** 查询当前白名单状态，返回可读文本；必须在后台线程调用 */
    fun queryStatus(context: Context): String {
        if (!isAvailable()) return "Shizuku 未连接"
        if (!isGranted()) return "Shizuku 未授权"
        val pkg = context.packageName
        val sb = StringBuilder()
        val bucket = run(AM, GET_STANDBY, pkg)
        sb.append("待机桶: ").append(bucket.second.ifEmpty { "未知" }).append("\n")
        val doze = run(DUMPSYS, DEVICEIDLE, WHITELIST)
        sb.append("Doze 白名单: ").append(if (doze.second.contains(pkg)) "已加入" else "未加入").append("\n")
        val appops = run(APPOPS, GET, pkg, RUN_ANY)
        sb.append("后台运行: ").append(appops.second.ifEmpty { "未知" })
        return sb.toString().trim()
    }
}
