rootProject.name = "CS3xHermesAdult"

File(rootDir, ".").listFiles()!!
    .filter { it.isDirectory && File(it, "build.gradle.kts").exists() && it.name != "gradle" }
    .forEach { include(it.name) }
