package io.catpaw.kittytalk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import io.catpaw.kittytalk.databinding.ActivityThemeBinding

class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val cfg = PaletteStore.load(this)
        setTheme(PaletteStore.themeStyleRes(cfg.palette))
        super.onCreate(savedInstanceState)
        if (cfg.palette == Palette.SYSTEM) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backButton.setOnClickListener { finish() }

        setupModeGroup(cfg.themeMode)
        setupPaletteGrid(cfg)

        // 任何切换都会保存并重建本页，即时生效
        binding.modeGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (id) {
                binding.modeLight.id -> ThemeMode.LIGHT
                binding.modeDark.id -> ThemeMode.DARK
                else -> ThemeMode.FOLLOW
            }
            if (PaletteStore.load(this).themeMode == mode) return@addOnButtonCheckedListener
            apply(mode, PaletteStore.load(this).palette)
        }
    }

    private fun setupModeGroup(current: ThemeMode) {
        binding.modeGroup.check(
            when (current) {
                ThemeMode.FOLLOW -> binding.modeFollow.id
                ThemeMode.LIGHT -> binding.modeLight.id
                ThemeMode.DARK -> binding.modeDark.id
            }
        )
    }

    private fun setupPaletteGrid(cfg: ThemeConfig) {
        binding.paletteGrid.removeAllViews()
        for (pal in Palette.entries) {
            val cell = paletteCell(pal, pal == cfg.palette)
            val gp = GridLayout.LayoutParams()
            gp.width = 0
            gp.height = GridLayout.LayoutParams.WRAP_CONTENT
            gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            gp.setMargins(dp(4), dp(6), dp(4), dp(6))
            binding.paletteGrid.addView(cell, gp)
        }
    }

    /** FlClash 风格圆形色块：圆形纯色 + 选中描边 + 下方标签 */
    private fun paletteCell(pal: Palette, selected: Boolean): View {
        val frame = FrameLayout(this)
        val circle = CircleView(this)
        circle.color = if (pal == Palette.SYSTEM) {
            themeColor(android.R.attr.colorPrimary)
        } else {
            colorOf(palColorRes(pal, 0))
        }
        circle.checked = selected
        val size = dp(56)
        circle.layoutParams = FrameLayout.LayoutParams(
            size, size, Gravity.CENTER_HORIZONTAL or Gravity.TOP
        )

        // 跟随系统：左下角放取色器小图标
        if (pal == Palette.SYSTEM) {
            val picker = ImageView(this)
            picker.setImageResource(R.drawable.ic_color_picker)
            picker.setColorFilter(themeColor(android.R.attr.colorControlNormal))
            picker.layoutParams = FrameLayout.LayoutParams(
                dp(18), dp(18),
                Gravity.BOTTOM or Gravity.START
            ).apply { bottomMargin = dp(8); leftMargin = dp(4) }
            frame.addView(picker)
        }

        val label = TextView(this)
        label.text = paletteLabel(pal)
        label.textSize = 12f
        label.setGravity(Gravity.CENTER_HORIZONTAL)
        label.setTextColor(themeColor(android.R.attr.textColorSecondary))
        label.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = size + dp(4)
        }

        frame.addView(circle)
        frame.addView(label)
        frame.setOnClickListener {
            val cfg = PaletteStore.load(this)
            // 预先把圆钮设成选中态，反馈即时
            circle.checked = true
            circle.invalidate()
            if (cfg.palette != pal) {
                apply(cfg.themeMode, pal)
            }
        }
        return frame
    }

    private fun palColorRes(pal: Palette, type: Int): Int = when (pal) {
        Palette.PURPLE -> arrayOf(R.color.pal_purple_primary, R.color.pal_purple_primaryContainer)[type]
        Palette.BLUE -> arrayOf(R.color.pal_blue_primary, R.color.pal_blue_primaryContainer)[type]
        Palette.GREEN -> arrayOf(R.color.pal_green_primary, R.color.pal_green_primaryContainer)[type]
        Palette.PINK -> arrayOf(R.color.pal_pink_primary, R.color.pal_pink_primaryContainer)[type]
        Palette.ORANGE -> arrayOf(R.color.pal_orange_primary, R.color.pal_orange_primaryContainer)[type]
        Palette.SYSTEM -> arrayOf(android.R.color.transparent, android.R.color.transparent)[type]
    }

    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            binding.topBar.setPadding(0, top, 0, 0)
            insets
        }
        // 状态栏/导航栏透明沉浸
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !dark
    }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0xFF6750A4.toInt())
        a.recycle()
        return c
    }

    private fun paletteLabel(pal: Palette): String = when (pal) {
        Palette.SYSTEM -> "跟随系统"
        Palette.PURPLE -> "紫色"
        Palette.BLUE -> "蓝色"
        Palette.GREEN -> "绿色"
        Palette.PINK -> "粉色"
        Palette.ORANGE -> "橙色"
    }

    private fun colorOf(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun apply(mode: ThemeMode, palette: Palette) {
        PaletteStore.save(this, mode, palette)
        AppCompatDelegate.setDefaultNightMode(PaletteStore.appcompatNightMode(mode))
        recreate()
    }

    /** 圆形色块：纯色圆 + 选中白色描边 */
    private class CircleView(ctx: Context) : View(ctx) {
        var color: Int = Color.TRANSPARENT
        var checked: Boolean = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * ctx.resources.displayMetrics.density
            color = Color.WHITE
        }
        private val selectRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * ctx.resources.displayMetrics.density
            color = 0xFF6750A4.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val r = ((w - 8f) / 2f).coerceAtMost((h - 8f) / 2f)
            val cx = w / 2f
            val cy = h / 2f
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            fill.color = color
            canvas.drawOval(rect, fill)
            if (checked) {
                canvas.drawOval(rect, selectRing)
            } else {
                canvas.drawOval(rect, stroke)
            }
        }
    }
}