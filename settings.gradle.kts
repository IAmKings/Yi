pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven { url = uri("https://maven.aliyun.com/repository/central") }; mavenCentral() }
}
rootProject.name = "Yi"
include(":app")
include(":llama-kt")
project(":llama-kt").projectDir = file("libs/llama.kt/llama-kt")
