package io.github.hypercopy.data.rules
import android.content.Context
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import java.util.UUID

class RuleRepository(private val context: Context) {
    fun readRules(): List<RuleConfig> {
        // v1.101 一次性迁移：菜鸟规则正则精确化（升级不覆盖已有规则，需显式迁移）
        migrateCainiaoRuleV101()
        // v1.125 一次性迁移：默认关闭原作者快递100规则 + 菜鸟规则补全适配（对齐识别器35家）
        migrateExpressRulesV125()
        // v1.132 一次性迁移：Chrome 通配规则降级（priority=-100，排列表末尾）——避免抢占特定域名规则
        migrateChromeRuleV132()
        // v1.134 一次性迁移：撤销 v1.133 错误写入的 clearClipboardAfterJump=true
        //（用户澄清：便捷下载需识别剪贴板自动下载，跳转前清剪贴板会破坏自动识别）
        migrateWechatVideoRuleV134()
        // v1.136 一次性迁移：圆通 YT 前缀单号位数放宽（13 位 → 10-13 位）
        migrateYtExpressRuleV136()
        // v1.138 一次性迁移：取件码通知规则 actionMode 修正
        //（v1.138 首版构建时 ruleActionModeFromValue 缺 NotifyOnly 分支，
        //  本地已写入 ParseAndOpen 版；覆盖安装不更新已有规则 → 需显式修正）
        migrateNotifyOnlyRuleV138()
        // v1.139.1 一次性迁移：旧版云端规则（cloud_xxx 无源标识）→ 作者源（cloud_1812z_xxx）
        migrateCloudSourcesV1391()
        // v1.139.2 幂等迁移：便捷下载规则补齐抖音提取正则 + 跳转模板（用户已编辑的规则同样受益）
        migrateEasyDownloadRuleV1392()
        // v1.139.2b 清理旧识别器保存的"模拟打开"便捷下载规则（WebViewResolveAndOpen → 移入回收站）
        cleanLegacyEasyDownloadRulesV1392()
        // v1.141 修正被 v1.140.xx 编辑器强制覆盖成 DirectOpen 的文本类内置规则 actionMode
        // （取件码→NotifyOnly，短信验证码→ClipboardWrite）。文本类编辑器无 actionMode 选择器，
        // 用户不可能主动改成 DirectOpen，此迁移只针对 builtin_ 前缀内置规则，安全。
        migratePickupTextActionMode()
        // v1.141.30 一次性迁移：美团小程序内置规则图标包名 → 美团外卖（com.sankuai.meituan.takeoutnew）
        // 背景：v1.141.29 把取件跳转规则 target.packageName 从空改为 com.sankuai.meituan（美团主App），
        //      但真机装的是美团外卖独立 App，主App非外卖包名显示不出"美团外卖"图标。
        // 幂等：每次 readRules 检查，packageName 非目标外卖包名则改（尊重用户后续手动修改）。
        migrateTakeoutJumpIconV14130()
        // v1.141.73 幂等迁移：取件码通知 extraction 位数对齐（\d{9,12}→\d{4,12}，修复 4-8 位尾号提取为空）
        // + 快递单号 matchRegex 补 JDVA（与 trigger 对齐，京东生鲜单号可查件）
        migrateTextRulesV14173()
        // v1.141.79 幂等迁移：本地美团小程序(takeout_jump) pkg 对齐——v1.141.75 assets 已改 pkg 为空
        // （实测最终走 weixin://dl/business 小程序，setPackage 强投美团App失败），本地旧版需显式迁移
        migrateTakeoutJumpPkgV14179()
        // v1.141.81 幂等迁移：外卖取件通知规则柜位/取件码正则完善（对齐 assets 权威多变体版）
        migrateWaimaiNotifyV14181()
        // v1.141.32 幂等迁移：短信验证码提取 + 取件码通知 内置规则关键词全量扩充
        // 背景：v1.141.32 默认覆盖行业全量短信码场景（认证/激活/授权/绑定/OTP/PIN + 领货/寄件/寄存等），
        //      但内置规则一旦写入本地不自动覆盖（v1.43 设计），需用 assets 权威正则显式升级原版。
        migrateSmsCodeKeywordsV14132()
        // v1.141.45 一次性迁移：取件码/快递单号 正则缺口修复（尾号4位/JDVA/百世43）
        migrateBuiltinRulesV14145()
        // v1.141.47 一次性迁移：取件码/验证码 ReDoS 修复（前导 .* → .{0,300}）
        migrateRedosFixV14147()
        // v1.141.49 一次性自检（不改规则）：定位"复制整段短信不命中快递规则"问题
        // 19:42 日志实锤：整段文本【京东物流】关于运单JD0228717729868配送情况，显示已签收
        // 诊断"不含疑似单号"，纯单号 JD0228717729868 正常命中 → 输出默认/UNICODE 边界对比 + char codes
        migrateRegexSelfTestV14149()
        // v1.141.50 一次性迁移：\b → ASCII 数字字母边界（真机 ART \w 含中文，\b 在中文与字母间无边界）
        // 根因：19:42 自检坐实——整段短信不命中、纯单号命中、charCodes 正常 → ART \b=Unicode 语义。
        // 修复：快递/验证码规则 + ExpressCompanyDetector 全部 \b 改为 (?<![A-Za-z0-9])...(?![A-Za-z0-9])
        migrateBoundaryFixV14150()
        // v1.141.52 一次性迁移：快递菜鸟规则 clearClipboardAfterJump=true（跳转前清剪贴板）
        // 根因：委托直达用 extras 传单号不依赖剪贴板，但跳转后剪贴板残留单号 → 菜鸟 JS 检测弹
        // 「是否要查询包裹」→ 自动确认 → 页面重绘展开收起（20:49 实锤偶发）。跳转前清空可根治。
        migrateCainiaoClearClipboardV14152()
        // v1.141.63 一次性迁移：淘宝·链接规则 clearClipboardAfterJump=true（跳转前清剪贴板防偶发口令弹窗）
        migrateTaobaoLinkClearClipboardV14163()
        // ===== v1.141.55 干净规则基线（无淘宝链接/闲鱼/口令规则） =====
        // 说明：v1.141.56 之后新增的淘宝系规则及其迁移(58/59/64/67/73)已在彻底回退到 55 时移除，
        // 保持与 v1.141.55 一致的 5 条内置规则（外卖/便捷下载/取件码/快递单号/短信验证码）。
        val file = rulesFile()
        val rules = if (!file.exists()) emptyList() else runCatching { rulesFromJson(file.readText()) }.getOrDefault(emptyList())
        // v1.52 防御：历史数据可能存在重复 id（异常导入/合并产生），
        // LazyColumn key={it.id} 会因重复 id 崩溃 → 去重并修复持久化
        val deduped = rules.distinctBy { it.id }
        if (deduped.size != rules.size) {
            HyperLog.d(TAG, "v1.52 duplicate rule ids detected: ${rules.size - deduped.size} removed, persisting fix")
            persistRules(deduped)
        }
        // 功能②：按优先级降序排序（priority 大者优先，同级保持原顺序）
        return deduped.sortedByDescending { it.priority }
    }

