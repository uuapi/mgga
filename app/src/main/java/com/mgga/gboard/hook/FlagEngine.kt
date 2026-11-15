package com.mgga.gboard.hook

import android.content.Context
import com.mgga.gboard.Config
import com.mgga.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Flag 覆写引擎。
 *
 * Gboard 的 Phenotype flag 经由一个混淆的泛型读取器（返回 Object，内部字符串 "Invalid flag: "）。
 * DexKit 定位该读取器后，按 flag 名改写返回值。
 * 预设组整合自 KeyFlux 与 FuckGboard 的全量 flag 表。
 */
object FlagEngine {
    const val GROUP = "Flag"

    // FuckGboard 已知混淆 flag 类（多版本候选，动态兜底前的最后防线）
    private val KNOWN_FLAG_CLASSES = listOf("adbx", "odx", "obw", "acps", "nxw", "ajoh")

    // ---------- KeyFlux 预设表 ----------
    private val CLIPBOARD_FORCED_FALSE = setOf(
        "enable_clipboard_entity_extraction", "enable_clipboard_query_refactoring"
    )
    private val CLIPBOARD_CHIPS = setOf(
        "enable_clipboard_action_chips", "enable_clipboard_entity_extraction", "enable_copy_to_reply"
    )
    private val AI_TRUE = setOf(
        "enable_ai_core_llm", "enable_ai_core_smart_reply", "enable_emojify",
        "enable_emojify_settings_option", "enable_smart_reply", "enable_smart_compose",
        "enable_smart_compose_inline_suggestions", "enable_inline_suggestions",
        "enable_inline_suggestions_on_all_apps", "enable_custom_sticker_tab",
        "enable_custom_sticker_lol_fix", "enable_custom_sticker_naive_prompt_expander",
        "enable_sticker_predictions_while_typing", "enable_animated_emoji_content_suggestions",
        "show_animated_emoji_in_expression_moment", "enable_proactive_emoji_kitchen",
        "enable_expression_moment",
    )
    private val GRAMMAR_TRUE = setOf(
        "enable_grammar_checker", "enable_on_device_proofread", "enable_llm_based_grammar_checker",
        "enable_writing_tools_cooperative_mode", "enable_text_conversion",
        "enable_highlight_voice_reconversion_composing_text", "nga_enable_undo_delete",
        "enable_proofread", "enable_pk_auto_correction_locales",
    )
    private val MULTILINGUAL_TRUE = setOf(
        "enable_multilingual_typing", "enable_crank_for_first_supported_locale_in_multilingual",
        "enable_crank_for_primary_locale_in_multilingual", "enable_more_candidates_view_for_multilingual",
        "enable_auto_multi_lang_on_all_pixel_devices", "enable_speech_enhancement_for_multilang_users",
    )
    private val FLOATING_TRUE = setOf(
        "enable_auto_float_keyboard_in_landscape", "enable_auto_float_keyboard_in_multi_window",
        "enable_auto_float_keyboard_in_freeform", "enable_split_keyboard_on_tablet_large",
        "enable_dynamic_font_size_slider", "enable_split_keyboard",
        "enable_tablet_split_keyboard", "enable_enter_exit_animation",
    )
    private val EMOJI_KITCHEN_TRUE = setOf(
        "enable_emoji_kitchen_browse", "enable_emoji_kitchen_browse_entry_point_v2",
        "enable_emoji_kitchen_for_zero_state_emojis", "enable_embedded_photo_picker",
        "enable_emoji_search_v2", "enable_emoji_recommendations",
        "enable_play_emoji_kitchen_mix_animation",
    )
    private val ACCESS_POINT_TRUE = setOf(
        "enable_access_points_menu_redesign", "enable_access_point_keyboard",
        "use_silk_theme_by_default", "use_system_font", "enable_custom_themes",
        "enable_silk_theme", "enable_candidates_access_points_switching_animation",
        "keyboard_redesign_google_sans", "keyboard_redesign_forbid_key_shadows",
        "enable_fast_access_bar", "enable_spellchecker_chips_ui",
        "enable_spellchecker_chips_ui_web_view", "emoji_scale_supported",
    )
    private val METERED_TRUE = setOf(
        "allow_language_pack_downloads_on_metered_connections",
        "allow_metered_network_to_download_langid_model",
        "allow_metered_small_speech_pack_downloads", "force_speech_language_pack_updates",
    )
    private val INLINE_TRUE = setOf(
        "enable_inline_suggestions_on_decoder_side",
        "enable_multiword_predictions_as_inline_from_crank_cifg",
    )
    private val TFLITE_TRUE = setOf(
        "enable_nwp_tflite_engine", "enable_emoji_predictor_tflite_engine",
    )
    private val PRIVACY_TRUE = setOf(
        "disable_correction_storage", "disable_content_capture_for_input_view",
        "deprecate_native_log_event",
    )
    private val PRIVACY_FALSE = setOf(
        "always_log_speed_stats", "enable_logging_for_emoji_search_query",
        "enable_internal_speech_enhancement_pii_logging", "enable_report_from_training_cache",
        "enable_chinese_training_cache", "enable_spell_checker_training_cache",
        "enable_training_cache_metrics_processors", "enable_conversation_id_in_training_cache",
        "enable_auto_correction_stats", "enable_metric_counts_stats", "enable_spatial_stats",
        "enable_spell_checker_stats", "enable_typo_stats", "voice_donation_promo_banner",
        "voice_donation_confirm_banner",
    )

