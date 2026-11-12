import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

val paperVersion = "26.3.build.246-stable"

subprojects {
    if (path == ":junkgames-plugin") return@subprojects // container of the plugin projects
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:$paperVersion")
        "compileOnly"(kotlin("stdlib"))
        "testImplementation"("io.papermc.paper:paper-api:$paperVersion")
        "testImplementation"(kotlin("stdlib"))
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
            // real default methods in interfaces so Java add-ons can implement them
            freeCompilerArgs.addAll("-jvm-default=no-compatibility", "-Xjsr305=strict")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}