    fun saveRule(rule: RuleConfig): RuleSaveResult {
        // v1.33 空白规则防御：触发器为空（兜底 .* 匹配一切）拒绝保存，防止误跳转
        if (rule.triggerRegexes.none { it.isNotBlank() }) return RuleSaveResult.Rejected
        val currentRules = readRules()
        val existingIndex = currentRules.indexOfFirst { it.id == rule.id }
        if (existingIndex >= 0) {
            // 编辑已有规则（id 匹配）→ 正常更新
            val rules = currentRules.toMutableList()
            rules[existingIndex] = rule
            persistRules(rules)
            // v1.139.1c 内置规则被用户编辑 → 标记为"我的"（归属内置），未修改的作者原版归属云端
            if (rule.id.startsWith(BuiltinRules.ID_PREFIX)) markModifiedBuiltin(rule.id)
            return RuleSaveResult.Updated
        }
        // 新增规则 → 内容级去重：与已有规则功能内容完全相同时不重复添加
        val duplicate = currentRules.firstOrNull { it.sameContentAs(rule) }
        if (duplicate != null) return RuleSaveResult.Duplicate
        // v1.52 防御：id 已存在但内容不同（异常导入/合并）→ 重新生成唯一 id，防止列表 key 崩溃
        val finalRule = if (currentRules.any { it.id == rule.id }) rule.copy(id = UUID.randomUUID().toString()) else rule
        persistRules(currentRules + finalRule)
        return RuleSaveResult.Added
    }
    /** v1.36 合并同类规则：同目标 App（包名+分类）已有规则时合并触发器，而不是新增。
     *  解决"同一个 App 的不同口令被保存成多条规则"的问题。 */
    fun saveRuleMerged(rule: RuleConfig): RuleSaveResult {
        // 空白规则防御同 saveRule
        if (rule.triggerRegexes.none { it.isNotBlank() }) return RuleSaveResult.Rejected
        val currentRules = readRules()
        // 编辑已有规则（id 匹配）→ 正常更新
        val existingIndex = currentRules.indexOfFirst { it.id == rule.id }
        if (existingIndex >= 0) {
            val rules = currentRules.toMutableList()
            rules[existingIndex] = rule
            persistRules(rules)
            return RuleSaveResult.Updated
        }
        // 内容级去重优先
        if (currentRules.any { it.sameContentAs(rule) }) return RuleSaveResult.Duplicate
        // v1.37 合并同类规则（修正 v1.36 策略）：
        // - 内置规则（builtin_）与云规则（cloud_）保持不动，不参与合并（内置规则不被用户口令污染）
        // - 只在用户自定义规则中找同类（同目标包名+同分类）
        // - 多个同类时合并进"最近添加"的一条（createdAt 最新）；无自定义同类 → 正常新建
        val sameTarget = currentRules
            .filter {
                !it.id.startsWith(BuiltinRules.ID_PREFIX) &&
                    !it.id.startsWith("cloud_") &&
                    it.id != rule.id &&
                    it.target.packageName.isNotBlank() &&
                    it.target.packageName == rule.target.packageName &&
                    it.category == rule.category &&
                    // v1.141.62 修复：口令 vs 链接不应合并（用户实锤：先加淘宝口令再加淘宝链接被误合并）
                    // 口令规则 target 模板为空（走剪贴板），链接规则模板=${url:input}（直接开 URL），
                    // 虽同包名(com.taobao.taobao)同分类(Link)但目标模板不同 → 非同质规则，不合并
                    it.target.type == rule.target.type &&
                    it.target.template == rule.target.template
            }
            .maxByOrNull { it.createdAt }
        if (sameTarget != null) {
            val mergedTriggers = (sameTarget.triggerRegexes + rule.triggerRegexes.filter { it.isNotBlank() }).distinct()
            val merged = sameTarget.copy(
                triggerRegexes = mergedTriggers,
                matchRegex = listOfNotNull(sameTarget.matchRegex, rule.matchRegex)
                    .filter { it.isNotBlank() }.distinct().joinToString("|"),
                parameterRegex = listOfNotNull(sameTarget.parameterRegex, rule.parameterRegex)
                    .filter { it.isNotBlank() }.distinct().joinToString("|"),
                extractionRegexes = (sameTarget.extractionRegexes + rule.extractionRegexes.filter { it.isNotBlank() }).distinct(),
            )
            persistRules(currentRules.map { if (it.id == sameTarget.id) merged else it })
            return RuleSaveResult.Merged
        }
        // v1.52 防御：id 冲突保护（同 saveRule）
        val finalRule = if (currentRules.any { it.id == rule.id }) rule.copy(id = UUID.randomUUID().toString()) else rule
        persistRules(currentRules + finalRule)
        return RuleSaveResult.Added
    }
/** 查找与给定规则内容相同的已有规则（用于 UI 显示重复的规则名；排除自身） */
    fun findDuplicate(rule: RuleConfig): RuleConfig? =
        readRules().firstOrNull { it.id != rule.id && it.sameContentAs(rule) }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        val rules = readRules().map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
        }
        persistRules(rules)
    }
    // ===== 场景规则集（v1.33） =====
    /** 激活场景：备份全部规则启用状态，然后仅启用指定分组、禁用其他分组 */
    fun applyScene(group: String): Boolean {
        val rules = readRules()
        if (rules.isEmpty()) return false
        val settingsRepository = SettingsRepository(context)
        // 备份当前启用状态（id:enabled 每行一条）
        val backup = rules.joinToString("\n") { "${it.id}:${it.enabled}" }
        settingsRepository.persistSceneBackup(backup)
        settingsRepository.persistSceneGroup(group)
        val updated = rules.map { it.copy(enabled = it.group == group) }
        persistRules(updated)
        return true
    }
    /** 退出场景：恢复备份的启用状态 */
    fun exitScene() {
        val settingsRepository = SettingsRepository(context)
        val backup = settingsRepository.readSceneBackup()
        settingsRepository.persistSceneGroup("")
        settingsRepository.persistSceneBackup("")
        if (backup.isBlank()) return
        val enabledMap = backup.lineSequence().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx) to (line.substring(idx + 1) == "true")
        }.toMap()
        if (enabledMap.isEmpty()) return
        val updated = readRules().map { rule -> enabledMap[rule.id]?.let { rule.copy(enabled = it) } ?: rule }
        persistRules(updated)
    }

    // ===== 回收站（v1.26 软删除） =====
    private fun trashFile() = context.filesDir.resolve(TRASH_FILE_NAME)
    /** 移入回收站（软删除）：从 rules.json 移除并存入 trash.json */
    fun moveToTrash(ruleIds: Set<String>) {
        if (ruleIds.isEmpty()) return
        val current = readRules()
        val toTrash = current.filter { it.id in ruleIds }
        if (toTrash.isEmpty()) return
        // 记录被移入回收站的内置规则 id，避免重启后被 ensureBuiltinRules 自动补回
        val deletedBuiltin = toTrash.filter { it.id.startsWith(BuiltinRules.ID_PREFIX) }
        if (deletedBuiltin.isNotEmpty()) {
            val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_DELETED_BUILTIN_IDS, emptySet()) ?: emptySet()
            prefs.edit().putStringSet(KEY_DELETED_BUILTIN_IDS, existing + deletedBuiltin.map { it.id }).apply()
        }
        val now = System.currentTimeMillis()
        val trash = readTrash() + toTrash.map { TrashEntry(it, now) }
        persistRules(current.filterNot { it.id in ruleIds })
        writeTrash(trash)
    }
    /** 从回收站恢复 */
    fun restoreFromTrash(ruleIds: Set<String>) {
        if (ruleIds.isEmpty()) return
        val trash = readTrash()
        val restoring = trash.filter { it.rule.id in ruleIds }
        if (restoring.isEmpty()) return
        // v1.70 修复（交叉验证确认的两个 bug）：
        // ① 同 id 已存在（如还原内置重建出厂版）→ 覆盖为回收站版本（用户主动点恢复=要这个版本），
        //    原实现恢复被跳过但条目仍被移除 → 用户版本永久丢失
        // ② 不同 id 但同内容（如 cloud_ 版 vs 主库 builtin_ 版）→ 跳过不恢复（防双份并存）
        val current = readRules().toMutableList()
        var changed = false
        for (entry in restoring) {
            val rule = entry.rule
            val existingIndex = current.indexOfFirst { it.id == rule.id }
            if (existingIndex >= 0) {
                if (!current[existingIndex].sameContentAs(rule)) {
                    current[existingIndex] = rule // 覆盖：恢复回收站版本
                    changed = true
                }
                // 同 id 同内容 → 无需操作
            } else if (current.none { it.sameContentAs(rule) }) {
                current.add(rule)
                changed = true
            }
            // 不同 id 同内容 → 无意义恢复，跳过（双份防护）
        }
        if (changed) persistRules(current)
        writeTrash(trash.filterNot { it.rule.id in ruleIds })
        // 恢复内置规则时清除其删除标记（删除标记不再需要；也避免后续"删除-恢复-删除"状态混乱）
        val restoredBuiltin = restoring.map { it.rule }.filter { it.id.startsWith(BuiltinRules.ID_PREFIX) }
        if (restoredBuiltin.isNotEmpty()) {
            val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_DELETED_BUILTIN_IDS, emptySet()) ?: emptySet()
            prefs.edit().putStringSet(KEY_DELETED_BUILTIN_IDS, existing - restoredBuiltin.map { it.id }).apply()
        }
    }
    /** 彻底删除（仅从回收站清除） */
    fun purgeTrash(ruleIds: Set<String>) {
        if (ruleIds.isEmpty()) return
        writeTrash(readTrash().filterNot { it.rule.id in ruleIds })
    }
    /** 清空回收站 */
    fun emptyTrash() {
        writeTrash(emptyList())
    }
    fun readTrash(): List<TrashEntry> = runCatching {
        val text = trashFile().readText()
        val root = org.json.JSONObject(text)
        val items = root.optJSONArray("items") ?: org.json.JSONArray()
        buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val rule = ruleConfigFromJson(obj.optJSONObject("rule") ?: continue)
                add(TrashEntry(rule, obj.optLong("deletedAt", System.currentTimeMillis())))
            }
        }.distinctBy { it.rule.id } // v1.52 防御：回收站重复 id 防列表 key 崩溃
    }.getOrDefault(emptyList())
    private fun writeTrash(items: List<TrashEntry>) {
        val root = org.json.JSONObject()
        root.put("version", 1)
        val array = org.json.JSONArray()
        items.forEach { entry ->
            array.put(
                org.json.JSONObject()
                    .put("rule", entry.rule.toJson())
                    .put("deletedAt", entry.deletedAt),
            )
        }
        root.put("items", array)
        val tmp = trashFile().parentFile?.resolve(TRASH_FILE_NAME + ".tmp")
        runCatching {
            if (tmp != null) {
                tmp.writeText(root.toString(2))
                tmp.renameTo(trashFile())
            } else {
                trashFile().writeText(root.toString(2))
            }
        }
    }
    /**
     * 一键还原内置规则（v1.24）：
     * - 清除"已删除"标记 → 重新确保全部内置规则为出厂内容（覆盖用户对内建的修改，浏览器兜底保留）
     * - 用户自定义规则不受影响
     * @return 还原后内置规则数量（0 表示内置规则源为空）
     */
    fun restoreBuiltinRules(): Int {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DELETED_BUILTIN_IDS).apply()
        clearModifiedBuiltinMarks() // v1.139.1c 还原出厂=作者原版，清除"修改过"标记
        // 删除现有全部内置规则（含用户修改过的），随后 ensureBuiltinRules 以出厂内容重建
        // v1.69 修复：云下载的同源规则（cloud_link_xxx）也是内置规则的云端更新版，
        // 还原出厂时必须一并清除，否则与重建的内置规则（builtin_cloud_link_xxx）双份并存
        // （实测：21 条云下载 + 3 自定义 = 24 条，还原后 21+32+1=54 条重复暴涨）
        val current = readRules()
        val keep = current.filterNot {
            val isBuiltinOrCloud = it.id.startsWith(BuiltinRules.ID_PREFIX) || it.id.startsWith("cloud_")
            val isBrowser = it.id == BuiltinRules.BROWSER_RULE_ID || it.id == "cloud_link_浏览器"
            isBuiltinOrCloud && !isBrowser
        }
        persistRules(keep)
        ensureBuiltinRules()
        val builtin = BuiltinRules.loadAll(context)
        return builtin.size
    }

    fun reorderRules(categories: Set<RuleCategory>, orderedRuleIds: List<String>) {
        if (categories.isEmpty() || orderedRuleIds.isEmpty()) return
        val currentRules = readRules()
        val categoryRules = currentRules.filter { it.category in categories }
        val orderedIds = orderedRuleIds.toSet()
        val orderedRules = orderedRuleIds.mapNotNull { ruleId -> categoryRules.firstOrNull { it.id == ruleId } } +
            categoryRules.filterNot { it.id in orderedIds }
        // v1.70 修复（交叉验证确认）：拖拽顺序同步写入 priority（1000 起递减），
        // 否则 readRules 按 priority 排序后拖拽顺序丢失（设过 priority 的规则拖拽无效：
        // 拖拽 A 到末尾 → 下次读取 A 弹回最前）。
        // 拖拽是用户显式操作，其顺序意图应覆盖先前手动设置的 priority 数值
        // （priority 的意义就是决定顺序，顺序变了 priority 应同步，否则自相矛盾）。
        val priorityById = orderedRules.withIndex().associate { (index, rule) ->
            rule.id to (1000 - index)
        }
        val rules = currentRules.map { rule ->
            val newPriority = priorityById[rule.id]
            if (newPriority != null && newPriority != rule.priority) {
                rule.copy(priority = newPriority)
            } else {
                rule
            }
        }
        persistRules(rules)
    }

    fun persistRules(rules: List<RuleConfig>) {
        // Bug①修复：原子写（先写临时文件再 renameTo，避免多进程并发读写产生半截文件）
        val file = rulesFile()
        runCatching {
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(rulesToJson(rules))
            tmp.renameTo(file)
        }.onFailure {
            // rename 失败（跨设备等）时回退直接写
            runCatching { file.writeText(rulesToJson(rules)) }
        }
        ruleChanges.tryEmit(Unit)
    }

    /** 功能⑫：一键合并遗留重复规则——内容相同（sameContentAs）的规则合并为一条（保留第一个，其余删除） */
    fun mergeDuplicateRules(): Int {
        val rules = readRules()
        val seen = LinkedHashMap<String, RuleConfig>() // 内容签名 → 保留的规则
        val merged = mutableListOf<RuleConfig>()
        var removed = 0
        rules.forEach { rule ->
            val key = rule.contentSignature()
            val existing = seen[key]
            if (existing == null) {
                seen[key] = rule
                merged.add(rule)
            } else {
                removed++
            }
        }
        if (removed > 0) persistRules(merged)
        return removed
    }

    /** 功能⑭：批量启用/禁用 */
    fun setRulesEnabled(ruleIds: Set<String>, enabled: Boolean) {
        if (ruleIds.isEmpty()) return
        persistRules(readRules().map { rule -> if (rule.id in ruleIds) rule.copy(enabled = enabled) else rule })
    }

    /**
     * 合并内置规则到本地规则库（仅补缺失，不覆盖已存在）。
     * - 首次启动：本地无任何内置规则 → 写入全部内置规则
     * - 后续启动：跳过已存在的内置规则，保留用户编辑/云更新内容
     * - 被用户删除的内置规则会自动补回（内置规则视为系统默认）
     */
    fun ensureBuiltinRules() {
        // v1.45 一次性迁移：恢复内置淘口令为"最初云仓库"版本（用户需求：内置规则保持最开始的样子）
        // v1.9-v1.44 的 assets 名为"淘口令(新旧格式)"，用户要求回到最初的"淘口令"（旧名+旧正则）；
        // 仅执行一次（prefs 标记），且仅当用户未手动改名（name 仍含"新旧格式"）时回滚
        migrateLegacyKouLing()
        val builtin = BuiltinRules.loadAll(context)
        if (builtin.isEmpty()) return
        val current = readRules()
        // v1.43 移除 v1.36 的"版本刷新"机制：内置规则一旦写入本地，此后永不自动覆盖/修改
        // （用户需求：最开始内置的云端规则是不动的，仅缺失时补充，已有内容保持原样）
        // 浏览器兜底规则：本地可能是内置版(builtin_)或用户云下载版(cloud_)，统一移到列表末尾
        val browserIds = setOf(BuiltinRules.BROWSER_RULE_ID, "cloud_link_浏览器")

        // 1) 普通内置规则（不含浏览器）：仅补缺失，不覆盖用户编辑/云更新；跳过用户已删除的内置
        // v1.69 防御：内容级去重——已有同内容规则（如云下载的 cloud_ 版）时不再注入内置版，
        // 防止任何路径产生"同一条规则两个副本"（24→54 重复暴涨的根因之一）
        val normalBuiltin = builtin.filterNot { it.id == BuiltinRules.BROWSER_RULE_ID }
        val currentIds = current.map { it.id }.toSet()
        val rest = current.filterNot { it.id in browserIds }
        val missingNormal = normalBuiltin.filter { builtin ->
            builtin.id !in currentIds &&
                builtin.id !in deletedBuiltinIds() &&
                rest.none { it.sameContentAs(builtin) }
        }

        // 2) 浏览器兜底：本地已有则保留（移到末尾）；没有则注入内置版
        val existingBrowser = current.firstOrNull { it.id in browserIds }
        val browserFinal = existingBrowser ?: builtin.firstOrNull { it.id == BuiltinRules.BROWSER_RULE_ID }

        // 3) 组装：缺失普通内置 + 现有规则（移除浏览器） + 浏览器兜底（末尾）
        val newList = missingNormal + rest + listOfNotNull(browserFinal)

        // 无变化则不写盘（id 顺序一致 + 内容一致）
        if (newList.map { it.id } == current.map { it.id }) {
            val contentSame = newList.zip(current).all { (a, b) -> a.sameContentAs(b) }
            if (contentSame) return
        }
        persistRules(newList)
    }
        /**
     * v1.141.81 幂等迁移：外卖取件通知规则正则完善（对齐 assets 权威版）。
     * 背景：assets 已支持多变体柜位（A柜79格口/1号柜外卖柜38格口/格口号：11/3号格口）、
     *      已送至 匹配、取餐码/开柜 取件码。本地已写入旧版不覆盖（v1.43）→ 显式迁移。
     * 幂等：本地 matchRegex 含「1号柜外卖柜」特征则视为已更新，跳过。
     */
    private fun migrateWaimaiNotifyV14181() {
        if (!rulesFile().exists()) return
        val targetId = "${BuiltinRules.ID_PREFIX}cloud_text_外卖取件通知"
        val asset = runCatching {
            context.assets.open("builtin_rules/text/外卖取件通知.json").use { input ->
                val obj = org.json.JSONObject(input.bufferedReader().readText())
                val arr = obj.getJSONArray("extractionRegexes")
                Triple(obj.getString("matchRegex"), arr.getString(0), arr.getString(1))
            }
        }.getOrNull() ?: return
        val (match, cabinet, code) = asset
        var changed = false
        val migrated = runCatching {
            rulesFromJson(rulesFile().readText())
        }.getOrDefault(emptyList()).map { rule ->
            val curCabinet = rule.extractionRegexes.firstOrNull().orEmpty()
            if (rule.id == targetId && curCabinet != cabinet) {
                changed = true
                rule.copy(
                    matchRegex = match,
                    triggerRegexes = listOf(match),
                    extractionRegexes = listOf(cabinet, code),
                )
            } else rule
        }
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.81 migrate waimai notify: cabinet/code variants applied")
        }
    }

    /**
     * v1.141.79 幂等迁移：美团小程序内置规则 pkg 对齐（takeoutnew → 空）。
     * 背景：v1.141.75 assets/takeout_jump.json pkg 已改为空（实测 mt.cn → peisong → weixin://dl/business
     *       拉起微信小程序，https 网页+美团包名 setPackage 强投失败），但本地已写入旧版不覆盖（v1.43），
     *       需显式迁移。幂等：pkg 已空/非目标规则不动。
     */
    private fun migrateTakeoutJumpPkgV14179() {
        if (!rulesFile().exists()) return
        var changed = false
        val migrated = runCatching {
            rulesFromJson(rulesFile().readText())
        }.getOrDefault(emptyList()).map { rule ->
            if (rule.id == "${BuiltinRules.ID_PREFIX}cloud_link_takeout_jump" &&
                rule.target.packageName == "com.sankuai.meituan.takeoutnew"
            ) {
                changed = true
                rule.copy(target = rule.target.copy(packageName = ""))
            } else rule
        }
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.79 migrate takeout_jump pkg -> empty (weixin:// 小程序实测)")
        }
    }

    /**
     * v1.141.73 幂等迁移：文本类内置规则两处修复（本地已写入的旧版不自动覆盖，需显式迁移）。
     * ① 取件码通知 extraction 第三分支 (\\d{9,12}) → (\\d{4,12})：
     *    matchRegex 匹配 4-12 位但 extraction 只提取 9-12 位 → 「手机尾号/尾号」4-8 位命中但提取为空。
     * ② 快递单号 matchRegex 补 JDVA（trigger 已有）：京东生鲜单号 JDVA 开头不触发查件。
     * 幂等：条件不满足（已修复/非原版）时不改动；无 prefs 标记，每次 readRules 检查。
     */
    private fun migrateTextRulesV14173() {
        if (!rulesFile().exists()) return
        var changed = false
        val migrated = runCatching {
            rulesFromJson(rulesFile().readText())
        }.getOrDefault(emptyList()).map { rule ->
            when (rule.id) {
                "${BuiltinRules.ID_PREFIX}cloud_text_取件码通知" -> {
                    val ex = rule.extractionRegexes.firstOrNull() ?: return@map rule
                    val fixed = ex.replace("(\\d{9,12})", "(\\d{4,12})")
                    if (fixed != ex) {
                        changed = true
                        rule.copy(extractionRegexes = listOf(fixed) + rule.extractionRegexes.drop(1))
                    } else rule
                }
                "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless" -> {
                    if (!rule.matchRegex.contains("JDVA") && rule.matchRegex.contains("JDAP)\\d{11,15}")) {
                        changed = true
                        rule.copy(matchRegex = rule.matchRegex.replace("JDAP)\\d{11,15}", "JDAP|JDVA)\\d{11,15}"))
                    } else rule
                }
                else -> rule
            }
        }
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.73 migrate text rules: pickup digits aligned + JDVA added")
        }
    }

    /**
     * v1.101 一次性迁移：菜鸟查件内置规则正则精确化（按公司标准位数）。
     * 背景：旧正则 `(?:YT|SF|...)\d{9,20}` 过宽，10 位短号 YT1234567890 也触发跳转，
     * 菜鸟查询后提示「请检查运单号输入是否正确」。升级不覆盖已有规则（v1.43 设计），
     * 此处用 assets 权威正则显式迁移一次。
     */
    private fun migrateCainiaoRuleV101() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CAINIAO_RULE_V101, false)) return
        val cainiaoId = "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless"
        val assetRegex = runCatching {
            context.assets.open("builtin_rules/text/快递单号菜鸟查件_com.cainiao.wireless.json").use { input ->
                val json = input.bufferedReader().readText()
                val obj = org.json.JSONObject(json)
                obj.getString("matchRegex") to obj.getJSONArray("triggerRegexes").getString(0)
            }
        }.getOrNull()
        if (assetRegex != null) {
            val (newMatch, newTrigger) = assetRegex
            // 注意：不能调 readRules()（会递归进入本迁移），直接读文件解析
            var changed = false
            val migrated = runCatching {
                if (rulesFile().exists()) rulesFromJson(rulesFile().readText()) else emptyList()
            }.getOrDefault(emptyList()).map { rule ->
                // 仅迁移未改动的内置菜鸟规则（matchRegex 含旧宽松前缀段即视为原版）
                if (rule.id == cainiaoId && rule.matchRegex.contains("\\d{9,20}")) {
                    changed = true
                    rule.copy(
                        matchRegex = newMatch,
                        triggerRegexes = listOf(newTrigger) + rule.triggerRegexes.drop(1),
                    )
                } else rule
            }
            // v1.101b 修复：标记仅在真正迁移后写入（条件不满足时重试，避免永久错过）
            if (changed) {
                persistRules(migrated)
                prefs.edit().putBoolean(KEY_CAINIAO_RULE_V101, true).apply()
                HyperLog.d(TAG, "v1.101 migrate cainiao rule: precise digit lengths applied")
            }
        }
    }

    /**
     * v1.125 一次性迁移：默认关闭原作者快递100规则 + 菜鸟规则补全适配。
     * 背景：① 快递100规则(宽松9-20位,15家)与菜鸟规则(精确位数,16家)功能冗余，且优先级顺序不稳定；
     *       ② 识别器已支持35家快递，但触发规则只有16家（19家"能识别不跳转"缺口）。
     * 方案：① 快递100规则 enabled=false（默认关闭，用户可手动重开，仅迁移一次尊重后续操作）
     *       ② 菜鸟规则补全19家缺口公司正则（对齐识别器）+ priority=100（保证永远优先命中）
     * 注意：不能调 readRules()（会递归进入本迁移），直接读文件解析；仅迁移原版规则（含旧特征）。
     */
    private fun migrateExpressRulesV125() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPRESS_RULES_V125, false)) return
        val kuaidi100Id = "${BuiltinRules.ID_PREFIX}cloud_text_快递100_com.Kingdee.Express"
        val cainiaoId = "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless"
        // 从 assets 读权威新版本（快递100 enabled=false + 菜鸟补全版）
        val newKuaidi = loadAssetExpressRule("快递100_com.Kingdee.Express.json")
        val newCainiao = loadAssetExpressRule("快递单号菜鸟查件_com.cainiao.wireless.json")
        if (newKuaidi == null || newCainiao == null) return
        var changed = false
        val migrated = runCatching {
            if (rulesFile().exists()) rulesFromJson(rulesFile().readText()) else emptyList()
        }.getOrDefault(emptyList()).map { rule ->
            when (rule.id) {
                // ① 快递100 默认关闭（仅迁移一次；用户之后手动重开不会被再次关闭）
                kuaidi100Id -> if (rule.enabled) {
                    changed = true
                    rule.copy(enabled = false)
                } else rule
                // ② 菜鸟规则补全适配：旧版（无 ANEKY 特征）→ assets 权威版全量替换 + priority=100
                cainiaoId -> if (!rule.matchRegex.contains("ANEKY")) {
                    changed = true
                    rule.copy(
                        matchRegex = newCainiao.matchRegex,
                        parameterRegex = newCainiao.parameterRegex,
                        triggerRegexes = newCainiao.triggerRegexes,
                        extractionRegexes = newCainiao.extractionRegexes,
                        priority = 100,
                    )
                } else if (rule.priority < 100) {
                    changed = true
                    rule.copy(priority = 100)
                } else rule
                else -> rule
            }
        }
        if (changed) {
            persistRules(migrated)
            prefs.edit().putBoolean(KEY_EXPRESS_RULES_V125, true).apply()
            HyperLog.d(TAG, "v1.125 migrate express rules: kuaidi100 disabled, cainiao expanded to 35 companies, priority=100")
        }
    }

    /** 从 assets 读取内置规则 JSON 并解析为 RuleConfig（供迁移使用） */
    private fun loadAssetExpressRule(fileName: String): RuleConfig? = runCatching {
        context.assets.open("builtin_rules/text/$fileName").use { input ->
            val json = input.bufferedReader().readText()
            rulesFromJson(json).firstOrNull()
        }
    }.getOrNull()

    /**
     * v1.132 一次性迁移：Chrome 通配规则降级（priority=0 → -100）。
     * 背景：Chrome 规则 `https?://[^\s]+` 匹配所有 URL 且排在前面，抢占特定域名规则
     * （如微信视频号→便捷下载），且用户未装 Chrome 时触发网页兜底（跳浏览器）。
     * 降级后：特定规则（priority≥0）先命中，Chrome 通配仅作最后兜底。
     */
    private fun migrateChromeRuleV132() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CHROME_RULE_V132, false)) return
        val chromeId = "${BuiltinRules.ID_PREFIX}cloud_link_Chrome_com.android.chrome"
        var changed = false
        val migrated = runCatching {
            if (rulesFile().exists()) rulesFromJson(rulesFile().readText()) else emptyList()
        }.getOrDefault(emptyList()).map { rule ->
            // 仅迁移原版通配 Chrome 规则（priority=0 且 matchRegex 含通配特征）
            if (rule.id == chromeId && rule.priority == 0 && rule.matchRegex.contains("https?://[^\\s]+")) {
                changed = true
                rule.copy(priority = -100)
            } else rule
        }
        if (changed) {
            persistRules(migrated)
            prefs.edit().putBoolean(KEY_CHROME_RULE_V132, true).apply()
            HyperLog.d(TAG, "v1.132 migrate chrome rule: priority downgraded to -100 (wildcard fallback)")
        }
    }
    /**
     * v1.134 一次性迁移：撤销 v1.133 错误写入的 clearClipboardAfterJump=true。
     * 背景：v1.133 误以为「便捷下载会自动识别剪贴板弹提示，用户不需要」→ 跳转前清剪贴板。
     * 用户澄清：**便捷下载需要识别剪贴板自动下载**（复制链接→跳转→自动下载是用户要的），
     * 跳转前清剪贴板导致便捷下载打开时剪贴板为空 → 无法自动识别 → 错误。
     * 本迁移把本地已写入的 true 改回 false（仅一次，尊重用户后续手动修改）。
     */
    private fun migrateWechatVideoRuleV134() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_WECHAT_VIDEO_RULE_V134, false)) return
        val wechatVideoId = "${BuiltinRules.ID_PREFIX}cloud_link_微信视频号下载_com.lcw.easydownload"
        var changed = false
        val migrated = runCatching {
            if (rulesFile().exists()) rulesFromJson(rulesFile().readText()) else emptyList()
        }.getOrDefault(emptyList()).map { rule ->
            if (rule.id == wechatVideoId && rule.clearClipboardAfterJump) {
                changed = true
                rule.copy(clearClipboardAfterJump = false)
            } else rule
        }
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.134 migrate wechat video rule: clearClipboardAfterJump reset to false (撤销v1.133)")
        }
        prefs.edit().putBoolean(KEY_WECHAT_VIDEO_RULE_V134, true).apply()
    }
    private fun rulesFile() = context.filesDir.resolve(RULES_FILE_NAME)

    /**
     * v1.141.32 幂等迁移：短信验证码提取 + 取件码通知 内置规则关键词全量扩充。
     * 背景：内置规则写入本地后不再自动覆盖（v1.43 设计），而 v1.141.32 默认将关键词扩充到
     *      全量短信码场景（验证码类：认证/激活/授权/绑定/确认/登录/支付/注册 + OTP/PIN/
     *      one-time password；取件码类：领货/寄件/寄存/出库/驿站/存包/暂存）。
     * 方案：每次 readRules 检查，仅当本地内置规则仍为「旧关键词集」（缺新特征词）时，
     *      用 assets 权威 JSON 的 matchRegex/triggerRegexes/extractionRegexes 覆盖升级。
     *      尊重用户改过的新版（若已含新特征词则不动）。
     */
    private fun migrateSmsCodeKeywordsV14132() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        // v1.141.35 修复死循环：原实现每次 readRules 都重跑且 feature 判定因 \\s 转义
        // 与本地存储(双反斜杠)不匹配 → 永远判定旧版 → 每次覆盖写盘 → readRules 无限循环 → ANR 无响应。
        // 改为一次性迁移（prefs 标记，只执行一次），彻底消除循环。
        if (prefs.getBoolean(KEY_SMS_CODE_MIGRATED_V14135, false)) return
        val file = rulesFile()
        if (!file.exists()) { prefs.edit().putBoolean(KEY_SMS_CODE_MIGRATED_V14135, true).apply(); return }
        data class Target(val ruleId: String, val assetFile: String)
        val targets = listOf(
            Target(
                "${BuiltinRules.ID_PREFIX}cloud_text_短信验证码提取",
                "短信验证码提取.json",
            ),
            Target(
                "${BuiltinRules.ID_PREFIX}cloud_text_取件码通知",
                "取件码通知.json",
            ),
            Target(
                "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless",
                "快递单号菜鸟查件_com.cainiao.wireless.json",
            ),
        )
        // 一次性读所有 assets 权威正则，无条件覆盖对应的内置规则（用户核心内置规则，覆盖无损）
        val assetRegexById = targets.associate { t ->
            val data = runCatching {
                context.assets.open("builtin_rules/text/${t.assetFile}").use { input ->
                    val obj = org.json.JSONObject(input.bufferedReader().readText())
                    Triple(
                        obj.getString("matchRegex"),
                        obj.optJSONArray("triggerRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                        obj.optJSONArray("extractionRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                    )
                }
            }.getOrNull()
            t.ruleId to data
        }
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                val asset = assetRegexById[rule.id] ?: return@map rule
                val regexes = asset ?: return@map rule
                // 仅覆盖 builtin_ 内置版（不碰用户自定义 UUID 规则）
                if (rule.id.startsWith(BuiltinRules.ID_PREFIX)) {
                    changed = true
                    rule.copy(
                        matchRegex = regexes.first,
                        triggerRegexes = regexes.second,
                        extractionRegexes = regexes.third,
                    )
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.32 migrate sms-code keywords: 验证码/取件码/快递单号 内置规则已刷新")
        }
        // 无论是否有变更，标记已迁移（一次性）
        prefs.edit().putBoolean(KEY_SMS_CODE_MIGRATED_V14135, true).apply()
    }

    /**
     * v1.141.30 一次性迁移：美团小程序内置规则 target.packageName → 美团外卖。
     * 背景：v1.141.29 为显示该规则图标，把 packageName 从空改为 com.sankuai.meituan（美团主App），
     *      但真机实际装的是美团外卖独立 App（com.sankuai.meituan.takeoutnew），
     *      主 App 图标不是"美团外卖"logo → 显示普通美团图标，非用户要的外卖图标。
     * 方案：幂等迁移——每次 readRules 检查，仅当本地该内置规则 target.packageName 非目标外卖包名时
     *      统一改为美团外卖包名（仅针对 builtin_ 内置版，不碰用户自定义 UUID 规则）。
     * 注意：mt.cn 外卖跳转走 WebView（startWebViewResolve 固定传空包名），此处 packageName 纯图标展示。
     */
    /**
     * v1.141.45 一次性迁移：取件码/快递单号 内置规则正则升级（代码级测试缺口修复）：
     * ① 取件码通知第三分支 尾号/虚拟号 数字位数 9-12 → 4-12（覆盖"凭手机尾号xxxx取件"真实句式）
     * ② 快递单号补 JDVA 前缀（京东物流标准单号）+ 百世 43 号段 13 位定长放宽到 10-13 位
     * 无条件刷 2 条内置规则（读 assets 权威版），末尾置标记，一次性（v1.141.37 死循环修复模式）。
     */
    /**
     * v1.141.47 一次性迁移：取件码/验证码 内置规则 ReDoS 修复（审计发现 O(n²) 回溯）：
     * 前导 .* 改为有界 .{0,300}（短信正文 <300 字符全覆盖；超长文本快速失败，O(n²)→线性）。
     * 实测：取件码 16000 字符 6273ms→384ms，验证码 8000 字符 3853ms→470ms。
     * 无条件刷 2 条内置规则（读 assets 权威版），末尾置标记，一次性。
     */
    private fun migrateRedosFixV14147() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REDOS_FIX_V14147, false)) return
        val file = rulesFile()
        if (!file.exists()) { prefs.edit().putBoolean(KEY_REDOS_FIX_V14147, true).apply(); return }
        val targets = listOf(
            "${BuiltinRules.ID_PREFIX}cloud_text_取件码通知" to "取件码通知.json",
            "${BuiltinRules.ID_PREFIX}cloud_text_短信验证码提取" to "短信验证码提取.json",
        )
        val assetById = targets.associate { (id, asset) ->
            id to runCatching {
                context.assets.open("builtin_rules/text/$asset").use { input ->
                    val obj = org.json.JSONObject(input.bufferedReader().readText())
                    Triple(
                        obj.getString("matchRegex"),
                        obj.optJSONArray("triggerRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                        obj.optJSONArray("extractionRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                    )
                }
            }.getOrNull()
        }
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                val asset = assetById[rule.id] ?: return@map rule
                if (rule.id.startsWith(BuiltinRules.ID_PREFIX)) {
                    changed = true
                    rule.copy(
                        matchRegex = asset.first,
                        triggerRegexes = asset.second,
                        extractionRegexes = asset.third,
                    )
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.47 migrate redos fix: 取件码/验证码 前导 .* 改有界 .{0,300} (ReDoS修复)")
        }
        prefs.edit().putBoolean(KEY_REDOS_FIX_V14147, true).apply()
    }

    /**
     * v1.141.49 一次性正则自检（不改任何规则，只输出诊断日志）：
     * 定位 19:42 真机"复制整段短信文本不命中快递规则"问题——
     * 整段【京东物流】关于运单JD0228717729868配送情况，显示已签收 诊断"不含疑似单号"，
     * 纯单号 JD0228717729868 正常命中。桌面 JDK 实测两种输入均命中 →
     * 怀疑 ART 正则 \b 对中文边界处理差异（UNICODE_CHARACTER_CLASS）或输入含隐藏字符。
     * 输出：extractTrackingNumber 结果 / 默认 vs UNICODE 边界匹配 / char codes，I 级必落盘。
     */
    private fun migrateRegexSelfTestV14149() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REGEX_SELFTEST_V14150, false)) return
        prefs.edit().putBoolean(KEY_REGEX_SELFTEST_V14150, true).apply()
        val sample = "【京东物流】关于运单JD0228717729868配送情况，显示已签收"
        val pure = "JD0228717729868"
        try {
            HyperLog.i(TAG, "[selftest] 样例整段: $sample (len=${sample.length})")
            // ① extractTrackingNumber
            val exFull = ExpressCompanyDetector.extractTrackingNumber(sample)
            val exPure = ExpressCompanyDetector.extractTrackingNumber(pure)
            HyperLog.i(TAG, "[selftest] extractTrackingNumber 整段=${exFull ?: "null"} | 纯单号=${exPure ?: "null"}")
            // ② 规则 trigger 匹配：默认 vs UNICODE_CHARACTER_CLASS
            val file = rulesFile()
            val express = if (file.exists()) {
                runCatching { rulesFromJson(file.readText()) }.getOrDefault(emptyList())
                    .firstOrNull { it.category == RuleCategory.Express }
            } else null
            val trigger = express?.triggerPatterns()?.firstOrNull()
            if (trigger != null) {
                val defaultHit = cachedRegex(trigger, express.regexOptions).containsMatchIn(sample)
                // Kotlin RegexOption 无 UNICODE_CHARACTER_CLASS → 用 Java Pattern 直接编译对比
                val uniHit = runCatching {
                    java.util.regex.Pattern.compile(trigger, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
                        .matcher(sample).find()
                }.getOrDefault(false)
                val pureHit = cachedRegex(trigger, express.regexOptions).containsMatchIn(pure)
                HyperLog.i(TAG, "[selftest] trigger[0] 整段: default=$defaultHit unicode=$uniHit | 纯单号: default=$pureHit")
                HyperLog.i(TAG, "[selftest] regexOptions='${express.regexOptions}' rule=${express.id} trigger=${trigger.take(80)}...")
            } else {
                HyperLog.i(TAG, "[selftest] 未找到 Express 规则 trigger")
            }
            // ③ char codes（前 40 字符，检查隐藏字符）
            val sb = StringBuilder()
            for (c in sample.take(40)) {
                sb.append(Integer.toHexString(c.code).uppercase()).append(' ')
            }
            val codes = sb.toString()
            HyperLog.i(TAG, "[selftest] charCodes: $codes")
        } catch (e: Throwable) {
            HyperLog.i(TAG, "[selftest] 异常: ${e.message}")
        }
    }

    /**
     * v1.141.50 一次性迁移：正则边界 \b → ASCII 数字字母边界 lookaround。
     * 根因（v1.141.49 自检坐实）：真机 ART 的 java.util.regex \w 含中文（Unicode 语义），
     * \b 在中文与字母/数字之间无边界 → 整段短信【京东物流】关于运单JD0228717729868...
     * 规则命中失败 + diagnose "不含疑似单号"（desktop JDK \b=ASCII 正常 → 本地代码级测试全过，
     * 真机 19:42 复制整段短信不触发、复制纯单号正常触发）。
     * 修复：assets 快递/验证码规则 + ExpressCompanyDetector 的 \b 改为
     * (?<![A-Za-z0-9])...(?![A-Za-z0-9])（中文/标点/开头 = 边界，字母数字紧贴 = 无边界，语义与 ASCII \b 等价）。
     * 刷 2 条内置规则（快递/验证码，取件码无 \b 不涉及），末尾置标记，一次性。
     */
    private fun migrateBoundaryFixV14150() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BOUNDARY_FIX_V14150, false)) return
        val file = rulesFile()
        if (!file.exists()) { prefs.edit().putBoolean(KEY_BOUNDARY_FIX_V14150, true).apply(); return }
        val targets = listOf(
            "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless" to "快递单号菜鸟查件_com.cainiao.wireless.json",
            "${BuiltinRules.ID_PREFIX}cloud_text_短信验证码提取" to "短信验证码提取.json",
        )
        val assetById = targets.associate { (id, asset) ->
            id to runCatching {
                context.assets.open("builtin_rules/text/$asset").use { input ->
                    val obj = org.json.JSONObject(input.bufferedReader().readText())
                    Triple(
                        obj.getString("matchRegex"),
                        obj.optJSONArray("triggerRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                        obj.optJSONArray("extractionRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                    )
                }
            }.getOrNull()
        }
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                val asset = assetById[rule.id] ?: return@map rule
                if (rule.id.startsWith(BuiltinRules.ID_PREFIX)) {
                    changed = true
                    rule.copy(
                        matchRegex = asset.first,
                        triggerRegexes = asset.second,
                        extractionRegexes = asset.third,
                    )
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.50 migrate boundary fix: 快递/验证码 \\b→ASCII边界 (ART中文边界修复)")
        }
        prefs.edit().putBoolean(KEY_BOUNDARY_FIX_V14150, true).apply()
    }

    /**
     * v1.141.52 一次性迁移：快递菜鸟规则 clearClipboardAfterJump=true（跳转前清剪贴板）。
     * 根因：委托直达用 extras 传单号不依赖剪贴板，但跳转后剪贴板残留单号 → 菜鸟 JS 检测剪贴板
     * 弹「是否要查询包裹」→ 自动确认 → 页面重绘 → 详情页展开状态丢失（收起）。
     * 20:49 测试实锤：弹窗偶发（与清剪贴板时机竞态）。跳转前清空剪贴板可根治弹窗。
     * 代码层已兜底（PendingJumpCoordinator isEntrustIntent → launchAfterClipboardClear 强制清），
     * 此迁移同步本地规则字段保持一致（用户查看/导出规则时正确显示）。
     */
    private fun migrateCainiaoClearClipboardV14152() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CAINIAO_CLEAR_CLIPBOARD_V14152, false)) return
        val file = rulesFile()
        if (!file.exists()) { prefs.edit().putBoolean(KEY_CAINIAO_CLEAR_CLIPBOARD_V14152, true).apply(); return }
        val cainiaoId = "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless"
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                if (rule.id == cainiaoId && rule.id.startsWith(BuiltinRules.ID_PREFIX) && !rule.clearClipboardAfterJump) {
                    changed = true
                    rule.copy(clearClipboardAfterJump = true)
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.52 migrate cainiao clear-clipboard: 快递菜鸟规则跳转前清剪贴板(true)")
        }
        prefs.edit().putBoolean(KEY_CAINIAO_CLEAR_CLIPBOARD_V14152, true).apply()
    }
    private fun migrateTaobaoLinkClearClipboardV14163() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TAOBAO_LINK_CLEAR_CLIPBOARD_V14163, false)) return
        val file = rulesFile()
        if (!file.exists()) { prefs.edit().putBoolean(KEY_TAOBAO_LINK_CLEAR_CLIPBOARD_V14163, true).apply(); return }
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                // 淘宝·链接规则特征：target.template 为 ${url:input} + matchRegex 含淘宝系域名
                // （口令规则 template 空，不迁移——淘宝需读剪贴板口令弹窗，由无障碍自动确认处理）
                val isTaobaoLink = rule.target.template.orEmpty().contains("\${url:input}") &&
                    (rule.matchRegex.contains("taobao.com") || rule.matchRegex.contains("tb.cn") ||
                        rule.matchRegex.contains("tmall.com") || rule.matchRegex.contains("e.tb.cn"))
                if (isTaobaoLink && !rule.clearClipboardAfterJump) {
                    changed = true
                    rule.copy(clearClipboardAfterJump = true)
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.63 migrate taobao link clear-clipboard: 淘宝链接规则跳转前清剪贴板(true)")
        }
        prefs.edit().putBoolean(KEY_TAOBAO_LINK_CLEAR_CLIPBOARD_V14163, true).apply()
    }
    private fun migrateBuiltinRulesV14145() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_RULES_V14145, false)) return
        val file = rulesFile()
        if (!file.exists()) { prefs.edit().putBoolean(KEY_BUILTIN_RULES_V14145, true).apply(); return }
        val targets = listOf(
            "${BuiltinRules.ID_PREFIX}cloud_text_取件码通知" to "取件码通知.json",
            "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless" to "快递单号菜鸟查件_com.cainiao.wireless.json",
        )
        val assetById = targets.associate { (id, asset) ->
            id to runCatching {
                context.assets.open("builtin_rules/text/$asset").use { input ->
                    val obj = org.json.JSONObject(input.bufferedReader().readText())
                    Triple(
                        obj.getString("matchRegex"),
                        obj.optJSONArray("triggerRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                        obj.optJSONArray("extractionRegexes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: listOf(obj.getString("matchRegex")),
                    )
                }
            }.getOrNull()
        }
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                val asset = assetById[rule.id] ?: return@map rule
                if (rule.id.startsWith(BuiltinRules.ID_PREFIX)) {
                    changed = true
                    rule.copy(
                        matchRegex = asset.first,
                        triggerRegexes = asset.second,
                        extractionRegexes = asset.third,
                    )
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.45 migrate builtin rules: 取件码尾号4位/JDVA/百世43号段 内置规则已刷新")
        }
        prefs.edit().putBoolean(KEY_BUILTIN_RULES_V14145, true).apply()
    }
    private fun migrateTakeoutJumpIconV14130() {
        // v1.141.80 废弃：v1.141.75 实测 mt.cn → peisong.meituan.com → weixin://dl/business 拉起微信小程序，
        // pkg 必须为空（https 网页 + 美团包名 setPackage 强投失败）。v1.141.30 强制 takeoutnew 与新标准冲突，
        // 且与 v1.141.79 迁移互相拉锯（每次 readRules 来回改写）→ 本函数直接失效。
        return
        val file = rulesFile()
        if (!file.exists()) return
        val targetId = "${BuiltinRules.ID_PREFIX}cloud_link_takeout_jump"
        val targetPkg = "com.sankuai.meituan.takeoutnew"
        var changed = false
        val migrated = runCatching {
            rulesFromJson(file.readText()).map { rule ->
                if (rule.id == targetId && rule.target.packageName != targetPkg &&
                    rule.id.startsWith(BuiltinRules.ID_PREFIX)
                ) {
                    changed = true
                    rule.copy(target = rule.target.copy(packageName = targetPkg))
                } else rule
            }
        }.getOrDefault(emptyList())
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.141.30 migrate takeout_jump icon packageName -> $targetPkg")
        }
    }

    /**
     * v1.136 一次性迁移：圆通 YT 前缀单号位数放宽（\d{13} → \d{10,13}）。
     * 背景：用户实测 YT1234567890（YT + 10 位数字）为有效圆通单号（菜鸟 App 可查到包裹），
     * 原规则 `(?:YTO|YT)\d{13}` 仅匹配 13 位数字 → 10 位 YT 单号无法识别。
     * YTO 前缀保持 13 位；YT 前缀放宽为 10-13 位。仅执行一次（尊重用户后续手动修改）。
     */
    private fun migrateYtExpressRuleV136() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_YT_EXPRESS_RULE_V136, false)) return
        val expressId = "${BuiltinRules.ID_PREFIX}cloud_text_快递单号菜鸟查件_com.cainiao.wireless"
        val oldSeg = "(?:YTO|YT)\\d{13}"
        val newSeg = "(?:YTO)\\d{13}|(?:YT)\\d{10,13}"
        var changed = false
        val migrated = runCatching {
            if (rulesFile().exists()) rulesFromJson(rulesFile().readText()) else emptyList()
        }.getOrDefault(emptyList()).map { rule ->
            if (rule.id == expressId) {
                var r = rule
                if (r.matchRegex.contains(oldSeg)) {
                    r = r.copy(matchRegex = r.matchRegex.replace(oldSeg, newSeg))
                    changed = true
                }
                val triggers = r.triggerRegexes.map { if (it.contains(oldSeg)) { changed = true; it.replace(oldSeg, newSeg) } else it }
                if (triggers != r.triggerRegexes) r = r.copy(triggerRegexes = triggers)
                r
            } else rule
        }
        if (changed) {
            persistRules(migrated)
            HyperLog.d(TAG, "v1.136 migrate yt express rule: YT prefix digit range 13 -> 10-13")
        }
        prefs.edit().putBoolean(KEY_YT_EXPRESS_RULE_V136, true).apply()
    }

    /** v1.138 一次性迁移：取件码通知规则 actionMode 修正（notify_only 解析缺陷修复）。
     * 背景：v1.138 首版构建时 ruleActionModeFromValue 缺 NotifyOnly 分支，
     * "notify_only" 被解析成 ParseAndOpen 写入本地；覆盖安装不更新已有规则（v1.43 设计），
     * 需用 assets 权威 actionMode 显式修正本地规则一次。 */
    private fun migrateNotifyOnlyRuleV138() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Config.KEY_NOTIFY_ONLY_RULE_V138, false)) return
        val targetId = "${BuiltinRules.ID_PREFIX}cloud_text_取件码通知"
        val assetActionMode = runCatching {
            context.assets.open("builtin_rules/text/取件码通知.json").use { input ->
                org.json.JSONObject(input.bufferedReader().readText()).optString("actionMode")
            }
        }.getOrNull()
        if (assetActionMode != null && rulesFile().exists()) {
            var changed = false
            val migrated = rulesFromJson(rulesFile().readText()).map { rule ->
                if (rule.id == targetId && rule.actionMode.value != assetActionMode) {
                    changed = true
                    rule.copy(actionMode = ruleActionModeFromValue(assetActionMode))
                } else rule
            }
            if (changed) {
                persistRules(migrated)
                HyperLog.d(TAG, "v1.138 migrate notify_only rule: actionMode -> $assetActionMode")
            }
        }
        prefs.edit().putBoolean(Config.KEY_NOTIFY_ONLY_RULE_V138, true).apply()
    }
    /**
     * v1.139.1 幂等迁移：旧版云端规则（cloud_{folder}_{name} 无源标识）→ 作者源（cloud_1812z_{folder}_{name}）。
     * 背景：换源功能上线前下载的云端规则无源标识；换源后下载带源标识（cloud_{源key}_...），
     * 旧规则若不迁移将无法与作者源新下载规则合并（同规则双份）。
     * v1.139.1b 修复（用户反馈未迁移成功）：
     *  ① 去掉一次性键 → 每次 readRules 幂等检查（旧格式规则随时迁移，安全可重复）
     *  ② 去重保护用户修改：同 id 冲突时优先保留旧格式迁移来的版本（用户可能改过），删新下载默认版
     */
    /** v1.139.1c 记录用户修改过的内置规则 id */
    private fun markModifiedBuiltin(id: String) {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_MODIFIED_BUILTIN_IDS, emptySet()) ?: emptySet()
        if (id in existing) return
        prefs.edit().putStringSet(KEY_MODIFIED_BUILTIN_IDS, existing + id).apply()
        HyperLog.d(TAG, "builtin rule modified by user: $id")
    }

    /** v1.139.1c 读取用户修改过的内置规则 id 集合（来源判定：修改过=内置/我的） */
    fun modifiedBuiltinRuleIds(): Set<String> {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_MODIFIED_BUILTIN_IDS, emptySet()) ?: emptySet()
    }

    /** v1.139.1c 还原出厂时清空修改标记（内置规则回到作者原版归属云端） */
    private fun clearModifiedBuiltinMarks() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_MODIFIED_BUILTIN_IDS).apply()
    }

    /**
     * v1.139.2 幂等迁移：便捷下载规则全面升级——视频平台通配（15+ 平台）+ 任意 URL 提取。
     * 背景：用户把抖音分享链接加入规则后 extractionRegexes 仍只匹配微信 → 跳转无有效 URL；
     * 且用户确认：视频平台分享链接全部走便捷下载（智能识别器同步适配）。
     * 本迁移：检测触发正则是否含 kuaishou（全面版标记），不含则全量升级为全面配置：
     * ① name → 便捷下载
     * ② trigger/matchRegex → 15+ 视频平台域名通配（抖音/快手/B站/小红书/微博/西瓜/皮皮虾/火山/美拍/秒拍/YouTube/TikTok/微信视频号）
     * ③ extraction → 平台 URL 精确提取 + 微信视频号提取（r1/r2）
     * ④ template → \${r1}\${r2}（带 URL 跳转便捷下载）
     * 幂等：每次 readRules 检查，安全可重复。
     */
    /**
     * v1.139.2b 清理旧识别器保存的"模拟打开"便捷下载规则。
     * 背景：早期识别器对视频平台建议 WebViewResolveAndOpen + template=${r1}，
     * 用户保存后生成 UUID 自定义规则 → 与内置"便捷下载"规则重复，且命中会跳浏览器。
     * 本清理：检测 非内置 + 包名=com.lcw.easydownload + actionMode=WebViewResolveAndOpen 的规则
     * → 移入回收站（可恢复，不误删）。幂等：每次 readRules 检查。
     */
    private fun cleanLegacyEasyDownloadRulesV1392() {
        val file = rulesFile()
        if (!file.exists()) return
        val rules = runCatching { rulesFromJson(file.readText()) }.getOrDefault(emptyList())
        val legacy = rules.filter {
            !it.id.startsWith(BuiltinRules.ID_PREFIX) &&
                it.target.packageName == "com.lcw.easydownload" &&
                it.actionMode == RuleActionMode.WebViewResolveAndOpen
        }
        if (legacy.isEmpty()) return
        val legacyIds = legacy.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val trash = readTrash() + legacy.map { TrashEntry(it, now) }
        persistRules(rules.filterNot { it.id in legacyIds })
        writeTrash(trash)
        HyperLog.d(TAG, "v1.139.2b clean legacy easy-download rules (WebViewResolveAndOpen): ${legacy.size} moved to trash")
    }

    private fun migrateEasyDownloadRuleV1392() {
        val file = rulesFile()
        if (!file.exists()) return
        val rules = runCatching { rulesFromJson(file.readText()) }.getOrDefault(emptyList())
        val idx = rules.indexOfFirst { it.id == EASY_DOWNLOAD_RULE_ID }
        if (idx < 0) return
        val rule = rules[idx]
        if (rule.triggerRegexes.any { it.contains("kuaishou") } && rule.triggerRegexes.any { it.contains("douyu") } && rule.target.template.isBlank()) return // 已全面版（含直播平台+直接打开）
        val domains = listOf(
            "douyin\\.com", "iesdouyin\\.com",
            "kuaishou\\.com", "gifshow\\.com",
            "b23\\.tv", "bilibili\\.com",
            "xhslink\\.com", "xiaohongshu\\.com", "xhslink\\.cn",
            "weibo\\.com", "weibo\\.cn",
            "ixigua\\.com", "pipix\\.com", "huoshan\\.com",
            "meipai\\.com", "miaopai\\.com", "douyu\\.com", "huya\\.com", "yy\\.com",
            "youtu\\.be", "youtube\\.com",
            "vt\\.tiktok\\.com", "tiktok\\.com",
        ).joinToString("|")
        val platformUrl = "https?://(?:[a-zA-Z0-9-]+\\.)*(?:$domains)[^\\s]*"
        val wxSph = "https?://weixin\\.qq\\.com/sph/[A-Za-z0-9_-]+"
        val extractPlatform = "(https?://(?:[a-zA-Z0-9-]+\\.)*(?:$domains)[^\\s]*)"
        val extractWx = "(https?://weixin\\.qq\\.com/sph/[A-Za-z0-9_-]+)"
        val upgraded = rule.copy(
            name = "便捷下载",
            matchRegex = "($platformUrl|$wxSph)",
            triggerRegexes = listOf("($platformUrl|$wxSph)"),
            extractionRegexes = listOf(extractPlatform, extractWx),
            priority = 100,
            // v1.139.2b 直接打开便捷下载（不带 URL，App 读剪贴板识别；带 URL 会触发 VIEW 处理失败跳浏览器）
            target = rule.target.copy(template = ""),
        )
        persistRules(rules.mapIndexed { i, r -> if (i == idx) upgraded else r })
        HyperLog.d(TAG, "v1.139.2 migrate easy-download rule: upgraded to video-platform wildcard (name=便捷下载)")
    }

    /**
     * v1.141 修正 v1.140.xx 编辑器 bug 污染的文本类内置规则 actionMode：
     * 编辑器此前对非 Link 类规则强制保存为 DirectOpen，导致内置"取件码通知"（应为 NotifyOnly）
     * 和"短信验证码提取"（应为 ClipboardWrite）被覆盖成 DirectOpen，走跳转链路 → 灵动岛显示跳转确认而非结果。
     * 文本类编辑器无 actionMode 选择器，用户不可能主动改成 DirectOpen，此迁移只针对 builtin_ 前缀内置规则，安全。
     */
    private fun migratePickupTextActionMode() {
        val file = rulesFile()
        if (!file.exists()) return
        val rules = runCatching { rulesFromJson(file.readText()) }.getOrDefault(emptyList())
        var changed = false
        val fixed = rules.map { rule ->
            val correct = when {
                rule.id.startsWith(BuiltinRules.ID_PREFIX) && rule.id.contains("取件码") -> RuleActionMode.NotifyOnly
                rule.id.startsWith(BuiltinRules.ID_PREFIX) && rule.id.contains("短信验证码") -> RuleActionMode.ClipboardWrite
                else -> null
            }
            if (correct != null && rule.actionMode != correct) {
                changed = true
                rule.copy(actionMode = correct)
            } else rule
        }
        if (changed) {
            persistRules(fixed)
            HyperLog.d(TAG, "v1.141 校正文本类内置规则 actionMode（取件码/短信验证码）")
        }
    }

    private fun migrateCloudSourcesV1391() {
        val file = rulesFile()
        if (!file.exists()) return
        val rules = rulesFromJson(file.readText())
        var changed = false
        val migrated = rules.map { rule ->
            // 旧云端规则：cloud_{folder}_{name}（无源 key，且非 builtin_ 前缀）
            if (rule.id.startsWith("cloud_") && !rule.id.startsWith("cloud_1812z_") && !rule.id.startsWith("cloud_snacks_") && !rule.id.startsWith("cloud_custom_")) {
                val newId = rule.id.replaceFirst("cloud_", "cloud_1812z_")
                changed = true
                rule.copy(id = newId)
            } else rule
        }
        if (changed) {
            // 去重：同 id 冲突时优先保留列表中靠前（迁移来源/用户修改）的版本
            val seen = HashSet<String>()
            val deduped = ArrayList<RuleConfig>(migrated.size)
            for (rule in migrated) {
                if (seen.add(rule.id)) deduped.add(rule)
            }
            if (deduped.size != migrated.size) {
                HyperLog.d(TAG, "v1.139.1b 云端规则迁移去重: ${migrated.size - deduped.size} 条重复合并")
            }
            persistRules(deduped)
        }
    }

    /**
     * v1.45 一次性迁移：内置淘口令恢复为最初云仓库版本（"淘口令"旧名+旧正则）。
     * 背景：v1.9 起 assets 内置名为"淘口令(新旧格式)"（v1.36 还会刷新覆盖老用户），
     * 用户要求内置规则保持"最开始的样子" → 恢复为云仓库原始淘口令。
     * 仅执行一次（SharedPreferences 标记），仅当用户未手动改名时回滚（保护用户修改）。
     */
    private fun migrateLegacyKouLing() {
        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_KOU_LING_MIGRATED_V145, false)) return
        val taobaoId = "${BuiltinRules.ID_PREFIX}cloud_link_淘口令_com.taobao.taobao"
        val oldRegex = "[_\\$￥₳€£][a-zA-Z0-9]{6,20}[_\\$￥₳€£]"
        val migrated = readRules().map { rule ->
            if (rule.id == taobaoId && rule.name.contains("新旧格式")) {
                rule.copy(
                    name = "淘口令",
                    matchRegex = oldRegex,
                    parameterRegex = oldRegex,
                    triggerRegexes = listOf(oldRegex),
                    extractionRegexes = listOf("($oldRegex)"),
                )
            } else rule
        }
        persistRules(migrated)
        prefs.edit().putBoolean(KEY_KOU_LING_MIGRATED_V145, true).apply()
        HyperLog.d(TAG, "v1.45 migrate kouling: restored original taobao kouling rule")
    }

    /** 用户已删除的内置规则 id 集合（删除后重启/同步不自动补回） */
    private fun deletedBuiltinIds(): Set<String> =
        context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_DELETED_BUILTIN_IDS, emptySet()) ?: emptySet()

    companion object {
        const val TAG = "HyperCopy-RuleRepo"
        private val ruleChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changes = ruleChanges.asSharedFlow()

        const val RULES_FILE_NAME = "rules.json"
        const val TRASH_FILE_NAME = "trash.json"

        private const val KEY_DELETED_BUILTIN_IDS = "deleted_builtin_rule_ids"
    /** v1.139.1c 用户修改过的内置规则 id（修改后归属"内置/我的"，未修改的作者原版归属"云端"） */
    private const val KEY_MODIFIED_BUILTIN_IDS = "key_modified_builtin_ids"
    /** v1.139.2 便捷下载（微信视频号下载）规则稳定 id */
    private const val EASY_DOWNLOAD_RULE_ID = "builtin_cloud_link_微信视频号下载_com.lcw.easydownload"
        /** v1.45 一次性迁移标记：内置淘口令已恢复为最初云仓库版本 */
        private const val KEY_KOU_LING_MIGRATED_V145 = "kouling_migrated_v145"
        /** v1.101 一次性迁移标记：菜鸟规则正则精确化（按公司标准位数）；b 版修复：仅迁移成功才标记 */
        private const val KEY_CAINIAO_RULE_V101 = "cainiao_rule_v101b"
        /** v1.125 一次性迁移标记：快递100默认关闭 + 菜鸟规则补全适配（对齐识别器35家） */
        private const val KEY_EXPRESS_RULES_V125 = "express_rules_v125"
        /** v1.132 一次性迁移标记：Chrome 通配规则降级（priority=-100） */
        private const val KEY_CHROME_RULE_V132 = "chrome_rule_v132"
        /** v1.134 一次性迁移标记：撤销 v1.133 错误写入的 clearClipboardAfterJump=true */
        private const val KEY_WECHAT_VIDEO_RULE_V134 = "wechat_video_rule_v134"
        private const val KEY_YT_EXPRESS_RULE_V136 = "yt_express_rule_v136"
        // v1.141.35 短信码规则迁移一次性标记（防止 migrateSmsCodeKeywordsV14132 每次 readRules 重跑死循环）
        private const val KEY_SMS_CODE_MIGRATED_V14135 = "sms_code_migrated_v14135"
        private const val KEY_BUILTIN_RULES_V14145 = "builtin_rules_migrated_v14145"
        private const val KEY_REDOS_FIX_V14147 = "redos_fix_migrated_v14147"
        private const val KEY_REGEX_SELFTEST_V14149 = "regex_selftest_v14149"
        private const val KEY_REGEX_SELFTEST_V14150 = "regex_selftest_v14150"
        private const val KEY_BOUNDARY_FIX_V14150 = "boundary_fix_migrated_v14150"
        private const val KEY_CAINIAO_CLEAR_CLIPBOARD_V14152 = "cainiao_clear_clipboard_v14152"
        private const val KEY_TAOBAO_LINK_CLEAR_CLIPBOARD_V14163 = "taobao_link_clear_clipboard_v14163"
        private const val KEY_TAOBAO_LINK_TEMPLATE_V14158 = "taobao_link_template_v14158"
        private const val KEY_TAOBAO_KOULING_REGEX_V14159 = "taobao_kouling_regex_v14159"
    }
}

/** 规则保存结果 */
enum class RuleSaveResult {
    /** 新增成功 */
    Added,

    /** 更新已有规则（id 匹配） */
    Updated,

    /** 内容与已有规则相同，未重复添加 */
    Duplicate,

    /** v1.33 空白规则（触发器为空）拒绝保存 */
    Rejected,

    /** v1.36 已合并到同类规则（同目标 App，触发器并入已有规则） */
    Merged,
}

/** 回收站条目（v1.26 软删除） */
data class TrashEntry(val rule: RuleConfig, val deletedAt: Long)