    // ---------- FuckGboard 预设表 ----------
    private val RAMBLER_TRUE = setOf(
        "enable_nga", "enable_advanced_features_in_consolidated_sd_stack",
        "enable_dictation_feature_split_install", "handle_fallback_inside_sd_stack",
        "immediately_end_dictation_on_keyboard_hidden", "enable_soda_longform_experiment",
        "show_rambler_dict_settings", "enable_agentic_dictation", "config_agentic_dictation",
        "enable_jetson_in_toolbar", "filter_rambler_contributed_input_view_session",
        "enable_rambler_toolbar_at_cursor_position", "enable_rambler_al_toolbar",
        "enable_rambler_al_toolbar_overflow_menu", "enable_hide_rambler_al_idling_state",
        "enable_rambler_al_show_hide_new_animation", "enable_rambler_al_teleporting_animation",
        "rambler_motion_with_gradient_color", "enable_rambler_motion_dots",
        "rambler_preserve_rewritten_text_on_revert", "agentic_dictation_spot_light2",
        "agentic_dictation_new_gi_motion", "enable_jetson_s3_quota_experiment_override",
        "agentic_dictation_disable_pre_session_quota_check",
        "enable_pcm_audio_caching_for_offline_fallback",
        "agentic_dictation_enable_seamless_fallback",
        "agentic_dictation_enable_proactive_parallel_asr",
    )
    private val WRITING_CORE_TRUE = setOf(
        "writing_helper", "config_proofread", "proofread", "writing_tools",
        "writing_tools_enable_stable_entrance", "enable_super_icon_in_dynamic_writing_tools",
        "enable_writing_tools_replace_button", "enable_writing_tools_compact_layout",
        "enable_writing_tools_scroll_hint_anim", "writing_helper_chip_in_spellchecker",
        "writing_helper_on_selected_text", "writing_helper_enable_text_stylization_internal",
        "enable_writing_tools_cooperative_mode", "enable_writing_tools_for_minors",
        "enable_nga_lab_modeless_smartedit", "enable_writing_tools_voice_commands",
    )
    private val WRITING_V2_TRUE = setOf(
        "enable_writing_tools_v2", "enable_writing_tools_v2_on_toolbar",
        "enable_writing_tools_suggest_style", "writing_tools_v2",
        "writing_tools_v2_enable_suggested_instructions",
        "writing_tools_v2_enable_suggested_instructions_in_draft_response",
        "writing_tools_v2_display_instruction_suggestions_in_zero_state",
        "writing_tools_v2_allow_empty_user_instruction",
        "writing_tools_v2_show_predefined_prompts", "enable_writing_tools_my_style",
        "enable_writing_tools_my_style_plus",
    )
    private val ROUNDED_KEYS_TRUE = setOf(
        "belka_rounded_keyboard", "belka_ui", "belka_background", "belka_center_key",
        "top_rounded_keyboard", "always_top_rounded_keyboard_for_debug", "more_pill_keys",
        "enable_rounded_key_by_default", "enable_rounded_keys",
    )
    private val WORD_LEARNING_TRUE = setOf(
        "enable_device_intelligence", "enable_autofill_ime_integration",
        "enable_spell_checker_extension", "enable_pc_user_history_ngram_count",
        "populate_ngram_count_with_context", "populate_ngram_timestamp_in_user_history",
        "enable_multiword_predictions_from_user_history",
        "enable_multiword_suggestions_from_user_history",
        "enable_dynamic_lm_unigram_continuation_scoring",
        "preserve_context_in_dynamic_lm_compute_history", "enable_dynamic_lm_noise_filter",
        "enable_oov_deduplication", "config_expression", "enable_concept_prediction_engine",
        "enable_rule_based_concept_prediction", "enable_last_word_rule_based_concept_prediction",
        "enable_suffix_rule_based_concept_prediction", "enable_emoji_suggestion_from_top_candidate",
        "enable_predictions_from_emoji_shortcuts", "enable_ngram_emoji_shortcuts_by_spans",
        "enable_ngram_emoji_shortcuts_for_prediction",
        "retain_emoji_shortcut_candidates_on_selection",
        "update_streaming_touch_data_set_is_gesture", "enable_two_stage_decoder",
        "enable_space_transposition_post_correction", "enable_pk_spatial_model_features",
        "enable_pk_auto_correction_trigger_predictor", "enable_post_correction_trigger_predictor",
        "populate_preemptive_next_key_predictions",
    )
    private val WRITING_ALL_LANGUAGE_TAGS = setOf(
        "writing_helper_supported_language_tags", "llm_pc_supported_language_tags",
        "proofread_supported_apps",
    )

