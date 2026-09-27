import java.util.Properties

plugins {
    id("com.android.application")
}

/* 后端地址是项目配置，不在 app 界面上展示也不给改：
   local.properties（这台机的、不提交）里写 api.base=http://192.168.31.5 就覆盖；
   没写就用 adb reverse 那条 127.0.0.1:8081——手机插着 USB 时开箱就连得上 */
val projectApiBase: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("api.base", "http://127.0.0.1:8081")

android {
    namespace = "com.chat.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chat.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "API_BASE", "\"$projectApiBase\"")
    }

    // AGP8 起 BuildConfig 默认关掉，不显式打开就读不到 API_BASE
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 只写 source/target 17，不要 jvmToolchain(17)：那要求本机真装一个 JDK 17，
    // 而这台机器只有 21，Gradle 找不到就直接失败（且没配 toolchain 下载源）
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 单测里要读环境变量（后端地址、测试账号），Test 任务的 JVM 不自动带过来，得显式传。
// 只传非空的：把空串当属性值传下去， getProperty(key, 默认值) 会拿到这个空串而不是默认值
tasks.withType<Test>().configureEach {
    listOf("CHAT_API_BASE", "CHAT_USER", "CHAT_PW").forEach { k ->
        System.getenv(k)?.takeIf { it.isNotEmpty() }?.let { systemProperty(k, it) }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("junit:junit:4.13.2")
    // 单测跑在 JVM 上，Android 自带的那份 org.json 在单测里是空壳（一调就 "not mocked"），
    // 所以这里要单独给一份真的
    testImplementation("org.json:json:20240303")
}
