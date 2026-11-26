package com.yingcolor.lumanga

/**
 * All interface text, looked up in whichever language is active.
 *
 * Every entry is a property rather than a `const` because the language can change at runtime: the
 * activity is recreated on a switch, so a plain lookup is enough and nothing here has to be
 * observable. Names are unchanged from the single-language days, which is why call sites still
 * read `S.shelf` and friends.
 */
object S {
    private var current: Lang = Lang.ZH
    private var table: Texts = tableOf(Lang.ZH)

    /** The language in use, with "follow system" already resolved. */
    val lang: Lang get() = current

    /**
     * Switch language. The caller recreates the activity afterwards; text already on screen keeps
     * the old language until then, which is why switching goes through a recreate instead of a
     * state flag.
     */
    fun setLang(lang: Lang) {
        current = lang
        table = tableOf(lang)
    }

    /** Apply the stored preference ("" / "en" / "zh-TW" …), resolving "follow system" first. */
    fun applyStoredLanguage(stored: String) {
        val chosen = Lang.fromTag(stored)
        setLang(if (chosen == Lang.SYSTEM) systemLang() else chosen)
    }
    val modePage: String get() = table.modePage
    val modeScroll: String get() = table.modeScroll
    val modeScrollH: String get() = table.modeScrollH
    val gb: String get() = table.gb
    val mb: String get() = table.mb
    val unknownSize: String get() = table.unknownSize
    val shelf: String get() = table.shelf
    val shelfSubtitle: String get() = table.shelfSubtitle
    val search: String get() = table.search
    val searchClose: String get() = table.searchClose
    val chooseFolder: String get() = table.chooseFolder
    val manageFolders: String get() = table.manageFolders
    val emptyTitle: String get() = table.emptyTitle
    val emptyBody: String get() = table.emptyBody
    val emptyTip: String get() = table.emptyTip
    val noResult: String get() = table.noResult
    val loading: String get() = table.loading
    val refresh: String get() = table.refresh
    val backToTop: String get() = table.backToTop
    val settings: String get() = table.settings
    val settingsRoot: String get() = table.settingsRoot
    val translateToggleMark: String get() = table.translateToggleMark
    val retranslate: String get() = table.retranslate
    val retranslateStarted: String get() = table.retranslateStarted
    val settingsReadingSummary: String get() = table.settingsReadingSummary
    val back: String get() = table.back
    val previous: String get() = table.previous
    val next: String get() = table.next
    val firstPage: String get() = table.firstPage
    val lastPage: String get() = table.lastPage
    val closeBar: String get() = table.closeBar
    val directionLtrShort: String get() = table.directionLtrShort
    val directionRtlShort: String get() = table.directionRtlShort
    val direction: String get() = table.direction
    val ltr: String get() = table.ltr
    val rtl: String get() = table.rtl
    val pageMode: String get() = table.pageMode
    val single: String get() = table.single
    val double: String get() = table.double
    val appearance: String get() = table.appearance
    val themeSystem: String get() = table.themeSystem
    val themeLight: String get() = table.themeLight
    val themeDark: String get() = table.themeDark
    val keepAwake: String get() = table.keepAwake
    val tapToTurn: String get() = table.tapToTurn
    val tapToTurnTip: String get() = table.tapToTurnTip
    val gridColumns: String get() = table.gridColumns
    val sort: String get() = table.sort
    val clearProgress: String get() = table.clearProgress
    val cleared: String get() = table.cleared
    val clearProgressHint: String get() = table.clearProgressHint
    val clearWarningTitle: String get() = table.clearWarningTitle
    val clearWarningBody: String get() = table.clearWarningBody
    val clearConfirm: String get() = table.clearConfirm
    val favorite: String get() = table.favorite
    val unfavorite: String get() = table.unfavorite
    val favoriteLabel: String get() = table.favoriteLabel
    val readFilterOptions: List<String> get() = table.readFilterOptions
    val errorTitle: String get() = table.errorTitle
    val errorCorrupt: String get() = table.errorCorrupt
    val errorUnsupported: String get() = table.errorUnsupported
    val errorBig: String get() = table.errorBig
    val retry: String get() = table.retry
    val booksUnit: String get() = table.booksUnit
    val resume: String get() = table.resume
    val aboutTitle: String get() = table.aboutTitle
    val aboutRepo: String get() = table.aboutRepo
    val about: String get() = table.about
    val menu: String get() = table.menu
    val aboutBody: String get() = table.aboutBody
    val checkUpdate: String get() = table.checkUpdate
    val checkingUpdate: String get() = table.checkingUpdate
    val upToDate: String get() = table.upToDate
    val updateFailed: String get() = table.updateFailed
    val openUpdatePage: String get() = table.openUpdatePage
    val updateFound: String get() = table.updateFound
    val goUpdate: String get() = table.goUpdate
    val later: String get() = table.later
    val refreshDone: String get() = table.refreshDone
    val close: String get() = table.close
    val zoomHint: String get() = table.zoomHint
    val scrollHint: String get() = table.scrollHint
    val scrollHintH: String get() = table.scrollHintH
    val readMode: String get() = table.readMode
    val readModeTip: String get() = table.readModeTip
    val fitMode: String get() = table.fitMode
    val alignMode: String get() = table.alignMode
    val zoomPercent: String get() = table.zoomPercent
    val zoomTip: String get() = table.zoomTip
    val screenOrientation: String get() = table.screenOrientation
    val orientationTip: String get() = table.orientationTip
    val rotateToLandscape: String get() = table.rotateToLandscape
    val rotateToPortrait: String get() = table.rotateToPortrait
    val resetOrientationMemory: String get() = table.resetOrientationMemory
    val smoothing: String get() = table.smoothing
    val brightness: String get() = table.brightness
    val brightnessSystem: String get() = table.brightnessSystem
    val webtoonLoading: String get() = table.webtoonLoading
    val fitOptions: List<String> get() = table.fitOptions
    val alignOptions: List<String> get() = table.alignOptions
    val orientationOptions: List<String> get() = table.orientationOptions
    val smoothOptions: List<String> get() = table.smoothOptions
    val readModeOptions: List<String> get() = table.readModeOptions
    val folderError: String get() = table.folderError
    val noImages: String get() = table.noImages
    val done: String get() = table.done
    val cancel: String get() = table.cancel
    val folderType: String get() = table.folderType
    val libraries: String get() = table.libraries
    val addFolder: String get() = table.addFolder
    val removeFolder: String get() = table.removeFolder
    val lastFolderTip: String get() = table.lastFolderTip
    val librariesHint: String get() = table.librariesHint
    val personalize: String get() = table.personalize
    val personalizeSummary: String get() = table.personalizeSummary
    val themeColor: String get() = table.themeColor
    val themeColorTip: String get() = table.themeColorTip
    val shelfStatus: String get() = table.shelfStatus
    val shelfStatusMenu: String get() = table.shelfStatusMenu
    val shelfStatusTabs: String get() = table.shelfStatusTabs
    val shelfStatusTip: String get() = table.shelfStatusTip
    val pageAnim: String get() = table.pageAnim
    val pageAnimTip: String get() = table.pageAnimTip
    val pageAnimPreview: String get() = table.pageAnimPreview
    val themeColors: List<String> get() = table.themeColors
    val pageAnimOptions: List<String> get() = table.pageAnimOptions
    val sortOptions: List<String> get() = table.sortOptions
    val columnOptions: List<String> get() = table.columnOptions
    val language: String get() = table.language
    val languageSystem: String get() = table.languageSystem
    val languageTip: String get() = table.languageTip

