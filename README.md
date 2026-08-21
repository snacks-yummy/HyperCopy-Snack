<p align="center">
<img src="https://github.com/user-attachments/assets/3eb5e54f-0dee-4368-86fe-16bd94615f07" width="120" height="120" style="border-radius: 24px;" alt="HyperIsland Icon"/>
</p>

<h1 align="center">HyperCopy · 中文版</h1>

<p align="center"><b>复制后直达目标 App 的 Android 链接跳转增强模块（中文二改版）</b></p>

<p align="center">
<a href="https://github.com/snacks-yummy/HyperCopy-snack/releases"><img src="https://img.shields.io/github/v/release/snacks-yummy/HyperCopy-snack?style=flat-square&logo=github&color=black" alt="GitHub Release"/></a>
<img src="https://img.shields.io/github/downloads/snacks-yummy/HyperCopy-snack/total?style=flat-square" alt="Downloads"/>
<a href="https://android.com"><img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform"/></a>
<a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square" alt="LSPosed"/></a>
<a href="https://shizuku.rikka.app/"><img src="https://img.shields.io/badge/Support-Shizuku-2196F3?style=flat-square" alt="Shizuku"/></a>
<a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Build-Kotlin%20%2B%20Compose-7F52FF?style=flat-square&logo=kotlin" alt="Build"/></a>
</p>

<p align="center"><a href="README_EN.md"><b>English</b></a> | <b>简体中文</b></p>

---

