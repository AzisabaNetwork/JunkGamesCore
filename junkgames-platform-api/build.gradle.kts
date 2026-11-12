// Provider SPI (spec §12). Server forks (Minogami etc.) depend on this jar as compileOnly
// and register Native Providers into Bukkit's ServicesManager.
plugins { `maven-publish` }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "junkgames-platform-api"
            from(components["java"])
        }
    }
}
