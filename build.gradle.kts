plugins {
    `java-library`
    id("com.android.library") version "8.7.3" apply false
    kotlin("jvm") version "2.1.0"
    kotlin("android") version "2.1.0" apply false
    id("org.jetbrains.dokka") version "2.2.0"
    `maven-publish`
    signing
}

group = "ai.velr"
version = providers.gradleProperty("velrVersion")
    .orElse(providers.environmentVariable("VELR_VERSION"))
    .orElse("0.1.0-SNAPSHOT")
    .get()

val centralBundleDir = providers.gradleProperty("centralBundleDir")
    .orElse(providers.environmentVariable("CENTRAL_BUNDLE_DIR"))
    .orElse(layout.buildDirectory.dir("central-bundle-repository").map { it.asFile.absolutePath })

fun org.gradle.api.publish.maven.MavenPom.velrPom(
    displayName: String,
    displayDescription: String,
) {
    name.set(displayName)
    description.set(displayDescription)
    url.set("https://velr.ai")
    licenses {
        license {
            name.set("MIT License")
            url.set("https://github.com/velr-ai/velr-kotlin-driver/blob/main/LICENSE")
            distribution.set("repo")
        }
        license {
            name.set("Velr Runtime Binary Redistribution License")
            url.set("https://github.com/velr-ai/velr-kotlin-driver/blob/main/LICENSE.runtime")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("velr")
            name.set("Velr.ai")
            organization.set("Velr Tech AB")
            organizationUrl.set("https://velr.ai")
        }
    }
    scm {
        connection.set("scm:git:https://github.com/velr-ai/velr-kotlin-driver.git")
        developerConnection.set("scm:git:ssh://git@github.com/velr-ai/velr-kotlin-driver.git")
        url.set("https://github.com/velr-ai/velr-kotlin-driver")
        tag.set("v${project.version}")
    }
}

val signingKey = providers.gradleProperty("signingInMemoryKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
val signingTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("publish", ignoreCase = true) || it.contains("sign", ignoreCase = true)
}

java {
    withSourcesJar()
}

val javadocJar by tasks.registering(Jar::class) {
    description = "A documentation JAR containing Dokka HTML for the Java and Kotlin API."
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

dokka {
    dokkaSourceSets.configureEach {
        perPackageOption {
            matchingRegex.set("ai\\.velr\\.internal(\\..*)?")
            suppress.set(true)
        }
    }
}

tasks.named("assemble") {
    dependsOn(javadocJar)
}

dependencies {
    testImplementation("org.apache.arrow:arrow-vector:18.3.0")
    testImplementation("org.apache.arrow:arrow-c-data:18.3.0")
    testRuntimeOnly("org.apache.arrow:arrow-memory-netty:18.3.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<Javadoc>().configureEach {
    exclude("ai/velr/internal/**")
    (options as org.gradle.external.javadoc.StandardJavadocDocletOptions)
        .addStringOption("Xdoclint:all", "-quiet")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

val arrowJvmArgs = listOf(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

val smokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the Velr Java driver smoke tests through the bundled native library."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.velr.SmokeTest")
    jvmArgs(arrowJvmArgs)
    dependsOn(tasks.testClasses)
}

val kotlinSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the Velr Kotlin driver smoke tests through the bundled native library."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.velr.KotlinSmokeTestKt")
    jvmArgs(arrowJvmArgs)
    dependsOn(tasks.testClasses)
}

tasks.withType<Test>().configureEach {
    jvmArgs(arrowJvmArgs)
}

tasks.named("check") {
    dependsOn(smokeTest)
    dependsOn(kotlinSmokeTest)
}

publishing {
    repositories {
        maven {
            name = "CentralBundle"
            url = uri(centralBundleDir.get())
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(javadocJar)
            pom {
                velrPom(
                    "Velr Kotlin Driver",
                    "Kotlin bindings for the embedded Velr graph database",
                )
            }
        }
    }
}

signing {
    isRequired = !version.toString().endsWith("-SNAPSHOT") && signingTaskRequested
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
    }
    sign(publishing.publications)
}
