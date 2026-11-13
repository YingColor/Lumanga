package com.yingcolor.lumanga


object S {
    const val appName = "光漫堂"
    const val shelf = "书架"
    const val shelfSubtitle = "享受看漫画的时光吧~"
    const val reading = "在读"
    const val readingSubtitle = "正在看，还没看完"
    const val readingEmptyTitle = "还没有在读的漫画"
    const val readingEmptyBody = "随便打开一本看几页，它就会自动出现在这里。看到最后一页后会从这一栏移出。"
    const val goShelf = "去书架看看"
    const val search = "搜索漫画"
    const val searchClose = "收起搜索"
    const val chooseFolder = "选择漫画文件夹"
    const val changeFolder = "更换文件夹"
    const val manageFolders = "管理文件夹"
    const val emptyTitle = "还没有漫画"
    const val emptyBody = "选中存放漫画的文件夹，点一下就能看。压缩包（zip / cbz）和已经解压出来的图片文件夹都能读，混在一起也没关系。"
    const val emptyTip = "支持手机内部存储和 SD 卡，几个 GB 的大包和图片文件夹都能直接读"
    const val noResult = "没有找到匹配的漫画"
    const val loading = "正在整理书架…"
    const val refresh = "刷新"
    const val backToTop = "回到顶部"
    const val settings = "阅读设置"
    const val settingsRoot = "设置"
    const val translateToggleMark = "译"
    const val settingsReadingSummary = "阅读模式、缩放方式、翻页方向与动画、屏幕方向、亮度等"
    const val back = "返回"
    const val previous = "上一页"
    const val next = "下一页"
    const val firstPage = "首页"
    const val lastPage = "末页"
    const val closeBar = "收起工具条"
    const val directionLtrShort = "左 → 右"
    const val directionRtlShort = "右 → 左"
    const val direction = "翻页方向"
    const val ltr = "左 → 右"
    const val rtl = "右 → 左（日漫）"
    const val pageMode = "页面模式"
    const val single = "单页"
    const val double = "双页"
    const val appearance = "明暗模式"
    const val themeSystem = "跟随系统"
    const val themeLight = "浅色"
    const val themeDark = "深色"
    const val keepAwake = "阅读时屏幕常亮"
    const val tapToTurn = "点击屏幕两侧翻页"
    const val tapToTurnTip = "关掉后只能靠滑动翻页，点两侧不再翻页"
    const val gridColumns = "书架列数"
    const val gridTitle = "每排显示几本"
    const val sort = "排序"
    const val sortTitle = "排序方式"
    const val clearProgress = "清除所有阅读进度"
    const val cleared = "阅读进度已清除"
    const val clearProgressHint = "防误触：需连续点 3 次，弹出警告后再看完 3 秒才能确认"
    fun clearProgressTapHint(remaining: Int) = "清除所有阅读进度（再点 $remaining 次）"
    const val clearWarningTitle = "确定清除所有阅读进度？"
    const val clearWarningBody = "这会清空全部漫画的阅读进度、在读记录和最近阅读时间，且无法撤销。收藏不会被清除。请确认后再继续。"
    const val clearConfirm = "确认清除"
    fun clearConfirmCount(seconds: Int) = "请看完警告（$seconds 秒）"

