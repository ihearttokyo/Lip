package dev.lip

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import dev.lip.core.EditorGuard
import dev.lip.core.EditorSnapshot
import dev.lip.vocabulary.PortableVocabulary
import dev.lip.vocabulary.PortableVocabularyStore
import dev.lip.vocabulary.VocabularyDocumentTransfer
import dev.lip.vocabulary.VocabularyMergeLease
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val dictation by lazy { Dictation.get(this) }
    private val store get() = dictation.store
    private lateinit var page: LinearLayout
    private var tab = "Home"
    private var status: TextView? = null
    private var preview: TextView? = null
    private var record: Button? = null
    private var rawChoice: Button? = null
    private var cleanedChoice: Button? = null
    private var waveform: WaveformView? = null
    private var cancelModelSetup: (() -> Unit)? = null
    private var inAppRecording = false
    private var selectedHistoryExport: Transcript? = null
    private class HistoryImportOperation {
        val lease = HistoryImportLease()
        val cancellation = android.os.CancellationSignal()
        var confirmed = false
        var awaitingPicker = false
    }
    private var historyImportOperation: HistoryImportOperation? = null
    private var historyImportDialog: AlertDialog? = null
    private var historyExportBusy = false
    // Private current-export outcome only, no URI/text persistence or diagnostic logging.
    private var lastHistoryExportUri: Uri? = null
    private var lastHistoryExportStatus = "idle"
    private var lastHistoryExportFilename: String? = null
    private var testEditor: EditText? = null
    private var scratch = ""
    private var scratchStart = 0
    private var scratchEnd = 0
    private var dictionaryDraft: String? = null
    private class VocabularyOperation {
        val lease = VocabularyMergeLease()
        val cancellation = android.os.CancellationSignal()
        var awaitingPicker = false
        var confirmed = false
        var exportBytes: ByteArray? = null
    }
    private var vocabularyImport: VocabularyOperation? = null
    private var vocabularyExport: VocabularyOperation? = null
    private var vocabularyDialog: AlertDialog? = null
    private var vocabularyStatus: TextView? = null
    private var renderHistoryPreference: (() -> Unit)? = null
    private val historyObserver: () -> Unit = { renderHistoryPreference?.invoke() }
    private val observer: () -> Unit = {
        status?.text = dictation.message
        preview?.text = dictation.preview
        record?.text = when (dictation.phase) {
            Phase.LISTENING -> "Finish dictation"
            Phase.TRANSCRIBING -> "Transcribing…"
            Phase.CLEANING -> "Cleaning up…"
            Phase.INSERTING -> "Inserting…"
            else -> "Try dictation"
        }
        waveform?.level = dictation.level
        listOf(rawChoice to true, cleanedChoice to false).forEach { (button, raw) ->
            button?.apply {
                isEnabled = if (raw) dictation.canUseRaw else dictation.canUseCleaned
                isSelected = dictation.outputIsRaw == raw
                background = surface(if (isSelected) Palette.lavender else Color.WHITE, dp(14).toFloat(), Color.rgb(218, 215, 208))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString("tab") ?: "Home"
        scratch = savedInstanceState?.getString("scratch").orEmpty()
        scratchStart = savedInstanceState?.getInt("scratchStart") ?: 0
        scratchEnd = savedInstanceState?.getInt("scratchEnd") ?: 0
        dictionaryDraft = savedInstanceState?.getString("dictionaryDraft")
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        saveScratch()
        outState.putString("tab", tab)
        outState.putString("scratch", scratch)
        outState.putInt("scratchStart", scratchStart)
        outState.putInt("scratchEnd", scratchEnd)
        outState.putString("dictionaryDraft", dictionaryDraft)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); dictation.revalidateCompletedAgentPrivacy(); dictation.observe(observer); store.observeHistory(historyObserver) }
    override fun onPause() {
        dictation.unobserve(observer)
        store.unobserveHistory(historyObserver)
        // Protect the history ticket in ALL phases, including cleaning/READY with queued IO.
        if (inAppRecording && LipAccessibilityService.instance == null) dictation.protectCapture()
        inAppRecording = false
        super.onPause()
    }
    override fun onStop() {
        if (vocabularyImport?.awaitingPicker != true) cancelVocabularyImport()
        if (vocabularyExport?.awaitingPicker != true) cancelVocabularyExport()
        super.onStop()
    }
    override fun onDestroy() {
        cancelVocabularyImport(); cancelVocabularyExport()
        cancelHistoryImport()
        cancelModelSetup?.invoke(); cancelModelSetup = null
        super.onDestroy()
    }

    private fun render() {
        cancelVocabularyImport(); cancelVocabularyExport(); vocabularyStatus = null
        cancelHistoryImport()
        renderHistoryPreference = null
        saveScratch()
        status = null; preview = null; record = null; waveform = null; testEditor = null
        rawChoice = null; cleanedChoice = null
        val root = column().apply { setBackgroundColor(Palette.cream) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        page = column(24)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("Lip", 31f, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("Android dictation", 12f).apply { setTextColor(Palette.muted) })
        page.addSpaced(top, 24)
        when (tab) {
            "Home" -> home()
            "History" -> history()
            "Dictionary" -> dictionary()
            "Settings" -> settings()
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        listOf("Home", "History", "Dictionary", "Settings").forEach { name ->
            val button = action(name, name == tab) { tab = name; render() }
            button.textSize = 12f
            button.setPadding(dp(4), dp(4), dp(4), dp(4))
            button.isSelected = name == tab
            navigation.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
        }
        root.addView(navigation)
        setContentView(root)
        root.requestApplyInsets()
        observer()
    }

    private fun saveScratch() {
        testEditor?.let { scratch = it.text.toString(); scratchStart = it.selectionStart; scratchEnd = it.selectionEnd }
    }



    private fun title(value: String, subtitle: String) {
        page.addSpaced(label(value, 43f, true), 12)
        page.addSpaced(label(subtitle, 16f).apply { setTextColor(Palette.muted) }, 24)
    }

    private fun card(title: String, detail: String, button: String, block: () -> Unit) {
        val card = column(16).apply { background = surface(Color.WHITE, dp(18).toFloat(), Color.rgb(231, 229, 217)) }
        card.addSpaced(label(title, 18f), 5)
        card.addSpaced(label(detail, 13f).apply { setTextColor(Palette.muted) }, 12)
        card.addSpaced(action(button, true, block), 0)
        page.addSpaced(card, 12)
    }

    private fun home() {
        title("Your voice,\neverywhere.", "Speak naturally. Keep your keyboard.")
        page.addSpaced(label("Local-only · no account required. Bark 3.0 Clean (standard) or Pro (optional) runs on-device through a quantized Starling runtime. English only; phone speed and quality are not yet validated.", 14f))
        card("Microphone", if (hasMic()) "Ready for on-device recognition" else "Only records after you tap. Never always listening.",
            if (hasMic()) "Offline speech models" else "Enable microphone") {
            if (hasMic()) modelDialog() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        card("Floating bubble", if (LipAccessibilityService.instance != null) "Enabled · appears beside supported text fields"
            else "Use Lip with Gboard. Android Accessibility access lets Lip show its button and insert your completed text.",
            if (LipAccessibilityService.instance != null) "Accessibility settings" else "Enable floating bubble") { enableAccessibility() }
        languagePicker(page)
        val banner = label("Your history is encrypted and stored only on this device. Change retention in Settings.", 13f).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = surface(Palette.lavender, dp(14).toFloat())
        }
        page.addSpaced(banner, 22)
        page.addSpaced(label("Try it here", 27f, true), 8)
        page.addSpaced(label("Place the cursor or select text, then dictate. This test never sends a message.", 13f), 10)
        testEditor = EditText(this).apply {
            hint = "Write something, then add your voice…"
            minLines = 2
            setTextColor(Palette.ink)
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = surface(Color.WHITE, dp(14).toFloat())
            contentDescription = "Dictation test editor"
            setText(scratch)
            setSelection(scratchStart.coerceIn(0, scratch.length), scratchEnd.coerceIn(0, scratch.length))
        }.also { page.addSpaced(it) }
        waveform = WaveformView(this).also { page.addSpaced(it, 4, dp(40)) }
        status = label(dictation.message, 14f).also { it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; page.addSpaced(it, 10) }
        record = action("Try dictation", true) { tryDictation() }.also { page.addSpaced(it) }
        val controls = LinearLayout(this)
        controls.addView(action("Cancel") { dictation.cancel() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(action("Insert") { dictation.insert() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        page.addSpaced(controls)
        preview = label(dictation.text, 16f).apply {
            setTextIsSelectable(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = surface(Color.WHITE, dp(16).toFloat())
        }.also { page.addSpaced(it) }
        page.addSpaced(action("Copy completed text") { copy(dictation.text.ifEmpty { dictation.raw }) })
        val choices = LinearLayout(this)
        rawChoice = action("Raw") { dictation.useRaw() }.apply { contentDescription = "Raw transcript" }
        cleanedChoice = action("Cleaned") { dictation.useCleaned() }.apply { contentDescription = "Cleaned transcript" }
        choices.addView(rawChoice, LinearLayout.LayoutParams(0, dp(48), 1f))
        choices.addView(cleanedChoice, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        page.addSpaced(choices)
        page.addSpaced(label("Speech and formatting run on-device. No sign-in or cloud cleanup. Recognition can make mistakes; review before sending.", 12f), 0)
    }



    private fun tryDictation() {
        when {
            dictation.phase == Phase.LISTENING -> dictation.stop()
            dictation.busy -> Unit
            !hasMic() -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            else -> {
                val editor = testEditor ?: return
                val original = EditorSnapshot(0, packageName, 0, "test", editor.text.toString(), editor.selectionStart, editor.selectionEnd)
                inAppRecording = true
                val valid = {
                    val current = original.copy(text = editor.text.toString(), start = editor.selectionStart, end = editor.selectionEnd)
                    editor.isAttachedToWindow && editor.hasWindowFocus() && editor.isFocused && EditorGuard.canInsert(original, current)
                }
                dictation.start(insert = { result, done ->
                    if (!valid()) done(false)
                    else {
                        val start = minOf(original.start, original.end)
                        editor.text.replace(start, maxOf(original.start, original.end), result)
                        editor.setSelection(start + result.length)
                        done(true)
                    }
                }, stillValid = valid)
            }
        }
    }



    private fun enableAccessibility() {
        AlertDialog.Builder(this).setTitle("Enable Lip's floating bubble")
            .setMessage(getString(R.string.accessibility_description) + "\n\nAndroid grants this service broad technical access. Lip uses only focused text-field metadata/content locally for safe insertion; it never navigates other apps. You can disable it at any time in Accessibility settings.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }

    private fun history() {
        title("Your words,\nkept here.", "Encrypted local history. Never synced to a Lip server.")
        val historyPage = page
        page.addSpaced(action("Import transcript JSON…") { chooseHistoryImport() })
        page.addSpaced(label("One-time restore does not enable automatic recording. Review the chosen plaintext file before adding its encrypted local record.", 13f))
        val search = EditText(this).apply { hint = "Search transcripts"; isSingleLine = true; setTextColor(Palette.ink) }
        page.addSpaced(search)
        val list = column()
        page.addSpaced(list)
        var request = 0
        var after: String? = null
        val previous = mutableListOf<String?>()
        var pendingSearch: Runnable? = null
        lateinit var draw: (String) -> Unit
        draw = { query ->
            val ticket = ++request
            val cursor = after
            list.removeAllViews()
            list.addSpaced(label("Loading encrypted history…", 14f))
            Work.io.execute {
                val result = runCatching { store.historyPage(query, cursor) }
                runOnUiThread {
                    if (!isFinishing && tab == "History" && page === historyPage && request == ticket) {
                        list.removeAllViews()
                        if (result.isFailure) {
                            list.addSpaced(label("History cannot be read. Existing encrypted data has been preserved.", 14f))
                            list.addSpaced(action("Retry") { draw(query) })
                        }
                        else {
                            val slice = result.getOrThrow()
                            if (slice.entries.isEmpty()) list.addSpaced(label(
                                if (query.isEmpty() && previous.isEmpty()) "No saved dictations yet. Only eligible recordings made with history enabled are retained."
                                else "No matching transcripts on this page.", 15f))
                            else list.addSpaced(label("Page ${previous.size + 1} · ${slice.entries.size} transcripts", 12f))
                            slice.entries.forEach { entry ->
                                val item = column(16).apply { background = surface(Color.WHITE, dp(18).toFloat()) }
                                item.addSpaced(label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.time)), 12f), 8)
                                val end = entry.clean.offsetByCodePoints(0, minOf(600, entry.clean.codePointCount(0, entry.clean.length)))
                                val excerpt = entry.clean.substring(0, end) + if (end < entry.clean.length) "…" else ""
                                item.addSpaced(label(excerpt, 16f).apply { maxLines = 8; setTextIsSelectable(true) })
                                item.addSpaced(label("${entry.language ?: "Unknown language"} · Model: ${entry.model ?: "Unknown"}${if (entry.usedChatGpt) " · Legacy cloud-cleanup flag" else ""}", 12f))
                                item.addSpaced(action("Export selected JSON…") { exportHistory(entry) }, 6)
                                item.addSpaced(action("Copy") { copy(entry.clean) }, 6)
                                item.addSpaced(action("View full text") {
                                    AlertDialog.Builder(this).setTitle("Dictated text").setMessage(entry.clean).setPositiveButton("Close", null).show()
                                }, 6)
                                item.addSpaced(action("View raw transcript") {
                                    AlertDialog.Builder(this).setTitle("Raw transcript").setMessage(entry.raw).setPositiveButton("Close", null).show()
                                }, 6)
                                item.addSpaced(action("Delete") {
                                    AlertDialog.Builder(this).setTitle("Delete this transcript?").setMessage("This removes its encrypted local history entry.")
                                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                                            localTask("Transcript deleted") { store.deleteTranscript(entry.id) }
                                        }.show()
                                }, 0)
                                list.addSpaced(item)
                            }
                            val navigation = LinearLayout(this)
                            if (previous.isNotEmpty()) navigation.addView(action("Previous page") {
                                after = previous.removeAt(previous.lastIndex)
                                draw(query)
                            }, LinearLayout.LayoutParams(0, dp(48), 1f))
                            slice.nextCursor?.let { next -> navigation.addView(action("Next page") {
                                previous.add(after)
                                after = next
                                draw(query)
                            }, LinearLayout.LayoutParams(0, dp(48), 1f)) }
                            if (navigation.childCount > 0) list.addSpaced(navigation)
                        }
                    }
                }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                request++
                after = null
                previous.clear()
                pendingSearch?.let { search.removeCallbacks(it) }
                val query = s?.toString().orEmpty()
                pendingSearch = Runnable { if (page === historyPage) draw(query) }.also { search.postDelayed(it, 200) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        draw("")
    }

    private fun dictionary() {
        title("Spell it\nyour way.", "Names, technical terms and phrases you want preserved.")
        page.addSpaced(label("One entry per line. Keyboard suggestions use A–Z words with optional apostrophes, up to 48 characters. Saved words are protected from autocorrect. Other entries remain stored for future support. Bark speech recognition does not apply dictionary hints.", 14f))
        val words = EditText(this).apply {
            hint = "Pianoforte\ngetUser\n東京\n你好"
            minLines = 7
            gravity = Gravity.TOP
            setTextColor(Palette.ink)
            textSize = 17f
            background = surface(Color.WHITE, dp(16).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        page.addSpaced(words)
        words.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { dictionaryDraft = s?.toString().orEmpty() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        if (dictionaryDraft != null) words.setText(dictionaryDraft)
        else Work.io.execute {
            val existing = runCatching { store.dictionary() }
            runOnUiThread {
                if (words.isAttachedToWindow && tab == "Dictionary" && dictionaryDraft == null) {
                    if (existing.isSuccess) words.setText(existing.getOrThrow().joinToString("\n"))
                    else toast("Dictionary cannot be read; existing data kept")
                }
            }
        }
        page.addSpaced(action("Save dictionary", true) {
            val value = words.text.toString()
            if (value.length > 8_000 || value.lines().size > 200 || value.lines().any { it.length > 200 }) {
                toast("Use up to 200 terms, 200 characters each, and 8,000 characters total"); return@action
            }
            localTask("Dictionary saved") { store.saveDictionary(value.lines()) }
        })
        page.addSpaced(label("Portable vocabulary", 22f, true), 24)
        page.addSpaced(label("Import a prepared Lip vocabulary v1 JSON, then explicitly merge it. Existing manual lines above are separate and unchanged; portable export does not include them. English A–Z words with optional apostrophes (up to48 characters) protect Lip typing/autocorrect. Phrases and other languages remain stored, not activated. Supported words also enter neural swipe decoding when their collapsed key path has at most40 steps. This does not train model weights or add speech biasing, history scans or automatic backup.", 14f))
        val portableStatus = label("Checking encrypted portable vocabulary…", 13f)
        vocabularyStatus = portableStatus; page.addSpaced(portableStatus)
        Work.io.execute {
            val count = runCatching { store.portableVocabularyEntries().size }
            runOnUiThread {
                if (vocabularyStatus === portableStatus && tab == "Dictionary" && !isDestroyed)
                    portableStatus.text = if (count.isSuccess) "${count.getOrThrow()} portable entries · encrypted on this device, excluded from Android backup"
                        else "Portable vocabulary unavailable. Existing encrypted data is preserved; Unicode16 compatibility is required."
            }
        }
        page.addSpaced(action("Import vocabulary JSON…") { chooseVocabularyImport() })
        page.addSpaced(action("Export portable vocabulary…") { chooseVocabularyExport() })
    }

    private fun settings() {
        title("Make it\nsound like you.", "Audio and dictated text stay on this device.")
        languagePicker(page)
        page.addSpaced(action("Speech model · ${dev.lip.speech.BarkProfile.fromId(store.speechModel).title}") { modelDialog() })
        picker(page, "Cleanup style", listOf("polished", "light", "verbatim"), listOf("Local commands", "Raw", "Verbatim"), store.style) { store.style = it }
        page.addSpaced(label("Local commands applies only explicit whole-line format commands. Raw and Verbatim preserve the recognized text. Clean provides model-native filler/restart removal; there is no separate AI editor, account or network cleanup.", 13f))
        page.addSpaced(action("Keyboard layout, material & haptics") { startActivity(Intent(this,dev.lip.keyboard.KeyboardSettingsActivity::class.java)) })
        toggle("Keyboard typing and next-word suggestions",store.typingSuggestions){store.typingSuggestions=it}
        toggle("Keyboard autocorrect at word boundaries",store.typingAutocorrect){store.typingAutocorrect=it}
        toggle("Insert automatically", store.autoInsert) { store.autoInsert = it }
        page.addSpaced(label("Turn off to review in the floating bubble before insertion. Changed focus or cursor always blocks automatic insertion.", 13f))
        toggle("Floating bubble", store.bubbleEnabled) { store.bubbleEnabled = it; LipAccessibilityService.refresh() }
        val historyState = label(historyPreferenceStatus(store.historyReadiness()), 13f)
        var reflectingHistory = false
        lateinit var historyToggle: Switch
        fun changeHistory(value: Boolean) {
            store.setHistoryEnabled(value)
            renderHistoryPreference?.invoke()
        }
        historyToggle = toggle("Keep encrypted transcript history", store.historyEnabled) { value ->
            if (!reflectingHistory) {
                if (!value) changeHistory(false)
                else {
                    reflectingHistory = true; historyToggle.isChecked = false; reflectingHistory = false
                    AlertDialog.Builder(this).setTitle("Keep future dictation history?")
                        .setMessage("Save original and output text, language, model metadata and date encrypted on this device. No sync or typed/swiped history. Off stops future saves but keeps existing records. Existing recordings will not be added retroactively. Device/key loss can make records unrecoverable.")
                        .setNegativeButton("Keep off", null)
                        .setPositiveButton("Enable local history") { _, _ ->
                            reflectingHistory = true; historyToggle.isChecked = true; reflectingHistory = false
                            changeHistory(true)
                        }.show()
                }
            }
        }
        renderHistoryPreference = {
            val snapshot = store.historyReadiness()
            reflectingHistory = true; historyToggle.isChecked = snapshot.requested; reflectingHistory = false
            historyState.text = historyPreferenceStatus(snapshot)
        }
        renderHistoryPreference?.invoke()
        page.addSpaced(historyState)
        page.addSpaced(label("Off by default; older retention settings require fresh confirmation. Turning history off prevents new unclaimed saves; an already-admitted write may finish before Off is confirmed. Existing entries remain until you delete them. New saves stop at 50 MiB / 2,000 record/stage files and 1 MiB per payload; no automatic eviction. Review History first to migrate an older archive. History uses independent encrypted records and paged search. Storage errors preserve readable entries and the current transcript. Tokens and transcripts are excluded from Android backup.", 13f))
        page.addSpaced(action("Clear transcript history") {
            AlertDialog.Builder(this).setTitle("Clear all transcript history?").setMessage("This permanently removes Lip's encrypted local history. It cannot be undone.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear history") { _, _ -> localTask("History cleared") { store.clearHistory() } }.show()
        })
        page.addSpaced(action("Privacy & source code") { open("https://ihearttokyo.github.io/Lip/privacy.html") })
        page.addSpaced(label("Lip local-only development build\nOriginal open-source project inspired by Wispr Flow. Not affiliated with Wispr or OpenAI. No cloud audio, always-on recording or automatic message sending.", 12f), 0)
    }

    private fun vocabularyCurrent(operation: VocabularyOperation, exporting: Boolean = false): Boolean =
        (if (exporting) vocabularyExport else vocabularyImport) === operation && !isFinishing && !isDestroyed && tab == "Dictionary"
    private fun cancelVocabularyImport() {
        val operation = vocabularyImport; vocabularyImport = null
        operation?.lease?.cancel(); operation?.cancellation?.cancel()
        val dialog = vocabularyDialog; vocabularyDialog = null; dialog?.dismiss()
    }
    private fun cancelVocabularyExport() {
        val operation = vocabularyExport; vocabularyExport = null
        operation?.lease?.cancel(); operation?.cancellation?.cancel(); operation?.exportBytes = null
    }
    private fun chooseVocabularyImport() {
        if (vocabularyImport != null || vocabularyExport != null) return
        val operation = VocabularyOperation(); vocabularyImport = operation
        vocabularyDialog = AlertDialog.Builder(this).setTitle("Choose prepared vocabulary JSON?")
            .setMessage("Read one selected Lip vocabulary v1 file (up to16MiB/100,000 entries), then review before merging. Lip will not scan Gmail, Outlook, WhatsApp or other histories. It is NOT a secret/PII detector: review and exclude private terms before importing. Original file and manual dictionary are unchanged. Only regular local documents are qualified; cloud-provider transports may be refused.")
            .setNegativeButton("Cancel") { _, _ -> cancelVocabularyImport() }
            .setOnCancelListener { cancelVocabularyImport() }
            .setPositiveButton("Choose JSON…") { _, _ ->
                if (vocabularyCurrent(operation)) {
                    vocabularyDialog = null; operation.awaitingPicker = true
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 6101) }
                    catch (_: Exception) { cancelVocabularyImport(); toast("No document picker; nothing imported.") }
                }
            }.show()
    }
    private fun receiveVocabularyImport(resultCode: Int, uri: Uri?) {
        val operation = vocabularyImport ?: return
        if (!operation.awaitingPicker) return
        operation.awaitingPicker = false
        if (!vocabularyCurrent(operation) || resultCode != RESULT_OK || uri == null) { cancelVocabularyImport(); return }
        Work.io.execute {
            val result = runCatching {
                val incoming = PortableVocabulary().decode(VocabularyDocumentTransfer.read(contentResolver, uri, operation.cancellation))
                operation.cancellation.throwIfCanceled()
                incoming to store.previewPortableVocabulary(incoming)
            }
            runOnUiThread {
                if (vocabularyCurrent(operation)) {
                    if (result.isFailure) {
                        cancelVocabularyImport(); toast("Vocabulary could not be validated. Unicode16 and a supported regular JSON file are required. Original file and encrypted store were preserved.")
                    } else {
                        val (incoming, preview) = result.getOrThrow()
                        showVocabularyPreview(operation, incoming, preview)
                    }
                }
            }
        }
    }
    private fun showVocabularyPreview(operation: VocabularyOperation, incoming: List<PortableVocabulary.Entry>, preview: PortableVocabularyStore.Preview) {
        if (!vocabularyCurrent(operation)) return
        val sample = incoming.take(20).joinToString("\n") { "• ${it.term} [${it.language}]" }
        val remaining = (incoming.size - 20).coerceAtLeast(0)
        vocabularyDialog = AlertDialog.Builder(this).setTitle("Review vocabulary merge")
            .setMessage("Selected: ${preview.incoming} entries\nExisting: ${preview.existing} · merged: ${preview.merged}\nNew: ${preview.added} · higher weights: ${preview.updated}\nActive English entries after merge: ${preview.activeEnglish}\n\n$sample${if (remaining > 0) "\n… $remaining more entries (not shown)" else ""}\n\nOnly the first20 terms are shown. Review the COMPLETE prepared JSON and exclude secrets/PII before merging; Lip cannot identify them reliably. Existing approved spelling/source wins and weight uses maximum, not sum. Other languages/phrases stay stored but do not activate keyboard support. Eligible English terms also join the neural swipe lexicon; no speech hints or model-weight training. Concurrent changes are reread/preserved at commit. Your manual draft above is untouched. An already-admitted merge may finish after closing.")
            .setNegativeButton("Cancel") { _, _ -> cancelVocabularyImport() }
            .setOnCancelListener { cancelVocabularyImport() }
            .setPositiveButton("Merge vocabulary") { _, _ ->
                if (vocabularyCurrent(operation) && !operation.confirmed) {
                    operation.confirmed = true; vocabularyDialog = null
                    Work.io.execute {
                        val saved = runCatching { store.mergePortableVocabulary(incoming, operation.lease::claim) }
                        runOnUiThread {
                            if (vocabularyCurrent(operation)) {
                                vocabularyImport = null
                                if (saved.isSuccess) {
                                    val actual = saved.getOrThrow()
                                    vocabularyStatus?.text = "${actual.preview.merged} portable entries · ${actual.preview.activeEnglish} active English typing words"
                                    toast(if (actual.changed) "Vocabulary merged locally. Manual draft preserved." else "Already present; nothing changed.")
                                } else {
                                    operation.lease.cancel()
                                    toast("Merge unconfirmed. Original JSON/manual dictionary were preserved; check Vocabulary before retrying.")
                                }
                            }
                        }
                    }
                }
            }.show()
    }
    private fun chooseVocabularyExport() {
        if (vocabularyImport != null || vocabularyExport != null) return
        val operation = VocabularyOperation(); vocabularyExport = operation
        vocabularyDialog = AlertDialog.Builder(this).setTitle("Export readable vocabulary JSON?")
            .setMessage("Portable terms/languages/weights/coarse source categories will be PLAINTEXT, not an encrypted backup. Names can be private. Anyone with access to the chosen provider may read them. No manual dictionary, messages, history or credentials are included. Choose a NEW empty regular file. No automatic Drive upload/sync or provider compatibility is promised. An admitted write can finish after closing; failure can leave partial plaintext.")
            .setNegativeButton("Cancel") { _, _ -> cancelVocabularyExport() }
            .setOnCancelListener { cancelVocabularyExport() }
            .setPositiveButton("Choose new file…") { _, _ ->
                if (vocabularyCurrent(operation, true)) {
                    vocabularyDialog = null
                    Work.io.execute {
                        val snapshot = runCatching { store.exportPortableVocabulary() }
                        runOnUiThread {
                            if (vocabularyCurrent(operation, true)) {
                                if (snapshot.isFailure) { cancelVocabularyExport(); toast("Vocabulary export unavailable. Existing data was preserved.") }
                                else {
                                    operation.exportBytes = snapshot.getOrThrow(); operation.awaitingPicker = true
                                    try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "Lip-vocabulary-${java.util.UUID.randomUUID()}.json"), 6102) }
                                    catch (_: Exception) { cancelVocabularyExport(); toast("No document picker; nothing exported.") }
                                }
                            }
                        }
                    }
                }
            }.show()
    }
    private fun receiveVocabularyExport(resultCode: Int, uri: Uri?) {
        val operation = vocabularyExport ?: return
        if (!operation.awaitingPicker) return
        operation.awaitingPicker = false
        val bytes = operation.exportBytes; operation.exportBytes = null
        if (!vocabularyCurrent(operation, true) || resultCode != RESULT_OK || uri == null || bytes == null) { cancelVocabularyExport(); return }
        Work.io.execute {
            val written = runCatching { VocabularyDocumentTransfer.write(contentResolver, uri, bytes, operation.cancellation, operation.lease::claim) }
            runOnUiThread {
                if (vocabularyCurrent(operation, true)) {
                    vocabularyExport = null
                    toast(if (written.isSuccess) "Portable vocabulary exported as readable JSON. No automatic sync is enabled."
                        else "Export unconfirmed. Check the selected file: it may contain partial plaintext. Local vocabulary was preserved; no retry was made.")
                }
            }
        }
    }

    private fun cancelHistoryImport() {
        val operation=historyImportOperation;historyImportOperation=null
        operation?.lease?.cancel();operation?.cancellation?.cancel()
        val dialog=historyImportDialog;historyImportDialog=null;dialog?.dismiss()
    }
    private fun importCurrent(operation:HistoryImportOperation)=historyImportOperation===operation&&!isFinishing&&!isDestroyed&&tab=="History"
    private fun chooseHistoryImport() {
        if(historyImportOperation!=null)return
        val operation=HistoryImportOperation();historyImportOperation=operation
        historyImportDialog=AlertDialog.Builder(this).setTitle("Choose a transcript to restore?")
            .setMessage("Lip will read one JSON file that you select and show a preview. Nothing is imported until you confirm. This does not enable automatic history recording or cloud sync. The file itself remains readable plaintext; Lip will not delete or modify it.")
            .setNegativeButton("Cancel") { _,_->if(importCurrent(operation))cancelHistoryImport() }
            .setOnCancelListener { if(importCurrent(operation))cancelHistoryImport() }
            .setPositiveButton("Choose JSON…") { _,_->
                if(importCurrent(operation)) {
                    historyImportDialog=null
                    operation.awaitingPicker=true
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),4103) }
                    catch(_:Exception){cancelHistoryImport();toast("No file picker available; nothing imported.")}
                }
            }.show()
    }
    private fun receiveHistoryImport(resultCode:Int,uri:Uri?) {
        val operation=historyImportOperation ?: return // Recreated Activity has no retained authorization.
        if(!operation.awaitingPicker)return
        operation.awaitingPicker=false
        if(!importCurrent(operation)||resultCode!=RESULT_OK||uri==null){cancelHistoryImport();return}
        Work.io.execute {
            val result=runCatching {
                val document=HistoryDocumentImport.read(contentResolver,uri,operation.cancellation)
                document to store.previewHistoryImport(document.record)
            }
            runOnUiThread {
                if(importCurrent(operation)) {
                    if(result.isFailure){cancelHistoryImport();toast("This file or local archive could not be validated. Nothing imported; existing records preserved.")}
                    else showHistoryImportPreview(operation,result.getOrThrow().first,result.getOrThrow().second)
                }
            }
        }
    }
    private fun showHistoryImportPreview(operation:HistoryImportOperation,document:PortableHistoryDocument,disposition:HistoryImportDisposition) {
        if(!importCurrent(operation))return
        val item=document.record
        fun excerpt(value:String):String {val end=value.offsetByCodePoints(0,minOf(600,value.codePointCount(0,value.length)));return value.substring(0,end)+(if(end<value.length) "…" else "")}
        val note=when(disposition){HistoryImportDisposition.NEW->"New record. Confirm to add it to encrypted local history."
            HistoryImportDisposition.IDENTICAL->"An identical record already exists. Confirming makes no changes."
            else->"This ID conflicts with local history. Import is blocked; no record will be overwritten."}
        val text="File reports: ${document.sourcePlatform} (not verified capture provenance)\nID: ${item.id}\nDate: ${DateFormat.getDateTimeInstance().format(Date(item.createdAtUnixMs))}\nLanguage: ${item.language ?: "Unknown"}\nModel: ${item.model ?: "Unknown"}\nModel details: ${item.modelDetails ?: "Unknown"}\nLegacy cloud-cleanup flag: ${item.legacyCloudCleanup}\n\nOriginal (${item.original.codePointCount(0,item.original.length)} characters):\n${excerpt(item.original)}\n\nOutput (${item.output.codePointCount(0,item.output.length)} characters):\n${excerpt(item.output)}\n\nPreview shows up to 600 characters per text; the complete record is imported.\n$note\nAutomatic recording stays unchanged. An already-admitted import may finish after closing this screen."
        val builder=AlertDialog.Builder(this).setTitle("Review transcript import").setMessage(text)
            .setNegativeButton("Cancel") { _,_->if(importCurrent(operation))cancelHistoryImport() }
            .setOnCancelListener { if(importCurrent(operation))cancelHistoryImport() }
        if(disposition==HistoryImportDisposition.NEW||disposition==HistoryImportDisposition.IDENTICAL)builder.setPositiveButton("Confirm import") { _,_->
            if(importCurrent(operation)&&!operation.confirmed) {
                operation.confirmed=true;historyImportDialog=null
                Work.io.execute {
                    val saved=runCatching { store.importHistory(item,operation.lease) }
                    runOnUiThread {
                        if(importCurrent(operation)) {
                            historyImportOperation=null
                            if(saved.isSuccess){render();toast(if(saved.getOrThrow()==HistoryImportDisposition.NEW) "Transcript imported into encrypted local history." else "Identical transcript already exists; nothing changed.")}
                            else {operation.lease.cancel();toast("Import unconfirmed. Check History before retrying; existing records were not overwritten.")}
                        }
                    }
                }
            }
        }
        historyImportDialog=builder.show()
    }

    private fun exportHistory(entry: Transcript) {
        if (selectedHistoryExport != null || historyExportBusy) return
        AlertDialog.Builder(this).setTitle("Export readable transcript JSON?")
            .setMessage("This exports the selected original/output and record metadata (ID, date, language, model/details and any legacy cloud-cleanup flag) as plaintext, NOT an encrypted backup. Anyone with access to the chosen provider—including cloud storage—may read it. Choose a NEW empty file. No automatic upload or restore is enabled.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Choose new file…") { _, _ ->
                if (!isFinishing && !isDestroyed && selectedHistoryExport == null && !historyExportBusy) {
                    selectedHistoryExport = entry
                    lastHistoryExportUri = null; lastHistoryExportStatus = "awaiting_document"
                    val filename = "Lip-transcript-" + java.util.UUID.randomUUID() + ".json"
                    lastHistoryExportFilename = filename
                    try {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE,filename), 4102)
                    } catch (_: Exception) { selectedHistoryExport = null; lastHistoryExportStatus = "picker_unavailable"; Toast.makeText(this,"No document picker available; nothing exported.",Toast.LENGTH_LONG).show() }
                }
            }.show()
    }
    @Deprecated("Uses system document picker result; no retained URI permission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 6101) { receiveVocabularyImport(resultCode, data?.data); return }
        if (requestCode == 6102) { receiveVocabularyExport(resultCode, data?.data); return }
        if (requestCode == 4103) { receiveHistoryImport(resultCode,data?.data); return }
        if (requestCode != 4102) return
        val entry = selectedHistoryExport; selectedHistoryExport = null
        if (resultCode != RESULT_OK) { lastHistoryExportStatus = "canceled"; return }
        val uri = data?.data
        lastHistoryExportUri = uri
        if (entry == null || uri == null) { lastHistoryExportStatus = "selection_lost" }
        if (entry == null || uri == null) { Toast.makeText(this,"Export canceled after selection loss; choose the record again.",Toast.LENGTH_LONG).show(); return }
        historyExportBusy = true; lastHistoryExportStatus = "writing"
        Work.io.execute {
            val result = runCatching { HistoryDocumentExport.write(contentResolver,uri,HistoryTransfer.encode(entry)) }
            runOnUiThread {
                historyExportBusy = false
                lastHistoryExportStatus = if(result.isSuccess) "saved" else "unconfirmed"
                if (!isFinishing && !isDestroyed) Toast.makeText(this,
                    if (result.isSuccess) "Selected transcript exported as readable JSON."
                    else "Export unconfirmed. History was preserved; check the chosen file—it may contain partial plaintext. No retry was made.",Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun historyPreferenceStatus(value: HistoryConsentGate.Snapshot) = HistoryConsentPresentation.describe(value)

    private fun languagePicker(container: LinearLayout) = picker(container, "Dictation language",
        listOf("en-US"), listOf("English (Bark)"), store.language) { store.language = it }

    private fun picker(container: LinearLayout, heading: String, values: List<String>, names: List<String>, value: String, selected: (String) -> Unit) {
        container.addSpaced(label(heading, 14f), 5)
        val choices = PickerChoices(values,value)
        val labels = if(choices.unavailable) listOf("Choose a supported option")+names else names
        val spinner = Spinner(this).apply {
            contentDescription = heading
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(choices.initialIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { choices.selected(position)?.let(selected) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        container.addSpaced(spinner, 18, dp(48))
    }

    private fun toggle(title: String, checked: Boolean, changed: (Boolean) -> Unit): Switch {
        val control = Switch(this).apply {
            text = title; textSize = 15f; setTextColor(Palette.ink); minHeight = dp(48)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        page.addSpaced(control)
        return control
    }

    private fun modelDialog() {
        if (dictation.busy) { toast("Finish recording/transcription before changing models"); return }
        val profiles = dev.lip.speech.BarkProfile.entries
        AlertDialog.Builder(this).setTitle("Choose on-device speech model")
            .setSingleChoiceItems(profiles.map { it.title }.toTypedArray(), profiles.indexOf(dev.lip.speech.BarkProfile.fromId(store.speechModel))) { dialog, index ->
                if (dictation.busy) { toast("Finish transcription first"); return@setSingleChoiceItems }
                store.speechModel = profiles[index].id
                dialog.dismiss(); installModelDialog(profiles[index])
            }.setNegativeButton("Cancel", null)
            .setPositiveButton("Prepare / verify selected") { _, _ -> installModelDialog(dev.lip.speech.BarkProfile.fromId(store.speechModel)) }.show()
    }
    private fun installModelDialog(profile: dev.lip.speech.BarkProfile) {
        val bundled = dev.lip.speech.BarkModel.bundledAvailable(this, profile)
        val delivery = if (bundled) "Included in this APK. No model download or Wi-Fi is needed. The first use prepares a local copy (364 MB); keep at least 430 MB free for preparation." else "Download: 364 MB; keep at least 430 MB free. Wi-Fi is recommended."
        AlertDialog.Builder(this).setTitle(profile.title)
            .setMessage("${profile.title} Q4 runs locally for English only using Starling. Clean removes fillers/restarts; Pro is more verbatim. Punctuation, numbers and lists are learned outputs, not guaranteed switches. $delivery This quantized derivative has not been validated on your phone. Dictionary hints are saved but are not supported by this engine. Audio never leaves this device. Recognition can still make mistakes; review important text.")
            .setNegativeButton("Not now", null)
            .setPositiveButton(if (bundled) "Prepare included model" else "Install / verify") { _, _ ->
                // Each setup dialog owns its cancellation; a delayed Cancel cannot affect a later attempt.
                val installer = dev.lip.speech.BarkModel.file(java.io.File(noBackupFilesDir, profile.directoryName), profile)
                val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
                fun cancelSetup() {
                    canceled.set(true)
                    Thread { runCatching { installer.cancel() } }.start()
                }
                val progress = label(if (bundled) "Preparing the included model locally…" else "Verifying the local speech model…", 14f).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
                var finished = false
                val dialog = AlertDialog.Builder(this).setTitle("Local speech setup").setView(progress)
                    .setNegativeButton("Cancel") { _, _ -> cancelSetup() }
                    .setOnCancelListener { if (!finished) cancelSetup() }.create()
                val cancelAttempt: () -> Unit = {
                    cancelSetup()
                    if (dialog.isShowing) dialog.dismiss()
                }
                cancelModelSetup?.invoke()
                cancelModelSetup = cancelAttempt
                dialog.show()
                Work.io.execute {
                    val outcome = runCatching {
                        check(!canceled.get()) { "Speech setup canceled" }
                        if (bundled) {
                            dev.lip.speech.BarkModel.installBundled(this, java.io.File(noBackupFilesDir, profile.directoryName), profile) { canceled.get() }
                                ?: error("The included model could not be opened; existing data was preserved")
                        } else {
                        installer.install { received, total ->
                            if (canceled.get()) throw java.util.concurrent.CancellationException("Speech setup canceled")
                            runOnUiThread { if (!isDestroyed && !canceled.get() && dialog.isShowing) progress.text = "Downloading speech model · ${received * 100 / total}%" }
                        }
                        }
                    }
                    runOnUiThread {
                        if (cancelModelSetup === cancelAttempt) cancelModelSetup = null
                        finished = true
                        if (!isDestroyed && dialog.isShowing) dialog.dismiss()
                        if (!isFinishing && !isDestroyed && !canceled.get()) toast(if (outcome.isSuccess) "Speech model verified and ready offline"
                            else outcome.exceptionOrNull()?.message ?: "Speech setup failed; existing model preserved")
                    }
                }
            }.show()
    }

    private fun localTask(success: String, block: () -> Unit) {
        Work.io.execute {
            val result = runCatching(block)
            runOnUiThread { if (!isFinishing) { render(); toast(if (result.isSuccess) success else "Local storage action failed. Check retained data before retrying.") } }
        }
    }
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun copy(text: String) { if (text.isBlank()) toast("No transcript to copy") else { LipAccessibilityService.copy(this, text); toast("Copied; paste manually") } }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        dictation.revalidateCompletedAgentPrivacy()
        render()
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Microphone access denied. Enable it in Android app settings to dictate.")
    }
}
