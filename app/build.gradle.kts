import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.play.publisher)
}

val releaseKeystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword,
).all { !it.isNullOrBlank() }

// Service-account JSON for the Google Play Publisher API. Same env-var pattern
// as the keystore — keep the key file outside the repo. If unset, the plugin
// falls back to ADC / its own ANDROID_PUBLISHER_CREDENTIALS env (JSON contents).
val publisherCredentialsPath: String? = System.getenv("ANDROID_PUBLISHER_CREDENTIALS_PATH")

android {
    namespace = "nu.bacher.memos"
    compileSdk = 36

    defaultConfig {
        applicationId = "nu.bacher.memos"
        minSdk = 34
        targetSdk = 36
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.markdown.renderer.m3)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)

    implementation(libs.androidx.paging.compose)
}

play {
    track.set("internal")
    releaseStatus.set(ReleaseStatus.COMPLETED)
    defaultToAppBundles.set(true)
    publisherCredentialsPath?.let { serviceAccountCredentials.set(file(it)) }
}

/**
 * Rasterizes the adaptive launcher icon (background color + foreground
 * vector) to a 512x512 PNG for the Play Store listing. Re-run whenever
 * `ic_launcher_foreground.xml` or `ic_launcher_background` change.
 */
tasks.register("generatePlayIcon") {
    group = "play store"
    description = "Rasterize the adaptive launcher icon to a 512x512 PNG for the Play Store listing."

    val outFile = layout.projectDirectory
        .file("src/main/play/listings/en-US/graphics/icon/icon.png").asFile
    outputs.file(outFile)
    // The source-of-truth files. If they change, Gradle re-runs this task.
    inputs.files(
        "src/main/res/drawable/ic_launcher_foreground.xml",
        "src/main/res/values/colors.xml",
    )

    doLast {
        val size = 512
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g.setRenderingHint(
                RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE,
            )

            // Background — matches @color/ic_launcher_background (#2D5876).
            g.color = Color(0x2D, 0x58, 0x76)
            g.fillRect(0, 0, size, size)

            // Foreground — mirror of ic_launcher_foreground.xml's path,
            // scaled from the 108-unit viewport to 512 px.
            val scale = size.toDouble() / 108.0
            g.transform = AffineTransform.getScaleInstance(scale, scale)
            g.color = Color.WHITE
            g.stroke = BasicStroke(
                9f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

            val path = Path2D.Float().apply {
                // Left hump
                moveTo(38f, 72f); lineTo(38f, 50f)
                quadTo(38f, 38f, 46f, 38f); quadTo(54f, 38f, 54f, 50f)
                lineTo(54f, 72f)
                // Right hump
                moveTo(54f, 72f); lineTo(54f, 50f)
                quadTo(54f, 38f, 62f, 38f); quadTo(70f, 38f, 70f, 50f)
                lineTo(70f, 72f)
            }
            g.draw(path)
        } finally {
            g.dispose()
        }

        outFile.parentFile.mkdirs()
        ImageIO.write(image, "PNG", outFile)
        logger.lifecycle("Wrote ${outFile.absolutePath}")
    }
}
