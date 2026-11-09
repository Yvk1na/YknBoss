import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

plugins {
    java
}

group = "cn.yknboss"
version = "2.0.18"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("dev.aurelium:auraskills-api:2.3.12")
    compileOnly(files("libs/bettermodel-3.4.1-paper.jar"))
    compileOnly(files("libs/Multiverse-Core-5.7.3.jar"))
}

val proguard by configurations.creating
val yamlValidator by configurations.creating

dependencies {
    proguard("com.guardsquare:proguard-base:7.9.1")
    yamlValidator("org.yaml:snakeyaml:2.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-proc:none")
    options.compilerArgs.add("-Xlint:deprecation")
}

val validateConfigYaml by tasks.registering {
    group = "verification"
    description = "解析并校验内置 config.yml，阻止无效 YAML 进入发布包"
    val configFile = layout.projectDirectory.file("src/main/resources/config.yml")
    inputs.file(configFile)
    doLast {
        val urls = yamlValidator.files.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            try {
                val yamlClass = loader.loadClass("org.yaml.snakeyaml.Yaml")
                val yaml = yamlClass.getConstructor().newInstance()
                configFile.asFile.inputStream().use { input ->
                    val parsed = yamlClass.getMethod("load", InputStream::class.java)
                        .invoke(yaml, input)
                    if (parsed !is Map<*, *>) throw GradleException("config.yml 根节点必须是映射")
                }
            } catch (exception: InvocationTargetException) {
                throw GradleException("config.yml YAML 解析失败", exception.targetException)
            }
        }
    }
}

tasks.processResources {
    dependsOn(validateConfigYaml)
    filteringCharset = "UTF-8"
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveBaseName = "YknBoss"
}

val obfuscatedJar = layout.buildDirectory.file("libs/YknBoss-${project.version}-obfuscated.jar")

tasks.register<JavaExec>("obfuscate") {
    group = "build"
    description = "使用 ProGuard 对插件进行仅名称混淆并重新打包"
    dependsOn(tasks.jar)
    classpath = proguard
    mainClass = "proguard.ProGuard"
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.file(layout.projectDirectory.file("proguard.pro"))
    outputs.file(obfuscatedJar)

    doFirst {
        val reportsDirectory = layout.buildDirectory.dir("obfuscation").get().asFile
        reportsDirectory.mkdirs()

        val arguments = mutableListOf(
            "@${layout.projectDirectory.file("proguard.pro").asFile.absolutePath}",
            "-injars",
            tasks.jar.get().archiveFile.get().asFile.absolutePath,
            "-outjars",
            obfuscatedJar.get().asFile.absolutePath,
            "-printmapping",
            reportsDirectory.resolve("YknBoss-${project.version}-mapping.txt").absolutePath,
            "-printseeds",
            reportsDirectory.resolve("YknBoss-${project.version}-seeds.txt").absolutePath
        )

        fileTree("${System.getProperty("java.home")}/jmods") {
            include("*.jmod")
        }.files.sortedBy { it.name }.forEach { library ->
            arguments += "-libraryjars"
            arguments += "${library.absolutePath}(!**.jar;!module-info.class)"
        }

        sourceSets.main.get().compileClasspath.files.sortedBy { it.name }.forEach { library ->
            arguments += "-libraryjars"
            arguments += library.absolutePath
        }

        setArgs(arguments)
    }
}

val craftEnginePack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 CraftEngine 资源包"
    archiveFileName = "YknBoss-CraftEngine-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/craftengine") {
        into("yknboss")
    }
    from("packs/shared/models") {
        into("yknboss/resourcepack/assets/yknboss/models")
    }
    from("packs/shared/textures") {
        into("yknboss/resourcepack/assets/yknboss/textures")
    }
    from("packs/shared/items") {
        into("yknboss/resourcepack/assets/yknboss/items")
    }
    from("packs/shared/licenses") {
        into("yknboss/licenses")
    }
}

val itemsAdderPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 ItemsAdder 资源包"
    archiveFileName = "YknBoss-ItemsAdder-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/itemsadder") {
        into("contents/yknboss")
    }
    from("packs/shared/models") {
        into("contents/yknboss/models")
    }
    from("packs/shared/textures") {
        into("contents/yknboss/textures")
    }
    from("packs/shared/items") {
        into("contents/yknboss/items")
    }
    from("packs/shared/licenses") {
        into("contents/yknboss/licenses")
    }
}

val bakeEndsunModel by tasks.registering(Exec::class) {
    group = "distribution"
    description = "将阿努赫 1.5 倍尺寸烘焙进 BetterModel 模型"
    val sourceModel = layout.projectDirectory.file("modle/endsun/bettermodel/endsun_anuhe.bbmodel")
    val outputModel = layout.projectDirectory.file("packs/bettermodel/models/endsun_anuhe.bbmodel")
    val bakeScript = layout.projectDirectory.file("scripts/bake_endsun_model.ps1")
    inputs.file(sourceModel)
    inputs.file(bakeScript)
    inputs.property("bakeScale", 1.5)
    outputs.file(outputModel)
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", bakeScript.asFile.absolutePath,
        "-SourcePath", sourceModel.asFile.absolutePath,
        "-OutputPath", outputModel.asFile.absolutePath,
        "-BakeScale", "1.5"
    )
}

val betterModelPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "打包 BetterModel Boss 与特殊物品模型"
    dependsOn(bakeEndsunModel)
    archiveFileName = "YknBoss-BetterModel-Pack-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("packs/bettermodel") {
        exclude("models/ancient_codex.bbmodel")
        exclude("models/ancient_codex_rings.bbmodel")
    }
    from("modle/end_witch/bettermodel/end_witch.bbmodel") {
        into("models")
        rename { "end_witch.bbmodel" }
    }
    from("packs/shared/licenses") {
        into("licenses")
    }
}

tasks.assemble {
    dependsOn(craftEnginePack, itemsAdderPack, betterModelPack)
}
