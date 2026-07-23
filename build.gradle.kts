import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-rc2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.branzx"
version = "1.0.0"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "luckperms"
        url = uri("https://repo.lucko.me/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    // The central wallet — resolved at runtime from Bukkit services; only the
    // API types are needed at compile time (BranzDiscord depends on it).
    compileOnly(files("../branz-wallet/build/libs/BranzWallet-1.0.0.jar"))
    // Rank grants go through LuckPerms; soft dependency, interface-only.
    compileOnly("net.luckperms:api:5.4")

    // Discord gateway. Audio is dropped — the storefront never joins voice.
    implementation("net.dv8tion:JDA:5.2.1") {
        exclude(module = "opus-java")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // Relocate the transitive libraries most likely to collide with other
    // plugins on the server classpath.
    relocate("okhttp3", "dev.branzx.discord.libs.okhttp3")
    relocate("okio", "dev.branzx.discord.libs.okio")
    relocate("com.fasterxml.jackson", "dev.branzx.discord.libs.jackson")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.runServer {
    minecraftVersion("26.2")
    jvmArgs("-Dcom.mojang.eula.agree=true", "--enable-native-access=ALL-UNNAMED")
}
