package com.cardroid.app.helper

/** Private displays may be omitted from the shell UID's DisplayManager lookup. */
internal object PanelDisplayInfo {
    fun ownedPanels(dump: String, appUid: Int): Map<Int, String> = dump.lineSequence().mapNotNull { line ->
        if (!line.trimStart().startsWith("mBaseDisplayInfo=DisplayInfo{")) return@mapNotNull null
        if (!Regex("(?:, | )FLAG_PRIVATE(?:,| )").containsMatchIn(line)) return@mapNotNull null
        val name = Regex("DisplayInfo\\{\"CarDroid Panel: ([A-Za-z0-9_.]+)\",").find(line)?.groupValues?.get(1)
            ?: return@mapNotNull null
        val id = Regex("\\bdisplayId (\\d+),").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return@mapNotNull null
        val owner = Regex("\\bowner com\\.cardroid\\.app \\(uid (\\d+)\\)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        if (owner != appUid) return@mapNotNull null
        id to name
    }.toMap()
}
