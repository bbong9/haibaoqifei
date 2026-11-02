package com.shilapi.xcertplay

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build

/** The receiver consumes state codes only, never BluetoothDevice extras. */
internal class CarAudioDiagnostics(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    var adapterState = "unknown"; private set
    var a2dpState = "unknown"; private set
    var a2dpPlaying = "unknown"; private set
    var hfpState = "unknown"; private set
    var playback = "unknown"; private set

    private val adapter get() = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            val line = when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    adapterState = adapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                    "Car audio: adapter state=$adapterState"
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    a2dpState = connectionState(state)
                    "Car audio: a2dp state=$a2dpState prev=${connectionState(previous)}"
                }
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                    a2dpPlaying = playingState(state)
                    "Car audio: a2dp playing=$a2dpPlaying"
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    hfpState = connectionState(state)
                    "Car audio: hfp state=$hfpState"
                }
                else -> return
            }
            log(line)
            log(route())
        }
    }

    fun start() {
        adapterState = runCatching { adapterState(adapter?.state ?: -1) }.getOrDefault("unknown")
        a2dpState = runCatching { connectionState(adapter?.getProfileConnectionState(BluetoothProfile.A2DP) ?: -1) }.getOrDefault("unknown")
        hfpState = runCatching { connectionState(adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) ?: -1) }.getOrDefault("unknown")
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
    }

    fun stop() { runCatching { context.unregisterReceiver(receiver) } }

    fun playbackChanged(playing: Boolean?) {
        val state = when (playing) { true -> "playing"; false -> "paused"; null -> "unknown" }
        if (state == playback) return
        playback = state
        log("Now playing: state=$state")
    }

    @Suppress("DEPRECATION")
    private fun route(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val active = runCatching { audio?.isMusicActive?.toString() ?: "unknown" }.getOrDefault("unknown")
        val a2dp = runCatching { audio?.isBluetoothA2dpOn?.toString() ?: "unknown" }.getOrDefault("unknown")
        val mode = runCatching { audioMode(audio?.mode ?: -1) }.getOrDefault("unknown")
        return "Car audio: route musicActive=$active a2dpOn=$a2dp mode=$mode"
    }

    companion object {
        fun adapterState(state: Int): String = when (state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turningOn"
            BluetoothAdapter.STATE_TURNING_OFF -> "turningOff"
            else -> "unknown"
        }
        fun connectionState(state: Int): String = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            else -> "unknown"
        }
        fun playingState(state: Int): String = when (state) {
            BluetoothA2dp.STATE_PLAYING -> "playing"
            BluetoothA2dp.STATE_NOT_PLAYING -> "stopped"
            else -> "unknown"
        }
        fun audioMode(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "inCall"
            AudioManager.MODE_IN_COMMUNICATION -> "inComm"
            else -> "unknown"
        }
    }
}
