package com.aliahad.aichat.ui.navigation

/** Top-level destinations. The NavController is the only owner of the active route. */
enum class AppRoute(val route: String) {
    CHAT("chat"),
    SETTINGS("settings"),
    ;

    companion object {
        fun fromRoute(route: String?): AppRoute =
            entries.firstOrNull { it.route == route } ?: CHAT
    }
}
