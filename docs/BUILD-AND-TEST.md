# 构建、调试与验收

> 目标环境：Windows + Git Bash。命令都在项目根目录 `D:/Harrison/Code/tv-hub` 下执行。

---

## 1. 前置

| 需要 | 说明 |
|---|---|
| JDK 17+ | 本机已有，`./gradlew` 会读 `JAVA_HOME` |
| Android SDK | 路径写进 `local.properties`：`sdk.dir=C\:\\Users\\Harrison\\AppData\\Local\\Android\\Sdk`。这个文件是**本机专属**，已进 `.gitignore` |
| adb | 不在 PATH 里。用 `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`，或 `export PATH="$PATH:/c/Users/Harrison/AppData/Local/Android/Sdk/platform-tools"` |

国内网络相关配置已经写死在仓库里，不用再改：

- `settings.gradle.kts`：阿里云 `google` / `public` / `gradle-plugin` 镜像优先，官方仓库兜底。
  **不能只留国内源** —— 实测 AGP 9.4.1 在 `gradle-plugin` 与 `public` 是 404，只有 `repository/google` 有。
- `gradle/wrapper/gradle-wrapper.properties`：发行版走腾讯云镜像，同时必须 `validateDistributionUrl=false`。

---

## 2. 编译

```bash
./gradlew :app:assembleDebug          # 首次约 2 分钟，增量 15–30 秒
./gradlew :app:assembleRelease        # 需要 keystore.properties，否则用 debug 签名
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 26MB）。

**debug 包带 `testOnly`，安装必须带 `-t`**，否则报 `INSTALL_FAILED_TEST_ONLY`。

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

改过 `applicationId` 的话新旧包不覆盖，会并存或冲突 —— 现在固定 `app.tvhub`，不要动。

---

## 3. 模拟器

已建 AVD `tvhub_preview`（`-d tv_1080p`，Android 16 / x86_64，1920×1080 @320dpi）。
**不要动** 其他设备（曾有一个 ApkStart 自带的手机模拟器占端口）。

```bash
# 启动（必须作为后台任务跑，用 & / nohup 会被环境回收）
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd tvhub_preview -port 5556 \
    -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect

# 等开机
adb -s emulator-5556 wait-for-device
adb -s emulator-5556 shell getprop sys.boot_completed   # 反复查到 1

# 关闭
adb -s emulator-5556 emu kill
```

冷启动到 `sys.boot_completed=1` 约 **90–100 秒**（x86 软件渲染）。无加速时格外慢，别以为卡死了。

---

## 4. 运行与验收

```bash
# 首次冷启动读 3.3MB catalog.json 约 20 秒；截图前要等够，否则拍到 Splash
adb -s emulator-5556 shell am force-stop app.tvhub
adb -s emulator-5556 shell am start -n app.tvhub/.MainActivity
sleep 22

# 截图
adb -s emulator-5556 exec-out screencap -p > docs/screenshots/current/xxx.png
```

**坐标换算的坑**：`screencap` 出来是 1920×1080，但目视模型看的是缩到 1088 宽的图。
点某处要先在图上看坐标再 **×1.7647**（1920/1088）。

`input tap x y` 用 1920×1080 坐标；`input keyevent 19/20/21/22/23` 对应 上/下/左/右/OK，`4` 是 BACK。

**导航注意事项**：顶栏 tab 的历史行为是「焦点到就切页」，现在改为点击/OK 触发；
内容区按 BACK 会把焦点送回顶栏。用 `tap` 直接点内容区能进详情，不受顶栏影响。

---

## 5. 切主题 / 切语言（不点 UI）

点 UI 容易被焦点链挡，直接改偏好再重启最快：

```bash
A=$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe
$A -s emulator-5556 shell am force-stop app.tvhub

