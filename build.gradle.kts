plugins {
    id("java-library")
    id("org.allaymc.gradle.plugin") version "0.2.1"
}

group = "me.daoge.allaymap"
description = "AllayMap is a minimalistic and lightweight world map viewer for Allay servers, using the vanilla map rendering style"
version = "0.1.2-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allay {
    api = "0.18.0"

    plugin {
        entrance = ".AllayMap"
        authors += "daoge_cmd"
        website = "https://github.com/smartcmd/AllayMap"
    }
}

repositories {
    mavenCentral()
    maven("https://storehouse.okaeri.eu/repository/maven-public/")
}

dependencies {
    compileOnly(group = "org.projectlombok", name = "lombok", version = "1.18.34")
    implementation(group = "eu.okaeri", name = "okaeri-configs-yaml-snakeyaml", version = "5.0.13")
    annotationProcessor(group = "org.projectlombok", name = "lombok", version = "1.18.34")
}
