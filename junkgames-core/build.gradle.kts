plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":junkgames-api"))
    implementation(project(":junkgames-platform-api"))
    // bundled unrelocated: every plugin in junkgames-plugin/ declares `depend: [JunkGamesCore]`
    // and uses this single copy (Kotlin function types cross the API boundary)
    implementation(kotlin("stdlib"))
}

tasks {
    shadowJar {
        archiveBaseName.set("JunkGamesCore")
        archiveClassifier.set("")
        dependencies { exclude(dependency("org.jetbrains:annotations:.*")) }
        // Kotlin module metadata is merged by Shadow's transformer; let duplicates reach it
        // instead of being dropped by the default EXCLUDE strategy
        filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    }
    build { dependsOn(shadowJar) }
    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
    }
}
