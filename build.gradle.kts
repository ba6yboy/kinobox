plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "ua.kinobox"
version = "0.1.0"

// JDK 25 — поточний LTS. Щоб зібрати під JDK 27, змініть число нижче
// і javafx.version на "27.0.1".
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val javafxModules = listOf("javafx.controls", "javafx.media")

javafx {
    version = "25.0.5"
    modules = javafxModules
}

dependencies {
    // SDL2 під капотом: нативна підтримка DualSense (USB і Bluetooth) на Windows
    implementation("com.badlogicgames.jamepad:jamepad:2.30.0.0")

    // Відтворення відео через libVLC (потрібен встановлений VLC 3.x, 64-bit)
    implementation("uk.co.caprica:vlcj:4.12.1")
    implementation("uk.co.caprica:vlcj-javafx:1.2.1")

    // Розбір JSON-відповідей TMDB
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "ua.kinobox.app.Launcher"
}

/*
 * Однакові налаштування для БУДЬ-ЯКОГО запуску: і `gradlew run`, і зеленої стрілки
 * біля main() в IntelliJ (вона створює власну JavaExec-задачу і не бере налаштувань `run`).
 *  - JavaFX кладемо на module path → зникає "Unsupported JavaFX configuration";
 *  - дозволяємо нативний доступ (JavaFX, Jamepad/SDL) → зникають "restricted method";
 *  - UTF-8 для консолі → кирилиця в логах на Windows не перетворюється на "����".
 */
tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "--enable-native-access=javafx.graphics,javafx.media,ALL-UNNAMED",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "--sun-misc-unsafe-memory-access=allow", // vlcj використовує sun.misc.Unsafe
    )
    // для `run` module path уже налаштовує плагін org.openjfx.javafxplugin
    if (name != "run") doFirst {
        val (fxJars, otherJars) = classpath.files.partition { it.name.startsWith("javafx-") }
        if (fxJars.isNotEmpty() && "--module-path" !in allJvmArgs) {
            classpath = files(otherJars)
            jvmArgs(
                "--module-path", fxJars.joinToString(File.pathSeparator),
                "--add-modules", javafxModules.joinToString(","),
            )
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
