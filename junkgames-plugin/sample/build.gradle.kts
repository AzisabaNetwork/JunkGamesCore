// Sample JunkGames plugin. Copy this directory to start a new one.
version = "1.0.0"

dependencies {
    // only for tests: compile the bundled game against the real compiler
    testImplementation(project(":junkgames-core"))
}

tasks.jar {
    archiveBaseName.set("JunkGamesSample")
}
