import io.sentry.android.gradle.sourcecontext.UploadSourceBundleTask
import io.sentry.android.gradle.tasks.SentryUploadNativeSymbolsTask
import io.sentry.android.gradle.tasks.SentryUploadProguardMappingsTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("io.sentry.android.gradle") version "6.20.0"
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Machine-specific settings from the untracked local.properties (git-ignored).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

/**
 * Non-empty value of a Gradle property, else an environment variable, else the
 * same key in local.properties.
 */
fun configValue(key: String, environmentVariable: String): Provider<String> =
    providers.gradleProperty(key)
        .orElse(providers.environmentVariable(environmentVariable))
        .orElse(providers.provider { localProperties.getProperty(key) })
        .map { it.trim() }
        .filter { it.isNotEmpty() }

// Sentry is opt-in per build: without a DSN the gplay flavor hides error
// reporting completely. Set `sentry.dsn` in local.properties, as a Gradle
// property, or as the SENTRY_DSN environment variable.
val sentryDsn: String = configValue("sentry.dsn", "SENTRY_DSN").getOrElse("").let { dsn ->
    // Same pattern as CrashReportPolicy.isValidDsn, which also guards at runtime.
    val valid = Regex("^https?://[0-9a-fA-F]{32}@[A-Za-z0-9.-]+(:[0-9]+)?(/[^/\\s]+)*/[0-9]+$")
    if (dsn.isEmpty() || valid.matches(dsn)) {
        dsn
    } else {
        logger.warn("w: sentry.dsn is not a valid Sentry DSN (https://<public key>@<host>/<project id>); error reporting stays off in this build")
        ""
    }
}

android {
    namespace = "com.saschl.cameragps"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    // Reproducible builds: native libs (libsqliteJni.so from sqlite-bundled) are
    // stripped with the NDK's llvm-strip, so CI and F-Droid must use the SAME
    // NDK. Keep in sync with `ndk:` in the fdroiddata
    ndkVersion = "29.0.14206865"

    androidResources {
        generateLocaleConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }


    defaultConfig {
        applicationId = "com.saschl.cameragps"
        minSdk = 26
        targetSdk = 37
        versionCode = 163
        versionName = "v1.6.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // F-Droid builds from source without the keystore or signing secrets — release
    // must fall back to an unsigned APK instead of failing. CI passes the secrets
    // as (possibly empty) environment variables, so blank values count as missing.
    val releaseKeystore = file(System.getenv("SIGNING_KEYSTORE_PATH") ?: "keystore.jks")
    val releaseSigningAvailable = releaseKeystore.isFile && releaseKeystore.length() > 0 &&
            !System.getenv("SIGNING_KEY_ALIAS").isNullOrBlank()

    signingConfigs {
        create("release") {
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            storeFile = releaseKeystore
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig =
                if (releaseSigningAvailable) signingConfigs.getByName("release") else null

        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Play Store build: GMS fused location, Play in-app review, Sentry.
        create("gplay") {
            dimension = "distribution"
            isDefault = true
            // Crash reporting is only offered when a Sentry DSN is configured.
            buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
        // F-Droid build: no GMS, no Play libraries, no Sentry.
        create("foss") {
            dimension = "distribution"
        }
    }

    dependenciesInfo {
        // The dependency-info block is an encrypted blob only Google Play can read;
        // F-Droid rejects APKs that contain it.
        includeInApk = false
        includeInBundle = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Translations are community-maintained on Weblate and partial by design.
        warning += "MissingTranslation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }

}

dependencies {

    implementation(project(":sharednew"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.timber)
    implementation(libs.logging)
    //implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.accompanist.permissions)

    // Proprietary bits stay out of the foss (F-Droid) flavor.
    "gplayImplementation"(libs.google.play.services.location)
    "gplayImplementation"(libs.review)
    "gplayImplementation"(libs.review.ktx)
    "gplayImplementation"(libs.sentry.android)
    "gplayImplementation"(libs.sentry.android.timber)

    implementation(libs.material)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.compose.runtime.livedata)
    //implementation(libs.betterypermissionhelper)

    // Room database dependencies
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.appcompat)

    implementation(compose.components.resources)

}


// Uploads (ProGuard mappings, source context, native symbols) need a Sentry
// auth token: `sentry.authToken` (local.properties or Gradle property),
// SENTRY_AUTH_TOKEN, or a sentry.properties file with auth.token.
// Without one, or with -PdisableSentryUpload=true, they are turned off so
// release builds work without a Sentry account.
val sentryUploadsDisabled = providers.gradleProperty("disableSentryUpload")
    .map { it.toBoolean() }
    .getOrElse(false)
val sentryAuthToken = configValue("sentry.authToken", "SENTRY_AUTH_TOKEN")
val sentryAuthAvailable = sentryAuthToken.isPresent ||
        listOf(file("sentry.properties"), rootProject.file("sentry.properties")).any { it.isFile }
val sentryUploadsEnabled = !sentryUploadsDisabled && sentryAuthAvailable

// Sentry's --no-upload source task still requires authentication; skip the tasks entirely.
tasks.configureEach {
    if (this is SentryUploadProguardMappingsTask ||
        this is UploadSourceBundleTask ||
        this is SentryUploadNativeSymbolsTask
    ) {
        onlyIf("Sentry uploads are enabled") { sentryUploadsEnabled }
    }
}

sentry {
    // Your own Sentry organization and project (`sentry.org` / `sentry.project` in
    // local.properties or as Gradle properties, or SENTRY_ORG / SENTRY_PROJECT);
    // unset values fall back to sentry.properties.
    org.set(configValue("sentry.org", "SENTRY_ORG"))
    projectName.set(configValue("sentry.project", "SENTRY_PROJECT"))
    authToken.set(sentryAuthToken)

    // Mapping and source-context uploads show deobfuscated stack traces with source
    // in Sentry. Bundling sources needs an org even without uploading, so both are
    // only set up when uploads can actually run.
    autoUploadProguardMapping.set(sentryUploadsEnabled)
    includeSourceContext.set(sentryUploadsEnabled)

    // The foss flavor ships without Sentry; the SDK is added explicitly via
    // gplayImplementation instead of auto-installation (which is variant-blind).
    ignoredFlavors.set(setOf("foss"))
    autoInstallation {
        enabled.set(false)
    }

    // Don't report build-plugin telemetry to Sentry.
    telemetry.set(false)
}
