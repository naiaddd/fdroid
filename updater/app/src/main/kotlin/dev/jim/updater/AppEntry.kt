package dev.jim.updater

data class AppEntry(
    val displayName: String,
    val packageName: String,
    val tagPrefix: String,
    val hasAbiSplit: Boolean,
)

val KNOWN_APPS =
    listOf(
        AppEntry("Indicium", "dev.jim.statbot.indicium", "indicium", hasAbiSplit = true),
        AppEntry("Industria", "com.example.industria", "industria", hasAbiSplit = true),
        AppEntry("Anetmon", "dev.jim.anetmon", "anetmon", hasAbiSplit = false),
        AppEntry("Sentry", "dev.jim.sentry", "sentry", hasAbiSplit = false),
        AppEntry("Tutor", "dev.jim.tutor", "tutor", hasAbiSplit = true),
        AppEntry("Didact", "dev.jim.didact", "didact", hasAbiSplit = true),
        AppEntry("Updater", "dev.jim.updater", "updater", hasAbiSplit = false),
    )