    const val favorite = "收藏"
    const val unfavorite = "取消收藏"
    const val favoriteLabel = "已收藏"
    val readFilterOptions = listOf("全部", "已读完", "正在读", "收藏")
    const val errorTitle = "这一本打不开"
    const val errorCorrupt = "这个文件可能损坏，或者使用了加密 / 不支持的压缩方式。"
    const val errorUnsupported = "这个位置暂时不能直接跳着读取。把漫画移到手机内部存储的普通文件夹里再试。"
    const val errorBig = "单张图片太大，手机内存不够。"
    const val retry = "重试"
    const val booksUnit = "本"
    const val resume = "继续阅读"
    const val startRead = "开始阅读"
    const val aboutTitle = "关于这个应用"
    const val aboutRepo = "GitHub 仓库 · github.com/YingColor/Lumanga"
    const val about = "关于软件"
    const val menu = "菜单"
    const val aboutBody = "本地漫画阅读器 · 漫画只从你选的文件夹读取，不上传任何内容\n" +
        "只有点「检测更新」和你自己开启翻译时才会联网\n" +
        "支持 ZIP / CBZ / RAR / 7Z，单页 / 双页 / 卷轴"
    fun versionLabel(name: String, code: Int) = "版本 $name（构建 $code）"
    const val checkUpdate = "检测更新"
    const val checkingUpdate = "检测中…"
    const val upToDate = "已是最新版本"
    const val updateFailed = "暂时连不上 GitHub"
    const val openUpdatePage = "在浏览器中打开发布页"
    const val updateFound = "检测到新版本"
    fun updateFoundBody(name: String, code: Int) =
        "最新版本 $name（构建 $code）\n是否现在更新？"
    const val goUpdate = "去更新"
    const val later = "稍后"
    const val refreshDone = "刷新完成了 喵~"
    const val close = "关闭"
    const val zoomHint = "轻点左右边缘可连续翻页 · 画面超出屏幕时可拖动查看 · 点中间显示/收起工具条 · 双指放大 · 放大后双击复原"
    const val scrollHint = "上下滑动连续阅读 · 点中间显示/收起工具条 · 点左右侧滚动一屏"
    const val scrollHintH = "左右滑动连续阅读 · 点中间显示/收起工具条 · 点左右侧滚动一屏"
    const val readMode = "阅读模式"
    const val modePage = "翻页"
    const val modeScroll = "卷轴"
    const val modeScrollH = "横向卷轴"
    const val readModeTip = "卷轴：上下连续滚动，适合超长条漫。横向卷轴：所有页接成一条横向长卷，左右自由拖动、不按页吸附。翻页：一页一页翻，可双页摊开。"
    const val fitMode = "缩放方式"
    const val alignMode = "原点设置（卷轴模式）"
    const val zoomPercent = "固定缩放比例"
    const val zoomTip = "除「伸展」会把画面拉满变形外，其余六种都按原比例缩放，不会把画面拉长或压扁。超长条漫建议卷轴模式配符合宽度或固定缩放。"
    const val screenOrientation = "屏幕方向"
    const val orientationTip = "这里是全局默认方向。每部漫画可以在阅读器里用右上角「切横屏/切竖屏」单独记住自己的方向，没单独设过的书就跟随这里的设置；离开阅读器会自动回到全局方向（跟随系统就回跟随系统，平板不会被硬拧成竖屏）。"
    const val rotateToLandscape = "切横屏"
    const val rotateToPortrait = "切竖屏"
    const val resetOrientationMemory = "让所有漫画恢复跟随全局方向"
    const val smoothing = "图像平滑方式"
    const val brightness = "背光控制"
    const val brightnessSystem = "跟随系统亮度"
    const val webtoonLoading = "正在准备长图…"
    const val webtoonNoImage = "这一页暂时无法显示"
    val fitOptions = listOf("原始尺寸", "符合屏幕", "符合宽度", "符合高度", "固定缩放", "伸展", "覆盖")
    val alignOptions = listOf("水平置中", "靠左", "靠右")
    
    
    val orientationOptions = listOf("直立", "横屏", "跟随系统")
    val orientationValues = listOf(1, 2, 0)
    val smoothOptions = listOf("平滑", "锐利")
    val readModeOptions = listOf(modePage, modeScroll, modeScrollH)
    const val folderError = "文件夹授权失效了，请重新选一次"
    const val noImages = "这里没有找到图片"
    const val done = "完成"
    const val cancel = "取消"
    const val coverLoading = "封面加载中"
    const val tipFirst = "首次使用：选择存放漫画包的文件夹"
    const val gb = "GB"
    const val mb = "MB"
    const val unknownSize = "未知大小"
    const val folderType = "文件夹"
    const val category = "分类"
    const val categoryAll = "全部分类"
    const val libraries = "漫画文件夹"
    const val addFolder = "添加文件夹"
    const val removeFolder = "移除"
    const val lastFolderTip = "至少要保留一个文件夹"
    const val librariesHint = "可以添加多个文件夹：不同作者、不同类型、机身存储和 SD 卡分开放的漫画都会出现在同一个书架上。分类自动取每个文件夹下的第一层目录名。"
    const val folderRemoved = "已移除这个文件夹"
    const val rarTip = "支持 zip / cbz / rar / 7z 和已解压的图片文件夹"

