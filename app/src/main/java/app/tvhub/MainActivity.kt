package app.tvhub

import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* 沉浸式全屏：让内容延伸到状态栏/导航栏底下，否则系统栏区域会透出桌面壁纸，
           在非16:9设备（尤其手机）上看起来像是背景没铺满。必须在 setContent 之前调用。 */
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Prefs 先起来，L 才知道该用哪种语言
        Prefs.init(this)
        /* 系统语言改过的话，Activity 会重建后走到这里：刚切成简体中文就问一次要不要换国内源。
           必须在 L.init 之前 —— 它会把进程默认 Locale 改成 App 内语言，之后就分不清系统语言了。 */
        Prefs.noteSystemLanguage()
        L.init(this)
        Images.init(this)
        UpdateIndex.init(this)
        Prefs.systemDark = isNight(resources.configuration)
        setContent {
            /* 更新索引：先读缓存（立刻能显示「可更新」），首帧后 2.5s 再联网刷新。
               没有配置索引地址时这两个调用都是空操作，不会发任何请求。 */
            LaunchedEffect(Unit) { UpdateIndex.refresh(applicationContext) }
            KeepScreenOnWhileInstalling()
            /* 系统栏（状态栏/导航栏）图标颜色跟主题走：亮色背景用深色图标，暗色背景用浅色图标。
               C.dark 是 Compose state，这里用 SideEffect 在每次重组后同步，覆盖手动切换主题的场景。 */
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !C.dark
                controller.isAppearanceLightNavigationBars = !C.dark
            }
            TvHub()
        }
    }

    /** 安装期间保持亮屏：只要还有下载 / 校验 / 安装任务在跑，就给窗口挂 KEEP_SCREEN_ON。
     *
     *  电视的「自动锁屏」其实是两条独立的计时：屏幕超时休眠 + 屏保（Daydream）。
     *  FLAG_KEEP_SCREEN_ON 会让系统在窗口可见期间持有一把屏幕唤醒锁，两条计时都被按住，
     *  所以进度条不会跑到一半电视黑屏、把下载和安装会话掐掉。
     *  任务结束（成功 / 失败 / 取消）自动撤掉 flag，不改系统设置、不需要额外权限，
     *  也不影响用户离开本应用之后电视的正常休眠行为。 */
    @Composable
    private fun KeepScreenOnWhileInstalling() {
        val active = Installer.active
        DisposableEffect(active) {
            if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Prefs.systemDark = isNight(newConfig)
    }

    private fun isNight(c: Configuration) = c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onResume() {
        super.onResume()
        Device.tick++
    }
}
