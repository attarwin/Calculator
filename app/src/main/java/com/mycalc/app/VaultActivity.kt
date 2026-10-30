package com.mycalc.app

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.inputmethod.EditorInfo
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

class VaultActivity : AppCompatActivity() {

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val cBg = Color.parseColor("#101214")
    private val cSurface = Color.parseColor("#1C1F23")
    private val cText = Color.parseColor("#ECEFF1")
    private val cSub = Color.parseColor("#90A4AE")
    private val cAcc = Color.parseColor("#FF9F1A")

    private lateinit var body: FrameLayout
    private lateinit var tabViews: List<TextView>
    private var current = 0
    private var exportTarget: VFile? = null
    private var web: WebView? = null
    private var webProgress: ProgressBar? = null
    private var webAddress: EditText? = null
    private var webCallback: ValueCallback<Array<Uri>>? = null

    private val webFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = webCallback
            webCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val w = web
            if (current == 2 && w != null && w.canGoBack()) {
                w.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else {
                    val u = data.data
                    if (u != null) uris.add(u)
                }
                importUris(uris)
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val f = exportTarget
            exportTarget = null
            val uri = res.data?.data
            if (res.resultCode == RESULT_OK && f != null && uri != null) exportFile(f, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hides the vault from screenshots and from the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        buildUi()
        onBackPressedDispatcher.addCallback(this, backCallback)
        select(0)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        val left = Guard.leftAt
        Guard.leftAt = 0L
        Guard.external = false
        if (left > 0 && SystemClock.elapsedRealtime() - left > 60000) finishAndRemoveTask()
    }

    override fun onDestroy() {
        try {
            val w = web
            if (w != null) {
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
        } catch (e: Exception) {
        }
        web = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!Guard.external && !isFinishing) finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        VaultStore.clearTmp(this)
        CookieManager.getInstance().flush()
        if (isFinishing) return
        if (Guard.external) {
            Guard.leftAt = SystemClock.elapsedRealtime()
        } else {
            finishAndRemoveTask()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(r).toFloat()
        return d
    }

    private fun lp(top: Int = 8): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(match, wrap)
        p.topMargin = dp(top)
        return p
    }

    private fun newColumn(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(4), dp(16), dp(24))
        return c
    }

    private fun setBody(c: View) {
        body.removeAllViews()
        val sv = ScrollView(this)
        sv.addView(c)
        body.addView(sv, FrameLayout.LayoutParams(match, match))
    }

    private fun hint(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(cSub)
        v.setPadding(dp(4), dp(8), dp(4), dp(8))
        return v
    }

