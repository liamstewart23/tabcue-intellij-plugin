pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download the JDK the build asks for. Without a resolver the build depends on a
    // JDK already being installed — and the only one on this machine is PhpStorm's bundled
    // runtime, which the IDE upgraded from Java 21 to 25 mid-development and broke the build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tabcue"
