/*
 * This file is part of Piper for Burp Suite (https://github.com/silentsignal/burp-piper)
 * Copyright (c) 2018 Andras Veres-Szentkiralyi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.protobuf.gradle.ProtobufExtension
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.remove
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.20"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.silentsignal.burp"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.google.protobuf:protobuf-lite:3.0.0")
    implementation("org.snakeyaml:snakeyaml-engine:1.0")
    implementation("net.portswigger.burp.extender:burp-extender-api:1.7.22")
    implementation("net.portswigger.burp.extensions:montoya-api:2025.8")
    implementation("com.esotericsoftware:minlog:1.3")

    testImplementation("org.testng:testng:6.9.10")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn("generateProto")
}

sourceSets {
    val main by getting {
        java.srcDir(layout.buildDirectory.dir("generated/source/proto/main/java"))
        java.srcDir(layout.buildDirectory.dir("generated/source/proto/main/javalite"))
        resources.srcDir("src/main/yaml")
        resources.srcDir("static")
    }
}

extensions.configure<ProtobufExtension>("protobuf") {
    protoc {
        artifact = "com.google.protobuf:protoc:3.0.0"
    }
    plugins {
        id("javalite") {
            artifact = "com.google.protobuf:protoc-gen-javalite:3.0.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                remove("java")
            }
            task.plugins {
                id("javalite")
            }
        }
    }
}

tasks.withType<Test> {
    useTestNG()
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("src/main/yaml") {
        include("*.yaml")
    }

    from("static") {
        include("mime.pb")
    }

    from({
        configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })
}
