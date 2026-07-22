package com.cloudphone.webrtcstreamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cloudphone.webrtcstreamer.databinding.ActivityMainBinding
import com.cloudphone.webrtcstreamer.databinding.DialogSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    // Dynamic Permission Launcher for WebRTC Hardware Features
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(
                this,
                R.string.permission_denied_toast,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Step 1: Enable Edge-to-Edge and prepare immersive layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Step 2: Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Step 3: Setup Immersive Sticky Fullscreen
        setupImmersiveFullscreen()

        // Step 4: Check & Request Audio/Camera Permissions for WebRTC
        checkAndRequestPermissions()

        // Step 5: Configure Hardware-Accelerated WebView for Real Scrcpy Stream
        setupLowLatencyWebView()

        // Step 6: Setup Floating Settings & Instance Manager Button Listener
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Step 7: Handle System Back Gesture to prevent closing stream accidentally
        setupBackNavigation()

        // Step 8: Load Configured VPS Stream URL (Default: 185.227.111.231:7000)
        loadStreamUrl()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveSystemBars()
        }
    }

    /**
     * Configures Sticky Fullscreen mode hiding status bar, navigation bar, and display cutouts.
     */
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

    /**
     * Requests necessary runtime permissions required by WebRTC (Camera & Audio Recording).
     */
    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions)
        }
    }

    /**
     * Applies zero-delay, low-latency, hardware-accelerated WebView settings optimized for
     * scrcpy / WebRTC video streaming.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupLowLatencyWebView() {
        binding.webView.apply {
            // Force hardware acceleration for zero-delay video rendering layer
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Touch pass-through & scroll optimizations (prevent OS gesture interference)
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
            isHapticFeedbackEnabled = false
            isLongClickable = false
            setOnLongClickListener { true } // Disable context text selection menus

            settings.apply {
                // Core Web Features required for Cloud Phone / WebRTC UI
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                // Mixed content support for plain HTTP VPS endpoints (e.g. http://185.227.111.231:7000)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // WebRTC media playback without user gesture requirement
                mediaPlaybackRequiresUserGesture = false

                // File access and viewport configurations
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true

                // Cache & Low-latency rendering optimizations
                cacheMode = WebSettings.LOAD_NO_CACHE
                
                // User Agent setup for responsive WebRTC stream wrapper
                userAgentString = userAgentString.replace("; wv", "")
            }

            // WebChromeClient: Auto-grants WebRTC audio/video capture permissions
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    runOnUiThread {
                        request?.grant(request.resources)
                    }
                }
            }

            // WebViewClient: Keeps navigation inside app & bypasses SSL errors for direct IP VPS
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false // Force all link clicks to render internally
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Bypass SSL verification for direct IP endpoints
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject JS to strip all Web-Scrcpy UI headers, sidebars, and control panels,
                    // expanding pure raw scrcpy video canvas to 100% fullscreen
                    val cleanUpJs = """
                        (function() {
                            var style = document.createElement('style');
                            style.innerHTML = `
                                header, nav, .header, .control-header, .device-list, .control-buttons, .navbar, .top-bar {
                                    display: none !important;
                                }
                                body, html {
                                    margin: 0 !important;
                                    padding: 0 !important;
                                    background: #000000 !important;
                                    overflow: hidden !important;
                                    width: 100vw !important;
                                    height: 100vh !important;
                                }
                                canvas, video, .video-layer, #stream-canvas {
                                    width: 100vw !important;
                                    height: 100vh !important;
                                    object-fit: contain !important;
                                    position: absolute !important;
                                    top: 0 !important;
                                    left: 0 !important;
                                }
                            `;
                            document.head.appendChild(style);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(cleanUpJs, null)
                }
            }
        }
    }

    /**
     * Prevents accidental app exit when pulling back gestures or pressing physical back buttons.
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    showSettingsDialog()
                }
            }
        })
    }

    /**
     * Displays a Material3 dialog to configure VPS IP (185.227.111.231) and select active Android instances.
     */
    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val currentUrl = getPersistedUrl()
        dialogBinding.etStreamUrl.setText(currentUrl)

        var dialog: AlertDialog? = null

        // Connect button handler for Instance #1
        dialogBinding.btnConnectInstance1.setOnClickListener {
            val baseIp = dialogBinding.etStreamUrl.text.toString().trim()
            val targetUrl = if (isValidUrl(baseIp)) baseIp else DEFAULT_STREAM_URL
            saveAndReloadUrl(targetUrl)
            dialog?.dismiss()
        }

        // Connect button handler for Instance #2 (Secondary Port 7001)
        dialogBinding.btnConnectInstance2.setOnClickListener {
            val baseIp = dialogBinding.etStreamUrl.text.toString().trim()
            val cleanIp = baseIp.replace("7000", "7001")
            val targetUrl = if (isValidUrl(cleanIp)) cleanIp else "http://185.227.111.231:7001"
            saveAndReloadUrl(targetUrl)
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

        // Positive button: Save VPS IP & Reload
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val inputUrl = dialogBinding.etStreamUrl.text.toString().trim()
            if (isValidUrl(inputUrl)) {
                saveAndReloadUrl(inputUrl)
                dialog.dismiss()
            } else {
                dialogBinding.tilStreamUrl.error = getString(R.string.error_invalid_url)
            }
        }
    }

    /**
     * Validates input URL string format.
     */
    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ws://") || url.startsWith("wss://")
    }

    /**
     * Saves the new stream URL to SharedPreferences and reloads the WebView stream.
     */
    private fun saveAndReloadUrl(url: String) {
        sharedPreferences.edit().putString(KEY_STREAM_URL, url).apply()
        Toast.makeText(this, R.string.toast_url_saved, Toast.LENGTH_SHORT).show()
        binding.webView.loadUrl(url)
    }

    /**
     * Reads the persisted URL or returns default (185.227.111.231:7000).
     */
    private fun getPersistedUrl(): String {
        return sharedPreferences.getString(KEY_STREAM_URL, DEFAULT_STREAM_URL) ?: DEFAULT_STREAM_URL
    }

    /**
     * Loads the stored stream URL into the WebView.
     */
    private fun loadStreamUrl() {
        binding.webView.loadUrl(getPersistedUrl())
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
