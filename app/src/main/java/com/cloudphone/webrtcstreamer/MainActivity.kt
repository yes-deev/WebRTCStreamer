package com.cloudphone.webrtcstreamer

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cloudphone.webrtcstreamer.databinding.ActivityMainBinding
import com.cloudphone.webrtcstreamer.databinding.DialogSettingsBinding
import com.cloudphone.webrtcstreamer.scrcpy.ControlMessageWriter
import com.cloudphone.webrtcstreamer.scrcpy.H264Decoder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 100% Native Pure Android Scrcpy Client Activity (Zero WebView / Zero Web dependencies).
 * Direct TCP socket connection -> Android MediaCodec hardware decoding -> SurfaceView rendering.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    private var socket: Socket? = null
    private var decoder: H264Decoder? = null
    private var controlWriter: ControlMessageWriter? = null

    @Volatile
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-Edge immersive window setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupImmersiveFullscreen()
        setupNativeTouchEvents()

        // Attach SurfaceHolder callback to native SurfaceView
        binding.surfaceView.holder.addCallback(this)

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

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

    /**
     * Native MotionEvent touch listener mapping user finger gestures directly to
     * Scrcpy binary control packets.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupNativeTouchEvents() {
        binding.surfaceView.setOnTouchListener { v, event ->
            val writer = controlWriter ?: return@setOnTouchListener false

            val width = v.width
            val height = v.height
            val pointerCount = event.pointerCount

            for (i in 0 until pointerCount) {
                val pointerId = event.getPointerId(i).toLong()
                val x = event.getX(i).toInt().coerceIn(0, width)
                val y = event.getY(i).toInt().coerceIn(0, height)
                val pressure = event.getPressure(i)

                writer.sendTouchEvent(
                    action = event.actionMasked,
                    pointerId = pointerId,
                    x = x,
                    y = y,
                    screenWidth = width,
                    screenHeight = height,
                    pressure = pressure
                )
            }
            true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        connectToNativeScrcpyServer(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        disconnectScrcpy()
    }

    /**
     * Connects directly to the remote Scrcpy/Redroid instance via native TCP socket.
     */
    private fun connectToNativeScrcpyServer(holder: SurfaceHolder) {
        disconnectScrcpy()

        val hostAndPort = getPersistedHostAndPort()
        val host = hostAndPort.first
        val port = hostAndPort.second

        thread(name = "NativeScrcpyConnector") {
            try {
                socket = Socket(host, port).apply {
                    tcpNoDelay = true // Zero TCP buffering latency
                    soTimeout = 0
                }

                val inputStream: InputStream = socket!!.getInputStream()
                val outputStream: OutputStream = socket!!.getOutputStream()

                controlWriter = ControlMessageWriter(outputStream)
                decoder = H264Decoder(holder.surface)
                decoder?.start(inputStream)

                isConnected = true

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Connected to Scrcpy VPS: $host:$port",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Connection failed to $host:$port. Tap settings gear to check IP.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun disconnectScrcpy() {
        isConnected = false
        try {
            decoder?.stop()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            decoder = null
            socket = null
            controlWriter = null
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showSettingsDialog()
            }
        })
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val currentIp = getPersistedIpString()
        dialogBinding.etStreamUrl.setText(currentIp)

        var dialog: AlertDialog? = null

        dialogBinding.btnConnectInstance1.setOnClickListener {
            val inputIp = dialogBinding.etStreamUrl.text.toString().trim()
            saveAndReloadHost(inputIp)
            dialog?.dismiss()
        }

        dialogBinding.btnConnectInstance2.setOnClickListener {
            saveAndReloadHost("185.227.111.231:5556")
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .setNeutralButton(R.string.btn_reset) { _, _ ->
                saveAndReloadHost(DEFAULT_VPS_HOST)
            }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val inputIp = dialogBinding.etStreamUrl.text.toString().trim()
            if (inputIp.isNotEmpty()) {
                saveAndReloadHost(inputIp)
                dialog.dismiss()
            } else {
                dialogBinding.tilStreamUrl.error = "Please enter a valid IP address or host:port"
            }
        }
    }

    private fun saveAndReloadHost(hostStr: String) {
        sharedPreferences.edit().putString(KEY_VPS_HOST, hostStr).apply()
        Toast.makeText(this, "VPS Host saved. Connecting...", Toast.LENGTH_SHORT).show()
        if (binding.surfaceView.holder.surface.isValid) {
            connectToNativeScrcpyServer(binding.surfaceView.holder)
        }
    }

    private fun getPersistedIpString(): String {
        return sharedPreferences.getString(KEY_VPS_HOST, DEFAULT_VPS_HOST) ?: DEFAULT_VPS_HOST
    }

    private fun getPersistedHostAndPort(): Pair<String, Int> {
        val raw = getPersistedIpString().replace("http://", "").replace("https://", "").replace("/", "")
        val parts = raw.split(":")
        val host = parts.getOrNull(0) ?: "185.227.111.231"
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 5555
        return Pair(host, port)
    }

    override fun onDestroy() {
        disconnectScrcpy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "webrtc_streamer_prefs"
        private const val KEY_VPS_HOST = "vps_stream_url"
        private const val DEFAULT_VPS_HOST = "185.227.111.231:5555"
    }
}