    // flag 名字段缓存
    private val nameFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    @Volatile
    private var customFlagsCache: Pair<String, List<CustomFlag>>? = null

    data class CustomFlag(val name: String, val type: String, val value: String)

    fun install(module: XposedModule, cl: ClassLoader) {
        AppHooks.onContextReady { ctx ->
            val sp = ctx.getSharedPreferences("mgga_cache", Context.MODE_PRIVATE)
            val method = DexKitSupport.cachedMethod(sp, ctx, "flag_method", cl) { b ->
                DexKitSupport.findFlagMethod(b)
            }
            if (method != null) {
                hookGenericReader(module, method)
            } else {
                MLog.w(GROUP, "generic reader not found, trying known obfuscated classes")
                hookKnownClasses(module, cl)
            }
        }
    }

    /** DexKit 找到的泛型读取器：返回 Object，所有 flag 共用 */
    private fun hookGenericReader(module: XposedModule, method: Method) {
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            val name = flagName(chain.thisObject) ?: return@intercept result
            val override = evaluate(name, result) ?: return@intercept result
            if (typeMatches(result, override)) override else result
        }
        MLog.i(GROUP, "generic flag reader hooked: ${method.declaringClass.name}#${method.name}")
    }

    /** FuckGboard 路线：多版本混淆类候选 + g() getter + 动态扫描兜底 */
    private fun hookKnownClasses(module: XposedModule, cl: ClassLoader) {
        var hooked = false
        for (name in KNOWN_FLAG_CLASSES) {
            val clazz = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
            val g = runCatching { clazz.getDeclaredMethod("g") }.getOrNull() ?: continue
            g.isAccessible = true
            module.hook(g).intercept { chain ->
                val result = chain.proceed()
                val flagName = flagName(chain.thisObject) ?: return@intercept result
                val override = evaluate(flagName, result) ?: return@intercept result
                if (typeMatches(result, override)) override else result
            }
            MLog.i(GROUP, "known flag class hooked: $name")
            hooked = true
            break
        }
        if (!hooked) {
            DexKitSupport.dynamicFlagClass(cl)?.let { dyn ->
                runCatching {
                    val g = dyn.getDeclaredMethod("g")
                    g.isAccessible = true
                    module.hook(g).intercept { chain ->
                        val result = chain.proceed()
                        val flagName = flagName(chain.thisObject) ?: return@intercept result
                        val override = evaluate(flagName, result) ?: return@intercept result
                        if (typeMatches(result, override)) override else result
                    }
                    MLog.i(GROUP, "dynamic flag class hooked: ${dyn.name}")
                    hooked = true
                }
            }
        }
        if (!hooked) MLog.w(GROUP, "no flag class found in this Gboard version")
    }

    private fun typeMatches(original: Any?, override: Any?): Boolean {
        if (original == null) return true
        return original.javaClass == override?.javaClass
    }

    /** 从混淆 flag 对象读名字：字段 a → 首个 String 字段（沿继承链） */
    private fun flagName(obj: Any?): String? {
        if (obj == null) return null
        val field = nameFieldCache.computeIfAbsent(obj.javaClass) { clazz ->
            runCatching {
                val f = clazz.getDeclaredField("a")
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    return@computeIfAbsent f
                }
                null
            }.getOrNull() ?: run {
                var c: Class<*>? = clazz
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (f.type == String::class.java) {
                            f.isAccessible = true
                            return@run f
                        }
                    }
                    c = c.superclass
                }
                null
            }
        } ?: return null
        return runCatching { field.get(obj) as? String }.getOrNull()
    }

    /**
     * 核心：按 flag 名 + 当前配置决定覆写值。null = 不覆写。
     * 注意语言类后缀（*_language_tags/_locales/_countries）自动映射 "*" / ""。
     */
    fun evaluate(name: String, original: Any?): Any? {
        val isLangTag = name.endsWith("_language_tags") ||
            name.endsWith("_locales") || name.endsWith("_countries")

        fun boolTarget(v: Boolean): Any = if (isLangTag) {
            if (v) "*" else ""
        } else {
            v
        }

        // 1) 剪贴板：query 重构 flag 必须写死 false，否则 limit 恒为 100
        if (Config.clipboardEnabled) {
            if (!Config.flagClipboardChips && name in CLIPBOARD_FORCED_FALSE) return boolTarget(false)
            if (Config.flagClipboardChips && name in CLIPBOARD_CHIPS) return boolTarget(true)
        }

        // 2) 特殊 Long flag
        if (Config.flagWordLearning && name == "intended_oov_min_count") return 1L
        if (Config.flagRambler && name == "ad_activation_type") return 2L

        // 3) AI 写作后端
        if (Config.flagWritingTools) {
            if (name == "writing_tools_v2_backend_type") {
                return when (Config.writingBackend) {
                    "cloud" -> 1L
                    "aicore" -> 2L
                    else -> 3L
                }
            }
            // 非 AICore 后端强制关闭端侧证明，绕过 Gemini Nano 硬件校验
            if (Config.writingBackend != "aicore" &&
                (name == "writing_tools_enable_hybrid" || name == "enable_on_device_proofread")
            ) {
                return boolTarget(false)
            }
            if (Config.flagAllKeyboards && name in WRITING_ALL_LANGUAGE_TAGS) return "*"
            when (name) {
                "writing_helper_model_version" ->
                    return "202408051448_prod_sd_config"
                "writing_helper_text_stylization_model_version" ->
                    return "202504090000_writing_tools_config"
                "nga_lab_modeless_smartedit_regex_version" -> return "v3"
            }
        }

        // 4) 布尔预设组（仅当原始值就是 Boolean 时才改，避免类型错乱）
        if (original is Boolean) {
            val target: Boolean? = when {
                Config.flagAi && name in AI_TRUE -> true
                Config.flagGrammar && name in GRAMMAR_TRUE -> true
                Config.flagMultilingual && name in MULTILINGUAL_TRUE -> true
                Config.flagFloating && name in FLOATING_TRUE -> true
                Config.flagEmojiKitchen && name in EMOJI_KITCHEN_TRUE -> true
                Config.flagAccessPoint && name in ACCESS_POINT_TRUE -> true
                Config.flagMeteredDownloads && name in METERED_TRUE -> true
                Config.flagInlineSuggestions && name in INLINE_TRUE -> true
                Config.flagTflite && name in TFLITE_TRUE -> true
                Config.flagPrivacy && name in PRIVACY_TRUE -> true
                Config.flagPrivacy && name in PRIVACY_FALSE -> false
                Config.flagRambler && name in RAMBLER_TRUE -> true
                Config.flagWritingTools && name in WRITING_CORE_TRUE -> true
                Config.flagWritingTools && Config.flagWritingToolsV2 && name in WRITING_V2_TRUE -> true
                Config.flagRoundedKeys && name in ROUNDED_KEYS_TRUE -> true
                Config.flagWordLearning && name in WORD_LEARNING_TRUE -> true
                else -> null
            }
            if (target != null) return boolTarget(target)
        }

        // 5) 用户自定义 flag
        for (cf in customFlags()) {
            if (cf.name == name) {
                return when (cf.type) {
                    "bool" -> cf.value.toBooleanStrictOrNull()
                    "long" -> cf.value.toLongOrNull()
                    "string" -> cf.value
                    else -> coerceLike(original, cf.value)
                }
            }
        }
        return null
    }

    private fun coerceLike(original: Any?, value: String): Any? = when (original) {
        is Boolean -> value.toBooleanStrictOrNull()
        is Long -> value.toLongOrNull()
        is Int -> value.toIntOrNull()
        is String -> value
        else -> null
    }

    private fun customFlags(): List<CustomFlag> {
        val json = Config.customFlagsJson
        if (json.isBlank()) return emptyList()
        customFlagsCache?.let { (j, list) -> if (j == json) return list }
        val list = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val n = o.optString("name")
                if (n.isBlank()) null
                else CustomFlag(n, o.optString("type", "auto"), o.optString("value", ""))
            }
        } catch (t: Throwable) {
            MLog.w(GROUP, "custom_flags_json parse failed", t)
            emptyList()
        }
        customFlagsCache = json to list
        return list
    }
}
