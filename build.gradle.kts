plugins {
    application
    java
    id ("com.github.johnrengelman.shadow") version "8.1.1"
}

application.mainClass = "Main"
group = "org.ToastiCodingStuff"
version = "1.0-SNAPSHOT"

val jdaVersion = "6.2.0"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.vrchatapi:vrchatapi-java:main-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.github.bastiaanjansen:otp-java:2.0.3")
    implementation("net.dv8tion:JDA:$jdaVersion")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")
    implementation("io.github.cdimascio:dotenv-java:3.0.0")
}

tasks.test {
    useJUnitPlatform()
}