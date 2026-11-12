// Block SPI for add-on plugins: IR model, type system, compile/runtime contracts (spec §6.3).
plugins { `maven-publish` }

dependencies {
    "api"(project(":junkgames-platform-api"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "junkgames-api"
            from(components["java"])
        }
    }
}
