import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kover)
    `java-library`
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.obscura"
            artifactId = "obscura-kit"
            version = "0.1.0"
            from(components["java"])
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

// ──────────────────────────────────────────────────────────────────────
// Test source-set split
//
// :lib:test            — pure unit tests. No network. <10s. Runs on every PR.
// :lib:integrationTest — server-dependent scenario suite (~256 tests against
//                        a live Obscura server). 4-5 min. Runs on main only.
//
// The integration suite is gated on a reachable server via assumeTrue() inside
// the tests themselves, so it skips-not-fails when the server is down. That's
// fine for nightly, but it means "green CI" must NOT depend on these — hence
// the split.
// ──────────────────────────────────────────────────────────────────────
sourceSets {
    main {
        proto {
            // Fixtures live one directory up so they can be shared with
            // other repos (obscura-server, ObscuraKit-swift) without
            // copy-paste drift.
            srcDir("../fixtures")
        }
    }
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["implementation"], configurations["testImplementation"])
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations["runtimeOnly"], configurations["testRuntimeOnly"])
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the server-dependent integration scenario suite."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

// The integration tests were originally in src/test/ where they could see
// `internal` symbols on ObscuraClient (APIClient field, etc.). A new source
// set is a new Kotlin compilation, which loses internal visibility.
// Associate it with main as a "friend" compilation to restore that access.
// Must run AFTER the sourceSet is created above.
kotlin {
    target.compilations.named("integrationTest") {
        associateWith(target.compilations.named("main").get())
    }
}

dependencies {
    // Protobuf
    implementation(libs.protobuf.kotlin)
    implementation(libs.protobuf.java)

    // SQLDelight
    implementation(libs.sqldelight.jvm)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)

    // Signal Protocol
    implementation(libs.libsignal)

    // Networking
    implementation(libs.okhttp)

    // JSON
    implementation(libs.json)

    // Serialization (typed ORM models)
    implementation(libs.serialization.json)

    // Coroutines
    implementation(libs.coroutines.core)

    // Testing — shared by both suites via the extendsFrom above.
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(libs.coroutines.test)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
        }
    }
}

sqldelight {
    databases {
        create("ObscuraDatabase") {
            packageName.set("com.obscura.kit.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.3.2")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    description = "Runs pure unit tests (no network, no server). Fast feedback."
}

tasks.check {
    // Deliberately NOT depending on integrationTest. CI runs integrationTest
    // separately on main pushes. ./gradlew check stays a fast feedback loop.
}

// ──────────────────────────────────────────────────────────────────────
// Coverage
//
// Default `:lib:koverHtmlReport` measures coverage from `:lib:test` only
// — the fast PR-gate suite. PR-time numbers reflect what your unit suite
// covers, which is the number you can move with new unit tests.
//
// The integration suite is intentionally excluded by disabling its
// instrumentation. To get a combined report including integration:
//
//   ./gradlew :lib:integrationTest :lib:koverHtmlReport \
//     -Pkover.includeIntegration=true
//
// Reports land in lib/build/reports/kover/{html,xml}.
// ──────────────────────────────────────────────────────────────────────
val includeIntegrationInCoverage =
    providers.gradleProperty("kover.includeIntegration").map { it == "true" }.getOrElse(false)

kover {
    currentProject {
        instrumentation {
            if (!includeIntegrationInCoverage) {
                disabledForTestTasks.add("integrationTest")
            }
        }
    }
    reports {
        filters {
            excludes {
                // Generated code — protobuf + sqldelight produce thousands
                // of LOC we neither own nor need to cover.
                classes(
                    "com.obscura.kit.db.*",                  // sqldelight-generated
                    "obscura.*",                             // protobuf-generated (obscura.v2.*)
                    "client.*",                              // protobuf-generated
                    "xyz.obscura.server.contracts.*",        // protobuf-generated (server contract types)
                    "scenarios.*"                            // integration test classes
                )
            }
        }
    }
}

// Kover wires `koverXmlReport`/`koverHtmlReport` to depend on every Test
// task in the project. Without this, the unit-only report still triggers
// :lib:integrationTest (5 min). Strip that dependency unless the opt-in
// property is set.
if (!includeIntegrationInCoverage) {
    tasks.matching { it.name.startsWith("kover") && it.name.contains("Report") }
        .configureEach { setDependsOn(dependsOn.filterNot { it == integrationTest }) }
}
