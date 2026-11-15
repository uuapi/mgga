package com.mgga.gboard.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mgga.gboard.BuildConfig
import com.mgga.gboard.Config
import com.mgga.gboard.R
import com.mgga.gboard.hook.ThemeInstaller
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * MGGA 设置页。
 * 通过 libxposed service 的 remote preferences（group "mgga"）读写配置，
 * LSPosed 负责推送到 Gboard 进程，Hook 侧经 Config 读取。
 */
class MainActivity : AppCompatActivity() {

    private var service: XposedService? = null
    private val rows = mutableListOf<Row>()

    private data class Row(
        val key: String,
        val kind: Kind,
        val boolDefault: Boolean = false,
        val intDefault: Int = 0,
        val strDefault: String = "",
        val load: (String) -> Unit,
    )

    private enum class Kind { BOOL, INT, TEXT }

    private lateinit var statusText: TextView
    private lateinit var rootColumn: LinearLayout

    /** 搜索过滤：每个可配置行的视图 + 检索文本（标题/描述/键名） */
    private data class FilterEntry(val view: View, val hay: String)
    private val filterable = mutableListOf<FilterEntry>()

    private val glossaryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { readGlossary(it) } }

    private fun importGlossary() {
        runCatching {
            glossaryPicker.launch(arrayOf("text/*", "application/octet-stream"))
        }.onFailure { toast("无法打开文件选择器: ${it.message}") }
    }

    private fun readGlossary(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { text ->
            writeText("user_glossary", text)
            toast("词表已导入（${text.lines().size} 行），强停 Gboard 后生效")
        } ?: toast("读取失败")
    }

    private fun restoreTheme() {
        Thread {
            val err = ThemeInstaller.restoreDefaultTheme()
            runOnUiThread { toast(err ?: "已恢复原厂主题并强停 Gboard") }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        bindService()
    }

    // ---------------- 服务绑定 ----------------

    private fun bindService() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    runOnUiThread { refreshStatus(); loadAll() }
                }

                override fun onServiceDied(s: XposedService) {
                    service = null
                    runOnUiThread { refreshStatus() }
                }
            })
        } catch (t: Throwable) {
            statusText.post { statusText.text = getString(R.string.service_bound_fail) }
        }
        statusText.postDelayed({ if (service == null) refreshStatus() }, 3000)
    }

    private fun refreshStatus() {
        val s = service
        statusText.text = if (s == null) {
            getString(R.string.service_bound_fail)
        } else {
            val inScope = runCatching { s.scope.contains(Config.GBOARD_PACKAGE) }.getOrDefault(false)
            getString(R.string.service_bound_ok) + if (inScope) "，作用域已包含 Gboard ✓" else "，⚠ 尚未勾选 Gboard 作用域"
        }
    }

    private fun loadAll() {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: return
        for (row in rows) {
            when (row.kind) {
                Kind.BOOL -> row.load(prefs.getBoolean(row.key, row.boolDefault).toString())
                Kind.INT -> row.load(prefs.getInt(row.key, row.intDefault).toString())
                Kind.TEXT -> row.load(prefs.getString(row.key, row.strDefault) ?: row.strDefault)
            }
        }
    }

    private fun writeBool(key: String, value: Boolean) {
        service?.getRemotePreferences(Config.PREF_GROUP)?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun writeInt(key: String, value: Int) {
        service?.getRemotePreferences(Config.PREF_GROUP)?.edit()?.putInt(key, value)?.apply()
    }

    private fun writeText(key: String, value: String) {
        service?.getRemotePreferences(Config.PREF_GROUP)?.edit()?.putString(key, value)?.apply()
    }

    // ---------------- UI 构建 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(rootColumn)

        title(rootColumn, "MGGA — Make Gboard Great Again")
        subtitle(rootColumn, "v${BuildConfig.VERSION_NAME} · libxposed API 102 · 改动后需强停 Gboard 生效")

        // 功能搜索
        val search = EditText(this).apply {
            hint = "🔍 搜索功能 / 开关名"
            isSingleLine = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                for (e in filterable) {
                    e.view.visibility =
                        if (q.isEmpty() || e.hay.contains(q, ignoreCase = true)) View.VISIBLE
                        else View.GONE
                }
            }
        })
        rootColumn.addView(search)

        // 状态卡
        card(rootColumn).apply {
            statusText = TextView(this@MainActivity).apply {
                text = getString(R.string.service_bound_fail)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            addView(statusText)
            addView(
                button(this@MainActivity, getString(R.string.scope_request)) { requestScope() },
                linearParams(dp(0), ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(button(this@MainActivity, getString(R.string.force_stop_gboard)) { forceStop() })
            addView(button(this@MainActivity, "恢复 Gboard 原厂主题 (root)") { restoreTheme() })
        }

        section(R.string.sec_general)
        switchRow("详细日志", "输出调试日志到 logcat -s MGGA", "verbose_log", false)
        switchRow("注入提示", "Gboard 启动时短暂提示 MGGA 已注入", "startup_toast", false)
        button(this@MainActivity, "导出配置（复制到剪贴板）") { exportConfig() }.apply {
            rootColumn.addView(
                this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            )
        }
        button(this@MainActivity, "导入配置（从剪贴板粘贴）") { importConfig() }.apply {
            rootColumn.addView(
                this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            )
        }

        section(R.string.sec_clipboard)
        switchRow("启用剪贴板增强", "解除历史条数与过期限制", "clipboard_enabled", true)
        intRow("历史条数上限", "0 = 不限制", "clipboard_limit", 0)
        intRow("保留天数", "0 = 永久", "clipboard_days", 0)
        switchRow("敏感信息保护", "验证码/卡号/密码框内容不进入剪贴板历史", "secure_clipboard_enabled", true)

        section(R.string.sec_flags)
        switchRow("启用 Flag 覆写引擎", "DexKit 动态定位 Gboard 服务端灰度开关", "flag_engine_enabled", true)
        switchRow("AI 智能功能", "Smart Reply/Compose、Emojify、贴纸等", "flag_ai", false)
        switchRow("语法检查与校对", "grammar checker / on-device proofread", "flag_grammar", false)
        switchRow("写作工具 (Writing Tools)", "Pixel 专属 AI 写作", "flag_writing_tools", false)
        switchRow("写作工具 V2", "预置 prompt / My Style（需上一项开启）", "flag_writing_tools_v2", false)
        switchRow("写作后端全语言", "language tags 置 * 支持全部语言", "flag_all_keyboards", true)
        choiceRow("AI 推理后端", "writing_backend", listOf("cloud" to "云端 (Gboard Server)", "aicore" to "设备端 Gemini Nano", "astrea" to "Astrea 私有推理（推荐）"), "astrea")
        switchRow("多语言同时输入", "", "flag_multilingual", false)
        switchRow("浮动键盘", "横屏/分屏/自由窗口自动悬浮", "flag_floating", false)
        switchRow("Emoji Kitchen", "emoji 融合与表情搜索 v2", "flag_emoji_kitchen", false)
        switchRow("功能菜单重设计", "Silk 主题 / 系统字体 / 自定义主题", "flag_access_point", false)
        switchRow("流量下载语言包", "允许计费网络下载", "flag_metered_downloads", false)
        switchRow("禁用遥测", "关闭训练缓存/统计/日志上报", "flag_privacy", false)
        switchRow("Gemini Rambler 语音", "Pixel 语音听写（需配合设备伪装场景自行验证）", "flag_rambler", false)
        switchRow("圆角键帽 (M3E)", "Material 3 Expressive 键帽样式", "flag_rounded_keys", false)
        switchRow("增强词学习", "新词秒学/动态 LM/概念预测", "flag_word_learning", false)
        switchRow("行内联想 (实验)", "decoder 侧 inline suggestions", "flag_inline_suggestions", false)
        switchRow("TFLite 引擎 (实验)", "", "flag_tflite", false)
        switchRow("剪贴板操作 Chips", "与上方限制解除互斥", "flag_clipboard_chips", false)
        textRow(
            "自定义 Flag (JSON)",
            "示例: [{\"name\":\"enable_x\",\"type\":\"bool\",\"value\":\"true\"}]",
            "custom_flags_json", ""
        )

        section(R.string.sec_appearance)
        switchRow("AMOLED 纯黑背景", "深色模式下 surface 容器改纯黑", "amoled_enabled", false)
        switchRow("候选 emoji 提亮", "候选栏 emoji 强制 100% 不透明", "emoji_alpha_enabled", true)

        section(R.string.sec_input)
        switchRow("密码框完整键盘", "⚠ 密码框也显示全键盘，存在被记录的安全权衡", "password_keyboard_enabled", false)
        switchRow("九键半角标点", "中文九键数字页逗号/句号输出半角并显示 +−×÷", "punctuation_enabled", true)

        section(R.string.sec_toolbar)
        switchRow("键盘工具栏", "剪贴板保险库/粘贴/全选/光标导航/撤销", "toolbar_enabled", false)
        textRow("文本快捷 1", "⭐ 按钮插入的文本", "toolbar_text_1", "")
        textRow("文本快捷 2", "⭐⭐ 按钮插入的文本", "toolbar_text_2", "")
        switchRow("截图自动入库", "监视 Screenshots 目录新截图进保险库", "shot_capture_enabled", false)
        switchRow("截图推入剪贴板", "入库同时复制到系统剪贴板", "shot_to_clipboard", false)

        section(R.string.sec_gloss)
        switchRow("候选词译词", "候选词下方叠加目标语言释义（纯离线词典）", "gloss_enabled", false)
        textRow("目标语言", "zh / en / ja / de / fr / es / ru", "gloss_target_lang", "zh")
        intRow("译词字号 (sp)", "8-14", "gloss_size", 11)
        switchRow("注入横幅", "首次注入时在键盘上显示胶囊提示", "gloss_banner", false)
        button(this@MainActivity, "导入用户词表 (TSV 文件)") { importGlossary() }.apply {
            rootColumn.addView(
                this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            )
        }

        section(R.string.sec_shuangpin)
        switchRow("双拼键帽提示", "字母键叠加声母/韵母（跟随 Gboard 双拼方案）", "shuangpin_enabled", false)
        intRow("提示字号 (sp)", "7-17", "shuangpin_size", 10)
        switchRow("动态提示", "等待韵母时只显示可配韵母", "shuangpin_dynamic", true)
        switchRow("隐藏主字母", "开启双拼时隐藏键帽上的全拼字母", "shuangpin_blank_letters", false)
        intRow("主字母字号 (sp)", "0 = 不改", "shuangpin_letter_size", 0)
        switchRow("分号键", "浮动 ; 按钮（MS 等方案输入 ing 用）", "shuangpin_semicolon", false)
        switchRow("灵活 emoji 联想", "拼音输入时按内置词库显示 emoji 候选", "emoji_suggest_enabled", false)

        section(R.string.sec_misc)
        switchRow("Termux 中文兼容", "Termux 内拼音正常输入 + 进出自动切语言", "termux_enabled", true)
        switchRow("RGB 灯效 (实验)", "性能开销较大", "rgb_enabled", false)
        choiceRow(
            "RGB 特效", "rgb_effect",
            listOf(
                "ripple" to "波纹",
                "water" to "流体水滴",
                "neon" to "霓虹闪电",
                "supernova" to "超新星",
                "magma" to "熔岩余烬",
                "sonic" to "声波",
                "blackhole" to "黑洞",
                "rain" to "环境雨滴",
                "chroma" to "雷蛇彩虹",
            ),
            "ripple",
        )
        intRow("特效速度 %", "30-200", "rgb_wave_speed", 100)
        switchRow("滑行轨迹", "滑动打字霓虹尾迹", "rgb_trail_enabled", false)
        switchRow("WPM 过热", "打字加速时特效增强", "rgb_turbo_enabled", false)
        switchRow("键帽流光", "光带扫过键帽", "rgb_key_flow_enabled", false)
        switchRow("仅键帽描边", "流光只亮键帽边框", "rgb_key_border_only", false)
        intRow("流光速度 %", "20-300", "rgb_key_flow_speed", 100)
        switchRow("RGB 底光", "键盘底部呼吸光带", "rgb_glow_enabled", true)
        textRow("RGB 主色", "#00FFF5", "rgb_color_primary", "#00FFF5")
        textRow("RGB 副色", "#FF00AA", "rgb_color_secondary", "#FF00AA")
        textRow("用户词表 (TSV)", "每行: 词条<TAB>译文，优先于内置词典", "user_glossary", "")

        subtitle(rootColumn, "MGGA 整合自 GboardHook / KeyFlux / FuckGboard / gboard-material-expressive-black / ForceGboardInputType / gboardtweak / gboard-gloss / Gboard+ / gboard-shuangpin-hints / gboard-termux-ime / gboard-RGB-studio · 纯本地 hook，无外部服务依赖")
        return scroll
    }

    // ---------------- UI 辅助 ----------------

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }

    private fun subtitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.6f
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
    }

    private fun section(titleRes: Int) {
        val tv = TextView(this).apply {
            text = getString(titleRes)
            textSize = 16f
            setPadding(dp(4), dp(20), dp(4), dp(4))
        }
        rootColumn.addView(tv)
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        parent.addView(l)
        return l
    }

    private fun linearParams(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(4) }

    private fun button(ctx: Context, text: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun switchRow(title: String, desc: String, key: String, def: Boolean) {
        val sw = SwitchMaterial(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply { text = title; textSize = 15f })
                    if (desc.isNotBlank()) {
                        addView(TextView(this@MainActivity).apply {
                            this.text = desc; textSize = 12f; alpha = 0.6f
                        })
                    }
                },
                linearParams(dp(0), ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(sw)
        }
        rootColumn.addView(box)
        filterable.add(FilterEntry(box, "$title $desc $key"))
        sw.setOnCheckedChangeListener { _, checked -> writeBool(key, checked) }
        rows.add(Row(key, Kind.BOOL, boolDefault = def) { v ->
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = v.toBoolean()
            sw.setOnCheckedChangeListener { _, checked -> writeBool(key, checked) }
        })
    }

    private fun intRow(title: String, desc: String, key: String, def: Int) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = linearParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply { text = title; textSize = 15f })
                    if (desc.isNotBlank()) addView(TextView(this@MainActivity).apply {
                        text = desc; textSize = 12f; alpha = 0.6f
                    })
                },
                linearParams(dp(0), ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(et)
        }
        rootColumn.addView(box)
        filterable.add(FilterEntry(box, "$title $desc $key"))
        fun commit() {
            val v = et.text.toString().trim().toIntOrNull() ?: def
            writeInt(key, v)
        }
        et.setOnFocusChangeListener { _, has -> if (!has) commit() }
        et.setOnEditorActionListener { _, _, _ -> commit(); false }
        rows.add(Row(key, Kind.INT, intDefault = def) { v ->
            et.setText(v)
        })
    }

    private fun textRow(title: String, hint: String, key: String, def: String) {
        val et = EditText(this).apply {
            this.hint = hint
            setTextIsSelectable(false)
            layoutParams = linearParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(TextView(this@MainActivity).apply { text = title; textSize = 15f })
            addView(et)
        }
        rootColumn.addView(box)
        filterable.add(FilterEntry(box, "$title $hint $key"))
        fun commit() {
            writeText(key, et.text.toString().trim())
        }
        et.setOnFocusChangeListener { _, has -> if (!has) commit() }
        rows.add(Row(key, Kind.TEXT, strDefault = def) { v ->
            et.setText(v)
        })
    }

    private fun choiceRow(title: String, key: String, options: List<Pair<String, String>>, def: String) {
        val labels = options.map { it.second }
        val values = options.map { it.first }
        val tv = TextView(this).apply { textSize = 15f; text = title }
        val current = TextView(this).apply { textSize = 12f; alpha = 0.6f }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(tv)
            addView(current)
        }
        filterable.add(FilterEntry(box, "$title $key"))
        box.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current.tag as? String ?: def)) { d, which ->
                    writeText(key, values[which])
                    current.text = labels[which]
                    current.tag = values[which]
                    d.dismiss()
                }
                .show()
        }
        rootColumn.addView(box)
        rows.add(Row(key, Kind.TEXT, strDefault = def) { v ->
            val idx = values.indexOf(v).let { if (it < 0) values.indexOf(def) else it }
            current.text = labels.getOrElse(idx) { v }
            current.tag = v
        })
    }

    // ---------------- 动作 ----------------

    private fun exportConfig() {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: run {
            toast(getString(R.string.service_bound_fail)); return
        }
        val obj = org.json.JSONObject()
        for (row in rows) {
            when (row.kind) {
                Kind.BOOL -> obj.put(row.key, prefs.getBoolean(row.key, row.boolDefault))
                Kind.INT -> obj.put(row.key, prefs.getInt(row.key, row.intDefault))
                Kind.TEXT -> obj.put(row.key, prefs.getString(row.key, row.strDefault) ?: row.strDefault)
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("mgga_config", obj.toString()))
        toast("配置已复制到剪贴板（${rows.size} 项）")
    }

    private fun importConfig() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            ?: run { toast("剪贴板为空"); return }
        runCatching {
            val obj = org.json.JSONObject(text)
            var n = 0
            for (row in rows) {
                if (!obj.has(row.key)) continue
                when (row.kind) {
                    Kind.BOOL -> writeBool(row.key, obj.getBoolean(row.key))
                    Kind.INT -> writeInt(row.key, obj.getInt(row.key))
                    Kind.TEXT -> writeText(row.key, obj.getString(row.key))
                }
                n++
            }
            loadAll()
            toast("已导入 $n 项，强停 Gboard 生效")
        }.onFailure { toast("导入失败: ${it.message}") }
    }

    private fun requestScope() {
        val s = service ?: run {
            toast(getString(R.string.service_bound_fail)); return
        }
        runCatching {
            s.requestScope(listOf(Config.GBOARD_PACKAGE), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: MutableList<String>) {
                    runOnUiThread {
                        toast(getString(R.string.scope_request_ok))
                        refreshStatus()
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    runOnUiThread { toast(getString(R.string.scope_request_fail, message)) }
                }
            })
            toast(getString(R.string.scope_request_ok))
        }.onFailure { toast(getString(R.string.scope_request_fail, it.message ?: "error")) }
    }

    private fun forceStop() {
        Thread {
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "am force-stop ${Config.GBOARD_PACKAGE}")
                )
                p.waitFor() == 0
            }.getOrDefault(false)
            runOnUiThread { toast(getString(if (ok) R.string.force_stop_ok else R.string.force_stop_fail)) }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
