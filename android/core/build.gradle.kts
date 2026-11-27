import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    // WebRTC de escritorio solo para probar NetherNet en el ordenador (en Android se usa libwebrtc).
    testImplementation("dev.onvoid.webrtc:webrtc-java:1.0.2")
    testRuntimeOnly("dev.onvoid.webrtc:webrtc-java:1.0.2:linux-x86_64")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// Imprime el classpath de pruebas (para lanzar a mano las pruebas de extremo a extremo).
tasks.register("printTestClasspath") {
    val cp = sourceSets.test.get().runtimeClasspath
    doLast { println(cp.asPath) }
}
