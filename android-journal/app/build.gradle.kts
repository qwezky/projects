plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.example.journal"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.example.journal"
        minSdk = 26
        targetSdk = 36
        // 版本号必须能看出来：同一个文件名装出旧包的坑已经踩过一次
        // （服务端换了包、手机从"下载"目录点开同名旧文件 → 装的还是旧的）。
        // 除文件名带版本+哈希，界面标题栏也会把这个号打出来，装完一眼可核对。
        versionCode = 3
        versionName = "1.2"
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
      // 打开是为了拿到 BuildConfig.VERSION_NAME：界面标题栏要把版本号打出来
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
// testDebugUnitTest 会被判定 UP-TO-DATE，直接复用上一次的结果，会让人以为
// "改了参数还是同样的结论"。这里把这些变量登记成输入。
tasks.withType<Test>().configureEach {
  listOf(
    "E2E_KEY", "E2E_USER", "E2E_HOST", "E2E_PORT", "E2E_WS_URL", "E2E_ARCHIVE_DIR",
  ).forEach { key -> inputs.property(key, System.getenv(key) ?: "") }

  // E2E 用例在"未授权"时会打印该做什么；吞掉 stdout 的话只看到 skip。
  testLogging {
    showStandardStreams = true
    events("skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

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

  // Local tests: jUnit + coroutines
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // SSH / SFTP：手机 → 服务器（存档上传 + 触发归档 + 读待办索引）
  implementation(libs.jsch)
  implementation(libs.java.websocket)
}