    const val personalize = "个性化"
    const val personalizeSummary = "主题颜色、明暗模式"
    const val themeColor = "主题颜色"
    const val themeColorTip = "清新低饱和的六套配色，按钮、进度条、选中态都跟着换色；明暗模式各有一套对应配色"
    const val pageAnim = "翻页动画"
    const val pageAnimTip = "只影响单页翻页；卷轴模式是连续滚动，没有翻页动画"
    const val pageAnimPreview = "点两侧翻页试试效果"

    val themeColors = listOf("抹茶绿", "湖水青", "晴空蓝", "薰衣草", "樱花粉", "蜜桔橙")

    val pageAnimOptions = listOf("滑动", "淡入淡出", "翻书", "缩放")

    val sortOptions = listOf(
        "名称 A → Z",
        "名称 Z → A",
        "最近修改",
        "最早修改",
        "体积最大",
        "体积最小",
        "最近阅读"
    )
    val columnOptions = listOf("2 列", "3 列", "4 列", "5 列")

    fun foldersCount(count: Int) = "$count 个文件夹"

    fun pageOf(cur: Int, total: Int) = "$cur / $total"
    fun pageProgress(page: Int, total: Int): String {
        if (total <= 0) return "已读过"
        return "第 ${(page + 1).coerceIn(1, total)} / $total 页"
    }

    
    const val translateConfig = "翻译配置"
    const val translateConfigTip = "预翻译整本默认也用这套配置，但预翻译面板里可以单独改。阅读时点「译」开启实时翻译。"
    const val translateConfigOcr = "OCR 配置"
    const val translateConfigModel = "翻译大模型配置"
    const val translateModelEmpty = "还没填模型"
    const val translate = "漫画实时翻译"
    const val translateSummary = "把画面里的外文认出来，译好再贴回页面。开关在阅读页的「译」按钮上，这里只放识别和翻译服务的设置。"
    const val translateApi = "翻译服务地址"
    const val translateModel = "模型名称"
    const val translateProtocol = "接口类型"
    const val translateProtocolTip = "不同服务商的接口格式不一样，选错了会一直失败。自建的 llama.cpp / LM Studio / vLLM 选「OpenAI 兼容」。"
    const val translateProtocolOpenAi = "官方接口、中转站、本地 llama.cpp / LM Studio / vLLM"
    const val translateProtocolAnthropic = "Claude 官方接口（api.anthropic.com）"
    const val translateProtocolGemini = "Gemini 官方接口（generativelanguage.googleapis.com）"
    const val translateProtocolOllama = "Ollama 自带的 /api/chat 接口（不是 /v1 那个）"
    const val translateModeGroup = "逐组翻译（每个气泡单独发）"
    const val translateModeGroupTip = "每个气泡单独发一次请求：阅读时译好一组就先贴一组，页面一组一组变成译文，某一组失败也只留那一组原文。关掉是整页一次发送，整体更快，但要等整页译完才显示。"
    const val preTranslateMode = "翻译方式"
    const val translateModePageShort = "整页一次"
    const val translateModeGroupShort = "逐组（每个气泡单独发）"
    const val translateSourceLanguage = "漫画原文语言"
    const val translateSourceLanguageTip = "默认按日文处理。「自动识别」会按每页认出的字判断是日文、中文还是英文，一本里混着不同语言的页也能各按各的规则处理。内置的 PP-OCRv6 本来就能认中文、英文等文字，不用换模型；韩文还认不出来。选非日文时请用 PP-OCRv6_small / medium，MangaOCR_mobile 只学过日文。"
    const val translateSourceLanguageAutoShort = "自动（按页识别）"
    const val translateTargetLanguage = "译文语言"
    const val translateTargetLanguageTip = "译成哪种语言取决于你填的翻译模型：日译中专用的小模型只会输出中文，要译成英/日/韩请换通用翻译模型。已译好的页按语言分开保存，切换不会删掉原来的中文译文。"
    const val preTranslateLanguage = "翻译语言"
    fun preTranslateLanguageValue(from: String, to: String) = "$from → $to"
    const val fetchModels = "获取模型"
    const val fetchingModels = "获取中…"
    const val fetchModelsReason = "获取失败："
    const val fetchModelsEmpty = "接口没有返回任何模型"
    const val modelPickerTitle = "选择模型"
    const val modelPickerPick = "点一个填入"
    const val translateApiTip = "填你自建或购买的翻译服务地址。只填 IP 和端口也行（会自动当成 http:// 并补 /v1），例如 192.168.1.10:8080。应用不内置任何翻译服务，不填就无法翻译。"
    const val translateOnlyText = "只发送识别出的文字，不上传图片"
    const val translateLayoutHorizontal = "译文横排（从左到右）"
    const val translateLayoutVerticalShort = "竖排"
    const val translateLayoutHorizontalShort = "横排（从左到右）"
    const val preTranslateLayout = "译文排版"
    const val translateLayoutHorizontalTip = "打开后译文像普通中文书一样横着排、从左往右读；关掉是竖排（默认）。已译好的页和预翻译的页都按这个显示，改了立刻生效，不用重译。"
    const val translateWorking = "正在翻译…"
    const val translateReading = "正在识别文字…"
    const val translateNoText = "这一页没认出文字"
    const val translateNotJapanese = "这一页没找到可翻译的文字，已跳过"
    const val translateFailed = "翻译失败 · 点这里重试"
    const val preTranslate = "预翻译"
    const val preTranslateDelete = "删除预翻译"
    const val preTranslateDeleteConfirm = "删除"
    const val preTranslateDeleted = "已删除这本的预翻译"
    const val preTranslateDeleteHint = "防误触：下面的「删除」要连点 3 次才生效（中间停超过 2 秒就重新数）"
    const val preTranslateDeleteEmpty = "这本还没存过译文，没有可删的。"
    const val preTranslateTitle = "预翻译整本"
    const val preTranslateIntro = "一次性把整本译好存到手机，之后翻页不用再等；没网也能看已经译好的页。"
    const val preTranslateApiKey = "API Key"
    const val preTranslateApiKeyTip = "接口需要鉴权时填，不需要就留空。只保存在本机。"
    const val preTranslateOcr = "文字识别模型"
    const val ocrModelTitle = "文字识别模型"
    const val ocrModelTip = "三款都内置在手机里，不用下载。默认用 PP-OCRv6_small（速度最快、最省电）；切换后从下一页开始生效，预翻译整本和批量预翻译都跟着用这一款。"
    const val ocrModelDefault = "默认"
    const val ocrModelSmallTip = "逐列识别，速度最快、最省电，日常看最稳"
    const val ocrModelMediumTip = "逐列识别、小字更稳，但明显更慢更耗电，只建议整本预翻译时用"
    const val ocrModelMangaTip = "漫画专用：整个气泡一次读出一句，语序自然，但个别字和速度不如 small 稳"
    const val ocrModelSwitching = "正在加载模型…"
    fun preTranslateOcrValue(name: String): String = "$name · 在「翻译配置」里可切换"
    const val preTranslatePages = "要翻译的页 · 点缩略图勾选"
    const val preTranslateSelectAll = "全选"
    const val preTranslateSelectNone = "全不选"
    const val preTranslateStart = "开始预翻译"
    const val preTranslateStop = "停止"
    const val preTranslateKeepOpen = "翻译时请让这个窗口保持打开；关掉会停止，但译好的页会留着"
    const val preTranslateNeedEndpoint = "先填翻译服务地址"
    const val preTranslateNeedPages = "至少选一页"
    const val preTranslateCancelled = "已停止，译好的页已经保存"
    const val preTranslateResumeToggle = "跳过已译好的页（断点续传）"
    const val preTranslateResumeHint = "中断后再开始，只补没译完的页；关掉这个开关会全部重译。"
    const val preTranslateBusy = "批量预翻译正在进行，请先到批量窗口里停止或查看"
    const val preTranslateConfigTip = "这里的地址、Key、模型和接口类型只用于预翻译，不会改动「翻译配置」里的全局设置。没单独改过的项跟随全局设置；识别模型、原文/译文语言、译文排版和翻译方式始终跟随全局。"
    const val preTranslateFollowGlobal = "恢复跟随全局"
    const val preTranslateFollowing = "已恢复为跟随全局设置"
    const val translatedBadge = "已翻译"
    const val translateTest = "测试连接"
    const val translateTesting = "测试中…"
    const val translateTestOk = "连接成功，翻译可用"
    const val translateTestFail = "连不上。请检查手机是否允许本应用联网，以及地址是否正确"

