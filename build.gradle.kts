plugins {
    java
    checkstyle
    `maven-publish`
    id("dev.plex.updater") version "1.1"
}

repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "oasis"
                url = uri("https://telesphorepo.com/maven/oasis-snapshots/")
            }
        }
        filter {
            includeGroup("com.oasis")
        }
    }

    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        url = uri("https://telesphorepo.com/maven/plex/")
    }

    maven {
        url = uri("https://maven.enginehub.org/repo/")
    }

    maven {
        url = uri("https://repo.essentialsx.net/snapshots/")
    }

    maven {
        url = uri("https://nexus.scarsz.me/content/groups/public/")
    }

    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("dev.plex:api:2.0.1-SNAPSHOT")
    compileOnly(platform("com.intellectualsites.bom:bom-newest:1.56"))
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.16.2")
    compileOnly("net.essentialsx:EssentialsX:2.22.1-SNAPSHOT")
    compileOnly("com.discordsrv:discordsrv:1.30.5")
    compileOnly("com.oasis:oasis-api:3.0-SNAPSHOT")
}

group = "dev.plex"
version = "2.0.1-SNAPSHOT"
description = "Module-ECC"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

checkstyle {
    toolVersion = "14.4.0"
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