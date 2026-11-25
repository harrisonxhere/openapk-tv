# 声明 / NOTICE

## 来源

本仓库是 [Open Shelf](https://github.com/wingedpanther/openshelf)（原作者 wingedpanther）
的**非官方衍生版本**，与上游项目无隶属、授权或背书关系。

- 基于版本：Open Shelf v0.6.0，commit `1e187094b53607c6fd48ce3bd527714ff1f326e7`（2026-11-11）
- 首次修改日期：**2026-11-14**
- 现用名称：**OpenAPK TV**（2026-11-14 由上游原名更名 `TV Hub`，2026-11-24 再更名为现名）
- 依据：GPLv3 §5(a) 要求衍生作品带有显著的修改声明及相关日期

## 已做的修改

- 新增全量简体中文界面 `values-zh-rCN/strings.xml`（153 个条目、中文日期格式、18 个分类名映射）
- 新增可配置国内下载源 `Mirror.kt`：镜像优先、原链回退；图片与 APK 下载逐源尝试
- 构建依赖与 Gradle 发行版改用国内镜像，官方仓库保留为兜底
- **2026-11-14：品牌更名为 `TV Hub`**。界面文案、启动画面、Manifest `android:label`、
  README 标题、APK 文件名约定与 User-Agent 中的自称均已替换；来源声明中仍保留上游
  名称 "Open Shelf"，以标明本作品的出处（§5(a)）
- **2026-11-24：品牌再更名为 `OpenAPK TV`**（应用显示名 / `android:label` / 界面文案 /
  横幅），并换上自制标识 `art/logo.svg`：猫耳电视轮廓 + 屏幕内的机器人头，
  启动器图标与 TV 横幅随之重绘，不再使用上游图形素材。`applicationId` 仍为 `app.tvhub`，
  包名未变，已装版本可直接覆盖升级
- **`applicationId` / `namespace` / Kotlin package 由 `io.github.wingedpanther.openshelf`
  改为 `app.tvhub`**；主 Composable 由 `OpenShelf()` 改为 `TvHub()`；`rootProject.name`
  改为 `tv-hub`
- 更名不涉及 `catalog.json`、签名校验逻辑与安装链路，目录数据与 APK 字节均未改动

## 许可证与源码

本项目**整体**依 **GNU General Public License v3.0（GPL-3.0）** 授权，与上游一致，
不得改用其他许可证（GPLv3 §5(c)）。完整条款见 [LICENSE](LICENSE)，该全文及其中
所有版权、无担保声明均原样保留，未作删改（§4、§5(b)）。

Corresponding Source 指本 Git 仓库在对应 commit / tag 处的完整内容，包含构建脚本、
资源文件与 Gradle wrapper。分发任何 APK 时，须在同一发布渠道提供该版本对应的源码
地址或可公开访问的仓库与 tag（§6(d)）。签名密钥不属于源码，单独保管；构建方式见
README 的 Build 一节。

你可以自由使用、修改与再分发本项目；再分发时同样须采用 GPL-3.0、保留本声明并提供源码。

## 第三方资产

- **Geist 字体**：SIL Open Font License 1.1，见 [FONT-LICENSE-OFL.txt](FONT-LICENSE-OFL.txt)。
- **`catalog.json` 中的应用名称、描述与图片**：版权归各自项目所有，由公开 GitHub 数据
  汇总而来，既不属于上游项目，也不属于本项目的原创资产。
- **AndroidX / Jetpack Compose / Kotlin 等依赖**：各自遵循其上游许可证。

## 商标与安装标识

"Open Shelf" 及其原始图标、横幅属于上游项目的标识。GPL-3.0 不授予商标权
（GPLv3 §7(e)）。本项目以 `OpenAPK TV` 作为自有名称，不宣称与上游存在官方关联。

因 `applicationId` 已变更，本项目 APK 与上游版本在系统里是**两个不同的应用**，
可以同时安装；若此前安装过本项目的旧包（`io.github.wingedpanther.openshelf`），
需手动卸载，新包不会覆盖它。

应用图标与 TV 横幅自 2026-11-24 起为本项目自制（`art/logo.svg` + `art/generate_icons.py`
可复现），不再包含上游名称或图形；这不表示与上游存在关联，也不影响上述名称与许可证声明。
自 2026-11-25 起，应用内（顶栏 / 启动页）改用另一份自制标识 `art/logo-mark.svg`
（白色电视轮廓 + 绿色渐变笑脸，由项目作者提供），与桌面图标并存。
标识内的机器人头形取自 Android 机器人形象（Android 品牌资源，CC BY 3.0），仅用于说明
这是一款 Android TV 应用，不暗示 Google 的任何背书或关联。
