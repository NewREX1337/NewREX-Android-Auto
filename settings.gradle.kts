pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // Sadece libadb-android ve onun spake2 parçası buradan gelir
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MuntashirAkon"); includeGroup("com.github.MuntashirAkon.spake2-java") }
        }
    }
}
rootProject.name = "NewREX"
include(":app")
