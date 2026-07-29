plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-model"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
