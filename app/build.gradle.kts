plugins {
    id("com.android.application")
}

android {
    namespace = "com.wakedrive.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wakedrive.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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
        viewBinding = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    androidResources {
        noCompress += listOf("onnx", "task")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // MediaPipe Face Landmarker (face landmarks + blendshapes)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // ONNX Runtime for YOLO11n / YOLOv8-pose / 6DRepNet
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // HTTP client for the existing Python backend
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
