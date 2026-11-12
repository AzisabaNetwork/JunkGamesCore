// Conventions shared by every plugin in junkgames-plugin/.
//
// - Kotlin, Paper API and junkgames-api are compileOnly: the stdlib and the API come from
//   JunkGamesCore at runtime, so every plugin.yml must declare `depend: [JunkGamesCore]`.
// - Commands are declared in plugin.yml.
// - `./gradlew :junkgames-plugin:<name>:build` → junkgames-plugin/<name>/build/libs/<PluginName>-<version>.jar
//   `./gradlew pluginJars` copies every plugin jar to build/plugins/.

subprojects {
    dependencies {
        "compileOnly"(project(":junkgames-api"))
        "testImplementation"(project(":junkgames-api"))
    }

    tasks.named<ProcessResources>("processResources") {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
    }
}

tasks.register<Copy>("pluginJars") {
    description = "Collects the jars of all plugins in junkgames-plugin/ into build/plugins"
    subprojects.forEach { p -> from(p.tasks.named("jar")) }
    into(rootProject.layout.buildDirectory.dir("plugins"))
}