> 🚀 **二改说明**：本项目基于 [1812z/HyperCopy](https://github.com/1812z/HyperCopy) 二次开发的中文版，在原版基础上持续迭代至 **v1.145.9**，保留全部原版能力的同时，新增智能识别执行类型、剪贴板改写回写、短信验证码提取、取件码通知、AND 触发条件、延迟跳转、35 家快递单号直达菜鸟详情页、**外卖柜取件短信 → 微信小程序全链路自动跳转**（WebView 预热 + 渲染崩溃兜底 + LLC 缓存直拉）等实用功能。

## ✨ 功能介绍
<table>
<tr>
<td width="50%">
<b>📋 复制直达</b><br/>
监听复制内容，命中规则后快速跳转到目标 App，节省手动打开、搜索、粘贴的时间。
</td>
<td width="50%">
<b>☁️ 云规则</b><br/>
内置云规则页，可在线获取规则，支持搜索、下载与一键配置。
</td>
</tr>
<tr>
<td width="50%">
<b>📦 快递直达</b><br/>
内置 35 家快递公司单号识别（顺丰/圆通/中通/申通/韵达/京东/EMS 等），复制单号即可直达菜鸟快递详情页。
</td>
<td width="50%">
<b>📜 口令识别</b><br/>
支持平台口令文本识别（京东/拼多多/支付宝/美团/快手等 30+ 平台），复制口令直达目标 App。
</td>
</tr>
<tr>
<td width="50%">
<b>🔔 实时通知</b><br/>
复制命中规则后可通过安卓通知确认跳转：无（直接跳转）/ 普通通知 / 实时通知 / 小米超级岛 4 种模式可选；取件码/取货码场景支持仅通知（不跳转、不改剪贴板）。
</td>
<td width="50%">
<b>🔐 LSPosed / Shizuku 支持</b><br/>
支持免 Root 的 Shizuku 方案，也支持 Root / LSPosed 监听剪贴板变化。
</td>
</tr>
<tr>
<td width="50%">
<b>🔢 短信智能提取</b><br/>
复制含验证码/取件码的短信：验证码自动提取写入剪贴板（粘贴即得），取件码通知栏提醒（支持 3-3-1020 / 口令码 / 提货码等格式）。
</td>
<td width="50%">
<b>📱 分身应用</b><br/>
检测到应用存在分身时，支持弹窗询问并选择需要打开的应用实例。
</td>
<td width="50%">
<b>⚙️ 系统服务</b><br/>
适配系统链接调用服务，支持快速配置默认链接调用方式。
</td>
</tr>
</table>

## 🆕 二改新增能力

| 版本 | 阶段主题 |
|---|---|
| **v1.145.9** | 京东链接规则装机默认关闭（手动开启不受影响）|
| **v1.145.6–v1.145.8** | ⭐ **外卖柜取件链路收口：回归基线工作链 + LLC 短链→scheme 缓存直拉（重复短信 ~30ms 免渲染）+ 冷启动超时自适应** |
| **v1.145.1** | 防循环内容指纹 + dpurl.cn UA 特判 |
| **v1.145.0** | WebView 渲染进程崩溃兜底（onRenderProcessGone）|
| **v1.144.9** | ⭐ **WebView 预热：冷启动首跳提速 49%（3610→1840ms）** |
| **v1.144.2–v1.144.8** | 权限自愈收口 / 一键配置常驻 / 去重窗口 0.5s / 闪显修复 / 日志调试默认 |
| **v1.139** | 键盘保护：浮动窗口防打断输入法 + 日志缓冲扩充至 3000 条 |
| **v1.138** | ⭐ **短信智能提取：验证码写入剪贴板 + 取件码通知栏提醒（仅通知模式）** |
| **v1.137** | 菜鸟弹窗扫描无限重试循环修复（业务提示弹窗识别）|
| **v1.136** | 圆通 YT 前缀单号位数修复（10-13 位，云端规则同步）|
| **v1.135** | 修复微信视频号跳转剪贴板异常（反向迁移，恢复便捷下载自动识别）|
| **v1.134** | 防跳转循环：同目标同内容 30 秒内不重复跳转 |
| **v1.132** | 微信视频号链接直达便捷下载 App |
| **v1.129** | 智能识别器增强：xhslink.cn 补全 / 【平台名】指纹识别 |
| **v1.125** | ⭐ **快递规则体系重构：35 家快递公司单号识别，已完成直达菜鸟快递详情页** |
| **v1.119** | 独立保活：命令链巡检，无需其他保活软件 |
| **v1.79** | 剪贴板改写回写（复制验证码自动提取纯数字）、触发条件 AND、延迟跳转 |
| **v1.78** | 智能识别执行类型拆分：抖音/快手/小红书短链与详情域名自动区分，图文笔记修复 |
| **v1.77** | 人性化缺口修复 6 项（编辑器防丢内容、按钮文字化、建议页返回确认等）|
| **v1.70 – v1.76** | 规则建议页增强 7 项 / 新装一键配置（Shizuku 静默完成 6 项）/ 还原规则暴涨 bug 修复 / 全页面布局修复 / 规则数量显示 |
| **v1.62 – v1.69** | UI 人性化全面改造：全页面按键缺失修复（33 文件）/ 顶栏文字按钮 / 还原内置规则重复暴涨修复 |
| **v1.59 – v1.61** | 平台口令文本识别 **31 平台**（京东/拼多多/支付宝/美团/快手…）+ 拼多多福袋码识别（防误触双规则）|
| **v1.47 – v1.56** | ⭐ **微信复制不跳转完整修复链**（免 root 终极方案：轮询检测器 + 透明悬浮抢焦点读取）|
| **v1.43 – v1.46** | 内置/云端规则永不自动修改原则 / 智能识别对齐官方规则规范（URL 按域名选执行模式）|
| **v1.29 – v1.42** | 默认 Shizuku / 快捷四件套 / 日志 UI / 剪贴板已空三连修 / 场景规则集 / 无障碍剪贴板检测通道 |
| **v1.9 – v1.28** | 内置云规则 / 淘口令新旧格式 / 口令识别 43 平台 / 规则优先级 / 排除负规则 / SAF 导入导出 / 回收站 / 模板库 / 统计 / 首次引导 |

## 使用说明

### 🔧 Shizuku 依赖
本项目的免 Root 剪贴板监听与系统级能力基于 **Shizuku** 运行：
- **官方项目**：[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)（https://github.com/RikkaApps/Shizuku）
- 本二改版实测环境：[thedjchi/Shizuku v13.7.0-thedjchi](https://github.com/thedjchi/Shizuku/releases/tag/v13.7.0-thedjchi)

1. 安装 HyperCopy（中文版）。
2. 新装首次打开，按首页「新装一键配置」卡片自动完成：Shizuku 授权 / 通知权限 / 省电无限制 / 后台弹出页面 / 自启动等设置。
3. 按使用环境选择监听方案：`LSPosed`（root）、`Shizuku`（免 root）或 `无障碍`（免 root 无悬浮窗）。
4. 在“云规则”页下载常用应用规则，或在“规则”页手动添加规则。
5. 复制链接、地址、快递单号或指定文本。
6. 命中规则后，HyperCopy 会按设置直接跳转、弹出确认通知，或把结果写回剪贴板。

### 二改新增功能的使用
- **🖊️ 剪贴板改写回写**：规则编辑器中执行模式选择「剪贴板改写」，设置模板（如 `${p1}` 提取纯数字）。命中后模板渲染结果自动写回剪贴板并弹出「已复制」提示——例如复制包含验证码的短信，自动提取 6 位纯数字。
- **🔗 触发条件 AND**：开启规则编辑器中的「所有触发条件都需匹配（AND）」开关后，规则的所有触发正则必须**同时命中**才执行（默认关闭 = 任一命中即可，防止「沾边就跳」的误触）。
- **⏱️ 延迟跳转**：在规则编辑器中设置延迟毫秒数（0-5000ms），命中规则后延迟指定时间再跳转；删除或禁用规则时，未执行的延迟跳转会**自动取消**。
- **🤖 智能识别执行类型**：无需配置。复制抖音、快手、小红书链接时自动区分短链与详情页——短链（如 `v.douyin.com`、`v.kuaishou.com`、`xhslink.com`）先解析再打开，详情页链接直接提取并打开。

## 规则能力
规则保存在应用私有目录的 `rules.json` 中，核心字段包括：
```json
{
  "name": "bilibili",
  "category": "link",
  "enabled": true,
  "actionMode": "direct_open",
  "matchRegex": ".*bilibili\\.com.*|.*b23\\.tv.*",
  "target": {
    "type": "url",
    "template": "",
    "packageName": "tv.danmaku.bili"
  }
}
```
支持的模板变量：
- `${p1}`、`${p2}`：`parameterRegex` 按顺序提取的捕获组。
- `${r1}`、`${r1_1}`：`extractionRegexes` 提取到的捕获组。
- `${input}`：完整复制内容。
- `${url:input}`：从完整复制内容中提取第一个 URL。
- `${redirectUrl}`：跳转后解析得到的重定向 URL。
- `${raw:变量名}`：原样插入参数，不做 URL 编码。
- `${pkg}`：当前规则的目标应用包名。
- `${time:yyyy-MM-dd HH:mm}`：当前时间（格式自定，如 `${time:HH:mm:ss}`）。
- `${lower:key}` / `${upper:key}`：参数转小写 / 大写。
- `${encode:key}`：参数做 URL 编码。

### 执行模式（actionMode）
| 模式 | 说明 |
|---|---|
| `parse_and_open` | 解析链接后打开（详情页零网络提取，如抖音/快手/小红书详情，默认）|
| `direct_open` | 直接打开目标应用 |
| `webview_resolve_and_open` | WebView 解析重定向后打开（短链场景）|
| `clipboard_write` | 将模板渲染结果写回剪贴板（**v1.79 新增**）|

### 规则字段总表（rules.json 完整字段）
| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 规则名称 |
| `category` | string | 分类：`link` 链接 / `text` 文本 / `address` 地址 / `express` 快递 |
| `enabled` | boolean | 是否启用（默认 true）|
| `actionMode` | string | 执行模式（见上表，默认 `parse_and_open`）|
| `matchRegex` | string | 主匹配正则（必填）|
| `parameterRegex` | string | 参数提取正则（捕获组供模板变量使用）|
| `triggerRegexes` | string[] | 触发条件正则列表（配合 `matchAllTriggers`）|
| `extractionRegexes` | string[] | 提取正则列表（捕获组供 `${r1}` 等使用）|
| `parseAfterRedirect` | boolean | 重定向后继续解析（默认 false）|
| `target` | object | 目标：`type`（`url`/`intent`）、`template`、`packageName`、`action` |
| `clearClipboardAfterJump` | boolean | 跳转后清空剪贴板（默认 false）|
| `priority` | int | 优先级：越大越优先（默认 0）|
| `group` | string | 分组/标签 |
| `excludeRegex` | string | 排除规则：命中则跳过（负规则）|
| `regexOptions` | string | 正则选项：`i` 忽略大小写 / `s` DOTALL / `m` MULTILINE（可组合如 `is`）|
| `notificationMode` | string | 规则级通知模式（覆盖全局设置；null = 跟随全局）|
| `sourcePackages` | string | 来源 App 包名白名单（逗号分隔；空 = 不限来源）|
| `activeTimeStart` / `activeTimeEnd` | string | 生效时间段（`HH:mm`；空 = 不限）|
| `matchAllTriggers` | boolean | **v1.79**：所有触发正则需同时命中（默认 false = 任一命中）|
| `delayMillis` | int | **v1.79**：延迟跳转毫秒数 0-5000（默认 0 = 立即）|

剪贴板改写规则示例（复制短信验证码自动提取纯数字）：
```json
{
  "name": "验证码提取",
  "category": "text",
  "enabled": true,
  "actionMode": "clipboard_write",
  "matchRegex": ".*验证码.*(\\d{6}).*",
  "parameterRegex": ".*(\\d{6}).*",
  "target": {
    "type": "url",
    "template": "${p1}"
  }
}
```

> 📖 更多规则编写细节见 [规则编写指南](docs/规则编写指南.md) 与 [规则架构说明](docs/rules-architecture.md)。

## Star History
<a href="https://www.star-history.com/?repos=snacks-yummy%2FHyperCopy-snack&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/image?repos=snacks-yummy/HyperCopy-snack&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/image?repos=snacks-yummy/HyperCopy-snack&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/image?repos=snacks-yummy/HyperCopy-snack&type=date&legend=top-left" />
 </picture>
</a>

## 上游
- 原版项目：[1812z/HyperCopy](https://github.com/1812z/HyperCopy)
- 官网：[https://hypercopy.1812z.top/](https://hypercopy.1812z.top/)

## 许可证
许可证文件待补充（同上游）。欢迎 Issue 与 PR。

<p align="center">
Made with ❤️ for Android users<br/>
<a href="https://github.com/snacks-yummy/HyperCopy-snack"><img src="https://img.shields.io/github/stars/snacks-yummy/HyperCopy-snack?style=social" alt="Star History"/></a>
</p>