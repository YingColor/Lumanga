package com.yingcolor.comicviewer

/** All user-facing copy lives here so it can be extracted for translation later. */
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
    const val about = "关于软件"
    const val menu = "菜单"
    const val aboutBody = "本地漫画阅读器 · 漫画只从你选的文件夹读取，不上传任何内容\n" +
        "只有点「检测更新」时才会访问 GitHub\n" +
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
    // Display order puts the locked-portrait default first. The stored values
    // are unchanged: 0 = follow system, 1 = portrait, 2 = landscape.
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

    /** Compact form used when four or five covers share a row. */
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
