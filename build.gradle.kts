plugins {
    id("fabric-loom") version "1.14.5"
    id("maven-publish")
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21

repositories {
    mavenCentral()
    mavenLocal()

    // Fabric
    maven("https://maven.fabricmc.net/")

    // Cobblemon
    maven("https://maven.impactdev.net/repository/development/")

    // GeckoLib (required by Cobblemon)
    maven("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")

    // Text Placeholder API
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }

    // Modrinth Maven (alternative for mods)
    maven("https://api.modrinth.com/maven")

    // CurseForge Maven
    maven("https://cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }
}

dependencies {
    // Minecraft & Fabric
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")

    // Fabric API
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Fabric Language Kotlin
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")

    // Cobblemon
    modCompileOnly("com.cobblemon:fabric:${project.property("cobblemon_version")}")

    // Text Placeholder API (included in jar)
    modImplementation(include("eu.pb4:placeholder-api:${project.property("placeholder_api_version")}")!!)

    // Kotlinx Serialization for JSON5 config
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SQLite JDBC
    implementation(include("org.xerial:sqlite-jdbc:3.46.1.0")!!)
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version"),
            "loader_version" to project.property("loader_version"),
            "fabric_kotlin_version" to project.property("fabric_kotlin_version")
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
    withSourcesJar()
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }

    repositories {
    }
}
