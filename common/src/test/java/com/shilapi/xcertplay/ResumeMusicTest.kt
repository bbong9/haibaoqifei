package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeMusicTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val phone = "00:11:22:33:44:55"
    private val token = Any()
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_music_resume", 0).edit().clear().commit()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        DiPlayPreferences.savePhone(app, phone, "iPhone")
        controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(token)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
    }

    @After fun tearDown() {
        CarPlayMediaKeys.detach(controller)
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_music_resume", 0).edit().clear().commit()
    }

    private fun arm() {
        ResumeMusic.setEnabled(app, true)
        ResumeMusic.record(app, phone, true)
        CarPlayMediaKeys.attach(app, controller, phone)
        CarPlayMediaKeys.onSessionActive(controller)
    }

    private fun waitForResume() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CarPlayMediaKeys.RESUME_DELAY_MILLIS + 10))

    @Test fun settingIsOffAndStateIsBoundToPhoneAndFreshness() {
        assertFalse(ResumeMusic.enabled(app))
        ResumeMusic.record(app, phone, true, 10_000)
        ResumeMusic.setEnabled(app, true)
        assertFalse(ResumeMusic.shouldResume(app, phone, 10_001))
        ResumeMusic.record(app, phone, true, 10_000)
        assertTrue(ResumeMusic.shouldResume(app, phone.lowercase(), 10_001))
        assertFalse(ResumeMusic.shouldResume(app, "00:11:22:33:44:66", 10_001))
        assertFalse(ResumeMusic.shouldResume(app, phone, 10_000 + 24 * 60 * 60 * 1000L + 1))
        assertFalse(ResumeMusic.shouldResume(app, phone, 9_999))
        ResumeMusic.record(app, phone, false, 10_001)
        assertFalse(ResumeMusic.shouldResume(app, phone, 10_002))
    }

    @Test fun oneExplicitPlayIsSentForTheSamePreviouslyPlayingPhone() {
        arm()
        CarPlayMediaKeys.onSessionActive(controller)
        waitForResume()
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PLAY)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
    }

    @Test fun driverMediaActionOrPhonePauseCancelsThePendingAttempt() {
        arm()
        CarPlayMediaKeys.onUserMediaAction(controller, CarPlayMediaButton.PAUSE)
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
        assertFalse(ResumeMusic.shouldResume(app, phone))
    }

    @Test fun aCallOrAlreadyPlayingPhoneDoesNotReceivePlay() {
        arm()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(playing = true))
        CarPlayMediaKeys.onSessionActive(controller)
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun teardownGeneratedFalseDoesNotOverwriteTheLastPlayingState() {
        arm()
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onIphonePlaying",
            ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertTrue(ResumeMusic.shouldResume(app, phone))
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun teardownRefreshesALongRunningPlayingRecordButDoesNotUndoAPause() {
        arm()
        ResumeMusic.record(app, phone, true, System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        assertFalse(ResumeMusic.shouldResume(app, phone))
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onIphonePlaying",
            ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertTrue(ResumeMusic.shouldResume(app, phone))

        CarPlayMediaKeys.onPhonePlaybackUpdate(controller, false)
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onIphonePlaying",
            ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertFalse(ResumeMusic.shouldResume(app, phone))
    }

    @Test fun detachRefreshesAPlayingRecordWithoutOverridingADriverPause() {
        arm()
        ResumeMusic.record(app, phone, true, System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(playing = true))
        CarPlayMediaKeys.detach(controller)
        assertTrue(ResumeMusic.shouldResume(app, phone))

        CarPlayMediaKeys.attach(app, controller, phone)
        CarPlayMediaKeys.onUserMediaAction(controller, CarPlayMediaButton.PAUSE)
        CarPlayMediaKeys.detach(controller)
        assertFalse(ResumeMusic.shouldResume(app, phone))
    }

    @Test fun aRealPauseBeforeTheMainVideoTokenIsSaved() {
        arm()
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        CarPlayMediaKeys.onPhonePlaybackUpdate(controller, false)
        assertFalse(ResumeMusic.shouldResume(app, phone))
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        CarPlayMediaKeys.onSessionActive(controller)
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun changedPhoneAndDisablingBeforeTheTimerPreventPlayback() {
        arm()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:66", "other")
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
        DiPlayPreferences.savePhone(app, phone, "iPhone")
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        CarPlayMediaKeys.onSessionActive(controller)
        ResumeMusic.setEnabled(app, false)
        waitForResume()
        verify(controller, never()).sendMediaButton(anyInt())
    }
}