    // ---- Endpoint names and failure reasons ----
    val protocolOpenAi: String get() = table.protocolOpenAi
    val langAuto: String get() = table.langAuto
    val langJapanese: String get() = table.langJapanese
    val langChinese: String get() = table.langChinese
    val langEnglish: String get() = table.langEnglish
    val errNoEndpoint: String get() = table.errNoEndpoint
    val errNoModel: String get() = table.errNoModel
    val errRequestFailed: String get() = table.errRequestFailed
    val errStopped: String get() = table.errStopped
    val errNoTranslation: String get() = table.errNoTranslation
    val errHttpRejected: String get() = table.errHttpRejected
    val errHttpPath: String get() = table.errHttpPath
    val errHttpTooLarge: String get() = table.errHttpTooLarge
    val errHttpRate: String get() = table.errHttpRate
    val errBadUrl: String get() = table.errBadUrl
    val errCleartext: String get() = table.errCleartext
    val errHostNotFound: String get() = table.errHostNotFound
    val errConnect: String get() = table.errConnect
    val errTimeout: String get() = table.errTimeout
    val errSocket: String get() = table.errSocket
    val errSsl: String get() = table.errSsl
    val errNoRelease: String get() = table.errNoRelease

    fun errHttpAuth(code: Int) = table.errHttpAuthFormat.format(code)
    fun errHttpServer(code: Int) = table.errHttpServerFormat.format(code)
    fun errIo(name: String) = table.errIoFormat.format(name)
    fun errUnknown(name: String) = table.errUnknownFormat.format(name)
    val translateConfig: String get() = table.translateConfig
    val translateConfigTip: String get() = table.translateConfigTip
    val translateConfigOcr: String get() = table.translateConfigOcr
    val translateConfigModel: String get() = table.translateConfigModel
    val translateModelEmpty: String get() = table.translateModelEmpty
    val translate: String get() = table.translate
    val translateSummary: String get() = table.translateSummary
    val translateApi: String get() = table.translateApi
    val translateModel: String get() = table.translateModel
    val translateProtocol: String get() = table.translateProtocol
    val translateProtocolTip: String get() = table.translateProtocolTip
    val translateProtocolOpenAi: String get() = table.translateProtocolOpenAi
    val translateProtocolAnthropic: String get() = table.translateProtocolAnthropic
    val translateProtocolGemini: String get() = table.translateProtocolGemini
    val translateProtocolOllama: String get() = table.translateProtocolOllama
    val translateModeGroup: String get() = table.translateModeGroup
    val translateModeGroupTip: String get() = table.translateModeGroupTip
    val preTranslateMode: String get() = table.preTranslateMode
    val translateModePageShort: String get() = table.translateModePageShort
    val translateModeGroupShort: String get() = table.translateModeGroupShort
    val concurrencyOcr: String get() = table.concurrencyOcr
    val concurrencyTranslate: String get() = table.concurrencyTranslate
    val concurrencyOcrUnavailable: String get() = table.concurrencyOcrUnavailable
    val translateSourceLanguage: String get() = table.translateSourceLanguage
    val translateSourceLanguageTip: String get() = table.translateSourceLanguageTip
    val translateSourceLanguageAutoShort: String get() = table.translateSourceLanguageAutoShort
    val translateTargetLanguage: String get() = table.translateTargetLanguage
    val translateTargetLanguageTip: String get() = table.translateTargetLanguageTip
    val preTranslateLanguage: String get() = table.preTranslateLanguage
    val fetchModels: String get() = table.fetchModels
    val fetchingModels: String get() = table.fetchingModels
    val fetchModelsReason: String get() = table.fetchModelsReason
    val fetchModelsEmpty: String get() = table.fetchModelsEmpty
    val modelPickerTitle: String get() = table.modelPickerTitle
    val translateApiTip: String get() = table.translateApiTip
    val translateOnlyText: String get() = table.translateOnlyText
    val translateLayoutHorizontal: String get() = table.translateLayoutHorizontal
    val translateLayoutVerticalShort: String get() = table.translateLayoutVerticalShort
    val translateLayoutHorizontalShort: String get() = table.translateLayoutHorizontalShort
    val preTranslateLayout: String get() = table.preTranslateLayout
    val translateLayoutHorizontalTip: String get() = table.translateLayoutHorizontalTip
    val coverWholeBalloon: String get() = table.coverWholeBalloon
    val coverWholeBalloonTip: String get() = table.coverWholeBalloonTip
    val translateWorking: String get() = table.translateWorking
    val translateReading: String get() = table.translateReading
    val translateNoText: String get() = table.translateNoText
    val translateNotJapanese: String get() = table.translateNotJapanese
    val translateFailed: String get() = table.translateFailed
    val preTranslate: String get() = table.preTranslate
    val preTranslateDelete: String get() = table.preTranslateDelete
    val preTranslateDeleteConfirm: String get() = table.preTranslateDeleteConfirm
    val preTranslateDeleted: String get() = table.preTranslateDeleted
    val preTranslateDeleteHint: String get() = table.preTranslateDeleteHint
    val preTranslateDeleteEmpty: String get() = table.preTranslateDeleteEmpty
    val preTranslateTitle: String get() = table.preTranslateTitle
    val preTranslateIntro: String get() = table.preTranslateIntro
    val preTranslateApiKey: String get() = table.preTranslateApiKey
    val preTranslateApiKeyTip: String get() = table.preTranslateApiKeyTip
    val preTranslateOcr: String get() = table.preTranslateOcr
    val ocrModelSharedHint: String get() = table.ocrModelSharedHint
    val translatedMark: String get() = table.translatedMark
    val ocrLiteNotice: String get() = table.ocrLiteNotice
    val ocrEngineTitle: String get() = table.ocrEngineTitle
    val ocrLocalModel: String get() = table.ocrLocalModel
    val ocrOnlineModel: String get() = table.ocrOnlineModel
    val ocrLocalModelDesc: String get() = table.ocrLocalModelDesc
    val ocrOnlineModelDesc: String get() = table.ocrOnlineModelDesc
    val ocrInUseTitle: String get() = table.ocrInUseTitle
    val ocrCurrentTag: String get() = table.ocrCurrentTag
    val ocrOnlineTag: String get() = table.ocrOnlineTag
    val ocrInUseLocalNote: String get() = table.ocrInUseLocalNote
    val ocrInUseOnlineHostOff: String get() = table.ocrInUseOnlineHostOff
    val ocrNoEndpoint: String get() = table.ocrNoEndpoint
    val ocrFillEndpoint: String get() = table.ocrFillEndpoint
    val onlineOcrTip: String get() = table.onlineOcrTip
    val ocrModelTitle: String get() = table.ocrModelTitle
    val ocrModelTip: String get() = table.ocrModelTip
    val ocrModelDefault: String get() = table.ocrModelDefault
    val ocrModelSmallTip: String get() = table.ocrModelSmallTip
    val ocrModelMediumTip: String get() = table.ocrModelMediumTip
    val ocrModelHybridTip: String get() = table.ocrModelHybridTip
    val ocrModelMangaTip: String get() = table.ocrModelMangaTip
    val ocrBlankRefill: String get() = table.ocrBlankRefill
    val ocrBlankRefillTip: String get() = table.ocrBlankRefillTip
    val preTranslatePages: String get() = table.preTranslatePages
    val preTranslateSelectAll: String get() = table.preTranslateSelectAll
    val preTranslateSelectNone: String get() = table.preTranslateSelectNone
    val preTranslateStart: String get() = table.preTranslateStart
    val preTranslateStop: String get() = table.preTranslateStop
    val preTranslateKeepOpen: String get() = table.preTranslateKeepOpen
    val preTranslateNeedEndpoint: String get() = table.preTranslateNeedEndpoint
    val preTranslateNeedPages: String get() = table.preTranslateNeedPages
    val preTranslateCancelled: String get() = table.preTranslateCancelled
    val preTranslateResumeToggle: String get() = table.preTranslateResumeToggle
    val preTranslateResumeHint: String get() = table.preTranslateResumeHint
    val preTranslateBusy: String get() = table.preTranslateBusy
    val preTranslateConfigTip: String get() = table.preTranslateConfigTip
    val preTranslateFollowGlobal: String get() = table.preTranslateFollowGlobal
    val preTranslateFollowing: String get() = table.preTranslateFollowing
    val translatedBadge: String get() = table.translatedBadge
    val translateTest: String get() = table.translateTest
    val translateTesting: String get() = table.translateTesting
    val translateTestOk: String get() = table.translateTestOk
    val translateTestFail: String get() = table.translateTestFail
    val batchPreTranslate: String get() = table.batchPreTranslate
    val batchTitle: String get() = table.batchTitle
    val batchIntro: String get() = table.batchIntro
    val batchScope: String get() = table.batchScope
    val batchScopeAll: String get() = table.batchScopeAll
    val batchSkipDone: String get() = table.batchSkipDone
    val batchResume: String get() = table.batchResume
    val batchStart: String get() = table.batchStart
    val batchStop: String get() = table.batchStop
    val batchClose: String get() = table.batchClose
    val batchKeepOpen: String get() = table.batchKeepOpen
    val batchNeedEndpoint: String get() = table.batchNeedEndpoint
    val batchNoBooks: String get() = table.batchNoBooks
    val batchAllDone: String get() = table.batchAllDone
    val batchStopped: String get() = table.batchStopped
    val batchDiscard: String get() = table.batchDiscard
    val batchDiscardTitle: String get() = table.batchDiscardTitle
    val batchDiscardBody: String get() = table.batchDiscardBody
    val batchDiscardConfirm: String get() = table.batchDiscardConfirm
    val batchConfirmTitle: String get() = table.batchConfirmTitle
    val batchConfirmStart: String get() = table.batchConfirmStart
    val batchBannerRunning: String get() = table.batchBannerRunning
    val batchBannerPending: String get() = table.batchBannerPending
    val batchBannerOpen: String get() = table.batchBannerOpen

