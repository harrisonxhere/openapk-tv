# 发布说明模板

一份文件 = 一个版本，文件名就是 tag：`docs/releases/v1.0.2.md`。CI 直接拿它当 Release 正文。

**规则**

- **英文在前，中文在后**，中间用 `---` 分开。仓库和 Release 的默认语言是英文。
- **简短**：每段一句概括 + 最多 5 条要点，不写实现细节、不写证书指纹、不写"比上版小 0.2 MB"。
- 安装说明只写一次，放在最后，中英对照各一行。
- 不提上游项目名（来源声明只在 README 与 NOTICE）。

---

## 骨架

```markdown
一句话概括这次更新。

**What's new**

- 动词开头的短句
- 动词开头的短句

---

一句话概括这次更新（中文）。

**更新内容**

- 动词开头的短句
- 动词开头的短句

## Install / 安装

\`\`\`bash
adb install -r openapk-tv-1.0.2.apk
\`\`\`

Android 9+ · arm64-v8a / armeabi-v7a / x86 · overwrite-installable from v1.0.1
Android 9 以上 · 三种 ABI 都支持 · 可从 v1.0.1 覆盖安装
```

## 参考

`v1.0.1.md` 是按这套规则写的实例。
