plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.astromg01.monetizei.server.MonetizeiHttpServerKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("junit:junit:4.13.2")
}