    fun clearProgressTapHint(remaining: Int) = table.clearProgressTapHintFormat.format(remaining)

    fun clearConfirmCount(seconds: Int) = table.clearConfirmCountFormat.format(seconds)

    fun versionLabel(name: String, code: Int) = table.versionLabelFormat.format(name, code)

    fun updateFoundBody(name: String, code: Int) = table.updateFoundBodyFormat.format(name, code)

    fun foldersCount(count: Int) = table.foldersCountFormat.format(count)

    fun pageProgress(page: Int, total: Int): String {
        if (total <= 0) return table.readAlready
        return table.pageProgressFormat.format((page + 1).coerceIn(1, total), total)
    }

    /** An arrow between two language names; the names themselves come from the language lists. */
    fun preTranslateLanguageValue(from: String, to: String) = "$from → $to"

    fun ocrInUseLocal(model: String) = table.ocrInUseLocalFormat.format(model)

    fun ocrInUseOnline(host: String) = table.ocrInUseOnlineFormat.format(host)

    fun ocrOnlineModelLine(model: String): String =
        if (model.isBlank()) table.ocrOnlineModelNone else table.ocrOnlineModelLineFormat.format(model)

    /** Shows only host:port; a long path would push the line out of shape. */
    fun ocrEndpointHost(api: String): String {
        var text = api.trim()
        if (text.startsWith("http://", true)) text = text.substring(7)
        if (text.startsWith("https://", true)) text = text.substring(8)
        return text.substringBefore('/')
    }

