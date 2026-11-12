package com.yingcolor.lumanga.data

import android.content.Context
import android.net.Uri
import com.yingcolor.lumanga.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything the shelf and the batch panel need to draw progress without owning the work. */
data class BatchState(
    val running: Boolean = false,
    val pending: List<Book> = emptyList(),
    val totalBooks: Int = 0,
    val doneBooks: Int = 0,
    val bookName: String = "",
    val pageDone: Int = 0,
    val pageTotal: Int = 0,
    val message: String = "",
    val folder: String = ""
)

/**
 * Folder-wide pre-translate.
 *
 * The queue is a small JSON file listing the volumes still to do, and each finished volume is
 * removed from it on disk. Combined with [PreTranslate.run] skipping pages already stored, an
 * interrupted run resumes at the exact page it stopped on — closing the panel, switching to the
 * reader, a dead battery or a lost connection all leave a resumable queue behind. The coroutine
 * lives in this process-level object rather than in a dialog, so dismissing the panel does not
 * stop the work.
 */
object BatchPreTranslate {
    private const val FILE = "pretranslate_queue.json"
    private const val QUEUE_VERSION = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFlow = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> get() = stateFlow
    private var job: Job? = null

    /** Called once at app start so an unfinished queue from a previous process is visible. */
    fun attach(context: Context) {
        // An activity can be recreated while the batch keeps running; do not wipe live progress.
        if (job?.isActive == true) return
        val queue = read(context.applicationContext)
        stateFlow.value = BatchState(pending = queue.books, folder = queue.folder)
    }

    fun start(
        context: Context,
        folder: String,
        books: List<Book>,
        api: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol = ApiProtocol.DEFAULT
    ) {
        if (books.isEmpty()) return
        val app = context.applicationContext
        cancelInternal()
        write(app, folder, books)
        stateFlow.value = BatchState(
            running = true,
            pending = books,
            totalBooks = books.size,
            folder = folder
        )
        job = scope.launch { process(app, api, apiKey, model, protocol) }
    }

    /** Picks up the queue left by a stopped or killed run, using the current settings. */
    fun resume(
        context: Context,
        api: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol = ApiProtocol.DEFAULT
    ) {
        if (stateFlow.value.running) return
        val queue = read(context.applicationContext)
        if (queue.books.isEmpty()) return
        start(context, queue.folder, queue.books, api, apiKey, model, protocol)
    }

    fun cancel() {
        cancelInternal()
    }

    /** Drops only the to-do list; stored pages and their "translated" badges stay intact. */
    fun discard(context: Context) {
        cancelInternal()
        clear(context.applicationContext)
        stateFlow.value = BatchState()
    }

    private fun cancelInternal() {
        job?.cancel()
        job = null
    }

    private suspend fun process(
        app: Context,
        api: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol
    ) {
        val remaining = read(app).books.toMutableList()
        val total = remaining.size
        var done = 0
        var failed = 0
        try {
            while (remaining.isNotEmpty()) {
                val book = remaining.first()
                val count = PreTranslate.pageCount(app, book)
                stateFlow.update {
                    it.copy(
                        running = true,
                        pending = remaining.toList(),
                        totalBooks = total,
                        doneBooks = done,
                        bookName = book.name,
                        pageDone = 0,
                        pageTotal = count,
                        message = ""
                    )
                }
                if (count <= 0) {
                    // An unreadable archive stays first in the queue for a later retry.
                    failed++
                    stateFlow.update {
                        it.copy(running = false, message = S.batchFailed(book.name, 0))
                    }
                    return
                }
                val result = PreTranslate.run(
                    context = app,
                    book = book,
                    pages = (0 until count).toList(),
                    api = api,
                    apiKey = apiKey,
                    model = model,
                    protocol = protocol,
                    skipStored = true
                ) { donePages, _ ->
                    stateFlow.update { it.copy(pageDone = donePages) }
                }
                when (result) {
                    is PreTranslate.Result.Done -> {
                        remaining.removeAt(0)
                        write(app, stateFlow.value.folder, remaining)
                        done++
                    }
                    PreTranslate.Result.Cancelled -> {
                        stateFlow.update { it.copy(running = false, message = S.batchStopped) }
                        return
                    }
                    is PreTranslate.Result.Failed -> {
                        stateFlow.update {
                            it.copy(running = false, message = S.batchFailed(book.name, result.page))
                        }
                        return
                    }
                }
            }
            clear(app)
            stateFlow.update {
                it.copy(
                    running = false,
                    pending = emptyList(),
                    doneBooks = total,
                    pageDone = 0,
                    pageTotal = 0,
                    bookName = "",
                    message = S.batchDone(done, failed)
                )
            }
        } catch (_: CancellationException) {
            stateFlow.update { it.copy(running = false, message = S.batchStopped) }
        } catch (_: Throwable) {
            stateFlow.update { it.copy(running = false, message = S.batchStopped) }
        }
    }

    private data class Queue(val folder: String, val books: List<Book>)

    private fun queueFile(context: Context): File = File(context.filesDir, FILE)

    private fun read(context: Context): Queue {
        val file = queueFile(context)
        if (!file.isFile) return Queue("", emptyList())
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("v", 0) != QUEUE_VERSION) return@runCatching Queue("", emptyList())
            val folder = root.optString("folder", "")
            val array = root.optJSONArray("books") ?: JSONArray()
            val books = ArrayList<Book>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                books.add(
                    Book(
                        id = id,
                        uri = Uri.parse(id),
                        name = item.optString("name"),
                        sizeBytes = item.optLong("size"),
                        lastModified = item.optLong("modified"),
                        kind = kindOf(item.optString("kind")),
                        group = item.optString("group"),
                        rootId = item.optString("root")
                    )
                )
            }
            Queue(folder, books)
        }.getOrDefault(Queue("", emptyList()))
    }

    private fun write(context: Context, folder: String, books: List<Book>) {
        if (books.isEmpty()) {
            clear(context)
            return
        }
        val array = JSONArray()
        books.forEach { book ->
            array.put(
                JSONObject()
                    .put("id", book.id)
                    .put("name", book.name)
                    .put("size", book.sizeBytes)
                    .put("modified", book.lastModified)
                    .put("kind", kindName(book.kind))
                    .put("group", book.group)
                    .put("root", book.rootId)
            )
        }
        val root = JSONObject()
            .put("v", QUEUE_VERSION)
            .put("folder", folder)
            .put("books", array)
        val target = queueFile(context)
        val temp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }

    private fun clear(context: Context) {
        runCatching { queueFile(context).delete() }
    }

    private fun kindName(kind: BookKind): String = when (kind) {
        BookKind.FOLDER -> "folder"
        BookKind.RAR -> "rar"
        BookKind.SEVEN_ZIP -> "7z"
        BookKind.ARCHIVE -> "zip"
    }

    private fun kindOf(name: String): BookKind = when (name) {
        "folder" -> BookKind.FOLDER
        "rar" -> BookKind.RAR
        "7z" -> BookKind.SEVEN_ZIP
        else -> BookKind.ARCHIVE
    }
}
