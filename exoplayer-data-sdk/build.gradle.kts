import java.util.Properties
import kotlin.apply

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id ("maven-publish")
}

android {
    namespace = "io.fastpix.exoplayer_data_sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Exo Player
    api(libs.exoplayer.core)
    api (libs.exoplayer)
    api(libs.exoplayer.hls)
    api(libs.exoplayer.smoothstreaming)
    api(libs.core)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

publishing {
    publications {
        create<MavenPublication>("bar") {
            groupId = "io.fastpix.data"
            artifactId = "exoplayer"
            version = "1.1.0"
            artifact("${buildDir}/outputs/aar/exoplayer-data-sdk-release.aar")

            pom.withXml {
                val dependenciesNode = asNode().appendNode("dependencies")
                configurations.getByName("api").dependencies.forEach { dependency ->
                    if (dependency.group != null && dependency.version != null) {
                        val dependencyNode = dependenciesNode.appendNode("dependency")
                        dependencyNode.appendNode("groupId", dependency.group)
                        dependencyNode.appendNode("artifactId", dependency.name)
                        dependencyNode.appendNode("version", dependency.version)
                        dependencyNode.appendNode("scope", "compile")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GithubPackages"
            url = uri("https://maven.pkg.github.com/FastPix/android-data-exoplayer-sdk")
            credentials {
                username = localProperties.getProperty("lpr.user") ?: System.getenv("GITHUB_USER")
                password = localProperties.getProperty("lpr.key") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}