    fun onlineOcrTestOk(count: Int) = table.onlineOcrTestOkFormat.format(count)

    fun onlineOcrTestFail(reason: String) = table.onlineOcrTestFailFormat.format(reason)

    fun onlineOcrRowHint(host: String): String =
        if (host.isBlank()) table.onlineOcrRowHintEmpty
        else table.onlineOcrRowHintFormat.format(host)

    fun preTranslateOcrValue(name: String) = table.preTranslateOcrValueFormat.format(name)

    fun modelPickerCount(count: Int) = table.modelPickerCountFormat.format(count)

    fun preTranslateDeleteBody(count: Int) = table.preTranslateDeleteBodyFormat.format(count)

    fun preTranslateDeleteTapHint(remaining: Int) =
        table.preTranslateDeleteTapHintFormat.format(remaining)

    fun preTranslateSelected(selected: Int, total: Int) =
        table.preTranslateSelectedFormat.format(selected, total)

    fun preTranslateRunning(done: Int, total: Int) =
        table.preTranslateRunningFormat.format(done, total)

    fun preTranslateDone(count: Int) = table.preTranslateDoneFormat.format(count)

    fun preTranslateDoneResume(newPages: Int, skipped: Int) =
        table.preTranslateDoneResumeFormat.format(newPages, skipped)

