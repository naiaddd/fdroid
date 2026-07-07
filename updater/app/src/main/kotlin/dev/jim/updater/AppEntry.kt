package dev.jim.updater

data class AppEntry(
    val displayName: String,
    val packageName: String,
    val tagPrefix: String,
    val hasAbiSplit: Boolean,
)
