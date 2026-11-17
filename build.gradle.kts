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

// ---------- Програма для Windows (jpackage) ----------

val jpackageDir = layout.buildDirectory.dir("jpackage")
val jdkTools = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }

/** Вхідні файли: застосунок і бібліотеки — в корінь (class path), JavaFX — у теку javafx (module path). */
val packageInput = tasks.register<Sync>("packageInput") {
    group = "distribution"
    description = "Готує jar-файли для jpackage"
    into(jpackageDir.map { it.dir("input") })
    from(tasks.jar)
    from(configurations.runtimeClasspath) {
        exclude("javafx-*")
    }
    from(configurations.runtimeClasspath) {
        include("javafx-*")
        into("javafx")
    }
}

/** build/jpackage/image/KinoBox — тека з KinoBox.exe і вбудованою Java. WiX не потрібен. */
val appImage = tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Створює KinoBox.exe з вбудованою Java (без інсталятора)"
    dependsOn(packageInput)
    val output = jpackageDir.map { it.dir("image") }
    doFirst {
        delete(output) // jpackage відмовляється писати в теку, де вже є попередня збірка
        executable = jdkTools.get().metadata.installationPath.file("bin/jpackage").asFile.absolutePath
    }
    args(
        "--type", "app-image",
        "--name", "KinoBox",
        "--app-version", version.toString(),
        "--vendor", "ba6yboy",
        "--icon", file("src/packaging/kinobox.ico").absolutePath,
        "--input", jpackageDir.get().dir("input").asFile.absolutePath,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "ua.kinobox.app.Launcher",
        "--dest", output.get().asFile.absolutePath,
        // Ті самі налаштування, що й для запуску з IntelliJ (див. tasks.withType<JavaExec> вище):
        // JavaFX — на module path, дозволи на нативний код і sun.misc.Unsafe
        "--java-options", "--module-path=\$APPDIR/javafx",
        "--java-options", "--add-modules=${javafxModules.joinToString(",")}",
        "--java-options", "--enable-native-access=javafx.graphics,javafx.media,ALL-UNNAMED",
        "--java-options", "--sun-misc-unsafe-memory-access=allow",
    )
}

/** build/jpackage/KinoBox-<версія>-windows.zip — портативна версія: розпакував і запустив. */
val portableZip = tasks.register<Zip>("portableZip") {
    group = "distribution"
    description = "Архів з KinoBox для запуску без встановлення"
    dependsOn(appImage)
    from(jpackageDir.map { it.dir("image") })
    archiveFileName = "KinoBox-$version-windows.zip"
    destinationDirectory = jpackageDir
}

/** build/jpackage/installer/KinoBox-<версія>.exe — інсталятор. Потрібен WiX Toolset. */
val installer = tasks.register<Exec>("installer") {
    group = "distribution"
    description = "Створює інсталятор KinoBox для Windows (потрібен WiX Toolset)"
    dependsOn(appImage)
    val output = jpackageDir.map { it.dir("installer") }
    doFirst {
        delete(output)
        executable = jdkTools.get().metadata.installationPath.file("bin/jpackage").asFile.absolutePath
    }
    args(
        "--type", "exe",
        // Беремо вже зібраний і перевірений образ — той самий KinoBox.exe, що працює зараз
        "--app-image", jpackageDir.get().dir("image/KinoBox").asFile.absolutePath,
        "--name", "KinoBox",
        "--app-version", version.toString(),
        "--vendor", "ba6yboy",
        // Лише латиниця: WiX 3 за замовчуванням пише інсталятор у кодуванні без кирилиці
        "--description", "KinoBox - movies and series with DualSense",
        "--dest", output.get().asFile.absolutePath,
        "--win-menu",                      // ярлик у меню «Пуск»
        "--win-menu-group", "KinoBox",     // … у групі KinoBox
        "--win-shortcut",                  // ярлик на робочому столі
        "--win-dir-chooser",               // дати вибрати теку встановлення
        "--win-per-user-install",          // для поточного користувача, без прав адміністратора
        // Постійний ідентифікатор продукту: нова версія замінює стару, а не ставиться поруч.
        // НЕ змінюйте його в майбутніх версіях.
        "--win-upgrade-uuid", "3d5e8a2c-7b14-4f9e-a6c1-52e09b8d7f43",
    )
}
