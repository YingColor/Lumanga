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
    const val emptyTitle = "还没有漫画"
    const val emptyBody = "选中存放 zip / cbz 漫画包的文件夹，点一下就能看。选一次就够了，以后打开自动显示。"
    const val emptyTip = "支持手机内部存储和 SD 卡，几个 GB 的大包也能直接读"
    const val noResult = "没有找到匹配的漫画"
    const val loading = "正在整理书架…"
    const val refresh = "刷新"
    const val backToTop = "回到顶部"
    const val settings = "阅读设置"
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
    const val appearance = "外观"
    const val themeSystem = "跟随系统"
    const val themeLight = "浅色"
    const val themeDark = "深色"
    const val keepAwake = "阅读时屏幕常亮"
    const val tapToTurn = "点击屏幕两侧翻页"
    const val gridColumns = "书架列数"
    const val gridTitle = "每排显示几本"
    const val sort = "排序"
    const val sortTitle = "排序方式"
    const val clearProgress = "清除所有阅读进度"
    const val cleared = "阅读进度已清除"
    const val errorTitle = "这一本打不开"
    const val errorCorrupt = "压缩包可能损坏，或者使用了加密 / 不支持的压缩方式。"
    const val errorUnsupported = "这个位置暂时不能直接跳着读取。把漫画包移到手机内部存储的普通文件夹里再试。"
    const val errorBig = "单张图片太大，手机内存不够。"
    const val retry = "重试"
    const val booksUnit = "本"
    const val resume = "继续阅读"
    const val startRead = "开始阅读"
    const val aboutTitle = "关于这个应用"
    const val aboutBody = "完全离线，不申请任何权限，不上传任何数据。图片只在内存里解码，磁盘上始终只有原来的压缩包。"
    const val close = "关闭"
    const val zoomHint = "轻点左右边缘可连续翻页 · 点中间显示/收起工具条 · 双指放大 · 放大后双击复原"
    const val scrollHint = "上下滑动连续阅读 · 点中间显示/收起工具条 · 点左右侧滚动一屏"
    const val readMode = "阅读模式"
    const val modePage = "翻页"
    const val modeScroll = "卷轴"
    const val readModeTip = "超长条漫请用卷轴模式：长图自动分块加载，连续下滑阅读，不会一次占满内存。"
    const val fitMode = "缩放方式"
    const val alignMode = "原点设置（卷轴模式）"
    const val zoomPercent = "固定缩放比例"
    const val zoomTip = "除「伸展」会把画面拉满变形外，其余六种都按原比例缩放，不会把画面拉长或压扁。超长条漫建议卷轴模式配符合宽度或固定缩放。"
    const val screenOrientation = "屏幕方向"
    const val smoothing = "图像平滑方式"
    const val brightness = "背光控制"
    const val brightnessSystem = "跟随系统亮度"
    const val webtoonLoading = "正在准备长图…"
    const val webtoonNoImage = "这一页暂时无法显示"
    val fitOptions = listOf("原始尺寸", "符合屏幕", "符合宽度", "符合高度", "固定缩放", "伸展", "覆盖")
    val alignOptions = listOf("水平置中", "靠左", "靠右")
    val orientationOptions = listOf("跟随系统", "直立", "横屏")
    val smoothOptions = listOf("平滑", "锐利")
    val readModeOptions = listOf(modePage, modeScroll)
    const val folderError = "文件夹授权失效了，请重新选一次"
    const val noImages = "这个包里没有找到图片"
    const val done = "完成"
    const val cancel = "取消"
    const val coverLoading = "封面加载中"
    const val tipFirst = "首次使用：选择存放漫画包的文件夹"
    const val gb = "GB"
    const val mb = "MB"
    const val unknownSize = "未知大小"

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
