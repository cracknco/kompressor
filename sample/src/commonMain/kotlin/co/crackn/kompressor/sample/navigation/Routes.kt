package co.crackn.kompressor.sample.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Route {
    @Serializable
    data object Image : Route

    @Serializable
    data object Video : Route

    @Serializable
    data object Audio : Route
}
