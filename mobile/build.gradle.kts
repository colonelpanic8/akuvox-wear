plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ivanmalison.akuvoxwear.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ivanmalison.akuvoxwear"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "GradleDependency"
    }

}

dependencies {
    implementation(libs.play.services.wearable) {
        isTransitive = false
    }
    compileOnly(libs.androidx.core)
    compileOnly(libs.play.services.base.compat)
    compileOnly(libs.play.services.basement.compat)
    compileOnly(libs.play.services.tasks.compat)
}
