plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.example.transfer"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.example.transfer"
        minSdk = 26
        targetSdk = 36
        // 版本号必须能看出来：同一个文件名装出旧包的坑已经踩过一次
        // （服务端换了包、手机从"下载"目录点开同名旧文件 → 装的还是旧的）。
        // 除了文件名带版本+哈希，界面标题栏也会把这个号打出来，装完一眼可核对。
        versionCode = 17
        versionName = "2.6"
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
      aidl = false
      // 打开是为了拿到 BuildConfig.VERSION_NAME：界面标题栏要把版本号打出来，
      // 装到手机上能一眼确认"这次装的确实是新包"
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests {
        // 单元测试跑在普通 JVM 上，android.util.Log 只是桩。
        // 打开这个开关让桩方法返回默认值（否则会抛 "not mocked"）。
        isReturnDefaultValues = true
      }
    }
}

// 踩过的坑：**环境变量不是 Gradle task 的输入** —— 只改 E2E_* 环境变量时
// testDebugUnitTest 会被判定 UP-TO-DATE，直接复用上一次的结果（耗时一模一样），
// 会让人以为"改了参数还是同样的结论"。这里把这些变量登记成输入。
tasks.withType<Test>().configureEach {
  listOf(
    "E2E_KEY", "E2E_USER", "E2E_WS_URL", "E2E_REMOTE_DIR", "E2E_TRANSPORT",
    "E2E_HOST", "E2E_PORT", "E2E_WS_HOST", "E2E_VERIFY_BYTES", "E2E_FORM_BYTES",
    "E2E_STREAM_BYTES", "E2E_LOCAL_BRIDGE_BYTES", "E2E_LO_BYTES", "E2E_TUN_BYTES",
  ).forEach { key -> inputs.property(key, System.getenv(key) ?: "") }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // SSH / SFTP（手机 → 服务器上传，以及用 SFTP 取代局域网 HTTP 服务）
  implementation(libs.jsch)
  implementation(libs.java.websocket)
}