    fun preTranslateFailed(page: Int) = table.preTranslateFailedFormat.format(page + 1)

    fun preTranslateDoneWithFailures(count: Int, failed: Int) =
        table.preTranslateDoneWithFailuresFormat.format(count, failed)

    fun preTranslateStored(count: Int) = table.preTranslateStoredFormat.format(count)

    fun batchConfirmBody(count: Int) = table.batchConfirmBodyFormat.format(count)

    fun batchBookCount(count: Int, done: Int) =
        table.batchBookCountFormat.format(count, done)

    fun batchPending(count: Int) = table.batchPendingFormat.format(count)

    fun batchRunning(book: Int, total: Int, name: String, page: Int, pages: Int) =
        table.batchRunningFormat.format(book, total, name, page, pages)

    fun batchDone(count: Int, failed: Int) =
        if (failed > 0) table.batchDoneWithFailFormat.format(count, failed)
        else table.batchDoneFormat.format(count)

    fun batchDoneWithFailures(count: Int, pages: Int) =
        table.batchDoneWithFailuresFormat.format(count, pages)

    fun batchFailed(name: String, page: Int) = table.batchFailedFormat.format(name, page + 1)

    fun batchBannerDetail(book: Int, total: Int, page: Int, pages: Int) =
        table.batchBannerDetailFormat.format(book, total, page, pages)

    fun batchBannerPendingDetail(count: Int) = table.batchBannerPendingDetailFormat.format(count)

    fun pageProgressCompact(page: Int, total: Int): String {
        if (total <= 0) return table.readShort
        return "${(page + 1).coerceIn(1, total)}/$total"
    }

    val orientationValues: List<Int> get() = listOf(1, 2, 0)

    fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> table.unknownSize
        bytes >= 1024L * 1024 * 1024 -> "%.1f %s".format(bytes / 1024f / 1024f / 1024f, table.gb)
        else -> "%.0f %s".format(bytes / 1024f / 1024f, table.mb)
    }
}
