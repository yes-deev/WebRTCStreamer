package com.cloudphone.webrtcstreamer.scrcpy

import android.view.MotionEvent
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Binary protocol serializer for Scrcpy control events (Touch, Key, Scroll).
 */
class ControlMessageWriter(outputStream: OutputStream) {

    private val dos = DataOutputStream(outputStream)

    companion object {
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_COLLAPSE_NOTIFICATION_PANEL = 6
        const val TYPE_GET_CLIPBOARD = 7
        const val TYPE_SET_CLIPBOARD = 8
        const val TYPE_SET_SCREEN_POWER_MODE = 9
    }

    /**
     * Serializes native Android MotionEvent into Scrcpy binary touch control packet.
     */
    @Synchronized
    fun sendTouchEvent(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float
    ) {
        try {
            val buffer = ByteBuffer.allocate(32)
            buffer.put(TYPE_INJECT_TOUCH_EVENT.toByte())
            
            val scrcpyAction = when (action) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_UP -> 1
                MotionEvent.ACTION_MOVE -> 2
                else -> action
            }
            buffer.put(scrcpyAction.toByte())
            buffer.putLong(pointerId)
            buffer.putInt(x)
            buffer.putInt(y)
            buffer.putShort(screenWidth.toShort())
            buffer.putShort(screenHeight.toShort())
            
            // Pressure 0.0 .. 1.0 mapped to 0 .. 0xFFFF
            val pressureShort = (pressure.coerceIn(0.0f, 1.0f) * 65535).toInt().toShort()
            buffer.putShort(pressureShort)
            buffer.putInt(1) // Primary button mask

            dos.write(buffer.array(), 0, buffer.position())
            dos.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Sends Scrcpy keycode event (e.g. Back, Home, Power).
     */
    @Synchronized
    fun sendKeyCode(action: Int, keyCode: Int, repeat: Int = 0, metaState: Int = 0) {
        try {
            val buffer = ByteBuffer.allocate(14)
            buffer.put(TYPE_INJECT_KEYCODE.toByte())
            buffer.put(action.toByte())
            buffer.putInt(keyCode)
            buffer.putInt(repeat)
            buffer.putInt(metaState)

            dos.write(buffer.array(), 0, buffer.position())
            dos.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
