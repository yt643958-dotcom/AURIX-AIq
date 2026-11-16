package com.example.ai

import com.example.automation.AutomationResult
import com.example.automation.PhoneAutomationManager
import com.example.core.AurixLanguage
import com.example.data.local.AurixRepository
import com.example.data.local.SettingsRepository
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AurixTools {

    private val openAppDecl = FunctionDeclaration(
        name = "open_app",
        description = "Opens an authorized installed app on the phone such as Facebook, WhatsApp, YouTube, or Chrome.",
        parameters = mapOf(
            "app_name" to Schema.string("Name of the app to open, e.g., facebook, whatsapp, youtube, chrome")
        )
    )

    private val openSettingsDecl = FunctionDeclaration(
        name = "open_settings",
        description = "Opens a supported Android settings screen (wifi, bluetooth, sound, display, battery, accessibility, location, general).",
        parameters = mapOf(
            "setting_type" to Schema.string("Type of phone setting to open, e.g., wifi, bluetooth, sound, display, accessibility, general")
        )
    )

    private val searchWebDecl = FunctionDeclaration(
        name = "search_web",
        description = "Searches the web using the device browser for the given query.",
        parameters = mapOf(
            "query" to Schema.string("Search query text in Bengali or English")
        )
    )

    private val readScreenDecl = FunctionDeclaration(
        name = "read_screen",
        description = "Reads visible text and posts on the currently open screen using the authorized AURIX Accessibility Service.",
        parameters = mapOf(
            "detail_level" to Schema.enumeration(
                values = listOf("full", "summary"),
                description = "Optional detail level of visible screen text to capture, defaults to full"
            )
        ),
        optionalParameters = listOf("detail_level")
    )

    private val scrollScreenDecl = FunctionDeclaration(
        name = "scroll_screen",
        description = "Scrolls the current screen down or up using the authorized AURIX Accessibility Service.",
        parameters = mapOf(
            "direction" to Schema.string("Scroll direction: 'down' or 'up'")
        )
    )

    private val proposeSocialOrInputDecl = FunctionDeclaration(
        name = "propose_social_or_input_action",
        description = "Prepares a proposed comment or text input action (e.g., posting a Bengali comment on Facebook) and shows it to the user for explicit confirmation before typing or posting.",
        parameters = mapOf(
            "app_name" to Schema.string("Target app name, e.g., Facebook, WhatsApp, Chrome"),
            "post_summary" to Schema.string("Brief summary of the visible post or context that was read"),
            "proposed_text" to Schema.string("The exact comment or text proposed by AURIX AI for the user to review and approve"),
            "button_label" to Schema.string("Label of the submit/post button to tap after approval, e.g., Post, Send, কমেন্ট"),
            "field_hint" to Schema.string("Optional hint of the text field, e.g., Write a comment")
        ),
        optionalParameters = listOf("field_hint")
    )

    private val switchLanguageDecl = FunctionDeclaration(
        name = "switch_language",
        description = "Switches and persists AURIX AI's active conversation language when requested by the user (e.g. 'Speak English' -> en-US, 'বাংলায় কথা বলো' -> bn-BD).",
        parameters = mapOf(
            "language_code" to Schema.enumeration(
                values = listOf("bn-BD", "en-US", "auto"),
                description = "Target language code: bn-BD for Bangladeshi Bengali, en-US for English, auto for Multilingual"
            )
        )
    )

    private val saveMemoryDecl = FunctionDeclaration(
        name = "save_memory",
        description = "Saves a helpful user preference or ongoing task context to local memory. Never save passwords, OTPs, or API keys.",
        parameters = mapOf(
            "fact" to Schema.string("Concise preference or context fact to remember"),
            "category" to Schema.string("Category: preference, task, or general")
        ),
        optionalParameters = listOf("category")
    )

    data class ToolMetadata(
        val name: String,
        val parameterNames: Set<String>
    )

    val registeredToolMetadata: List<ToolMetadata> = listOf(
        ToolMetadata("open_app", setOf("app_name")),
        ToolMetadata("open_settings", setOf("setting_type")),
        ToolMetadata("search_web", setOf("query")),
        ToolMetadata("read_screen", setOf("detail_level")),
        ToolMetadata("scroll_screen", setOf("direction")),
        ToolMetadata(
            "propose_social_or_input_action",
            setOf("app_name", "post_summary", "proposed_text", "button_label", "field_hint")
        ),
        ToolMetadata("switch_language", setOf("language_code")),
        ToolMetadata("save_memory", setOf("fact", "category"))
    )

    val allDeclarations: List<FunctionDeclaration> = listOf(
        openAppDecl,
        openSettingsDecl,
        searchWebDecl,
        readScreenDecl,
        scrollScreenDecl,
        proposeSocialOrInputDecl,
        switchLanguageDecl,
        saveMemoryDecl
    )

    val tool: Tool = Tool.functionDeclarations(allDeclarations)

    suspend fun executeToolCall(
        call: FunctionCallPart,
        automationManager: PhoneAutomationManager,
        settingsRepository: SettingsRepository,
        aurixRepository: AurixRepository,
        onSystemActionLogged: suspend (String) -> Unit,
        onLanguageSwitched: suspend (AurixLanguage) -> Unit
    ): FunctionResponsePart {
        fun arg(key: String): String =
            call.args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val resultJson: JsonObject = when (call.name) {
            "open_app" -> {
                val appName = arg("app_name")
                val res = automationManager.openApp(appName)
                when (res) {
                    is AutomationResult.Success -> {
                        onSystemActionLogged("Launched app: $appName")
                        jsonResult(true, res.message)
                    }
                    is AutomationResult.RequiresPermission -> {
                        onSystemActionLogged(res.guidance)
                        jsonResult(false, res.guidance, requiresPermission = res.permissionName)
                    }
                    is AutomationResult.Failed -> jsonResult(false, res.reason)
                }
            }

            "open_settings" -> {
                val settingType = arg("setting_type")
                val res = automationManager.openPhoneSettings(settingType)
                when (res) {
                    is AutomationResult.Success -> {
                        onSystemActionLogged("Opened settings: $settingType")
                        jsonResult(true, res.message)
                    }
                    is AutomationResult.RequiresPermission -> jsonResult(false, res.guidance)
                    is AutomationResult.Failed -> jsonResult(false, res.reason)
                }
            }

            "search_web" -> {
                val query = arg("query")
                val res = automationManager.searchWeb(query)
                when (res) {
                    is AutomationResult.Success -> {
                        onSystemActionLogged("Web search: $query")
                        jsonResult(true, res.message)
                    }
                    is AutomationResult.RequiresPermission -> jsonResult(false, res.guidance)
                    is AutomationResult.Failed -> jsonResult(false, res.reason)
                }
            }

            "read_screen" -> {
                val res = automationManager.readVisibleScreen()
                when (res) {
                    is AutomationResult.Success -> {
                        onSystemActionLogged("Read visible screen content")
                        jsonResult(true, res.message)
                    }
                    is AutomationResult.RequiresPermission -> {
                        onSystemActionLogged("Accessibility permission needed to read screen.")
                        jsonResult(false, res.guidance, requiresPermission = res.permissionName)
                    }
                    is AutomationResult.Failed -> jsonResult(false, res.reason)
                }
            }

            "scroll_screen" -> {
                val direction = arg("direction").ifEmpty { "down" }
                val res = automationManager.scrollScreen(direction)
                when (res) {
                    is AutomationResult.Success -> {
                        onSystemActionLogged("Scrolled $direction")
                        jsonResult(true, res.message)
                    }
                    is AutomationResult.RequiresPermission -> jsonResult(false, res.guidance, requiresPermission = res.permissionName)
                    is AutomationResult.Failed -> jsonResult(false, res.reason)
                }
            }

            "propose_social_or_input_action" -> {
                val appName = arg("app_name").ifEmpty { "Facebook" }
                val postSummary = arg("post_summary")
                val proposedText = arg("proposed_text")
                val buttonLabel = arg("button_label").ifEmpty { "Post" }
                val fieldHint = arg("field_hint").ifEmpty { null }

                automationManager.queueConfirmationAction(
                    actionTitle = "Confirm $appName Action",
                    appName = appName,
                    postSummary = postSummary,
                    proposedText = proposedText,
                    buttonLabelToClick = buttonLabel,
                    preferredFieldHint = fieldHint,
                    isSocialComment = appName.equals("Facebook", ignoreCase = true)
                )
                onSystemActionLogged("Proposed $appName text waiting for your approval: \"$proposedText\"")
                jsonResult(
                    true,
                    "Confirmation card shown to the user with proposed text: \"$proposedText\". Ask the user to review and tap Approve & Post before it will be submitted."
                )
            }

            "switch_language" -> {
                val code = arg("language_code")
                val targetLang = AurixLanguage.fromCode(code)
                settingsRepository.setLanguage(targetLang)
                onLanguageSwitched(targetLang)
                onSystemActionLogged("Language switched to ${targetLang.displayName}")
                jsonResult(true, "Language preference updated to ${targetLang.displayName} (${targetLang.code}).")
            }

            "save_memory" -> {
                val fact = arg("fact")
                val category = arg("category").ifEmpty { "general" }
                val saved = aurixRepository.saveMemoryFact(category, fact)
                if (saved) {
                    onSystemActionLogged("Remembered: $fact")
                    jsonResult(true, "Saved to persistent memory.")
                } else {
                    jsonResult(false, "Refused to store empty or sensitive credential data.")
                }
            }

            else -> jsonResult(false, "Unknown tool: ${call.name}")
        }

        return FunctionResponsePart(
            name = call.name,
            response = resultJson,
            id = call.id
        )
    }

    private fun jsonResult(
        success: Boolean,
        message: String,
        requiresPermission: String? = null
    ): JsonObject {
        val map = mutableMapOf(
            "success" to JsonPrimitive(success),
            "message" to JsonPrimitive(message)
        )
        if (requiresPermission != null) {
            map["requires_permission"] = JsonPrimitive(requiresPermission)
        }
        return JsonObject(map)
    }
}
