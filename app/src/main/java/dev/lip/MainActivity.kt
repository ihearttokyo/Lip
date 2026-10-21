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
import dev.lip.auth.Session
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
    private var signingIn = false
    private var cancelModelSetup: (() -> Unit)? = null
    private var inAppRecording = false
    private var testEditor: EditText? = null
    private var scratch = ""
    private var scratchStart = 0
    private var scratchEnd = 0
    private var dictionaryDraft: String? = null
    private var account: Session? = null
    private var accountLoaded = false
    private var accountTitle: TextView? = null
    private var accountDetail: TextView? = null
    private var accountButton: Button? = null
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
    override fun onResume() { super.onResume(); loadAccount(); dictation.observe(observer) }
    override fun onPause() {
        dictation.unobserve(observer)
        if (inAppRecording && LipAccessibilityService.instance == null && dictation.phase == Phase.LISTENING) dictation.protectCapture()
        inAppRecording = false
        super.onPause()
    }
    override fun onDestroy() {
        cancelModelSetup?.invoke(); cancelModelSetup = null
        super.onDestroy()
    }

    private fun render() {
        saveScratch()
        accountTitle = null; accountDetail = null; accountButton = null
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

    private fun loadAccount() {
        Work.io.execute {
            val saved = runCatching { dictation.chatGpt.session() }.getOrNull()
            runOnUiThread {
                account = saved; accountLoaded = true
                if (!isFinishing && !isDestroyed && tab == "Home") updateAccountCard()
            }
        }
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
        val connectionCard = column(16).apply { background = surface(Color.WHITE, dp(18).toFloat(), Color.rgb(231, 229, 217)) }
        accountTitle = label("", 18f).also { connectionCard.addSpaced(it, 5) }
        accountDetail = label("", 13f).also { it.setTextColor(Palette.muted); connectionCard.addSpaced(it) }
        accountButton = action("Continue with ChatGPT", true) { connect() }.also { connectionCard.addSpaced(it, 0) }
        page.addSpaced(connectionCard)
        updateAccountCard()
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
        page.addSpaced(label("Lip's downloaded multilingual model runs locally. Recognition can make mistakes. ChatGPT-plan cleanup is a preview, not unlimited API access.", 12f), 0)
    }

    private fun updateAccountCard() {
        val session = account
        accountTitle?.text = if (session == null) "Connect your ChatGPT plan" else "ChatGPT connected"
        accountDetail?.text = if (!accountLoaded) "Checking your saved connection…"
            else if (session == null) "Audio stays on-device. Only dictated text and your dictionary go to OpenAI for cleanup."
            else "${session.email ?: "Signed in"} · ${if (session.canUsePlan) "plan cleanup available" else "plan permission required"}"
        accountButton?.text = if (signingIn) "Cancel pending sign-in" else if (session == null) "Continue with ChatGPT" else "Reconnect ChatGPT"
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

    private fun connect() {
        if (signingIn) { dictation.chatGpt.cancelSignIn(); signingIn = false; render(); return }
        AlertDialog.Builder(this).setTitle("Use your ChatGPT plan")
            .setMessage("Lip sends the dictated transcript and your saved dictionary to OpenAI for text cleanup. Stable transcript segments may be sent while you speak for live cleanup. Cancel cannot recall requests already sent. Audio and surrounding editor text stay on-device. Eligible plan access and consent are required; OpenAI's account policies apply. Local encrypted history is enabled and can be disabled in Settings.")
            .setNegativeButton("Cancel", null).setPositiveButton("Continue with ChatGPT") { _, _ ->
                store.cloudConsent = true; store.liveCleanup = true
                signingIn = true; render()
                Work.io.execute {
                    var message: String
                    try {
                        val session = dictation.chatGpt.beginSignIn { url ->
                            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                        runOnUiThread { account = session; accountLoaded = true }
                        message = if (session.canUsePlan) "Connected. Eligible cleanup uses your ChatGPT plan."
                            else "Signed in without plan permission. Reconnect to authorize cleanup."
                    } catch (_: Exception) { message = "Sign-in did not complete. Check your browser, account eligibility and connection, then try again." }
                    runOnUiThread { signingIn = false; if (!isFinishing) { render(); toast(message) } }
                }
            }.show()
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
                                if (query.isEmpty() && previous.isEmpty()) "No dictations yet. Your next transcript will appear here."
                                else "No matching transcripts on this page.", 15f))
                            else list.addSpaced(label("Page ${previous.size + 1} · ${slice.entries.size} transcripts", 12f))
                            slice.entries.forEach { entry ->
                                val item = column(16).apply { background = surface(Color.WHITE, dp(18).toFloat()) }
                                item.addSpaced(label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.time)), 12f), 8)
                                val end = entry.clean.offsetByCodePoints(0, minOf(600, entry.clean.codePointCount(0, entry.clean.length)))
                                val excerpt = entry.clean.substring(0, end) + if (end < entry.clean.length) "…" else ""
                                item.addSpaced(label(excerpt, 16f).apply { maxLines = 8; setTextIsSelectable(true) })
                                item.addSpaced(label("${entry.language} · ${if (entry.usedChatGpt) "ChatGPT cleanup" else "On-device transcript"}", 12f))
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
        page.addSpaced(label("One entry per line. Lip supplies these terms as speech-recognition hints and ChatGPT cleanup context. Hints can help names; they do not train the local model or guarantee spelling.", 14f))
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
    }

    private fun settings() {
        title("Make it\nsound like you.", "Choose what leaves your phone and what stays.")
        languagePicker(page)
        picker(page, "Cleanup style", listOf("polished", "light", "verbatim"), listOf("Polished", "Light", "Verbatim"), store.style) { store.style = it }
        page.addSpaced(label("Polished removes fillers, applies clear self-corrections and formats lists. Light fixes punctuation and spacing. Verbatim skips ChatGPT. Meaning and factual accuracy still need your review.", 13f))
        lateinit var cleanupToggle: Switch
        cleanupToggle = toggle("ChatGPT text cleanup", store.cloudConsent) { enabled ->
            if (enabled) { cleanupToggle.isChecked = false; store.cloudConsent = false; connect() }
            else store.cloudConsent = false
        }
        lateinit var liveToggle: Switch
        liveToggle = toggle("Live ChatGPT preview", store.liveCleanup) { enabled ->
            if (!enabled) store.liveCleanup = false
            else {
                liveToggle.isChecked = false
                AlertDialog.Builder(this).setTitle("Clean while you speak?")
                    .setMessage("When ChatGPT cleanup is enabled, stable transcript segments and dictionary terms go to OpenAI before you finish. Cancel cannot recall requests already sent. Audio stays local. Preview requests use your plan allowance.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Enable") { _, _ -> store.liveCleanup = true; render() }.show()
            }
        }
        toggle("Insert automatically", store.autoInsert) { store.autoInsert = it }
        page.addSpaced(label("Turn off to review in the floating bubble before insertion. Changed focus or cursor always blocks automatic insertion.", 13f))
        toggle("Floating bubble", store.bubbleEnabled) { store.bubbleEnabled = it; LipAccessibilityService.refresh() }
        toggle("Keep encrypted transcript history", store.historyEnabled) { store.historyEnabled = it }
        page.addSpaced(label("Turning history off prevents new saves; existing entries remain until you delete them. History uses independent encrypted records and paged search. Storage errors preserve readable entries and the current transcript. Tokens and transcripts are excluded from Android backup.", 13f))
        page.addSpaced(action("Select ChatGPT model") {
            toast("Loading account-visible models…")
            Work.io.execute {
                try {
                    val models = dictation.chatGpt.listModels()
                    runOnUiThread { if (!isFinishing) AlertDialog.Builder(this).setTitle("ChatGPT model")
                        .setItems(models.map { it.displayName }.toTypedArray()) { _, which -> store.model = models[which].slug; toast("Model selected") }.show() }
                } catch (_: Exception) { runOnUiThread { toast("Sign in with plan permission, then try again") } }
            }
        })
        page.addSpaced(action("Manage ChatGPT plan usage") { open("https://chatgpt.com/settings/usage") })
        page.addSpaced(action("Sign out of ChatGPT") {
            AlertDialog.Builder(this).setTitle("Sign out?").setMessage("Lip will revoke its saved refresh token and remove local credentials. History is retained.")
                .setNegativeButton("Cancel", null).setPositiveButton("Sign out") { _, _ ->
                    store.cloudConsent = false
                    Work.io.execute {
                        val result = runCatching { dictation.chatGpt.signOut() }
                        runOnUiThread {
                            if (result.isSuccess) { account = null; accountLoaded = true }
                            render()
                            toast(when {
                                result.isFailure -> "Cleanup disabled, but local sign-out failed. Saved credentials may remain; revoke access in ChatGPT settings."
                                result.getOrThrow() -> "Signed out"
                                else -> "Signed out locally. Remote revocation unconfirmed; manage access in ChatGPT settings."
                            })
                        }
                    }
                }.show()
        })
        page.addSpaced(action("Clear transcript history") {
            AlertDialog.Builder(this).setTitle("Clear all transcript history?").setMessage("This permanently removes Lip's encrypted local history. It cannot be undone.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear history") { _, _ -> localTask("History cleared") { store.clearHistory() } }.show()
        })
        page.addSpaced(action("Privacy & source code") { open("https://ihearttokyo.github.io/Lip/privacy.html") })
        page.addSpaced(label("Lip 0.1.0 · prerelease\nOriginal open-source project inspired by Wispr Flow. Not affiliated with Wispr or OpenAI. No cloud audio, always-on recording or automatic message sending.", 12f), 0)
    }

    private fun languagePicker(container: LinearLayout) = picker(container, "Dictation language",
        listOf("en-US", "ja-JP", "zh-CN"), listOf("English", "日本語", "普通话 / Mandarin"), store.language) { store.language = it }

    private fun picker(container: LinearLayout, heading: String, values: List<String>, names: List<String>, value: String, selected: (String) -> Unit) {
        container.addSpaced(label(heading, 14f), 5)
        val spinner = Spinner(this).apply {
            contentDescription = heading
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(values.indexOf(value).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected(values[position]) }
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
        AlertDialog.Builder(this).setTitle("Offline speech model")
            .setMessage("Lip's multilingual Whisper model runs locally for English, Japanese and Mandarin. Download: 574 MB; keep at least 650 MB free. Wi-Fi is recommended. Audio never leaves this device. Recognition can still make mistakes; review important text.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Install / verify") { _, _ ->
                // Each setup dialog owns its cancellation; a delayed Cancel cannot affect a later attempt.
                val installer = dev.lip.speech.ModelFile(java.io.File(noBackupFilesDir, "speech"))
                val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
                fun cancelSetup() {
                    canceled.set(true)
                    Thread { runCatching { installer.cancel() } }.start()
                }
                val progress = label("Verifying the local speech model…", 14f).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
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
                        installer.install { received, total ->
                            if (canceled.get()) throw java.util.concurrent.CancellationException("Speech setup canceled")
                            runOnUiThread { if (!isDestroyed && !canceled.get() && dialog.isShowing) progress.text = "Downloading speech model · ${received * 100 / total}%" }
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
        render()
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Microphone access denied. Enable it in Android app settings to dictate.")
    }
}