    fun modelPickerCount(count: Int): String = "共 $count 个 · 点一下填入"

    fun preTranslateDeleteBody(count: Int): String =
        "这本已经存了 $count 页译文。删除后这些译文会一起没掉，翻到这些页又得重新识别和联网。"

    fun preTranslateDeleteTapHint(remaining: Int): String = "删除（再点 $remaining 次）"

    fun preTranslateSelected(selected: Int, total: Int): String = "已选 $selected / $total 页"

    fun preTranslateRunning(done: Int, total: Int): String = "正在翻译 $done / $total…"

    fun preTranslateDone(count: Int): String = "完成，这本共译好 $count 页"

    fun preTranslateDoneResume(newPages: Int, skipped: Int): String =
        "完成，新译 $newPages 页、沿用 $skipped 页"

    fun preTranslateFailed(page: Int): String = "第 ${page + 1} 页失败，已译好的页已经保存"

    fun preTranslateDoneWithFailures(count: Int, failed: Int): String =
        "完成，这本已存 $count 页；有 $failed 页没成功，再点一次「开始」只会补这几页"

    fun preTranslateStored(count: Int): String = "这本已经存了 $count 页译文 · 长按封面可删除"

    // ---- Folder batch pre-translate ----
    const val batchPreTranslate = "批量预翻译"
    const val batchTitle = "按文件夹批量预翻译"
    const val batchIntro = "把整个文件夹里的书一次译完，封面会打上「已翻译」角标。窗口可以关掉，任务会继续；中途停了或断网，下次点「继续上次」从没译完的那一页接着来。"
    const val batchScope = "选择文件夹"
    const val batchScopeAll = "全部书籍"
    const val batchSkipDone = "跳过已经标了「已翻译」的书"
    const val batchResume = "继续上次"
    const val batchStart = "开始批量预翻译"
    const val batchStop = "停止批量"
    const val batchClose = "关闭窗口"
    const val batchKeepOpen = "关掉窗口不会中断，书架顶部会一直显示进度"
    const val batchNeedEndpoint = "先填翻译服务地址"
    const val batchNoBooks = "这个文件夹里没有可翻译的书"
    const val batchAllDone = "这些书都已经标了「已翻译」；想全部重译，把上面的开关关掉"
    const val batchStopped = "已停止，译好的页都保存了，点「继续上次」接着译"
    const val batchDiscard = "放弃未完成"
    const val batchDiscardTitle = "放弃这次批量预翻译？"
    const val batchDiscardBody = "只会清掉「还剩几本」的待办清单，已经译好的页全部保留，随时可以重新批量。"
    const val batchDiscardConfirm = "放弃清单"
    const val batchConfirmTitle = "开始批量预翻译？"
    const val batchConfirmStart = "开始"
    fun batchConfirmBody(count: Int): String =
        "这次要译 $count 本，每页要十几秒，全部译完可能要很久。可以关掉窗口去做别的事，也可以随时停止，译好的页都会留着，下次接着译。"
    const val batchBannerRunning = "批量预翻译进行中"
    const val batchBannerPending = "上次的批量预翻译还没做完"
    const val batchBannerOpen = "点这里查看"

