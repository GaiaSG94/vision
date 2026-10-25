import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key) ?: ""

/** Valeur git, chaîne vide si le binaire git est absent (build hors dépôt). */
fun gitValue(vararg args: String): String = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output
}.getOrDefault("")

/** Date du commit HEAD (secondes) : croissante, donc utilisable comme versionCode. */
val gitCommitSeconds: Int = System.getenv("SAM_VERSION_CODE")?.toIntOrNull()
    ?: gitValue("log", "-1", "--format=%ct").toIntOrNull()
    ?: 1

/** Nombre de commits et empreinte courte : lisibles dans « À propos » et les logs. */
val gitCommitCount: Int = gitValue("rev-list", "--count", "HEAD").toIntOrNull() ?: 0
val gitShortSha: String = gitValue("rev-parse", "--short=7", "HEAD").ifBlank { "nogit" }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.sam3vision.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sam3vision.app"
        minSdk = 26
        targetSdk = 36
        // versionCode = date du commit HEAD : Android REFUSE d'installer une build
        // plus ancienne (INSTALL_FAILED_VERSION_DOWNGRADE), donc un APK périmé ne peut
        // plus écraser la version du téléphone (règle user du 2026-10-25). Une PR bâtie
        // sur un master plus vieux est rejetée, c'est voulu : scripts/install-phone.sh
        // explique quoi faire (rebuilder sur origin/master).
        versionCode = gitCommitSeconds
        versionName = "1.0.$gitCommitCount-$gitShortSha"

        // Valeurs lues depuis local.properties (jamais commité) ; vides si absent.
        buildConfigField("String", "LLM_BASE_URL", "\"${localProp("llm.base.url")}\"")
        buildConfigField("String", "LLM_API_KEY", "\"${localProp("llm.api.key")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${localProp("llm.model")}\"")
        // Voix ElevenLabs : défauts de debug, la saisie dans Réglages prime.
        buildConfigField("String", "TTS_API_KEY", "\"${localProp("tts.api.key")}\"")
        buildConfigField("String", "TTS_VOICE_ID", "\"${localProp("tts.voice.id")}\"")
        // Tap court : endpoint vidéo SAM 3.1 (laisser vide → annonce « Failed », aucun envoi).
        buildConfigField("String", "SAM31_ENDPOINT", "\"${localProp("sam31.endpoint")}\"")
        // Direct : base de l'app Modal sam3-eu-a100 (/live).
        buildConfigField("String", "SAM_BASE_URL", "\"${localProp("sam.base.url")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room : le schéma JSON est exporté (artifact du module de nettoyage), jamais régénéré à la main.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation("com.google.guava:guava:33.3.1-android")
    // Encodage MP3 pur Java (port LAME) : la voix du micro est archivée en cache.
    implementation(libs.jump3r)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
}
