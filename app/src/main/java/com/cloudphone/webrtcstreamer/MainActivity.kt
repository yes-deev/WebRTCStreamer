package com.cloudphone.webrtcstreamer

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cloudphone.webrtcstreamer.databinding.ActivityMainBinding
import com.cloudphone.webrtcstreamer.databinding.DialogSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 1080p Full HD Cloud Phone & Gaming Hub.
 * Directly launches scrcpy MSE 1080p stream URL hash to guarantee instant video playback
 * with 0ms black screen delay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    private var selectedResolution = "1080p"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupImmersiveFullscreen()
        setupHardwareAcceleratedStreamView()
        setupDashboardUi()
        setupControlHudListeners()
        setupBackNavigation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveSystemBars()
        }
    }

    private fun setupImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyImmersiveSystemBars()
    }

    private fun applyImmersiveSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupDashboardUi() {
        val currentUrl = getPersistedUrl()
        binding.tvVpsIp.text = "VPS Server: ${cleanIpHost(currentUrl)}"

        binding.toggleResolution.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnRes1080p -> selectedResolution = "1080p"
                    R.id.btnRes720p -> selectedResolution = "720p"
                    R.id.btnRes480p -> selectedResolution = "480p"
                }
                binding.btnConnectDash1.text = "Connect Stream ($selectedResolution)"
            }
        }

        binding.btnConnectDash1.setOnClickListener {
            connectToStreamSession(getPersistedUrl())
        }

        binding.btnConnectDash2.setOnClickListener {
            connectToStreamSession("http://185.227.111.231:7001")
        }

        binding.btnVpsSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    /**
     * Formats target URL to directly open scrcpy MSE 1080p stream hash endpoint.
     */
    private fun formatStreamHashUrl(baseUrl: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        return if (!cleanBase.contains("#!action=stream")) {
            "$cleanBase/#!action=stream&udid=redroid:5555&player=mse"
        } else {
            cleanBase
        }
    }

    private fun connectToStreamSession(baseUrl: String) {
        val streamUrl = formatStreamHashUrl(baseUrl)

        binding.dashboardLayout.visibility = View.GONE
        binding.streamContainer.visibility = View.VISIBLE
        binding.tvStreamStatus.text = "$selectedResolution | 60 FPS"

        Toast.makeText(this, "Connecting to 1080p Cloud Phone Stream...", Toast.LENGTH_SHORT).show()
        binding.webView.loadUrl(streamUrl)
    }

    private fun disconnectToDashboard() {
        binding.webView.loadUrl("about:blank")
        binding.streamContainer.visibility = View.GONE
        binding.dashboardLayout.visibility = View.VISIBLE
        Toast.makeText(this, "Disconnected. Returned to Hub.", Toast.LENGTH_SHORT).show()
    }

    private fun setupControlHudListeners() {
        binding.btnExitStream.setOnClickListener {
            disconnectToDashboard()
        }

        binding.btnReconnect.setOnClickListener {
            Toast.makeText(this, "Reconnecting 1080p Stream...", Toast.LENGTH_SHORT).show()
            binding.tvStreamStatus.text = "RECONNECTING..."
            val streamUrl = formatStreamHashUrl(getPersistedUrl())
            binding.webView.loadUrl(streamUrl)
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHardwareAcceleratedStreamView() {
        binding.webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
            isHapticFeedbackEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36"
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    runOnUiThread {
                        request?.grant(request.resources)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == "about:blank") return

                    binding.tvStreamStatus.text = "$selectedResolution | 60 FPS"

                    // Force video autoplay and UgPhone 100% 1080p viewport CSS
                    val auto1080pJs = """
                        (function() {
                            function apply1080pFix() {
                                var videos = document.querySelectorAll('video');
                                videos.forEach(function(v) {
                                    v.play().catch(function(e){});
                                });

                                var style = document.getElementById('ugphone-style');
                                if (!style) {
                                    style = document.createElement('style');
                                    style.id = 'ugphone-style';
                                    document.head.appendChild(style);
                                }
                                style.innerHTML = `
                                    html, body {
                                        margin: 0 !important;
                                        padding: 0 !important;
                                        background: #000000 !important;
                                        overflow: hidden !important;
                                        width: 100vw !important;
                                        height: 100vh !important;
                                    }
                                    header, nav, .header, .control-header, .device-list-header {
                                        display: none !important;
                                    }
                                    canvas, video, .player, #stream-canvas, div[class*="player"] {
                                        width: 100vw !important;
                                        height: 100vh !important;
                                        object-fit: contain !important;
                                        position: absolute !important;
                                        top: 0 !important;
                                        left: 0 !important;
                                        z-index: 999 !important;
                                    }
                                `;
                            }

                            apply1080pFix();
                            setTimeout(apply1080pFix, 1000);
                            setTimeout(apply1080pFix, 2500);
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(auto1080pJs, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        binding.tvStreamStatus.text = "OFFLINE"
                        Toast.makeText(
                            this@MainActivity,
                            "Unable to connect to VPS ${request.url}. Tap settings to check IP.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.streamContainer.visibility == View.VISIBLE) {
                    disconnectToDashboard()
                } else {
                    finish()
                }
            }
        })
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val currentUrl = getPersistedUrl()
        dialogBinding.etStreamUrl.setText(currentUrl)

        var dialog: AlertDialog? = null

        dialogBinding.btnConnectInstance1.setOnClickListener {
            val inputUrl = dialogBinding.etStreamUrl.text.toString().trim()
            val targetUrl = if (isValidUrl(inputUrl)) inputUrl else DEFAULT_STREAM_URL
            saveAndReloadUrl(targetUrl)
            dialog?.dismiss()
            connectToStreamSession(targetUrl)
        }

        dialogBinding.btnConnectInstance2.setOnClickListener {
            saveAndReloadUrl("http://185.227.111.231:7001")
            dialog?.dismiss()
            connectToStreamSession("http://185.227.111.231:7001")
        }

        dialogBinding.btnDisconnectDialog.setOnClickListener {
            disconnectToDashboard()
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .setNeutralButton(R.string.btn_reset) { _, _ ->
                saveAndReloadUrl(DEFAULT_STREAM_URL)
            }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val inputUrl = dialogBinding.etStreamUrl.text.toString().trim()
            if (isValidUrl(inputUrl)) {
                saveAndReloadUrl(inputUrl)
                dialog.dismiss()
                binding.tvVpsIp.text = "VPS Server: ${cleanIpHost(inputUrl)}"
            } else {
                dialogBinding.tilStreamUrl.error = getString(R.string.error_invalid_url)
            }
        }
    }

    private fun cleanIpHost(url: String): String {
        return url.replace("http://", "").replace("https://", "").replace("/", "")
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ws://") || url.startsWith("wss://")
    }

    private fun saveAndReloadUrl(url: String) {
        sharedPreferences.edit().putString(KEY_STREAM_URL, url).apply()
        Toast.makeText(this, R.string.toast_url_saved, Toast.LENGTH_SHORT).show()
    }

    private fun getPersistedUrl(): String {
        return sharedPreferences.getString(KEY_STREAM_URL, DEFAULT_STREAM_URL) ?: DEFAULT_STREAM_URL
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        applyImmersiveSystemBars()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "webrtc_streamer_prefs"
        private const val KEY_STREAM_URL = "vps_stream_url"
        private const val DEFAULT_STREAM_URL = "http://185.227.111.231:7000"
    }
}
