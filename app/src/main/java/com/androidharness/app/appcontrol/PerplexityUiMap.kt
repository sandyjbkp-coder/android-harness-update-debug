package com.androidharness.app.appcontrol

/**
 * PHASE 1.1 — Deep Perplexity UI Reconnaissance (evidence-based map).
 *
 * App: ai.perplexity.app.android
 * Version: 2.82.1 (versionCode 260591)
 * Launcher: ai.perplexity.app.android/.ui.main.MainActivity
 * Recon date: 2026-11-10
 * Device/UI evidence: uiautomator hierarchy dumps + screenshots in
 *   AndroidHarness-Recovery/perplexity_ui_recon/
 *
 * Notes:
 * - "uiautomator dump" succeeded in this session (produced full XML hierarchies),
 *   contrary to an earlier Phase 1 note that it failed with an app_process linker error.
 *   Both outcomes are recorded as environment-dependent observations.
 * - Bounds are device/screen specific: 1272x2772 portrait.
 * - Compose-heavy UI: many elements are android.view.View with content-desc/resource-id
 *   rather than classic Android widgets; "available actions" are inferred from dumped
 *   attributes (clickable/focusable/scrollable/long-clickable/enabled).
 * - Do NOT send research queries during reconnaissance. No chat automation implemented here.
 */
object PerplexityUiMap {

    const val PACKAGE = "ai.perplexity.app.android"
    const val VERSION_NAME = "2.82.1"
    const val VERSION_CODE = 260591L
    const val LAUNCHER_ACTIVITY = "ai.perplexity.app.android/.ui.main.MainActivity"

    data class UiElement(
        val screen: String,
        val label: String,
        val className: String,
        val resourceId: String?,
        val contentDesc: String?,
        val text: String?,
        val bounds: String,
        val clickable: Boolean,
        val focusable: Boolean,
        val scrollable: Boolean,
        val longClickable: Boolean,
        val enabled: Boolean,
        val visible: Boolean = true,
        val notes: String = "",
    )

    object Screens {
    const val THREADS_LIBRARY_SIDEBAR = "Threads/Library sidebar (thread history)"
    const val HOME_NEW_CHAT = "Home / New chat composer (Ask tab)"
    const val HOME_NEW_CHAT_NON_EMPTY = "Home / New chat composer with text entered (send affordance visible)"
    const val SPACES_TAB = "Spaces tab (navigated from sidebar)"
        const val EXISTING_THREAD = "Existing conversation thread (entry points discovered)"
        const val SETTINGS = "Settings (entry point discovered, not fully explored)"
        const val SEARCH_IN_LIBRARY = "Library search (entry point discovered)"
    }

