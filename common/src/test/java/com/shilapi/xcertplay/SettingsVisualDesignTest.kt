package com.shilapi.xcertplay

import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Switch
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.util.ReflectionHelpers
import kotlin.math.roundToInt
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1280dp-h800dp")
class SettingsVisualDesignTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [25], qualifiers = "zh-rCN-w390dp-h320dp-land-mdpi")
    fun legacyRailShrinksLongTitlesBeforeEllipsizing() {
        val context = RuntimeEnvironment.getApplication()
        val previousSize = InterfaceSize.preference(context)
        val previousLocale = AppLocale.preference(context)
        val previousFull = SettingsLayoutPreferences.forceFull(context)
        InterfaceSize.save(context, 100)
        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        SettingsLayoutPreferences.saveForceFull(context, true)
        RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        try {
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 390, 320)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
            val navigation = descendants(rail).filterIsInstance<TextView>().single { it.text.toString() == "导航与仪表" }
            val minPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                SettingsPalette.RAIL_MIN_TEXT_SP.toFloat(), screen.resources.displayMetrics)
            assertEquals(1, navigation.lineCount)
            // Robolectric's API 25 text metrics report one pixel per Chinese glyph.
            navigation.layout(navigation.left, navigation.top, navigation.left + 1, navigation.bottom)
            assertEquals("rail=${rail.width} textWidth=${navigation.width} textPixels=${navigation.paint.measureText(navigation.text.toString())}",
                minPx, navigation.textSize, 0.1f)
        } finally {
            controller.pause().stop().destroy()
            InterfaceSize.save(context, previousSize)
            AppLocale.save(context, previousLocale)
            SettingsLayoutPreferences.saveForceFull(context, previousFull)
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun physicalWindow160DpiMatrix() = physicalWindowMatrix(160, "mdpi")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun physicalWindow213DpiMatrix() = physicalWindowMatrix(213, "tvdpi")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun physicalWindow240DpiMatrix() = physicalWindowMatrix(240, "hdpi")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun physicalWindow320DpiMatrix() = physicalWindowMatrix(320, "xhdpi")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun physicalWindowArabicAndAdditionalSizes() {
        for (size in listOf(InterfaceSize.AUTO, 200)) {
            exercisePhysicalWindow(213, "tvdpi", 1.3f, size, AppAppearance.DARK, AppLocale.SIMPLIFIED_CHINESE)
        }
        exercisePhysicalWindow(320, "xhdpi", 1.3f, 200, AppAppearance.DARK, AppLocale.SIMPLIFIED_CHINESE)
        exercisePhysicalWindow(213, "tvdpi", 1.3f, 100, AppAppearance.DARK, AppLocale.ARABIC)
    }

    private fun physicalWindowMatrix(dpi: Int, qualifier: String) {
        for (font in listOf(1f, 1.3f)) for (size in listOf(100, 125, 150)) {
            for (appearance in listOf(AppAppearance.DARK, AppAppearance.LIGHT)) {
                exercisePhysicalWindow(dpi, qualifier, font, size, appearance, AppLocale.SIMPLIFIED_CHINESE)
            }
        }
    }

    private fun exercisePhysicalWindow(dpi: Int, qualifier: String, font: Float, size: Int,
        appearance: AppAppearance, locale: String) {
        val widthDp = (1920f * 160 / dpi).roundToInt()
        val heightDp = (1080f * 160 / dpi).roundToInt()
        RuntimeEnvironment.setQualifiers("zh-rCN-sw${heightDp}dp-w${widthDp}dp-h${heightDp}dp-land-$qualifier")
        RuntimeEnvironment.setFontScale(font)
        shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
            setWidth(1920)
            setHeight(1080)
            setRealWidth(1920)
            setRealHeight(1080)
        }
        val context = RuntimeEnvironment.getApplication()
        val previousApplicationFlags = context.applicationInfo.flags
        context.applicationInfo.flags = previousApplicationFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val previousSize = InterfaceSize.preference(context)
        val previousLocale = AppLocale.preference(context)
        val previousAppearance = AirPlayPersistence.loadAppAppearance(context)
        val previousCluster = AirPlayPersistence.loadClusterMapEnabled(context)
        val previousSpeedometer = AirPlayPersistence.loadClusterSpeedometerEnabled(context)
        InterfaceSize.save(context, size)
        AppLocale.save(context, locale)
        AirPlayPersistence.saveAppAppearance(context, appearance)
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        val spec = "dpi=$dpi font=$font size=$size appearance=$appearance locale=$locale"
        try {
            assertEquals(spec, dpi, context.resources.configuration.densityDpi)
            val expectedDensity = (dpi * InterfaceSize.scale(size, heightDp)).roundToInt()
            assertEquals(spec, expectedDensity, screen.resources.configuration.densityDpi)
            assertEquals(spec, font, screen.resources.configuration.fontScale, 0.001f)
            assertEquals(spec, locale.substringBefore('-'), screen.resources.configuration.locales[0].language)
            for (page in listOf("overview", "connection", "display", "audio", "navigation", "vehicle", "system", "cluster")) {
                when (page) {
                    "overview" -> ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                        ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.OVERVIEW))
                    "display" -> ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                        ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
                    "system" -> ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                        ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DIAGNOSTICS))
                    "cluster" -> ReflectionHelpers.callInstanceMethod<Unit>(screen, "openClusterSettings")
                    else -> ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                        ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.valueOf(page.uppercase())))
                }
                val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                assertEquals("$spec $page width", 1920, root.width)
                assertEquals("$spec $page height", 1080, root.height)
                if (locale == AppLocale.ARABIC) assertEquals("$spec $page layout direction",
                    View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
                assertCardBounds(root, "$spec page=$page", screen.resources.displayMetrics.density)
                if (dpi == 320 && size == 200 && page == "display") {
                    val choice = descendants(root).filterIsInstance<TextView>().first {
                        it.javaClass.simpleName == "SettingButton" && it.text.toString().contains(" · ") &&
                            ReflectionHelpers.getField<Boolean>(it, "settingsRow")
                    }
                    val layouts = ReflectionHelpers.callInstanceMethod<Any>(choice, "rowLayouts",
                        ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, choice.width))
                    assertTrue("$spec narrow choice must stack its value", ReflectionHelpers.getField<Boolean>(layouts, "stacked"))
                }
                val rail = ReflectionHelpers.getField<ScrollView?>(screen, "settingsRailScroll")
                if (rail != null) {
                    assertTrue("$spec $page rail wider than one third", rail.width <= root.width / 3)
                    for (text in descendants(rail).filterIsInstance<TextView>()) {
                        assertEquals("$spec $page navigation ${text.text}", 1, text.lineCount)
                        if (locale == AppLocale.SIMPLIFIED_CHINESE && text.text.toString() == "导航与仪表") {
                            assertEquals("$spec $page navigation shortened: rail=${rail.width}, textWidth=${text.width}, " +
                                "desired=${text.paint.measureText(text.text.toString())}, textSize=${text.textSize}",
                                0, text.layout.getEllipsisCount(0))
                        }
                    }
                }
                if (page in setOf("overview", "display", "system", "cluster")) {
                    val base = File(requireNotNull(System.getProperty("user.dir")))
                    val project = if (base.name == "common") base.parentFile else base
                    val directory = File(project, "build/seal-settings-card22/matrix").apply { mkdirs() }
                    val file = File(directory, "${locale}-${dpi}dpi-font${font}-size${size}-${appearance.name.lowercase()}-$page.png")
                    val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        file.outputStream().use { assertTrue("$spec $page screenshot", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
                descendants(root).filter { it.isShown && it.javaClass.simpleName == "DescriptionToggle" }
                    .toList().forEach(View::performClick)
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                assertCardBounds(root, "$spec page=$page expanded", screen.resources.displayMetrics.density)
                assertSwitchAlignment(root, "$spec page=$page expanded")
            }
            val line = ReflectionHelpers.getField<String>(screen, "settingsWindowDiagnostic")
            assertTrue(spec, line.startsWith("Settings window: widthPx=1920 heightPx=1080 densityDpi=$dpi "))
            assertEquals(spec, line, DiagnosticRedactor.redact(line))
        } finally {
            controller.pause().stop().destroy()
            InterfaceSize.save(context, previousSize)
            AppLocale.save(context, previousLocale)
            AirPlayPersistence.saveAppAppearance(context, previousAppearance)
            AirPlayPersistence.saveClusterMapEnabled(context, previousCluster)
            AirPlayPersistence.saveClusterSpeedometerEnabled(context, previousSpeedometer)
            AppAppearanceRuntime.resetForTest()
            context.applicationInfo.flags = previousApplicationFlags
            RuntimeEnvironment.setQualifiers("en-w1280dp-h800dp")
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun assertSwitchAlignment(root: View, spec: String) {
        for (switch in descendants(root).filterIsInstance<Switch>().filter { it.isShown }) {
            val row = switch.parent as? ViewGroup ?: continue
            val title = descendants(row).filterIsInstance<TextView>().firstOrNull {
                it !== switch && it.text.toString() == switch.contentDescription?.toString()
            } ?: continue
            val bounds = Rect()
            title.getDrawingRect(bounds)
            row.offsetDescendantRectToMyCoords(title, bounds)
            assertEquals("$spec switch ${title.text}", bounds.exactCenterY(), (switch.top + switch.bottom) / 2f, 2f)
        }
    }

    private fun assertCardBounds(root: View, spec: String, density: Float) {
        val inset = (SettingsPalette.ROW_INSET_DP * density).roundToInt()
        val cards = descendants(root).filterIsInstance<ViewGroup>().filter { it.tag == "settings_card" }.toList()
        assertTrue("$spec no cards", cards.isNotEmpty())
        for (card in cards) for (view in descendants(card).drop(1)) {
            if (!view.isShown) continue
            if (view is SeekBar || (view is TextView && view.text.isNotEmpty())) {
                assertTrue("$spec ${view.javaClass.simpleName} ${if (view is TextView) view.text else view.contentDescription} has no width",
                    view.width > 0)
                assertTrue("$spec ${view.javaClass.simpleName} has no height", view.height > 0)
            }
            if (view.width == 0 || view.height == 0) continue
            val rect = Rect()
            view.getDrawingRect(rect)
            card.offsetDescendantRectToMyCoords(view, rect)
            val description = if (view is TextView) view.text.toString().take(80) else view.javaClass.simpleName
            assertTrue("$spec $description left=${rect.left}, inset=$inset", rect.left >= inset)
            assertTrue("$spec $description right=${rect.right}, boundary=${card.width - inset}", rect.right <= card.width - inset)
            if (view is TextView && view.javaClass.simpleName != "SettingButton") {
                val layout = view.layout ?: continue
                assertTrue("$spec $description lines=${layout.lineCount} max=${view.maxLines}", layout.lineCount <= view.maxLines)
                assertTrue("$spec $description text height=${layout.height} available=${view.height - view.compoundPaddingTop - view.compoundPaddingBottom}",
                    layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                for (line in 0 until layout.lineCount) assertEquals("$spec truncated $description", 0, layout.getEllipsisCount(line))
            } else if (view.javaClass.simpleName == "SettingButton" &&
                ReflectionHelpers.getField<Boolean>(view, "settingsRow")) {
                val layouts = ReflectionHelpers.callInstanceMethod<Any>(view, "rowLayouts",
                    ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, view.width))
                val heading = ReflectionHelpers.getField<Layout>(layouts, "heading")
                val value = ReflectionHelpers.getField<Layout?>(layouts, "value")
                val stacked = ReflectionHelpers.getField<Boolean>(layouts, "stacked")
                val textHeight = if (stacked) heading.height + (value?.height ?: 0) else maxOf(heading.height, value?.height ?: 0)
                assertTrue("$spec $description custom text height=$textHeight view=${view.height}", textHeight <= view.height)
            }
        }
    }

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-sw811dp-w1442dp-h811dp-land-tvdpi")
    fun largeFontHeaderAndExpandedSwitchRemainAligned() {
        val context = RuntimeEnvironment.getApplication()
        val previousSize = InterfaceSize.preference(context)
        val previousFontScale = context.resources.configuration.fontScale
        InterfaceSize.save(context, 100)
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DIAGNOSTICS))
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                shadowOf(Looper.getMainLooper()).idle()
            }
            layout()
            val title = descendants(root).filterIsInstance<TextView>()
                .first { it.text.toString() == screen.getString(R.string.settings) }
            assertTrue("settings header text height ${title.layout.height} exceeds ${title.height}",
                title.layout.height <= title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
            val name = screen.getString(R.string.settings_fps_counter)
            val switch = descendants(root).filterIsInstance<Switch>().single { it.contentDescription == name }
            val row = switch.parent as ViewGroup
            val label = descendants(row).filterIsInstance<TextView>().first { it.text.toString() == name }
            descendants(row).single { it.contentDescription == screen.getString(R.string.settings_description_for, name) }
                .performClick()
            layout()
            val labelRect = Rect()
            label.getDrawingRect(labelRect)
            row.offsetDescendantRectToMyCoords(label, labelRect)
            assertEquals("expanded FPS row label and switch centre", labelRect.exactCenterY(),
                (switch.top + switch.bottom) / 2f, 2f)
        } finally {
            controller.pause().stop().destroy()
            InterfaceSize.save(context, previousSize)
            RuntimeEnvironment.setFontScale(previousFontScale)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-sw811dp-w1442dp-h811dp-land-tvdpi")
    fun realWindowDiagnosticTextStaysInsideCard() {
        val context = RuntimeEnvironment.getApplication()
        val previousSize = InterfaceSize.preference(context)
        val previousFontScale = context.resources.configuration.fontScale
        InterfaceSize.save(context, 100)
        RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        try {
            fun open(category: SettingsCategory): View {
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                    ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
                val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                shadowOf(Looper.getMainLooper()).idle()
                return root
            }
            fun snapshot(root: View, file: String) {
                val base = File(requireNotNull(System.getProperty("user.dir")))
                val project = if (base.name == "common") base.parentFile else base
                val directory = File(project, "build/seal-settings-card22/current").apply { mkdirs() }
                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                try {
                    val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
                    val canvas = Canvas(bitmap)
                    if (root === scroll) {
                        root.background?.setBounds(0, 0, root.width, root.height)
                        root.background?.draw(canvas)
                        val background = root.background
                        root.background = null
                        try {
                            canvas.translate(0f, -scroll.scrollY.toFloat())
                            root.draw(canvas)
                        } finally { root.background = background }
                    } else root.draw(canvas)
                    File(directory, file).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            assertEquals(213, context.resources.configuration.densityDpi)
            assertEquals(1.3f, screen.resources.configuration.fontScale, 0.001f)
            val display = open(SettingsCategory.DISPLAY)
            snapshot(display, "display-213dpi-1.3.png")
            val windowLine = ReflectionHelpers.getField<String>(screen, "settingsWindowDiagnostic")
            val report = ReflectionHelpers.callInstanceMethod<String>(screen, "diagnosticReportText",
                ReflectionHelpers.ClassParameter(Context::class.java, context))
            assertEquals(1, report.lineSequence().count { it == windowLine })
            assertEquals(windowLine, DiagnosticRedactor.redact(windowLine))
            val system = open(SettingsCategory.DIAGNOSTICS)
            val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
            val description = descendants(scroll).filterIsInstance<TextView>().single {
                it.text.toString().startsWith(screen.getString(R.string.reports_save_to_downloads_diplay))
            }
            val scrollRect = Rect()
            description.getDrawingRect(scrollRect)
            scroll.offsetDescendantRectToMyCoords(description, scrollRect)
            scroll.scrollTo(0, (scrollRect.top - screen.resources.displayMetrics.density.toInt() * 80).coerceAtLeast(0))
            snapshot(system, "system-213dpi-1.3.png")
            val card = generateSequence(description.parent as? View) { it.parent as? View }
                .filterIsInstance<ViewGroup>().first { it.background is GradientDrawable }
            val rect = Rect()
            description.getDrawingRect(rect)
            card.offsetDescendantRectToMyCoords(description, rect)
            val inset = (SettingsPalette.ROW_INSET_DP * screen.resources.displayMetrics.density).roundToInt()
            assertTrue("system diagnostic description starts at ${rect.left + description.paddingLeft}, required $inset",
                rect.left + description.paddingLeft >= inset)
            assertTrue("system diagnostic description ends beyond card inset",
                rect.right - description.paddingRight <= card.width - inset)
        } finally {
            controller.pause().stop().destroy()
            InterfaceSize.save(context, previousSize)
            RuntimeEnvironment.setFontScale(previousFontScale)
        }
    }

    @Test fun primaryAndSecondaryTextRemainLegibleOnBothGradients() {
        for (night in listOf(true, false)) {
            val palette = SettingsPalette.of(night)
            for (background in palette.gradient) {
                val card = ColorUtils.compositeColors(palette.card, background)
                val secondary = ColorUtils.compositeColors(palette.secondary, card)
                assertTrue("primary contrast on $background", ColorUtils.calculateContrast(palette.text, card) >= 4.5)
                assertTrue("secondary contrast on $background", ColorUtils.calculateContrast(secondary, card) >= 4.5)
            }
            assertTrue(ColorUtils.calculateContrast(palette.onAccent, palette.accent) >= 4.5)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun modernGradientUsesBottomLeftToTopRightAndSpecifiedStops() {
        for (night in listOf(true, false)) {
            val palette = SettingsPalette.of(night)
            val drawable = palette.background()
            assertEquals(GradientDrawable.Orientation.BL_TR, drawable.orientation)
            val bitmap = Bitmap.createBitmap(1001, 1, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                assertColorNear(palette.gradient[0], bitmap.getPixel(0, 0))
                assertColorNear(palette.gradient[1], bitmap.getPixel((1000 * palette.middleStop).toInt(), 0))
                assertColorNear(palette.gradient[2], bitmap.getPixel(1000, 0))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test @Config(sdk = [28])
    fun olderGradientPreservesTheSameStops() {
        for (night in listOf(true, false)) {
            val palette = SettingsPalette.of(night)
            val drawable = palette.background()
            assertEquals(GradientDrawable.Orientation.BL_TR, drawable.orientation)
            val colors = drawable.colors!!
            assertEquals(101, colors.size)
            assertEquals(palette.gradient[0], colors.first())
            assertEquals(palette.gradient[1], colors[(100 * palette.middleStop).toInt()])
            assertEquals(palette.gradient[2], colors.last())
        }
    }

    @Test fun leavingOrDestroyingSettingsReleasesItsBackground() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        val background = ReflectionHelpers.getField<GradientDrawable>(screen, "settingsBackground")
        val owner = ReflectionHelpers.getField<View>(screen, "settingsBackgroundOwner")
        assertSame(background, owner.background)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        assertSame(background, ReflectionHelpers.getField<GradientDrawable>(screen, "settingsBackground"))
        assertNull(owner.background)
        ReflectionHelpers.setField(screen, "page", "home")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertNull(ReflectionHelpers.getField<Any?>(screen, "settingsBackground"))
        assertNull(ReflectionHelpers.getField<Any?>(screen, "settingsBackgroundOwner"))
        ReflectionHelpers.setField(screen, "page", "settings")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val lastOwner = ReflectionHelpers.getField<View>(screen, "settingsBackgroundOwner")
        controller.pause().stop().destroy()
        assertNull(lastOwner.background)
        assertNull(ReflectionHelpers.getField<Any?>(screen, "settingsBackground"))
    }

    @Test fun rowHeightAndCardRadiusUseTheSharedDimensions() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        val root = screen.window.decorView
        val title = screen.getString(R.string.frame_rate)
        val button = descendants(root).filterIsInstance<TextView>()
            .first { it.text.toString().startsWith("$title · ") }
        val density = screen.resources.displayMetrics.density
        assertEquals((SettingsPalette.ROW_HEIGHT_DP * density).roundToInt(), button.minimumHeight)
        val card = generateSequence(button.parent as? View) { it.parent as? View }
            .first { it.background is GradientDrawable }
        assertEquals((SettingsPalette.CARD_CORNER_DP * density).roundToInt().toFloat(),
            (card.background as GradientDrawable).cornerRadius, 0.01f)
        assertEquals(SettingsPalette.ROW_HEIGHT_DP / 4, SettingsPalette.CARD_CORNER_DP)
        controller.pause().stop().destroy()
    }

    @Test fun settingsSlidersUseThePaletteAccent() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.AUDIO))
        val slider = descendants(screen.window.decorView).filterIsInstance<SeekBar>().first()
        val palette = SettingsPalette.of(screen.currentAppNight)
        assertEquals(palette.accent, slider.progressTintList!!.defaultColor)
        assertEquals(palette.accent, slider.thumbTintList!!.defaultColor)
        assertEquals(palette.switchOff, slider.progressBackgroundTintList!!.defaultColor)
        controller.pause().stop().destroy()
    }

    @Test fun deferredControlsKeepAVisibleConnectionMarker() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        val screen = controller.get().apply { sealEditionCheck = { true } }
        controller.setup()
        for (id in listOf(R.string.settings_smooth_video_latency, R.string.settings_mtk_decoder_tuning,
            R.string.settings_low_latency_decoder, R.string.call_echo_cancellation, R.string.call_voice_filter,
            R.string.settings_disable_media_key_audio_focus, R.string.settings_navigation_prompt_audio_focus,
            R.string.split_screen_areas, R.string.carplay_rotation, R.string.side_panel,
            R.string.settings_usb_charging, R.string.settings_call_at_call_rate, R.string.bt_suspend_during_carplay,
            R.string.settings_wifi_direct_scan_pause, R.string.mfi_certificate_signing_target,
            R.string.cluster_small_window_marker, R.string.cluster_safe_area_edit, R.string.cluster_safe_area_reset,
            R.string.choose_image, R.string.default_icon, R.string.car_button_name, R.string.carplay_dock)) {
            assertTrue(screen.getString(id), ReflectionHelpers.callInstanceMethod(screen, "showsNextConnectionMarker",
                ReflectionHelpers.ClassParameter(String::class.java, screen.getString(id))))
        }
        assertFalse(ReflectionHelpers.callInstanceMethod(screen, "showsNextConnectionMarker",
            ReflectionHelpers.ClassParameter(String::class.java, screen.getString(R.string.smooth_video))))
        controller.pause().stop().destroy()
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for ((a, b) in listOf(Color.red(expected) to Color.red(actual), Color.green(expected) to Color.green(actual),
            Color.blue(expected) to Color.blue(actual))) assertTrue("$expected != $actual", kotlin.math.abs(a - b) <= 2)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
