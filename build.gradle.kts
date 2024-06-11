import me.champeau.jmh.JMHTask

plugins {
  `java-library`

  pmd
  idea

  `maven-publish`
  signing

  // https://plugins.gradle.org/plugin/io.github.gradle-nexus.publish-plugin
  id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
  // https://plugins.gradle.org/plugin/com.diffplug.spotless
  id("com.diffplug.spotless") version "6.25.0"
  // https://plugins.gradle.org/plugin/me.champeau.jmh
  id("me.champeau.jmh") version "0.7.2"
}

group = "de.tum.in"

version = "0.6.1"

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11

  withSourcesJar()
  withJavadocJar()
}

var defaultEncoding = "UTF-8"

tasks.withType<JavaCompile> { options.encoding = defaultEncoding }

tasks.withType<Javadoc> {
  options.encoding = defaultEncoding
  options {
    this as StandardJavadocDocletOptions
    addBooleanOption("Xdoclint:all,-missing", true)
  }
}

tasks.withType<Test> { systemProperty("file.encoding", "UTF-8") }

idea {
  module {
    isDownloadJavadoc = true
    isDownloadSources = true
  }
}

repositories { mavenCentral() }

spotless {
  java {
    licenseHeaderFile("${project.rootDir}/config/LICENCE_HEADER")
    palantirJavaFormat()
  }
  kotlinGradle { ktfmt() }
}

tasks.create("jmhRandom") {
  doFirst {
    jmh.includes.add("RandomBenchmark*")
    jmh.warmupIterations = 5
    jmh.iterations = 15
  }
  finalizedBy("jmh")
}

tasks.create("jmhSynthetic") {
  doFirst { jmh.includes.add("SyntheticBenchmark*") }
  finalizedBy("jmh")
}

tasks.withType<JMHTask> { includeTests.set(true) }

dependencies {
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")

  // https://mvnrepository.com/artifact/com.google.guava/guava
  testImplementation("com.google.guava:guava:33.2.1-jre")
  // https://mvnrepository.com/artifact/org.hamcrest/hamcrest
  testImplementation("org.hamcrest:hamcrest:2.2")
  // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
  testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")

  // https://mvnrepository.com/artifact/org.immutables/value
  compileOnly("org.immutables:value:2.10.1:annotations")
  annotationProcessor("org.immutables:value:2.10.1")
  // https://mvnrepository.com/artifact/org.openjdk.jmh/jmh-generator-annprocess
  jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.36")
}

tasks.test {
  useJUnitPlatform()
  minHeapSize = "2g"
  maxHeapSize = "16g"
}

// PMD
// https://docs.gradle.org/current/dsl/org.gradle.api.plugins.quality.Pmd.html

pmd {
  toolVersion = "7.2.0" // https://pmd.github.io/
  reportsDir = project.layout.buildDirectory.dir("reports/pmd").get().asFile
  ruleSetFiles = files("${project.rootDir}/config/pmd-rules.xml")
  ruleSets = listOf() // We specify all rules in rules.xml
  isConsoleOutput = false
  isIgnoreFailures = false
}

tasks.withType<Pmd> {
  reports {
    xml.required.set(false)
    html.required.set(true)
  }
}

// Deployment - run with -Prelease clean publishToSonatype closeAndReleaseSonatypeStagingRepository
// Authentication: sonatypeUsername+sonatypePassword in ~/.gradle/gradle.properties
if (project.hasProperty("release")) {
  publishing {
    publications {
      create<MavenPublication>("mavenJava") {
        from(project.components["java"])

        signing {
          useGpgCmd()
          sign(publishing.publications)
        }

        pom {
          name.set("JBDD")
          description.set("Pure Java implementation of (Binary) Decision Diagrams")
          url.set("https://github.com/incaseoftrouble/jbdd")

          licenses {
            license {
              name.set("The GNU General Public License, Version 3")
              url.set("https://www.gnu.org/licenses/gpl.txt")
            }
          }

          developers {
            developer {
              id.set("incaseoftrouble")
              name.set("Tobias Meggendorfer")
              email.set("tobias@meggendorfer.de")
              url.set("https://github.com/incaseoftrouble")
              timezone.set("Europe/Berlin")
            }
          }

          scm {
            connection.set("scm:git:https://github.com/incaseoftrouble/jbdd.git")
            developerConnection.set("scm:git:git@github.com:incaseoftrouble/jbdd.git")
            url.set("https://github.com/incaseoftrouble/jbdd")
          }
        }
      }
    }
  }

  nexusPublishing { repositories.sonatype() }
}
