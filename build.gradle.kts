plugins {
    id("java")
    id("fabric-loom") version "1.7-SNAPSHOT"
}

group = "com.bettertrades"
version = "0.1.0-mc${property("minecraftVersion")}"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.nucleoid.xyz/")                          // sgui, polymer
    maven("https://maven.architectury.dev/")                      // architectury, a Cobblemon dependency
    maven("https://maven.impactdev.net/repository/development/")  // Cobblemon
    mavenCentral()
}

dependencies {
    minecraft("net.minecraft:minecraft:${property("minecraftVersion")}")
    mappings("net.fabricmc:yarn:${property("yarnMappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loaderVersion")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabricApiVersion")}")

    modImplementation("eu.pb4:sgui:${property("sguiVersion")}")
    include("eu.pb4:sgui:${property("sguiVersion")}")

    modImplementation("eu.pb4:polymer-core:${property("polymerVersion")}")
    modImplementation("eu.pb4:polymer-resource-pack:${property("polymerVersion")}")
    modImplementation("eu.pb4:polymer-autohost:${property("polymerVersion")}")
    include("eu.pb4:polymer-core:${property("polymerVersion")}")
    include("eu.pb4:polymer-resource-pack:${property("polymerVersion")}")
    include("eu.pb4:polymer-autohost:${property("polymerVersion")}")

    // Cobblemon is already on the server: bundling it in the jar would mean two copies.
    modCompileOnly("com.cobblemon:fabric:${property("cobblemonVersion")}")
    modCompileOnly("dev.architectury:architectury-fabric:13.0.8")
    modCompileOnly("net.fabricmc:fabric-language-kotlin:1.12.3+kotlin.2.0.21")

    // When LuckPerms is missing, the commands fall back to the OP level.
    compileOnly("net.luckperms:api:5.4")

    // Impactor Economy: the server's money. compileOnly and a soft dependency, so without Impactor
    // the mod still starts with the money side switched off. Not transitive: Impactor's own
    // dependencies would drag in artifacts that are not needed to compile.
    compileOnly("net.impactdev.impactor.api:economy:5.3.5") { isTransitive = false }
    compileOnly("net.impactdev.impactor.api:core:5.3.5") { isTransitive = false }

    implementation("org.xerial:sqlite-jdbc:${property("sqliteVersion")}")
    include("org.xerial:sqlite-jdbc:${property("sqliteVersion")}")

    // mariadb-java-client speaks the MySQL protocol, so it covers both MariaDB and MySQL.
    implementation("org.mariadb.jdbc:mariadb-java-client:${property("mariadbVersion")}")
    include("org.mariadb.jdbc:mariadb-java-client:${property("mariadbVersion")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
