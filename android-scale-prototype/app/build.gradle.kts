plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rommel.scaleprototype"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rommel.scaleprototype"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "2.4 (plan del día)"
    }

    buildTypes {
        // El APK debug (el que publica CI como artefacto) apunta al ambiente DEMO:
        // datos de prueba, sin riesgo de ensuciar producción durante las pruebas.
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://sfcalidemo.up.railway.app/\"")
        }
        // El APK release apunta a PRODUCCIÓN (datos reales).
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://sfcaliprod.up.railway.app/\"")
            // Firmado con la clave debug para que el release sea INSTALABLE sin manejar
            // secretos en el CI. Es suficiente para distribución interna por APK (no Play Store).
            // Para producción formal: generar un keystore propio y reemplazar esta línea por
            // un signingConfig dedicado (la firma propia hará que haya que reinstalar la app).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
