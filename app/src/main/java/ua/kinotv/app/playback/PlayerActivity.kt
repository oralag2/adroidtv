package ua.kinotv.app.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import ua.kinotv.app.data.network.SourceUrl
import java.io.ByteArrayInputStream

/** Opens both native streams and the source's embedded HTML player without a TV browser. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : ComponentActivity() {
    private lateinit var target: PlaybackTarget
    private lateinit var root: FrameLayout
    private lateinit var normalLayout: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var loading: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private var webView: WebView? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var showingCustomView = false
    private var pageFailed = false
    private var resumeNativePlayback = false
    private var restoredPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingCustomView) hideCustomView() else finish()
            }
        })
        val address = intent.getStringExtra(EXTRA_URL).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        target = try {
            PlaybackTarget.create(address, referer, title)
        } catch (error: IllegalArgumentException) {
            showError(error.message ?: "Не вдалося відкрити програвач.")
            return
        }
        normalLayout.findViewWithTag<TextView>(TITLE_TAG).text = target.title
        restoredPosition = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
        startPlayback()
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        normalLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            setBackgroundColor(Color.rgb(11, 16, 32))
        }
        toolbar.addView(Button(this).apply {
            text = "‹ Назад"
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        toolbar.addView(TextView(this).apply {
            tag = TITLE_TAG
            text = "KinoTV"
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(20), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(Button(this).apply {
            text = "Повторити"
            setOnClickListener { if (::target.isInitialized) startPlayback() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        normalLayout.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        content = FrameLayout(this)
        normalLayout.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(normalLayout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        loading = ProgressBar(this)
        content.addView(loading, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(24), dp(36), dp(24))
            setBackgroundColor(Color.rgb(11, 16, 32))
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        errorPanel.addView(errorText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        errorPanel.addView(Button(this).apply {
            text = "Повторити спробу"
            setOnClickListener { if (::target.isInitialized) startPlayback() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        content.addView(errorPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun startPlayback() {
        pageFailed = false
        errorPanel.visibility = View.GONE
        loading.visibility = View.VISIBLE
        if (target.kind == PlaybackTarget.Kind.EMBED) openEmbed() else openNative()
    }

    private fun openNative() {
        if (player != null) {
            player?.prepare()
            player?.play()
            return
        }
        val transport = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(false)
            .setUserAgent("KinoTV/0.2 (Android TV)")
            .setDefaultRequestProperties(mapOf("Referer" to target.referer))
        val nativePlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(transport))
            .build()
        player = nativePlayer
        val view = PlayerView(this).apply {
            player = nativePlayer
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = false
            keepScreenOn = true
            setOnKeyListener { _, _, event -> dispatchMediaKeyEvent(event) }
        }
        playerView = view
        content.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        nativePlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loading.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                showError("Не вдалося відтворити відео. Перевірте мережу та повторіть спробу.")
            }
        })
        val mediaItem = MediaItem.Builder().setUri(target.url).apply {
            if (target.kind == PlaybackTarget.Kind.HLS) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        nativePlayer.setMediaItem(mediaItem)
        if (restoredPosition > 0) nativePlayer.seekTo(restoredPosition)
        nativePlayer.prepare()
        nativePlayer.playWhenReady = true
        view.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openEmbed() {
        hideCustomView()
        destroyWebView()
        val browser = try {
            WebView(this)
        } catch (_: RuntimeException) {
            showError("На пристрої недоступний Android System WebView. Установіть або оновіть його для перегляду цього відео.")
            return
        }
        webView = browser
        browser.setBackgroundColor(Color.BLACK)
        browser.keepScreenOn = true
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!pageFailed) loading.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (showingCustomView) {
                    callback.onCustomViewHidden()
                    return
                }
                showingCustomView = true
                customViewCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreen.visibility = View.VISIBLE
                normalLayout.visibility = View.GONE
                window.insetsController?.apply {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                view.requestFocus()
            }

            override fun onHideCustomView() = hideCustomView()

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (request.isForMainFrame) !target.permitsNavigation(request.url.toString())
                else blockedResource(request.url.toString())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (blockedResource(request.url.toString())) {
                    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                } else null

            override fun onPageFinished(view: WebView, url: String) {
                if (!pageFailed) loading.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError("Не вдалося завантажити програвач. Перевірте мережу та повторіть спробу.")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) showError("Сервер програвача повернув HTTP ${errorResponse.statusCode}. Спробуйте пізніше.")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showError("Не вдалося підтвердити захищене з’єднання з програвачем.")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    hideCustomView()
                    destroyWebView()
                    showError("Програвач зупинився. Натисніть «Повторити спробу».")
                }
                return true
            }
        }
        content.addView(browser, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        browser.loadUrl(target.url, mapOf("Referer" to target.referer))
        browser.requestFocus()
    }

    private fun blockedResource(address: String): Boolean {
        if (address == "about:blank" || address.startsWith("blob:") || address.startsWith("data:")) return false
        return SourceUrl.publicHttps(address, target.url) == null
    }

    private fun hideCustomView() {
        if (!showingCustomView) return
        showingCustomView = false
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        normalLayout.visibility = View.VISIBLE
        window.insetsController?.show(WindowInsets.Type.systemBars())
        val callback = customViewCallback
        customViewCallback = null
        callback?.onCustomViewHidden()
        webView?.requestFocus()
    }

    private fun showError(message: String) {
        hideCustomView()
        pageFailed = true
        loading.visibility = View.GONE
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        errorPanel.getChildAt(1)?.requestFocus()
    }

    override fun onPause() {
        resumeNativePlayback = player?.playWhenReady == true
        player?.pause()
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (resumeNativePlayback) player?.play()
        webView?.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_POSITION, player?.currentPosition ?: restoredPosition)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        hideCustomView()
        destroyWebView()
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun destroyWebView() {
        webView?.let { browser ->
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.stopLoading()
            browser.webChromeClient = null
            browser.webViewClient = WebViewClient()
            browser.removeAllViews()
            browser.destroy()
        }
        webView = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URL = "player_url"
        private const val EXTRA_REFERER = "source_referer"
        private const val EXTRA_TITLE = "movie_title"
        private const val TITLE_TAG = "player_title"
        private const val STATE_POSITION = "playback_position"

        fun intent(context: Context, url: String, referer: String, title: String): Intent {
            val target = PlaybackTarget.create(url, referer, title)
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, target.url)
                .putExtra(EXTRA_REFERER, target.referer)
                .putExtra(EXTRA_TITLE, target.title)
        }
    }
}