    /** Elements discovered from 01_threads_home.xml / 04_library.xml */
    val threadsLibrarySidebar: List<UiElement> = listOf(
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Settings button (icon)", "android.view.View", null, "Settings", null,
            "[43,191][86,234]", clickable = false, focusable = false, scrollable = false, longClickable = false, enabled = true,
            notes = "Child of clickable container [13,161][116,264] in library dump; tap container to open Settings."),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Settings container", "android.view.View", null, null, null,
            "[13,161][116,264]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "User/profile label", "android.widget.TextView", null, null, "sandyjbkp62666",
            "[129,188][460,238]", clickable = false, focusable = false, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Search button container", "android.view.View", null, null, null,
            "[1062,161][1156,264]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Search icon", "android.view.View", null, "Search", null,
            "[1092,191][1135,234]", clickable = false, focusable = false, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Close sidebar container", "android.view.View", null, null, null,
            "[1156,161][1259,264]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Close icon", "android.view.View", null, "Close", null,
            "[1186,191][1229,234]", clickable = false, focusable = false, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread list (scrollable)", "android.view.View", null, null, null,
            "[0,282][1272,2572]", clickable = false, focusable = false, scrollable = true, longClickable = false, enabled = true,
            notes = "Vertical scroll region containing conversation history entries."),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: vWhat is the capital of France", "android.view.View", null, null, null,
            "[0,283][1272,608]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Clickable history item; has overflow/options button [1160,291][1264,395]."),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: Build a reference manual...", "android.view.View", null, null, null,
            "[0,609][1272,963]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: NV/EFS, raw AT/QMI...", "android.view.View", null, null, null,
            "[0,964][1272,1318]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: VERIFICATION COMPLETE...", "android.view.View", null, null, null,
            "[0,1319][1272,1680]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: Ek Node.js Code...", "android.view.View", null, null, null,
            "[0,1681][1272,2042]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: adb devices usb...", "android.view.View", null, null, null,
            "[0,2043][1272,2368]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Thread entry: india mei ane vale...", "android.view.View", null, null, null,
            "[0,2369][1272,2624]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Add/new thread button container", "android.view.View", null, null, null,
            "[1126,2409][1246,2529]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Add button icon", "android.view.View", null, "add button", null,
            "[1160,2443][1212,2495]", clickable = false, focusable = false, scrollable = false, longClickable = false, enabled = true,
            notes = "Tapping add button area transitioned to Home/New chat composer (evidence: 02_after_add_button)."),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Bottom tab bar container", "android.view.View", null, null, null,
            "[34,2572][1238,2703]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Threads tab", "android.view.View", null, null, null,
            "[43,2581][636,2694]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Label 'Threads' at [295,2650][385,2677]."),
        UiElement(Screens.THREADS_LIBRARY_SIDEBAR, "Spaces tab", "android.view.View", null, null, null,
            "[636,2581][1229,2694]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Label 'Spaces' at [892,2650][974,2677]."),
    )

    /** Elements discovered from 02_after_add_button.xml (Home / New chat composer) */
    val homeNewChat: List<UiElement> = listOf(
        UiElement(Screens.HOME_NEW_CHAT, "Library button", "android.widget.Button", "library-tab-button", "Library", null,
            "[26,162][146,265]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Opens library/threads sidebar view."),
        UiElement(Screens.HOME_NEW_CHAT, "Ask tab", "android.view.View", "home-tab-ask", null, null,
            "[404,162][636,265]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child icon content-desc 'Ask' [494,188][546,240]."),
        UiElement(Screens.HOME_NEW_CHAT, "Computer tab", "android.view.View", "home-tab-computer", null, null,
            "[636,162][868,265]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child icon content-desc 'Computer' [726,188][778,240]."),
        UiElement(Screens.HOME_NEW_CHAT, "Discover button", "android.widget.Button", "discover-tab-button", "Discover", null,
            "[1160,179][1229,248]", clickable = false, focusable = false, scrollable = false, longClickable = false, enabled = true,
            notes = "Dump shows clickable=false/focusable=false on button element; interaction may be on parent or different state."),
        UiElement(Screens.HOME_NEW_CHAT, "Assistant promo banner", "android.view.View", null, null, null,
            "[52,1496][1220,1660]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Text: 'Introducing Perplexity Assistant' / 'Supercharge your daily tasks and get answers anywhere'."),
        UiElement(Screens.HOME_NEW_CHAT, "Dismiss promo banner", "android.widget.ImageView", null, "Dismiss", null,
            "[1125,1487][1229,1591]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.HOME_NEW_CHAT, "Composer container", "android.view.View", null, null, null,
            "[26,1703][1246,1933]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true),
        UiElement(Screens.HOME_NEW_CHAT, "Search/chat input", "android.widget.EditText", "input-search", null, null,
            "[69,1714][1203,1818]", clickable = true, focusable = true, scrollable = false, longClickable = true, enabled = true,
            notes = "Hint/placeholder text 'Ask anything…' at [69,1746][1203,1786]. Primary chat composer field."),
        UiElement(Screens.HOME_NEW_CHAT, "Sources/search mode button", "android.view.View", "search-sources-button", null, null,
            "[35,1821][138,1924]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child content-desc 'Source input and search mode selector'."),
        UiElement(Screens.HOME_NEW_CHAT, "Model selector button", "android.view.View", "search-model-button", null, null,
            "[138,1821][338,1924]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child content-desc 'Model selector', label text 'Thinking'."),
        UiElement(Screens.HOME_NEW_CHAT, "Incognito mode button", "android.view.View", null, null, null,
            "[962,1821][1048,1924]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child content-desc 'Incognito mode'."),
        UiElement(Screens.HOME_NEW_CHAT, "Voice input button", "android.view.View", null, null, null,
            "[1048,1821][1134,1924]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child content-desc 'Voice input'."),
        UiElement(Screens.HOME_NEW_CHAT, "Voice to voice button", "android.view.View", null, null, null,
            "[1134,1821][1237,1924]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Child content-desc 'Voice to voice'."),
    )

    /** Elements visible only after text is entered in the composer (07_perp_1.2_nonempty.xml).
     * These were captured while input-search held a harmless test string ("AndroidHarness connection test").
     * No message was sent; app was relaunched (force-stop + am start) to restore clean state. */
    val homeNewChatNonEmpty: List<UiElement> = listOf(
        UiElement(Screens.HOME_NEW_CHAT_NON_EMPTY, "Send button", "android.view.View", "send-button", "Send", null,
            "[1134,1821][1237,1924]", clickable = true, focusable = true, scrollable = false, longClickable = false, enabled = true,
            notes = "Container child TextView 'Send' at [1164,1851][1207,1894]. Only visible/active when composer is non-empty. Primary submit affordance for sending a message."),
    )

    val allDiscovered: List<UiElement> = threadsLibrarySidebar + homeNewChat + homeNewChatNonEmpty
}
