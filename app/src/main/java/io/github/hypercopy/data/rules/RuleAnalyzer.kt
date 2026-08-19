package io.github.hypercopy.data.rules

/**
 * 规则自动分析器：对未命中的复制内容做智能识别，生成候选规则草稿。
 *
 * 识别能力：
 * 1. 口令识别（淘宝/京东/拼多多等新旧围栏格式）
 * 2. URL 域名 → 常见 App 映射
 */
object RuleAnalyzer {

    /**
     * 口令围栏正则：兼容新旧格式。
     * 围栏字符使用 Unicode 货币符号类 \p{Sc}（自动覆盖 ₴ ₤ ₳ € £ ¥ ￥ $ ¢ ₽ ₿ 等全部货币符号）+ 🔐。
     * v1.141.63 移除 `_`：下划线在代码/文件名/错误信息中极常见（INSTALL_FAILED_USER_REST 的
     * `_FAILED_` 被误判为围栏口令 → 误跳淘宝并污染 30s 防循环窗口），真实淘宝口令围栏从不使用下划线。
     * 例如 ₴bR88guYi5Bq₴、🔐bHPqguYg4gN₤、$abc123$ 均可识别。
     */
    val KOU_LING_REGEX: Regex =
        Regex("[\\p{Sc}🔐][A-Za-z0-9]{6,20}[\\p{Sc}🔐]")
    /**
     * 口令提取正则（v1.44 新增）：带捕获组，提取口令本体（围栏+口令码），
     * 供提取参数 r1 / ${1} 使用。触发正则与提取正则分离：
     * 触发用无捕获组（匹配更快更清晰），提取用带捕获组（能拿到参数）。
     * v1.141.63 同步移除 `_`（见 KOU_LING_REGEX 注释）。
     */
    val KOU_LING_EXTRACT_REGEX: Regex =
        Regex("([\\p{Sc}🔐][A-Za-z0-9]{6,20}[\\p{Sc}🔐])")
    /** URL 提取正则（v1.44 新增）：带捕获组，提取完整 URL，供 ParseAndOpen 提取参数 */
    val URL_EXTRACT_REGEX: Regex =
        Regex("(https?://[^\\s]+)")

