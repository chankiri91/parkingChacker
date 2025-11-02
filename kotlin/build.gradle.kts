plugins {
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    application
}

group = "com.parking"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // HTTPクライアント
    implementation("io.ktor:ktor-client-core:2.3.5")
    implementation("io.ktor:ktor-client-cio:2.3.5")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    
    // HTMLパーサー
    implementation("org.jsoup:jsoup:1.17.1")
    
    // JSON処理
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // ログ
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    
    // メール送信
    implementation("com.sun.mail:javax.mail:1.6.2")
    
    // Coroutines（suspend fun main用）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

application {
    mainClass.set("com.parking.ParkingCheckerKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

