package com.yingcolor.lumanga.data

import com.yingcolor.lumanga.theme.Palettes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue













class UiSettings(private val prefs: Prefs) {
    
    private var stateFavorites by mutableStateOf(prefs.favorites)
    val favorites: Set<String> get() = stateFavorites

    private var stateThemeMode by mutableStateOf(prefs.themeMode)
    val themeMode: Int get() = stateThemeMode

    private var stateThemeColor by mutableIntStateOf(Palettes.coerce(prefs.themeColor))
    val themeColor: Int get() = stateThemeColor

    private var statePageAnim by mutableIntStateOf(PageAnimation.coerce(prefs.pageAnim))
    val pageAnim: Int get() = statePageAnim

    private var stateReadingDirection by mutableStateOf(prefs.readingDirection)
    val readingDirection: Int get() = stateReadingDirection

    private var stateDoublePage by mutableStateOf(prefs.doublePage)
    val doublePage: Boolean get() = stateDoublePage

    private var stateKeepAwake by mutableStateOf(prefs.keepAwake)
    val keepAwake: Boolean get() = stateKeepAwake

    private var stateGridColumns by mutableStateOf(prefs.gridColumns)
    val gridColumns: Int get() = stateGridColumns

    private var stateSortMode by mutableStateOf(prefs.sortMode)
    val sortMode: Int get() = stateSortMode

    private var stateTapToTurn by mutableStateOf(prefs.tapToTurn)
    val tapToTurn: Boolean get() = stateTapToTurn

    private var stateTranslateEnabled by mutableStateOf(prefs.translateEnabled)
    val translateEnabled: Boolean get() = stateTranslateEnabled

    private var stateTranslateApi by mutableStateOf(prefs.translateApi)
    val translateApi: String get() = stateTranslateApi

    private var stateTranslateModel by mutableStateOf(prefs.translateModel)
    val translateModel: String get() = stateTranslateModel

    private var stateTranslateApiKey by mutableStateOf(prefs.translateApiKey)
    val translateApiKey: String get() = stateTranslateApiKey

    private var stateOcrModel by mutableIntStateOf(OcrModel.from(prefs.ocrModel).id)
    val ocrModel: Int get() = stateOcrModel

    fun setTranslateEnabled(value: Boolean) {
        stateTranslateEnabled = value
        prefs.translateEnabled = value
    }

    fun setTranslateApi(value: String) {
        stateTranslateApi = value
        prefs.translateApi = value
    }

    fun setTranslateModel(value: String) {
        stateTranslateModel = value
        prefs.translateModel = value
    }

    fun setTranslateApiKey(value: String) {
        stateTranslateApiKey = value
        prefs.translateApiKey = value
    }

    fun setOcrModel(value: Int) {
        val safe = OcrModel.from(value).id
        stateOcrModel = safe
        prefs.ocrModel = safe
    }

    private var stateReadMode by mutableStateOf(prefs.readMode)
    val readMode: Int get() = stateReadMode

    private var stateFitMode by mutableStateOf(prefs.fitMode)
    val fitMode: Int get() = stateFitMode

    private var stateZoomPercent by mutableStateOf(prefs.zoomPercent)
    val zoomPercent: Int get() = stateZoomPercent

    private var stateAlignment by mutableStateOf(prefs.alignment)
    val alignment: Int get() = stateAlignment

    private var stateScreenOrientation by mutableStateOf(prefs.screenOrientation)
    val screenOrientation: Int get() = stateScreenOrientation

    
    
    private var stateBookOrientationRev by mutableIntStateOf(0)
    val bookOrientationRev: Int get() = stateBookOrientationRev

    private var stateSmoothing by mutableStateOf(prefs.smoothing)
    val smoothing: Int get() = stateSmoothing

    private var stateBrightness by mutableStateOf(prefs.brightness)
    val brightness: Int get() = stateBrightness

    fun toggleFavorite(bookId: String) {
        val next = stateFavorites.toMutableSet()
        if (!next.add(bookId)) next.remove(bookId)
        stateFavorites = next
        prefs.favorites = next
    }

    fun setThemeMode(value: Int) {
        stateThemeMode = value
        prefs.themeMode = value
    }

    fun setThemeColor(value: Int) {
        val safe = Palettes.coerce(value)
        stateThemeColor = safe
        prefs.themeColor = safe
    }

    fun setPageAnim(value: Int) {
        val safe = PageAnimation.coerce(value)
        statePageAnim = safe
        prefs.pageAnim = safe
    }

    fun setReadingDirection(value: Int) {
        stateReadingDirection = value
        prefs.readingDirection = value
    }

    fun setDoublePage(value: Boolean) {
        stateDoublePage = value
        prefs.doublePage = value
    }

    fun setKeepAwake(value: Boolean) {
        stateKeepAwake = value
        prefs.keepAwake = value
    }

    fun setGridColumns(value: Int) {
        val safe = value.coerceIn(2, 5)
        stateGridColumns = safe
        prefs.gridColumns = safe
    }

    fun setSortMode(value: Int) {
        val safe = value.coerceIn(0, 6)
        stateSortMode = safe
        prefs.sortMode = safe
    }

    fun setTapToTurn(value: Boolean) {
        stateTapToTurn = value
        prefs.tapToTurn = value
    }

    fun setReadMode(value: Int) {
        val safe = value.coerceIn(0, 2)
        stateReadMode = safe
        prefs.readMode = safe
    }

    fun setFitMode(value: Int) {
        val safe = ZoomMode.coerce(value)
        stateFitMode = safe
        prefs.fitMode = safe
    }

    fun setZoomPercent(value: Int) {
        val safe = value.coerceIn(40, 400)
        stateZoomPercent = safe
        prefs.zoomPercent = safe
    }

    fun setAlignment(value: Int) {
        val safe = value.coerceIn(0, 2)
        stateAlignment = safe
        prefs.alignment = safe
    }

    fun setScreenOrientation(value: Int) {
        val safe = value.coerceIn(0, 2)
        stateScreenOrientation = safe
        prefs.screenOrientation = safe
    }

    
    fun bookOrientation(bookId: String): Int = prefs.bookOrientation(bookId)

    fun setBookOrientation(bookId: String, value: Int) {
        prefs.setBookOrientation(bookId, value)
        stateBookOrientationRev++
    }

    fun clearBookOrientations() {
        prefs.clearBookOrientations()
        stateBookOrientationRev++
    }

    fun setSmoothing(value: Int) {
        val safe = value.coerceIn(0, 1)
        stateSmoothing = safe
        prefs.smoothing = safe
    }

    fun setBrightness(value: Int) {
        val safe = value.coerceIn(-1, 100)
        stateBrightness = safe
        prefs.brightness = safe
    }
}