# theme: 0 跟随系统 / 1 浅色 / 2 深色      lang: 0 跟随系统 / 1 简中 / 2 English
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" "<map>" \
  '    <int name="theme" value="2" />' '    <int name="lang" value="1" />' "</map>" \
| MSYS_NO_PATHCONV=1 $A -s emulator-5556 shell "run-as app.tvhub sh -c 'cat > /data/data/app.tvhub/shared_prefs/prefs.xml'"

$A -s emulator-5556 shell am start -n app.tvhub/.MainActivity
```

- **必须 `MSYS_NO_PATHCONV=1`**，否则 Git Bash 把 `/data/...` 翻译成 `D:/Program Files/...`。
- **路径用单斜杠**，`//data/...` 会 `Permission denied`。
- 只想改系统给本应用的语言（不动上面那份偏好）：
  `adb shell cmd locale set-app-locales app.tvhub --locales zh-CN`（注意是复数 `locales`）。

---

## 6. 真机

已验证设备：小米电视 `MiTV-NSOP0` / `furyroad`，Android 9 / API 28 / armeabi-v7a，
1920×1080 @320dpi，`ro.build.characteristics=tv`。

```bash
adb -P 5038 start-server
adb connect 192.168.1.46:5555
adb -s 192.168.1.46:5555 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

- 电视需先开 ADB 网络调试。
- 装完系统会弹「帐号与安全」浮层盖住应用，此时按键会落进浮层 —— 按几次 BACK 关掉，
  用 `dumpsys activity activities | grep mResumedActivity` 确认前台再截图。
- 小米 PatchWall 首屏不显示 LEANBACK banner，要去「我的应用 / 全部应用」里找。这是桌面差异，不是 APK 问题。
- API 28 正好是 `minSdk`，是这个项目的最低支持边界。

---

## 7. 提交前自检清单

- [ ] `./gradlew :app:assembleDebug` 通过，无 `e:` 报错
- [ ] 新增字符串在 `values/` 与 `values-zh-rCN/` 都存在（键名一致）
- [ ] 代码里 `grep -n "[\u4e00-\u9fa5]"` 只剩注释
- [ ] 深色 / 浅色各截一张，中文 / 英文各截一张
- [ ] 焦点链路走一遍：顶栏 → 内容 → 详情 → BACK → 顶栏
- [ ] 截图归档到 `docs/screenshots/current/`

---

## 8. 发版（推 tag 即发，CI 全自动）

发版不需要人工建 Release，也不需要在本地出包：

```bash
# 1. 改版本号（两处一起改，CI 会校验 tag 与 versionName 是否一致）
#    app/build.gradle.kts: versionCode +1，versionName "1.0.2"
# 2. 写更新日志：docs/releases/v1.0.2.md（没有这个文件就退回自动生成的 notes）
# 3. 推
git commit -am "release: v1.0.2（versionCode 13）"
git tag -a v1.0.2 -m "v1.0.2"
git push origin main
git push origin v1.0.2     # ← 这一步触发 .github/workflows/release.yml
```

流水线做的事：校验 tag 与 `versionName` → `assembleRelease` → 改名 `openapk-tv-<ver>.apk` →
生成 `.sha256` → 建 Release（标题 `OpenAPK TV <ver>`，正文取 `docs/releases/<tag>.md`）→ 标记 latest。

- **先推 main 再推 tag**：反过来的话 tag 会指向旧 commit，包里的版本号与发布说明对不上。
- tag 重推 / 重跑会走 `gh release upload --clobber` 覆盖附件，不会报「release 已存在」。
- 跑的仍是 debug keystore 签名（仓库里没有 `keystore.properties`）。换正式证书后要在 Secrets 里配
  `KEYSTORE_B64` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 并在 workflow 里解码，否则 CI 出的包与
  手上的正式包签名不一致、无法覆盖安装。
- CI 建完 Release 后，`updates.json` 的 `self` 段要等下一次 `update-index` 才指向新版；
  急着生效就在 Actions 里对 `update-index` 点一次 Run workflow。

