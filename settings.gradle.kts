rootProject.name = "JunkGamesCore"

include("junkgames-platform-api", "junkgames-api", "junkgames-core")

// Individual JunkGames plugins live in this repository too: every directory under
// junkgames-plugin/ that has a build.gradle.kts becomes a plugin project.
file("junkgames-plugin").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":junkgames-plugin:${it.name}") }
