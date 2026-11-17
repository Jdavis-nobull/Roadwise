// BRouter routing engine (MIT, https://github.com/abrensch/brouter), vendored for offline routing.
plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:none")
    options.isWarnings = false
}
