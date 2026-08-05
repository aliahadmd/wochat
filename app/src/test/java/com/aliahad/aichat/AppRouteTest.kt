package com.aliahad.aichat

import com.aliahad.aichat.ui.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteTest {
    @Test
    fun routeRoundTripIsStable() {
        AppRoute.entries.forEach { route ->
            assertEquals(route, AppRoute.fromRoute(route.route))
        }
    }

    @Test
    fun unknownRouteFallsBackToChat() {
        assertEquals(AppRoute.CHAT, AppRoute.fromRoute("removed-route"))
        assertEquals(AppRoute.CHAT, AppRoute.fromRoute(null))
    }
}