    /** URL→App 映射表（常见平台） */
    private val APP_MAP: List<AppEntry> = listOf(
        AppEntry("淘宝", "com.taobao.taobao", listOf("taobao.com", "tb.cn", "tmall.com", "e.tb.cn")),
        AppEntry("京东", "com.jingdong.app.mall", listOf("jd.com", "jd.hk", "3.cn")),
        AppEntry("拼多多", "com.xunmeng.pinduoduo", listOf("pinduoduo.com", "yangkeduo.com", "pddpic.com")),
        AppEntry("哔哩哔哩", "tv.danmaku.bili", listOf("bilibili.com", "b23.tv", "biligame.com")),
        AppEntry("抖音", "com.ss.android.ugc.aweme", listOf("douyin.com", "iesdouyin.com")),
        AppEntry("小红书", "com.xingin.xhs", listOf("xiaohongshu.com", "xhslink.com", "xhslink.cn")),
        AppEntry("闲鱼", "com.taobao.idlefish", listOf("goofish.com", "idlefish.com")),
        AppEntry("微博", "com.sina.weibo", listOf("weibo.com", "weibo.cn", "sina.cn", "t.cn")),
        AppEntry("知乎", "com.zhihu.android", listOf("zhihu.com", "zhihu-app.com")),
        AppEntry("快手", "com.smile.gifmaker", listOf("kuaishou.com", "gifshow.com")),
        AppEntry("百度网盘", "com.baidu.netdisk", listOf("pan.baidu.com")),
        AppEntry("阿里云盘", "com.alicloud.databox", listOf("aliyundrive.com")),
        AppEntry("夸克网盘", "com.quark.clouddrive", listOf("pan.quark.cn")),
        AppEntry("美团", "com.sankuai.meituan", listOf("meituan.com", "mt.cn", "dpurl.cn", "waimai.meituan.com")),
        AppEntry("大众点评", "com.dianping.v1", listOf("dianping.com")),
        AppEntry("酷安", "com.coolapk.market", listOf("coolapk.com")),
        AppEntry("高德地图", "com.autonavi.minimap", listOf("amap.com", "gaode.com")),
        AppEntry("迅雷", "com.xunlei.downloadprovider", listOf("xunlei.com")),
        AppEntry("网易云音乐", "com.netease.cloudmusic", listOf("music.163.com", "163yun.com")),
        AppEntry("腾讯视频", "com.tencent.qqlive", listOf("v.qq.com")),
        AppEntry("爱奇艺", "com.qiyi.video", listOf("iqiyi.com", "iq.com")),
        AppEntry("优酷", "com.youku.phone", listOf("youku.com", "soku.com")),
        AppEntry("芒果TV", "com.hunantv.imgo.activity", listOf("mgtv.com", "hunantv.com")),
        AppEntry("饿了么", "me.ele", listOf("ele.me")),
        AppEntry("唯品会", "com.achievo.vipshop", listOf("vip.com")),
        AppEntry("苏宁", "com.suning.mobile.ebuy", listOf("suning.com")),
        AppEntry("得物", "com.shizhuang.duapp", listOf("dewu.com", "poizon.com")),
        AppEntry("网易严选", "com.netease.yanxuan", listOf("you.163.com")),
        AppEntry("什么值得买", "com.smzdm.client.android", listOf("smzdm.com")),
        AppEntry("当当", "com.dangdang.buy2", listOf("dangdang.com")),
        AppEntry("飞猪", "com.taobao.trip", listOf("fliggy.com")),
        AppEntry("顺丰速运", "com.sf.activity", listOf("sf-express.com")),
        AppEntry("QQ音乐", "com.tencent.qqmusic", listOf("y.qq.com")),
        AppEntry("腾讯文档", "com.tencent.docs", listOf("docs.qq.com")),
        AppEntry("钉钉", "com.alibaba.android.rimet", listOf("dingtalk.com")),
        AppEntry("企业微信", "com.tencent.wework", listOf("work.weixin.qq.com")),
        AppEntry("携程", "ctrip.android.view", listOf("ctrip.com")),
        AppEntry("去哪儿", "com.Qunar", listOf("qunar.com")),
        AppEntry("滴滴出行", "com.sdu.didi.psnger", listOf("didi.com")),
        AppEntry("豆瓣", "com.douban.frodo", listOf("douban.com")),
        AppEntry("百度贴吧", "com.baidu.tieba", listOf("tieba.baidu.com")),
        AppEntry("TapTap", "com.taptap", listOf("taptap.cn", "taptap.com")),
        AppEntry("Steam", "com.valvesoftware.android.steam.community", listOf("steampowered.com", "steamcommunity.com")),
        AppEntry("GitHub", "com.github.android", listOf("github.com")),
        AppEntry("Twitter/X", "com.twitter.android", listOf("x.com", "twitter.com")),
        AppEntry("Instagram", "com.instagram.android", listOf("instagram.com")),
        AppEntry("YouTube", "com.google.android.youtube", listOf("youtube.com", "youtu.be")),
        AppEntry("Facebook", "com.facebook.katana", listOf("facebook.com", "fb.com")),
        AppEntry("Discord", "com.discord", listOf("discord.com", "discord.gg")),
        AppEntry("Reddit", "com.reddit.frontpage", listOf("reddit.com")),
        AppEntry("Spotify", "com.spotify.music", listOf("spotify.com")),
        AppEntry("Telegram", "org.telegram.messenger", listOf("t.me", "telegram.me")),
        AppEntry("微信", "com.tencent.mm", listOf("weixin.qq.com")),
        AppEntry("QQ", "com.tencent.mobileqq", listOf("qq.com", "w.qq.com")),
        // ===== v1.139.2 全量扩充（国内补充） =====
        AppEntry("斗鱼", "air.tv.douyu.android", listOf("douyu.com")),
        AppEntry("虎牙", "com.duowan.kiwi", listOf("huya.com")),
        AppEntry("YY", "com.yy.mobile", listOf("yy.com")),
        AppEntry("酷狗音乐", "com.kugou.android", listOf("kugou.com")),
        AppEntry("酷我音乐", "cn.kuwo.player", listOf("kuwo.cn")),
        AppEntry("咪咕音乐", "cmccwm.mobilemusic", listOf("music.migu.cn")),
        AppEntry("喜马拉雅", "com.ximalaya.ting.android", listOf("ximalaya.com")),
        AppEntry("蜻蜓FM", "cn.danqing.fm", listOf("qingting.fm")),
        AppEntry("荔枝FM", "com.fm.lizhi", listOf("lizhi.fm", "lizhi.io")),
        AppEntry("天翼云盘", "com.cn21.ecloud", listOf("cloud.189.cn")),
        AppEntry("腾讯微云", "com.tencent.qqcloud", listOf("weiyun.com")),
        AppEntry("115网盘", "com.androidesk.www115", listOf("115.com")),
        AppEntry("蓝奏云", "com.lanzou.client", listOf("lanzou.com", "lanzoui.com", "lanzoux.com")),
        AppEntry("今日头条", "com.ss.android.article.news", listOf("toutiao.com")),
        AppEntry("腾讯新闻", "com.tencent.news", listOf("new.qq.com")),
        AppEntry("网易新闻", "com.netease.newsreader.activity", listOf("news.163.com")),
        AppEntry("蘑菇街", "com.mogujie", listOf("mogujie.com")),
        AppEntry("考拉海购", "com.netease.kaola", listOf("kaola.com")),
        AppEntry("百度地图", "com.baidu.BaiduMap", listOf("map.baidu.com")),
        AppEntry("汽车之家", "com.cubic.autohome", listOf("autohome.com.cn")),
        AppEntry("懂车帝", "com.ss.android.auto", listOf("dongchedi.com")),
        AppEntry("贝壳找房", "com.lianjia.beike", listOf("ke.com")),
        AppEntry("安居客", "com.anjuke.android.app", listOf("anjuke.com")),
        AppEntry("简书", "com.jianshu.haruki", listOf("jianshu.com")),
        AppEntry("CSDN", "net.csdn.csdnplus", listOf("csdn.net")),
        AppEntry("掘金", "com.daimajia.gold", listOf("juejin.cn")),
        AppEntry("12306", "com.MobileTicket", listOf("12306.cn", "kyfw.12306.cn")),
        AppEntry("百度", "com.baidu.searchbox", listOf("baidu.com")),
        AppEntry("Google", "com.google.android.googlequicksearchbox", listOf("google.com", "google.com.hk")),
        AppEntry("Wikipedia", "org.wikipedia", listOf("wikipedia.org")),
        // ===== v1.139.2 全量扩充（海外热门） =====
        AppEntry("WhatsApp", "com.whatsapp", listOf("whatsapp.com", "wa.me")),
        AppEntry("LinkedIn", "com.linkedin.android", listOf("linkedin.com", "lnkd.in")),
        AppEntry("Snapchat", "com.snapchat.android", listOf("snapchat.com")),
        AppEntry("Pinterest", "com.pinterest", listOf("pinterest.com", "pin.it")),
        AppEntry("Line", "jp.naver.line.android", listOf("line.me")),
        AppEntry("Tumblr", "com.tumblr", listOf("tumblr.com")),
        AppEntry("VK", "com.vkontakte.android", listOf("vk.com")),
        AppEntry("Threads", "com.instagram.barcelona", listOf("threads.net")),
        AppEntry("Netflix", "com.netflix.mediaclient", listOf("netflix.com", "nflx.so")),
        AppEntry("Twitch", "tv.twitch.android.app", listOf("twitch.tv")),
        AppEntry("Hulu", "com.hulu.plus", listOf("hulu.com")),
        AppEntry("Prime Video", "com.amazon.avod.thirdpartyclient", listOf("primevideo.com")),
        AppEntry("Medium", "com.medium.reader", listOf("medium.com")),
        AppEntry("GitLab", "com.gitlab", listOf("gitlab.com")),
        AppEntry("Signal", "org.thoughtcrime.securesms", listOf("signal.org")),
    )

    /**
     * v1.139.2 视频平台域名清单（与便捷下载通配规则一致）：
     * 抖音/快手/B站/小红书/微博/西瓜/皮皮虾/火山/美拍/秒拍/YouTube/TikTok/微信视频号。
     * 智能识别器识别到这些平台的分享链接 → 建议走便捷下载（com.lcw.easydownload）。
     */
    private val VIDEO_PLATFORM_HOSTS: List<String> = listOf(
        "douyin.com", "iesdouyin.com",
        "kuaishou.com", "gifshow.com",
        "b23.tv", "bilibili.com",
        "xhslink.com", "xiaohongshu.com", "xhslink.cn",
        "weibo.com", "weibo.cn",
        "ixigua.com", "pipix.com", "huoshan.com",
        "meipai.com", "miaopai.com",
        "douyu.com", "huya.com", "yy.com", // 直播平台（视频内容 → 便捷下载）
        "youtu.be", "youtube.com",
        "vt.tiktok.com", "tiktok.com",
    )