    fun batchBookCount(count: Int, done: Int): String =
        "共 $count 本 · 其中 $done 本已标「已翻译」"

    fun batchPending(count: Int): String = "上次未完成，还剩 $count 本"

    fun batchRunning(book: Int, total: Int, name: String, page: Int, pages: Int): String =
        "第 $book / $total 本：$name · $page / $pages 页"

    fun batchDone(count: Int, failed: Int): String =
        if (failed > 0) "完成 $count 本，$failed 本没译完（已留在队列里）" else "完成，共译好 $count 本"

    fun batchDoneWithFailures(count: Int, pages: Int): String =
        "完成 $count 本；有 $pages 页没成功，再点「继续上次」会只补这些页"

    fun batchFailed(name: String, page: Int): String =
        "《$name》第 ${page + 1} 页失败，已译好的页都留着，点「继续上次」重试"

    fun batchBannerDetail(book: Int, total: Int, page: Int, pages: Int): String =
        "第 $book/$total 本 · $page/$pages 页"

    fun batchBannerPendingDetail(count: Int): String = "还剩 $count 本"

    fun pageProgressCompact(page: Int, total: Int): String {
        if (total <= 0) return "已读"
        return "${(page + 1).coerceIn(1, total)}/$total"
    }

    fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> unknownSize
        bytes >= 1024L * 1024 * 1024 -> "%.1f %s".format(bytes / 1024f / 1024f / 1024f, gb)
        else -> "%.0f %s".format(bytes / 1024f / 1024f, mb)
    }
}
