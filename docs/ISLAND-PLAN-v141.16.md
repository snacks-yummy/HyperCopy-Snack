# HyperCopy 灵动岛方案锁定版（v1.141.16 稳定基线）

> 锁定时间：2026-08-18
> 锁定版本：v1.141.16(199) — 已安装，真机验证通过
> 状态：**已锁定为稳定基线，供后续开发参考**

---

## 一、核心方案（锁定）

灵动岛（HyperOS 顶部胶囊）采用 **动态自增 id + 一次性 session 断网 bypass + focus extras + 发送前 cancel 清场**。

### 1. 动态自增 id（根治"同内容/连续复制不弹"）
- 取件码基础 id=3002，验证码基础 id=3003，各自 `baseId*10000 + 全局seq`。
- 每条通知独立 id（30020001→30020015），全新通知 HyperOS 必弹。
- 根治：固定 id 时代"旧胶囊未收起时同 id 新内容被就地更新、不再重弹"的机制缺陷。
- 实现：`TextNotification.nextIslandId()`，全局 `AtomicInteger` seq，baseId 高位区分，互不撞。
- 实测：连续 15 条取件码全部发送成功且 id 递增正常。

### 2. 一次性 session 断网 bypass（稳定，放弃 daemon）
- `MiuiXmsfNetworkBlocker` 用一次性 app_process session：断网→READY→发通知→恢复退出。
- **v1.141.15 关键回退**：放弃 daemon 常驻。Shizuku newProcess 起的常驻进程其 stdin/stdout READY 读取不可靠（多次 block failed + 残留僵尸 app_process），稳定性远差于一次性。
- 代价：每条约 1.3s 冷启动，但文本通知实际不高频，可接受。

### 3. focus extras
- 保留 `MiuiSuperIslandNotification.apply`（miui.focus.param 扩展参数）。
- **v1.141.13 证伪**：纯普通高优通知不自动上岛，必须 focus+bypass。

### 4. 发送前 cancel 清场
- 每次发送前 cancel 本类上一个 id（`lastIslandIds`），避免通知栏堆积。
- cancel→notify 间隔 250ms（给 HyperOS 收起动画留余量）。

### 5. 发送后复核（v1.141.16 新增观测）
- notify 后 `getActiveNotifications()` 检查该 id 是否真实进入系统，日志记录 `inSystem=true/false`。

---

## 二、观测链（三层证据，判定"是否真上岛"）

| 层 | 手段 | 字段 | 意义 |
|---|---|---|---|
| app 发送 | HyperCopy 日志 | `文本通知已发送 ... inSystem=` | 是否真实 post 进系统 |
| 系统识别 | shell `dumpsys notification` | `focusType=PARAMS` | 系统识别为焦点通知（上岛类型） |
| 人工 | 肉眼 | 顶部弹出胶囊 | 真上岛 |

- `inSystem=true` + `focusType=PARAMS` + 看到胶囊 = 完整上岛链。
- **重要**：HyperOS 无公开 API 让第三方查询"胶囊是否已弹出"（SystemUI 私有行为，已全网查证无解）。最接近是 shell 层 `focusType`（仅 shell 有权限）。

---

## 三、实测数据（v1.141.16，锁定依据）

- 取件码连续 13~15 条：app 发送 100%，`inSystem=true` 100%，`focusType=PARAMS`，系统性识别为焦点通知。
- 三层链路 app→系统→识别全部 100%。
- 用户实测稳定率 ~95%：剩余 5% 为 HyperOS 胶囊展示动画偶发（连续快速上岛时 SystemUI 动画排队/吞），**第三方无法控制**。

---

## 四、通知渠道全清单（核查结论）

| 渠道 | 入口 | 通知 id | channel | 状态 |
|---|---|---|---|---|
| 文本-取件码 | `notifyOnlyResult`→`TextNotification` | 动态 3002xxxx | hypercopy_text_* | ✅ 已修复/锁定 |
| 文本-验证码 | `clipboardWriteNotify`→`TextNotification` | 动态 3003xxxx | hypercopy_text_* | ✅ 已修复/锁定 |
| 跳转通知 | `PendingJumpCoordinator.postNotification` | 固定 2001 | hypercopy_jump_* | ✅ 合理（单次决策） |
| 未匹配提示 | `notifyUnmatched` | 固定 3001 | hypercopy_unmatched | ✅ 正常 |
| 剪贴板兜底 | `ClipboardFallbackNotifier` | 固定 2002 | hypercopy_fallback | ✅ 正常（前台服务） |

- 文本类走独立 `TextNotification`（动态 id + focus + session），与全局跳转（固定2001）硬隔离，互不影响。
- channel 前缀：跳转 `hypercopy_jump_`、文本 `hypercopy_text_`、未匹配 `hypercopy_unmatched`，全部独立。

---

## 五、已清理项（v1.141.16 检查并完成）

- ✅ Config.kt 删除废弃常量：`NOTIFY_ONLY_NOTIFICATION_ID=3002`、`NOTIFY_ONLY_CHANNEL_ID=hypercopy_notify_only`
  - 取件码迁移到 `TextNotification` 后无任何代码引用，已删除。
  - 已构建验证编译通过。
- ✅ 系统残留通知：`dumpsys notification` 确认旧 `hypercopy_notify_only` channel 已无任何残留通知，无需手动清理。
- ✅ 保留：`KEY_NOTIFY_ONLY_RULE_V138`（actionMode 迁移标记，仍有用）。

---

## 六、已验证的反向结论（防走回头路）

- v1.141.13 纯普通通知 → 不自动上岛（参照菜鸟证伪，系统只对 focus 通知上岛）。
- v1.141.12~14 daemon 常驻 → Shizuku newProcess 管道不可靠，弃用。
- 固定 id + 同 id 更新 → 旧胶囊未收起时新内容被吞不弹（→ 用动态 id 解决）。

---

## 七、锁定范围备忘

- 本次锁定的灵动岛方案**仅作用于文本类规则通知**（取件码/验证码）。
- 跳转通知、未匹配、兜底三通道不受影响，维持现状。
- 后续如需优化：剩余 ~5% 是 HyperOS 系统行为，非本项目可解；如需再压，只能绕道 iOS 灵动岛式 overlay（大改，不建议）。