    /** v1.139.2 host 是否属于视频平台（子域匹配，如 v.douyin.com / www.bilibili.com） */
    private fun isVideoPlatformHost(host: String): Boolean {
        if (host == "weixin.qq.com") return true // 微信视频号（需 /sph/ 路径，上层再校验 URL 含 /sph/）
        return VIDEO_PLATFORM_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** 分析结果条目 */
    data class Suggestion(
        val platform: String,
        val packageName: String,
        val matchRegex: String,
        val actionMode: RuleActionMode,
        val template: String,
        // v1.44 提取正则（带捕获组）：与触发正则分离，供提取参数 r1 使用；触发/提取两轨完整
        val extractionRegex: String = matchRegex,
        // v1.57 重定向后解析（对齐官方短链规则：抖音/小红书/快手/B站短链重定向后提取参数拼 App scheme）
        val parseAfterRedirect: Boolean = false,
        // v1.141.75 多提取正则（与内置规则 extractionRegexes 对齐）：如外卖取件通知 [柜位, 取件码] → r1/r2
        val extractionRegexes: List<String> = emptyList(),
    )

    fun analyze(text: String): List<Suggestion> {
        if (text.isBlank()) return emptyList()
        val kouLing = KOU_LING_REGEX.find(text)
        if (kouLing != null) return analyzeKouLing(text)
        // v1.129 顺序调整（借鉴 OmniParse 多阶段流水线）：URL 优先精准直达（短链→webview_resolve 直达内容页），
        // URL 无法识别（未知域名返回空）→ fallback 文本关键词/【平台名】指纹
        val urlSuggestions = analyzeUrl(text)
        if (urlSuggestions.isNotEmpty()) return urlSuggestions
        // v1.59 平台口令文本识别（抖音/快手/京东/拼多多/支付宝/美团等：
        // 无围栏无 URL 的文本口令，如 "8.88 xxxxx:/ 复制打开抖音"）
        val textSuggestion = analyzePlatformText(text)
        if (textSuggestion != null) return listOf(textSuggestion)
        // v1.141.27 短信类纯码识别（验证码/口令码/取件码/取货码/取餐码等）：
        // 非围栏、非URL、非平台口令的短信码内容（如"验证码699749"、"美团口令码:99999"）。
        // 处理方式不确定/可能变化（现在仅通知，之后可能要复制/提取）→ 一次性产出多个候选，
        // 用户可在建议页自选：仅通知 / 提取+通知 / 复制到剪贴板。
        val smsCode = analyzeSmsCode(text)
        if (smsCode.isNotEmpty()) return smsCode
        return emptyList()
    }
    // v1.141.27 短信类纯码识别：识别验证码/取件码/口令码等短信码内容，产出多候选建议。
    // 触发词覆盖：取件码/取货码/提货码/口令码/取餐码/收货码/开柜码/验证码/校验码/动态码。
    // 输出多候选（用户自选需求场景）：
    //  ① 仅通知（NotifyOnly，通知显示原文）→ 归 Text 分类
    //  ② 提取+通知（NotifyOnly，通知显示提取的码 ${r1}）→ 归 Text 分类
    //  ③ 复制到剪贴板（ClipboardWrite，提取码 ${r1} 写入剪贴板）→ 归 Link 分类
    private val SMS_CODE_TRIGGER_REGEX = Regex(
        ".*(?:取件码|取货码|提货码|口令码|取餐码|收货码|开柜码|开箱码|领货码|寄件码|寄存码|出库码|驿站码|存包码|暂存码|验证码|校验码|动态码|动态密码|认证码|激活码|授权码|绑定码|换绑码|设备码|一次性密码|安全码|确认码|登录码|支付码|注册码|解锁码|应急码|OTP|PIN|one-time\\s*password)" +
            "(?:是|为)?[:：]?\\s*[『\"“「]?[0-9A-Za-z]{1,16}(?:-[0-9A-Za-z]{1,8}){0,3}[』\"”」]?.*"
    )
    private val SMS_CODE_EXTRACT_REGEX = Regex(
        ".*(?:取件码|取货码|提货码|口令码|取餐码|收货码|开柜码|开箱码|领货码|寄件码|寄存码|出库码|驿站码|存包码|暂存码|验证码|校验码|动态码|动态密码|认证码|激活码|授权码|绑定码|换绑码|设备码|一次性密码|安全码|确认码|登录码|支付码|注册码|解锁码|应急码|OTP|PIN|one-time\\s*password)" +
            "(?:是|为)?[:：]?\\s*[『\"“「]?([0-9A-Za-z]{1,16}(?:-[0-9A-Za-z]{1,8}){0,3})[』\"”」]?.*"
    )
    private fun analyzeSmsCode(text: String): List<Suggestion> {
        if (!SMS_CODE_TRIGGER_REGEX.containsMatchIn(text)) return emptyList()
        val trigger = SMS_CODE_TRIGGER_REGEX.pattern
        val extract = SMS_CODE_EXTRACT_REGEX.pattern
        return listOf(
            // ① 仅通知：通知栏显示短信原文（最轻，取件码/口令码场景默认）
            Suggestion(
                platform = "短信码 · 仅通知",
                packageName = "",
                matchRegex = trigger,
                actionMode = RuleActionMode.NotifyOnly,
                template = "",
                extractionRegex = extract,
            ),
            // ② 提取+通知：通知栏直接显示提取到的码值（验证码快速可见场景）
            Suggestion(
                platform = "短信码 · 提取通知",
                packageName = "",
                matchRegex = trigger,
                actionMode = RuleActionMode.NotifyOnly,
                template = "\${r1}",
                extractionRegex = extract,
            ),
            // ③ 复制到剪贴板：提取的码写入剪贴板（需粘贴到输入框场景，如登录验证码）
            Suggestion(
                platform = "短信码 · 复制到剪贴板",
                packageName = "",
                matchRegex = trigger,
                actionMode = RuleActionMode.ClipboardWrite,
                template = "\${r1}",
                extractionRegex = extract,
            ),
        )
    }
    // v1.59 平台口令文本特征（来自口令分享网站数据整理）：
    // 抖音：8.88 xxxxx:/ 复制打开抖音；快手：5.20 FUL:/ 复制打开快手
    private val DOUYIN_TEXT_REGEX = Regex("\\d+\\.\\d+\\s+[A-Za-z0-9]{2,8}:\\/")
    private val KUAISHOU_TEXT_REGEX = Regex("\\d+\\.\\d+\\s+[A-Za-z]{2,5}:\\/")
    // v1.129 通用平台指纹（OmniParse 思想）："复制这行字，打开/进入【小红书】就能看" → 提取【】内平台名
    private val BRACKET_PLATFORM_REGEX = Regex("[【\\[]\\s*([^】\\]\\s]{2,10})\\s*[】\\]]")
    // v1.60 拼多多福袋互助码（数据源 bz.5138zhuan.com / cv.intgold.cn 接口，1000+ 完整样本统计）：
    // 格式 = 8 位纯数字、首数字 6/7（100% 符合；7 开头 62% / 6 开头 38%）。
    // 误触防护（接码平台 20+ 真实验证码短信全为 4-6 位，但 8 位数字常见于
    // QQ 号/游戏房间号/手机尾号 → 裸 [67]\d{7} 会大量误触）→ 双规则：
    //  ① 互助特征词 + 8 位数字（福袋/助力/互助/组队/拼单/帮点/砍价…）→ 无互助词的 QQ 号不误触
    //  ② 整段文本就是 8 位数字（微信群长按复制纯码场景）
    private val PDD_FUDAI_HINT_REGEX = Regex("(福袋|助力|互助|组队|拼单|帮点|砍价|砍一刀|帮我点)")
    private val PDD_FUDAI_CODE_REGEX = Regex("(?:^|[^0-9])([67][0-9]{7})(?![0-9])")
    private val PDD_FUDAI_PURE_REGEX = Regex("^[67][0-9]{7}$")
    // v1.61 平台文本口令数据表（平台名/包名/关键词）。新增平台只需加一行，识别/保存自动生效。
    // 误触防护：关键词必须带"口令/复制打开/打开"等口令提示词，普通聊天（如"京东怎么样"）不触发。
    // 顺序注意：极速版/子品牌排在主平台前面（"复制打开抖音"是"复制打开抖音极速版"的子串，
    // firstOrNull 先命中先返回）；百度网盘在百度前、夸克网盘单独成行避免冲突。
    private data class TextPlatform(val label: String, val pkg: String, val keywords: String)
    private val TEXT_PLATFORMS: List<TextPlatform> = listOf(
        TextPlatform("京东", "com.jingdong.app.mall", "复制打开京东|京东口令|京口令|京东app|京东app内"),
        TextPlatform("拼多多", "com.xunmeng.pinduoduo", "拼多多口令|复制打开拼多多|多多买菜|#小程序://拼多多"),
        TextPlatform("支付宝", "com.eg.android.AlipayGphone", "复制打开支付宝|支付宝口令|吱口令|#吱口令"),
        TextPlatform("美团", "com.sankuai.meituan", "复制打开美团|美团团口令|美团app"),
        TextPlatform("快手极速版", "com.kuaishou.nebula", "复制打开快手极速版|快手极速版口令"),
        TextPlatform("快手", "com.smile.gifmaker", "复制打开快手|快手口令|#小程序://快手"),
        TextPlatform("抖音极速版", "com.ss.android.ugc.aweme.lite", "复制打开抖音极速版|抖音极速版口令"),
        TextPlatform("抖音", "com.ss.android.ugc.aweme", "复制打开抖音|抖音口令|#小程序://抖音"),
        TextPlatform("饿了么", "me.ele", "复制打开饿了么|饿了么口令|饿了么红包"),
        TextPlatform("淘宝", "com.taobao.taobao", "复制打开淘宝|淘宝口令"),
        TextPlatform("天猫", "com.tmall.wireless", "复制打开天猫|天猫口令"),
        TextPlatform("唯品会", "com.achievo.vipshop", "复制打开唯品会|唯品会口令"),
        TextPlatform("苏宁", "com.suning.mobile.ebuy", "复制打开苏宁|苏宁口令"),
        TextPlatform("得物", "com.shizhuang.duapp", "复制打开得物|得物口令"),
        TextPlatform("淘特", "com.taobao.litetao", "复制打开淘特|淘特口令|淘宝特价版"),
        TextPlatform("夸克网盘", "com.quark.clouddrive", "夸克网盘口令|复制打开夸克网盘|#小程序://夸克"),
        TextPlatform("百度网盘", "com.baidu.netdisk", "复制打开百度网盘|百度网盘口令"),
        TextPlatform("阿里云盘", "com.alicloud.databox", "复制打开阿里云盘|阿里云盘口令"),
        TextPlatform("UC浏览器", "com.UCMobile", "复制打开UC|UC口令"),
        TextPlatform("滴滴出行", "com.sdu.didi.psnger", "复制打开滴滴|滴滴口令"),
        TextPlatform("携程", "ctrip.android.view", "复制打开携程|携程口令"),
        TextPlatform("飞猪", "com.taobao.trip", "复制打开飞猪|飞猪口令"),
        TextPlatform("网易云音乐", "com.netease.cloudmusic", "复制打开网易云音乐|网易云口令"),
        TextPlatform("QQ音乐", "com.tencent.qqmusic", "复制打开QQ音乐|QQ音乐口令"),
        TextPlatform("小红书", "com.xingin.xhs", "复制打开小红书|小红书口令|进入【小红书】|小红书就能看|复制一下这行字"),
        TextPlatform("哔哩哔哩", "tv.danmaku.bili", "复制打开B站|复制打开哔哩哔哩|B站口令|哔哩哔哩口令"),
        TextPlatform("知乎", "com.zhihu.android", "复制打开知乎|知乎口令"),
        TextPlatform("大众点评", "com.dianping.v1", "复制打开大众点评|大众点评口令"),
        TextPlatform("高德地图", "com.autonavi.minimap", "复制打开高德|高德地图口令"),
        TextPlatform("微博", "com.sina.weibo", "复制打开微博|微博口令"),
        TextPlatform("百度", "com.baidu.searchbox", "复制打开百度|百度口令"),
    )
    /** v1.59 平台文本口令分析：识别无围栏无 URL 的分享口令 → DirectOpen 打开平台 App（App 自行识别剪贴板弹窗） */
    private fun analyzePlatformText(text: String): Suggestion? {
        val lower = text.lowercase()
        if (DOUYIN_TEXT_REGEX.containsMatchIn(text)) {
            return Suggestion(
                platform = "抖音 · 口令",
                packageName = "com.ss.android.ugc.aweme",
                matchRegex = DOUYIN_TEXT_REGEX.pattern,
                actionMode = RuleActionMode.DirectOpen,
                template = "",
                extractionRegex = DOUYIN_TEXT_REGEX.pattern,
            )
        }
        if (KUAISHOU_TEXT_REGEX.containsMatchIn(text)) {
            return Suggestion(
                platform = "快手 · 口令",
                packageName = "com.smile.gifmaker",
                matchRegex = KUAISHOU_TEXT_REGEX.pattern,
                actionMode = RuleActionMode.DirectOpen,
                template = "",
                extractionRegex = KUAISHOU_TEXT_REGEX.pattern,
            )
        }
        // 平台文本口令（含平台关键词+口令特征，无 https URL 时识别；
        // matchRegex 用"平台名+口令提示词"组合避免普通聊天误触发）
        if (!extractFirstInputUrl(text).isNullOrBlank()) return null
        // v1.60 拼多多福袋互助码（8 位数字 6/7 开头，bz.5138zhuan.com/cv.intgold.cn 1000+ 完整样本确认）
        // ① 互助特征词 + 福袋码：普通聊天里的 QQ 号/房间号/尾号无互助词，不误触
        if (PDD_FUDAI_HINT_REGEX.containsMatchIn(lower)) {
            if (PDD_FUDAI_CODE_REGEX.containsMatchIn(text)) {
                return Suggestion(
                    platform = "拼多多福袋 · 口令",
                    packageName = "com.xunmeng.pinduoduo",
                    matchRegex = ".*(福袋|助力|互助|组队|拼单|帮点|砍价|砍一刀|帮我点).*[67][0-9]{7}.*",
                    actionMode = RuleActionMode.DirectOpen,
                    template = "",
                    // v1.70 修复（交叉验证确认）：原实现存具体码 (68631286)，
                    // 下次复制不同福袋码时提取失败（提取正则只匹配旧码）
                    extractionRegex = "([67][0-9]{7})",
                )
            }
        }
        // ② 整段文本就是 8 位数字（微信群长按复制纯码）→ 打开拼多多
        if (PDD_FUDAI_PURE_REGEX.matches(text.trim())) {
            return Suggestion(
                platform = "拼多多福袋 · 口令",
                packageName = "com.xunmeng.pinduoduo",
                matchRegex = PDD_FUDAI_PURE_REGEX.pattern,
                actionMode = RuleActionMode.DirectOpen,
                template = "",
                // v1.70 同上：通用捕获正则而非具体码
                extractionRegex = "([67][0-9]{7})",
            )
        }
        val platform = TEXT_PLATFORMS.firstOrNull {
            // v1.61 大小写不敏感匹配（UC/QQ/B 站等英文关键词；用原文而非 lower 避免大写失配）
            Regex(it.keywords, RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        // v1.129 通用平台指纹（借鉴 OmniParse 平台指纹识别）："复制这行字，打开/进入【小红书】就能看"句式，
        // 【】内平台名 → 平台映射（覆盖所有平台的此类分享文案，无需逐平台加关键词）
        val bracketPlatform = platform ?: BRACKET_PLATFORM_REGEX.find(text)?.groupValues?.get(1)?.let { name ->
            TEXT_PLATFORMS.firstOrNull {
                it.label.contains(name, ignoreCase = true) || name.contains(it.label, ignoreCase = true)
            }
        }
        val finalPlatform = platform ?: bracketPlatform ?: return null
        return Suggestion(
            platform = "${finalPlatform.label} · 口令",
            packageName = finalPlatform.pkg,
            // (?i) 内联 flag：保存后的规则匹配剪贴板时同样大小写不敏感
            matchRegex = "(?i).*(${finalPlatform.keywords}).*",
            actionMode = RuleActionMode.DirectOpen,
            template = "",
            extractionRegex = "(?i)(${finalPlatform.keywords})",
        )
    }

    // ===== 口令分析 =====
    private fun analyzeKouLing(text: String): List<Suggestion> {
        // v1.141.61 修复：混合内容（口令围栏+商品链接）不应归为口令
        // 根因：analyze() 优先级口令先于 URL → 含链接的分享文案被 analyzeKouLing 兜底为「淘宝·口令」
        // → 用户保存时触发合并提示（已有同名规则），必须「保存并编辑」才能拆出独立链接规则。
        // 修复：检测到 URL 时返回空列表，让 analyze() 继续走 URL 分析器（精准生成「淘宝·链接」）。
        if (!extractFirstInputUrl(text).isNullOrBlank()) return emptyList()
        val lower = text.lowercase()
        // v1.44 词边界优化：jd/pdd 用独立词匹配避免子串误判（如 "ljdxxx"）；京东/拼多多中文关键词优先级最高
        val platform = when {
            lower.contains("京东") || lower.contains("京喜") -> "京东" to "com.jingdong.app.mall"
            Regex("(^|[^a-z0-9])jd([^a-z0-9]|$)").containsMatchIn(lower) -> "京东" to "com.jingdong.app.mall"
            lower.contains("拼多多") || lower.contains("多多买菜") || lower.contains("砍一刀") -> "拼多多" to "com.xunmeng.pinduoduo"
            Regex("(^|[^a-z0-9])pdd([^a-z0-9]|$)").containsMatchIn(lower) -> "拼多多" to "com.xunmeng.pinduoduo"
            else -> "淘宝" to "com.taobao.taobao"
        }
        return listOf(
            Suggestion(
                platform = "${platform.first} · 口令",
                packageName = platform.second,
                matchRegex = KOU_LING_REGEX.pattern,
                actionMode = RuleActionMode.DirectOpen,
                template = "",
                // v1.44 提取正则带捕获组：提取口令本体（围栏+口令码），供 ${1} / r1 使用
                extractionRegex = KOU_LING_EXTRACT_REGEX.pattern,
            ),
        )
    }
    // ===== URL 分析 =====
    // v1.140.x BT 下载类非标协议识别：magnet 磁力 / ed2k 电驴 / thunder 迅雷
    // 这些协议 host 为空，必须在本分支直接判定，否则落到通用 URL 逻辑因 host==null 返回空。
    private val MAGNET_REGEX = Regex("""magnet:\?xt=urn:[A-Za-z0-9-_]+:[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
    private val ED2K_REGEX = Regex("""ed2k://[^\s]+""", RegexOption.IGNORE_CASE)
    private val THUNDER_REGEX = Regex("""thunder://[^\s]+""", RegexOption.IGNORE_CASE)
    private fun analyzeDownloadLink(text: String): Suggestion? {
        val lower = text.lowercase()
        return when {
            MAGNET_REGEX.containsMatchIn(text) -> Suggestion(
                platform = "磁力链接",
                packageName = "com.xunlei.downloadprovider",
                matchRegex = "magnet:\\?xt=urn:[a-z0-9-_]+:[a-z0-9]+",
                actionMode = RuleActionMode.DirectOpen,
                template = "\${url:input}",
                extractionRegex = "(magnet:\\?xt=[^\\s]+)",
            )
            ED2K_REGEX.containsMatchIn(text) -> Suggestion(
                platform = "电驴链接",
                packageName = "com.xunlei.downloadprovider",
                matchRegex = "ed2k://[^\\\\s]+",
                actionMode = RuleActionMode.DirectOpen,
                template = "\${url:input}",
                extractionRegex = "(ed2k://[^\\\\s]+)",
            )
            THUNDER_REGEX.containsMatchIn(text) -> Suggestion(
                platform = "迅雷链接",
                packageName = "com.xunlei.downloadprovider",
                matchRegex = "thunder://[^\\\\s]+",
                actionMode = RuleActionMode.DirectOpen,
                template = "\${url:input}",
                extractionRegex = "(thunder://[^\\\\s]+)",
            )
            else -> null
        }
    }
    // v1.141.x 外卖取件柜短链域名 → 平台包名映射（美团/饿了么/肯德基/麦当劳等外卖柜取件场景）
    private val TAKEOUT_PKG_BY_HOST: Map<String, String> = mapOf(
        "mt.cn" to "com.sankuai.meituan",
        "meituan.com" to "com.sankuai.meituan",
        "dpurl.cn" to "com.sankuai.meituan",
        "waimai.meituan.com" to "com.sankuai.meituan",
        "ele.me" to "me.ele",
        "h5.ele.me" to "me.ele",
        "kfc.com.cn" to "com.yumc.kfc",
        "mcd.com.cn" to "com.mcdonalds.app.china",
    )
    /** 是否外卖取件柜短链域名（子域匹配） */
    private fun isTakeoutShortLinkHost(host: String): Boolean =
        TAKEOUT_PKG_BY_HOST.keys.any { host == it || host.endsWith(".$it") }
    /** 文本是否含外卖取件特征词（取件/外卖/柜/格口/骑手已放/开柜/存储） */
    private fun hasTakeoutPickupHint(text: String): Boolean {
        val lower = text.lowercase()
        return TAKEOUT_PICKUP_HINT_REGEX.containsMatchIn(lower)
    }
    private val TAKEOUT_PICKUP_HINT_REGEX = Regex("(取件|取货|外卖|智能柜|取餐柜|快递柜|格口|骑手|已放|开柜|[字格]柜|存储柜|门禁取件|凭码取件)")

    private fun analyzeUrl(text: String): List<Suggestion> {
        val url = extractFirstInputUrl(text) ?: return emptyList()
        // v1.140.x BT 下载类（magnet/ed2k/thunder）：host 为空无法走下方域名逻辑，先在此判定
        analyzeDownloadLink(url)?.let { return listOf(it) }
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return emptyList()
        // v1.141.x 外卖取件场景（美团/饿了么等外卖柜短链 + 取件码）：
        // mt.cn 链实测最终落在微信小程序（mt.cn→peisong.meituan.com H5→拉起小程序）。
        // 故同时产出 2 条建议，用户自选：
        //  ① 仅通知（NotifyOnly，纯净文本通知，归文本分类可自选通知渠道）
        //  ② 跳转微信（DirectOpen 打开 mt.cn，系统按 AppLinks/scheme 自动拉起微信小程序）
        if (isTakeoutShortLinkHost(host) && hasTakeoutPickupHint(text)) {
            val taRegex = ".*(?:mt\\.cn|dpurl\\.cn|waimai\\.meituan\\.com|ele\\.me|h5\\.ele\\.me|kfc\\.com\\.cn|mcd\\.com\\.cn)[^\\s]*.*"
            // v1.141.75 统一标准：仅通知对齐内置「外卖取件通知」——外卖语境匹配 + 柜位/取件码双提取 + 标准模板
            val notifyRegex = "^(?!.*(?:取出|取走)).*(?:(?:外卖|餐).*(?:格口|[A-Za-z0-9]{0,3}号?柜\\s*\\d+|取件码|取餐码|口令码|外卖柜\\s*\\d+)|(?:已放|送至|送达).*(?:mt\\.cn|dpurl\\.cn|ele\\.me|waimai\\.meituan\\.com|h5\\.ele\\.me)).*"
            val cabinetRegex = "([A-Za-z0-9]{0,3}号?柜外卖柜\\s*\\d+\\s*格口|[A-Za-z0-9]{0,3}号?柜外卖柜|[A-Za-z0-9]{0,3}号?柜\\s*\\d+(?:号)?\\s*格口|\\d+\\s*号?\\s*柜[^\\s，。]{0,5}\\d+\\s*格口|外卖柜\\s*\\d+\\s*格口|格口号?[:：]?\\s*\\d+|[A-Za-z0-9]{1,3}号?柜|\\d+(?:号)?\\s*格口|[A-Za-z0-9]{0,3}柜\\s*\\d+)"
            val codeRegex = "(?:使用|输入|凭|取件码|取餐码|口令码)(?:是|为)?[:：]?\\s*(\\d{4,12})\\s*(?:取件|领取|取货|收件|开柜)?"
            return listOf(
                Suggestion(
                    platform = "外卖取件 · 仅通知",
                    packageName = "",
                    matchRegex = notifyRegex,
                    actionMode = RuleActionMode.NotifyOnly,
template = "取件码 \${r2} · \${r1}",
                    // v1.141.86 parameterRegex 置空对齐内置（内置 parameterRegex=""，多提取由 extractionRegexes 承载）
                    // 根因：extractionRegex 非空会写入 parameterRegex → 与内置不一致 → sameContentAs 判定不同 → 重复检测失效
                    extractionRegex = "",
                    extractionRegexes = listOf(cabinetRegex, codeRegex),
                ),
                Suggestion(
                    platform = "美团小程序",
                    // v1.141.23：包名留空。template 是 https 网页 URL，若带美团包名会 setPackage 强投美团 App（其不处理 HTTPS）→ 打不开。留空→交浏览器。
                    packageName = "",
                    matchRegex = taRegex,
                    // v1.141.24：改用「后台无头 WebView」自动走完整链。
                    // 关键实测：软件内 WebView 加载 mt.cn → 302 peisong → 页面 JS 生成 weixin://dl/business/?t=TICKET
                    // 并 location.href 跳转 → WebView shouldOverrideUrlLoading 捕获该 scheme → 自动拉起微信小程序，
                    // 用户仅需点一次系统"检测到 App 跳转"确认（Android 系统强制，无法绕）。
                    // 故 actionMode=WebViewResolveAndOpen 走 startWebViewResolve，其中对 mt.cn/外卖场景
                    // 特判改用 HeadlessWebViewResolver（后台 WebView 引擎）而非 OneRedirectResolver（纯 HTTP 302，拿不到 JS scheme）。
                    actionMode = RuleActionMode.WebViewResolveAndOpen,
                    parseAfterRedirect = false,
                    template = "\${url:input}",
                    extractionRegex = URL_EXTRACT_REGEX.pattern,
                ),
            )
        }
        // v1.139.2 视频平台分享链接 → 便捷下载（用户确认：视频平台链接全部走便捷下载，智能识别器同步适配）
        if (isVideoPlatformHost(host)) {
            // 微信视频号需要 /sph/ 路径才走便捷下载；其他视频平台域名直接走
            if (host != "weixin.qq.com" || url.contains("/sph/")) {
                return listOf(
                    Suggestion(
                        platform = "便捷下载 · 视频平台",
                        packageName = "com.lcw.easydownload",
                        matchRegex = ".*(?:v\\.douyin\\.com|iesdouyin\\.com|kuaishou\\.com|gifshow\\.com|b23\\.tv|bilibili\\.com|xhslink\\.com|xiaohongshu\\.com|xhslink\\.cn|weibo\\.com|weibo\\.cn|ixigua\\.com|pipix\\.com|huoshan\\.com|meipai\\.com|miaopai\\.com|youtu\\.be|youtube\\.com|vt\\.tiktok\\.com|tiktok\\.com|weixin\\.qq\\.com/sph/)[^\\s]*.*",
                        actionMode = RuleActionMode.DirectOpen,
                        parseAfterRedirect = false,
                        // v1.139.2b 直接打开 App 不带 URL：便捷下载自行读剪贴板识别（带 URL 的 VIEW 可能处理失败跳浏览器）
                        template = "",
                        extractionRegex = "(https?://[^\\s]+)",
                    ),
                )
            }
        }
        // v1.57 对齐官方内置规则（hypercopy.1812z.top《制作规则》规范）：
        // 短链域名 → webview_resolve_and_open + parseAfterRedirect=true + App scheme 模板（重定向后提取参数跳具体内容）
        // 详情域名 → parse_and_open（提取 ID 拼 App scheme）
        // 其他 → direct_open（App 自己能识别 URL）
        when {
            // v1.78 抖音：短链（v.douyin.com 必须解析）→ webview_resolve；详情域名（www/m.douyin.com、iesdouyin.com 直接含 ID）→ parse_and_open
            host == "v.douyin.com" -> return listOf(
                Suggestion(
                    platform = "抖音 · 链接",
                    packageName = "com.ss.android.ugc.aweme",
                    matchRegex = ".*v\\.douyin\\.com[^\\s]*.*",
                    actionMode = RuleActionMode.WebViewResolveAndOpen,
                    parseAfterRedirect = true,
                    template = "snssdk1128://aweme/detail/\${r1}",
                    // v1.78 对齐官方内置规则：note|video 都支持（图文笔记短链解析后同样提取成功）
                    // 重定向后 URL 含 ID（www.iesdouyin.com/share/video/719xx / www.douyin.com/note/719xx...）
                    extractionRegex = "(?:share/)?(?:note|video)/(\\d+)",
                ),
            )
            host.endsWith(".douyin.com") || host.endsWith(".iesdouyin.com") -> return listOf(
                Suggestion(
                    platform = "抖音 · 链接",
                    packageName = "com.ss.android.ugc.aweme",
                    // v1.78 matchRegex 补子域覆盖（原缺失 www/m），(?<!v\\.) 排除短链避免误命中
                    matchRegex = ".*(?:iesdouyin\\.com[^\\s]*|(?<!v\\.)douyin\\.com[^\\s]*).*",
                    actionMode = RuleActionMode.ParseAndOpen,
                    template = "snssdk1128://aweme/detail/\${r1}",
                    // v1.78 详情链接直接含 ID：ParseAndOpen 零网络依赖直提（视频+图文笔记）
                    extractionRegex = "(?:share/)?(?:note|video)/(\\d+)",
                ),
            )
            // v1.78 小红书：短链（xhslink.com / xhslink.cn）→ webview_resolve；详情（www.xiaohongshu.com 直接含 ID）→ parse_and_open
            host == "xhslink.com" || host == "xhslink.cn" -> return listOf(
                Suggestion(
                    platform = "小红书 · 链接",
                    packageName = "com.xingin.xhs",
                    matchRegex = ".*xhslink\\\\.(?:com|cn)[^\\\\s]*.*",
                    actionMode = RuleActionMode.WebViewResolveAndOpen,
                    parseAfterRedirect = true,
                    template = "xhsdiscover://item/\${r1}",
                    // 重定向后 URL：www.xiaohongshu.com/discovery/item/64xx...
                    extractionRegex = "(?:discovery/)?item/([0-9a-zA-Z]+)",
                ),
            )
            host.endsWith(".xiaohongshu.com") -> return listOf(
                Suggestion(
                    platform = "小红书 · 链接",
                    packageName = "com.xingin.xhs",
                    matchRegex = ".*xiaohongshu\\.com[^\\s]*.*",
                    actionMode = RuleActionMode.ParseAndOpen,
                    template = "xhsdiscover://item/\${r1}",
                    // v1.78 对齐官方 extraction：(?:discovery/)?item/ 支持短形态 /item/64xx
                    extractionRegex = "(?:discovery/)?item/([0-9a-zA-Z]+)",
                ),
            )
            host == "b23.tv" || host.endsWith(".b23.tv") -> return listOf(
                Suggestion(
                    platform = "B站短链 · 链接",
                    packageName = "tv.danmaku.bili",
                    matchRegex = ".*(b23\\.tv[^\\s]*).*",
                    actionMode = RuleActionMode.WebViewResolveAndOpen,
                    parseAfterRedirect = true,
                    template = "bilibili://video/\${p1}",
                    // 重定向后 URL：www.bilibili.com/video/BV1xx...
                    extractionRegex = "video/(BV[0-9A-Za-z]{10})",
                ),
            )
            // v1.78 快手：短链（v.kuaishou.com 必须解析）→ webview_resolve；详情（kuaishou.com 子域/gifshow.com/chenzhongtech.com 直接含 ID）→ parse_and_open
            host == "v.kuaishou.com" -> return listOf(
                Suggestion(
                    platform = "快手 · 链接",
                    packageName = "com.smile.gifmaker",
                    matchRegex = ".*v\\.kuaishou\\.com[^\\s]*.*",
                    actionMode = RuleActionMode.WebViewResolveAndOpen,
                    parseAfterRedirect = true,
                    template = "kwai://work/\${r1}?enableSlidePlay=true&selectedPhotoId=\${r1}&path=%2Frest%2Fn%2FopenShare%2Ffeed%2Fview%2Flist",
                    // v1.78 补 photo（快手图文/照片集）；重定向后 URL：www.kuaishou.com/short-video/3xabc...
                    extractionRegex = "(?:short-video|photo)/([0-9a-zA-Z]+)",
                ),
            )
            host.endsWith(".kuaishou.com") || host.endsWith(".gifshow.com") || host.endsWith(".chenzhongtech.com") -> return listOf(
                Suggestion(
                    platform = "快手 · 链接",
                    packageName = "com.smile.gifmaker",
                    // v1.78 对齐官方 matchRegex（官方含 chenzhongtech.com）；(?<!v\\.) 排除短链避免误命中
                    matchRegex = ".*(?:gifshow\\.com[^\\s]*|chenzhongtech\\.com[^\\s]*|(?<!v\\.)kuaishou\\.com[^\\s]*).*",
                    actionMode = RuleActionMode.ParseAndOpen,
                    template = "kwai://work/\${r1}?enableSlidePlay=true&selectedPhotoId=\${r1}&path=%2Frest%2Fn%2FopenShare%2Ffeed%2Fview%2Flist",
                    // v1.78 详情链接直接含 ID：ParseAndOpen 零网络依赖直提
                    extractionRegex = "(?:short-video|photo)/([0-9a-zA-Z]+)",
                ),
            )
            host.endsWith(".bilibili.com") && url.contains("/video/") -> return listOf(
                Suggestion(
                    platform = "B站视频 · 链接",
                    packageName = "tv.danmaku.bili",
                    matchRegex = ".*bilibili\\.com/video/(BV[0-9A-Za-z]{10}).*",
                    actionMode = RuleActionMode.ParseAndOpen,
                    template = "bilibili://video/\${p1}",
                    extractionRegex = "video/(BV[0-9A-Za-z]{10})",
                ),
            )
            host.endsWith(".weibo.com") || host.endsWith(".weibo.cn") -> return listOf(
                Suggestion(
                    platform = "微博 · 链接",
                    packageName = "com.sina.weibo",
                    matchRegex = ".*weibo\\.(com|cn)/\\d+/([A-Za-z0-9]+).*",
                    actionMode = RuleActionMode.ParseAndOpen,
                    template = "sinaweibo://detail?mblogid=\${p1}",
                    extractionRegex = "weibo\\.(com|cn)/\\d+/([A-Za-z0-9]+)",
                ),
            )
            host.endsWith(".meituan.com") || host.endsWith(".dpurl.cn") || host.endsWith(".dianping.com") -> return listOf(
                Suggestion(
                    platform = "美团 · 链接",
                    packageName = "com.sankuai.meituan",
                    matchRegex = ".*(meituan\\.com[^\\s]*|dpurl\\.cn[^\\s]*|dianping\\.com[^\\s]*).*",
                    actionMode = RuleActionMode.DirectOpen,
                    template = "imeituan://www.meituan.com/web?notitlebar=1&url=\${url:input}",
                    extractionRegex = URL_EXTRACT_REGEX.pattern,
                ),
            )
            // v1.141.55 修复：e.tb.cn 是淘宝大促短链（APP_MAP 淘宝列表明确包含 e.tb.cn），
            // 原 host.endsWith(".tb.cn") 把 e.tb.cn 误吞进闲鱼分支（e.tb.cn 以 .tb.cn 结尾）
            // → 淘宝链接被识别为闲鱼（用户实锤：【淘宝】大促价保 e.tb.cn 链接 → 闲鱼·链接）。
            // 其余 tb.cn 子域（m.tb.cn 等）为淘宝/闲鱼共用短链，维持历史闲鱼默认。
            host.endsWith(".goofish.com") || host.endsWith(".idlefish.com") ||
                (host.endsWith(".tb.cn") && !host.endsWith("e.tb.cn")) -> return listOf(
                Suggestion(
                    platform = "闲鱼 · 链接",
                    packageName = "com.taobao.idlefish",
                    matchRegex = ".*(goofish\\.com[^\\s]*|idlefish\\.com[^\\s]*|(?<!e\\.)tb\\.cn[^\\s]*).*",
                    actionMode = RuleActionMode.ParseAndOpen,
                    template = "fleamarket://2.taobao.com/onepiece?h5Url=\${url:input}",
                    extractionRegex = URL_EXTRACT_REGEX.pattern,
                ),
            )
        }
        // 其他平台：direct_open（App 自己能识别 URL，打开 App 由 App 内处理跳转）
        val entry = APP_MAP.firstOrNull { app ->
            app.domains.any { domain -> host == domain || host.endsWith(".$domain") }
        }
        // v1.129 未知域名 → 返回空（不再产出"未知平台"建议抢占），
        // 让调用方 fallback 文本关键词/【平台名】指纹识别；纯未知链接无建议（保守防误判）
        if (entry == null) return emptyList()
        return listOf(
            Suggestion(
                platform = "${entry.name} · 链接",
                packageName = entry.packageName,
                matchRegex = entry.domains.takeIf { it.isNotEmpty() }?.joinToString("|") { Regex.escape(it) }?.let { ".*($it).*" } ?: URL_EXTRACT_REGEX.pattern,
                actionMode = RuleActionMode.DirectOpen,
                template = "\${url:input}",
                // v1.44 提取正则带捕获组：提取完整 URL，供 ParseAndOpen/参数模板使用
                extractionRegex = URL_EXTRACT_REGEX.pattern,
            ),
        )
    }
    private data class AppEntry(val name: String, val packageName: String, val domains: List<String>)
}
