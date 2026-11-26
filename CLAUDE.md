# CLAUDE.md

本文件是给 Claude Code / Cursor 等工具的入口指针。**完整说明在 [AGENTS.md](AGENTS.md)，请先读它。**

摘要（不能违反的六条）：

1. 用户可见文案必须走 `str(R.string.xxx)`，新增键要同时写 `values/strings.xml` 与 `values-zh-rCN/strings.xml`。
2. 布局按 1280×720 设计稿写死 dp，位置用 `Modifier.at(x, y)`；除 `RoomScreen` 的网格与搜索页的 `LazyColumn` 外不要引入滚动容器。
3. 可点元素必须包 `Focusable {}`，焦点态用 `Modifier.ring(...)`；跨视图换焦点用 `FocusHandoff.pending = true`。
4. 图片「整张原图」：`ContentScale.Fit` + `MaskedArt` 羽化，不许改成 `Crop` 裁边。
5. GPL-3.0-only。上游来源只在 `README.md` / `README.zh-CN.md` 顶部声明、`NOTICE.md` 以及应用内 `about_help` 各说一次；**更新日志、GitHub Release 说明不要再写「衍生自 XXX」**，许可段只留「GPL-3.0-only + 源码/改动见 NOTICE.md」。
6. README 默认英文（`README.md` 是 GitHub 首屏），中文在 `README.zh-CN.md`；两份顶部各有一行语言列表，改一份要同步另一份，新语言另建 `README.<code>.md` 并互相挂链接。

常用文档：`docs/ARCHITECTURE.md`（数据流）、`docs/DESIGN-SYSTEM.md`（视觉规则）、
`docs/SCREENS.md`（各视图坐标）、`docs/BUILD-AND-TEST.md`（构建与调试）、`docs/DECISIONS.md`（历史取舍）。

构建：`./gradlew :app:assembleDebug` → 安装必须 `adb install -r -t`（debug 包带 testOnly）。