    private fun actionButton(t: String, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 16f
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(Color.BLACK)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(16), dp(14), dp(16), dp(14))
        v.background = rounded(cAcc, 14)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.background = rounded(cSurface, 14)
        return c
    }

    private fun label(t: String, size: Float, color: Int): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        return v
    }

    private fun busy(msg: String): AlertDialog {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(24), dp(20), dp(24), dp(20))
        row.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        val t = TextView(this)
        t.text = msg
        t.setPadding(dp(16), 0, 0, 0)
        row.addView(t)
        val d = AlertDialog.Builder(this).setView(row).setCancelable(false).create()
        d.show()
        return d
    }

    private fun askNumber(title: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- shell

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20), dp(14), dp(8), dp(8))
        val title = label("Vault", 24f, cText)
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        val lock = label("Lock", 15f, cAcc)
        lock.setPadding(dp(16), dp(10), dp(16), dp(10))
        lock.setOnClickListener { finishAndRemoveTask() }
        header.addView(lock)
        root.addView(header, LinearLayout.LayoutParams(match, wrap))

        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(match, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(cSurface)
        val names = listOf("Files", "Notes", "Web", "Apps", "Settings")
        val list = ArrayList<TextView>()
        for (i in names.indices) {
            val t = label(names[i], 14f, cSub)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener { select(i) }
            list.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        tabViews = list
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)
    }

    private fun select(i: Int) {
        val prev = current
        current = i
        if (prev == 2 && i != 2) web?.onPause()
        for (k in tabViews.indices) tabViews[k].setTextColor(if (k == i) cAcc else cSub)
        when (i) {
            0 -> showFiles()
            1 -> showNotes()
            2 -> showWeb()
            3 -> showApps()
            else -> showSettings()
        }
    }

    // ---------------------------------------------------------------- web (Instagram website inside the vault)

    private fun normalizeWeb(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (!s.contains(' ') && s.contains('.')) return "https://" + s
        return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }

    private fun createWeb(): WebView {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(cBg)
        w.setDownloadListener { _, _, _, _, _ -> toast("Downloads are not supported in the vault browser") }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                val sc = u.scheme?.lowercase()
                // Anything that is not http/https (intent:, instagram://) is blocked so the real app never opens.
                return !(sc == "http" || sc == "https")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                val a = webAddress
                if (a != null && url != null && !a.hasFocus()) a.setText(url)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val p = webProgress ?: return
                p.progress = newProgress
                p.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                webCallback?.onReceiveValue(null)
                webCallback = filePathCallback
                Guard.external = true
                return try {
                    webFileLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    webCallback = null
                    false
                }
            }
        }
        web = w
        return w
    }

    private fun showWeb() {
        val w = web ?: createWeb()
        (w.parent as? ViewGroup)?.removeView(w)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(4), dp(8), dp(4))

        val back = label("Back", 14f, cAcc)
        back.setPadding(dp(8), dp(10), dp(10), dp(10))
        back.setOnClickListener { if (w.canGoBack()) w.goBack() }
        bar.addView(back)

        val ig = label("Instagram", 14f, cAcc)
        ig.setPadding(dp(8), dp(10), dp(10), dp(10))
        ig.setOnClickListener { w.loadUrl("https://www.instagram.com/") }
        bar.addView(ig)

        val addr = EditText(this)
        addr.setSingleLine()
        addr.hint = "Search or type URL"
        addr.textSize = 14f
        addr.setTextColor(cText)
        addr.setHintTextColor(cSub)
        addr.background = rounded(cSurface, 18)
        addr.setPadding(dp(14), 0, dp(14), 0)
        addr.imeOptions = EditorInfo.IME_ACTION_GO
        addr.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addr.setText(w.url ?: "")
        addr.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val u = normalizeWeb(v.text.toString())
                if (u.isNotEmpty()) w.loadUrl(u)
                v.clearFocus()
                true
            } else {
                false
            }
        }
        webAddress = addr
        bar.addView(addr, LinearLayout.LayoutParams(0, dp(40), 1f))
        col.addView(bar, LinearLayout.LayoutParams(match, wrap))

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 100
        pb.visibility = View.GONE
        webProgress = pb
        col.addView(pb, LinearLayout.LayoutParams(match, dp(3)))

        col.addView(w, LinearLayout.LayoutParams(match, 0, 1f))

        body.removeAllViews()
        body.addView(col, FrameLayout.LayoutParams(match, match))
        w.onResume()
        if (w.url == null) w.loadUrl("https://www.instagram.com/")
    }

    // ---------------------------------------------------------------- files

    private fun showFiles() {
        val col = newColumn()
        col.addView(actionButton("+ Add files") { pickFiles() }, lp(4))
        val files = VaultStore.files(this).sortedByDescending { it.added }
        if (files.isEmpty()) {
            col.addView(hint("No files yet. Add photos, videos or documents. They are encrypted inside the app."))
        } else {
            col.addView(hint("After adding, delete the original from your gallery or Files app yourself."))
        }
        for (f in files) col.addView(fileRow(f), lp(8))
        setBody(col)
    }

    private fun fileRow(f: VFile): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = rounded(cSurface, 14)
        val icon = TextView(this)
        icon.text = when {
            f.mime.startsWith("image/") -> "\uD83D\uDDBC"
            f.mime.startsWith("video/") -> "\uD83C\uDFAC"
            else -> "\uD83D\uDCC4"
        }
        icon.textSize = 24f
        row.addView(icon, LinearLayout.LayoutParams(dp(44), wrap))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = label(f.name, 15f, cText)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        val size = if (f.size > 0) Formatter.formatShortFileSize(this, f.size) + "  -  " else ""
        col.addView(label(size + DateFormat.getDateInstance().format(Date(f.added)), 12f, cSub))
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        row.setOnClickListener { fileMenu(f) }
        return row
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        Guard.external = true
        importLauncher.launch(i)
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dlg = busy("Encrypting " + uris.size + " file(s)...")
        Thread {
            var ok = 0
            for (u in uris) if (VaultStore.importFile(this, u) != null) ok++
            runOnUiThread {
                dlg.dismiss()
                toast("$ok of ${uris.size} added. Delete the originals yourself.")
                if (current == 0) showFiles()
            }
        }.start()
    }

    private fun fileMenu(f: VFile) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Open", "Export (save a copy)", "Delete")) { _, which ->
                when (which) {
                    0 -> openFile(f)
                    1 -> exportStart(f)
                    else -> confirmDelete(f)
                }
            }
            .show()
    }

    private fun confirmDelete(f: VFile) {
        AlertDialog.Builder(this)
            .setMessage("Delete \"" + f.name + "\" from the vault? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                VaultStore.deleteFile(this, f.id)
                showFiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportStart(f: VFile) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = f.mime
        i.putExtra(Intent.EXTRA_TITLE, f.name)
        exportTarget = f
        Guard.external = true
        exportLauncher.launch(i)
    }

    private fun exportFile(f: VFile, uri: Uri) {
        val dlg = busy("Saving a copy...")
        Thread {
            val ok = VaultStore.exportTo(this, f, uri)
            runOnUiThread {
                dlg.dismiss()
                toast(if (ok) "Copy saved" else "Could not save the copy")
            }
        }.start()
    }

    private fun openFile(f: VFile) {
        val isImage = f.mime.startsWith("image/")
        val isVideo = f.mime.startsWith("video/")
        if (!isImage && !isVideo) {
            toast("This file type can be opened after Export.")
            return
        }
        val dlg = busy("Opening...")
        Thread {
            val tmp: File? = try {
                VaultStore.decryptToTemp(this, f)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                dlg.dismiss()
                if (tmp == null) {
                    toast("Could not open this file")
                } else if (isImage) {
                    showImage(tmp)
                } else {
                    showVideo(tmp)
                }
            }
        }.start()
    }

    private fun fullscreenDialog(): Dialog {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.setOnDismissListener { VaultStore.clearTmp(this) }
        return d
    }

    private fun showImage(file: File) {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
        if (bmp == null) {
            toast("This image cannot be shown. Use Export.")
            return
        }
        val d = fullscreenDialog()
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(Color.BLACK)
        iv.setOnClickListener { d.dismiss() }
        d.setContentView(iv)
        d.show()
    }

    private fun showVideo(file: File) {
        val d = fullscreenDialog()
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        val vv = VideoView(this)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoPath(file.absolutePath)
        vv.setOnPreparedListener { it.start() }
        frame.addView(vv, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
        d.setContentView(frame)
        d.show()
    }

    // ---------------------------------------------------------------- notes

    private fun showNotes() {
        val col = newColumn()
        col.addView(actionButton("+ New note") { noteDialog(null) }, lp(4))
        val notes = VaultStore.notes(this).sortedByDescending { it.updated }
        if (notes.isEmpty()) col.addView(hint("No notes yet. Notes are encrypted."))
        for (n in notes) {
            val c = card()
            val t = label(n.title, 16f, cText)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            c.addView(t)
            val preview = label(n.body.replace('\n', ' '), 13f, cSub)
            preview.maxLines = 2
            preview.ellipsize = TextUtils.TruncateAt.END
            c.addView(preview)
            c.setOnClickListener { noteDialog(n) }
            col.addView(c, lp(8))
        }
        setBody(col)
    }

    private fun noteDialog(n: VNote?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val t = EditText(this)
        t.hint = "Title"
        t.setText(n?.title ?: "")
        val b = EditText(this)
        b.hint = "Write here..."
        b.minLines = 6
        b.gravity = Gravity.TOP
        b.setText(n?.body ?: "")
        box.addView(t)
        box.addView(b)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (n == null) "New note" else "Edit note")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                VaultStore.saveNote(this, n?.id, t.text.toString(), b.text.toString())
                showNotes()
            }
            .setNegativeButton("Cancel", null)
        if (n != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                VaultStore.deleteNote(this, n.id)
                showNotes()
            }
        }
        builder.show()
    }

    // ---------------------------------------------------------------- apps

    private fun showApps() {
        val col = newColumn()
        col.addView(
            hint("Quick launch only. These open the normal installed apps. They are NOT cloned or hidden copies."),
            lp(0)
        )
        col.addView(actionButton("+ Add app shortcut") { pickApp() }, lp(4))
        val pm = packageManager
        for (pkg in VaultStore.apps(this)) {
            val name: String? = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                null
            }
            if (name == null) {
                VaultStore.removeApp(this, pkg)
                continue
            }
            val c = card()
            c.addView(label(name, 16f, cText))
            c.addView(label("Tap to open  -  long press to remove", 12f, cSub))
            c.setOnClickListener { launchApp(pkg) }
            c.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Remove \"" + name + "\" from this list?")
                    .setPositiveButton("Remove") { _, _ ->
                        VaultStore.removeApp(this, pkg)
                        showApps()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            col.addView(c, lp(8))
        }
        setBody(col)
    }

    private fun pickApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(intent, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (found.isEmpty()) {
            toast("No apps found")
            return
        }
        val labels = found.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add app")
            .setItems(labels) { _, which ->
                VaultStore.addApp(this, found[which].second)
                showApps()
            }
            .show()
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            toast("Cannot open this app")
            return
        }
        startActivity(i)
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val col = newColumn()
        if (Passcode.isDefault(this)) {
            col.addView(hint("You are still using the default passcode 1234. Please change it."), lp(0))
        }
        col.addView(actionButton("Change passcode") { changePasscode() }, lp(4))
        col.addView(actionButton("Clear web data (Instagram login)") { clearWebData() }, lp(8))
        col.addView(actionButton("Lock and close") { finishAndRemoveTask() }, lp(8))
        col.addView(
            hint(
                "How it works\n" +
                    "- Type your passcode in the calculator and press = to open this vault.\n" +
                    "- Leaving the app, locking the phone or pressing Home closes it and removes it from Recent Apps.\n" +
                    "- Files, notes and the app list are encrypted with a key stored in the phone's Keystore.\n\n" +
                    "Limits\n" +
                    "- The app still shows in the launcher and in Settings > Apps under the name Calculator.\n" +
                    "- If this app is uninstalled, everything inside the vault is deleted. Use Export to keep copies.\n" +
                    "- This app cannot clone other apps. The Web tab opens the Instagram website inside the vault; it is not your installed Instagram app.\n" +
                    "- Only the Web tab uses the internet. Files and notes never leave the phone."
            ),
            lp(16)
        )
        setBody(col)
    }

    private fun clearWebData() {
        AlertDialog.Builder(this)
            .setMessage("Log out of everything in the Web tab and delete its cookies and site data?")
            .setPositiveButton("Clear") { _, _ ->
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                WebStorage.getInstance().deleteAllData()
                web?.clearCache(true)
                web?.clearHistory()
                toast("Web data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changePasscode() {
        askNumber("Current passcode") { cur ->
            if (!Passcode.verify(this, cur)) {
                toast("Wrong passcode")
                return@askNumber
            }
            askNumber("New passcode (4-8 digits)") { p1 ->
                if (p1.length < 4) {
                    toast("Passcode must be at least 4 digits")
                    return@askNumber
                }
                askNumber("Confirm new passcode") { p2 ->
                    if (p1 == p2) {
                        Passcode.set(this, p1)
                        toast("Passcode changed")
                        showSettings()
                    } else {
                        toast("Passcodes did not match")
                    }
                }
            }
        }
    }
}
