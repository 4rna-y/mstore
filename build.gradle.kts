plugins {
    java
    application
}

group = "io.github.mstore"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // KV のバックエンド。サーバー不要でファイル1つに収まるので、
    // MC サーバーの隣に置く常駐アプリの永続化先として都合がよい。
    // slf4j-api は optional 依存なので引きずってこない。
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.mstore.Mstore"
    applicationName = "mstore"
    // sqlite-jdbc がネイティブライブラリを読む。明示しないと JVM が警告を出す。
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Paper 26.x が動く JDK と同じターゲットに合わせる。
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
