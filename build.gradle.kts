plugins {
    java
    checkstyle
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        url = uri("https://telesphorepo.com/maven/plex/")
    }

    maven {
        url = uri("https://jitpack.io")
    }

    maven {
        url = uri("https://maven.enginehub.org/repo/")
    }

    maven {
        url = uri("https://repo.essentialsx.net/snapshots/")
    }

    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("dev.plex:api:2.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude("org.bukkit", "bukkit")
    }
    compileOnly(platform("com.intellectualsites.bom:bom-newest:1.56"))
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core")
    compileOnly("net.essentialsx:EssentialsX:2.22.1-SNAPSHOT")
    implementation("io.airlift:aircompressor-v3:3.14")
}

group = "dev.plex"
version = "2.0.1-SNAPSHOT"
description = "Module-ECC"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

checkstyle {
    toolVersion = "14.3.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.getByName<Jar>("jar") {
    archiveBaseName.set("Module-ECC")
    archiveVersion.set("")
}

tasks.shadowJar {
    archiveBaseName.set("Module-ECC")
    archiveClassifier.set("")
    archiveVersion.set("")
    // The record uses aircompressor's pure Java zstd; its native libraries are never loaded.
    exclude("aircompressor/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
    }
    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }
    processResources {
        filteringCharset = Charsets.UTF_8.name()
    }
}