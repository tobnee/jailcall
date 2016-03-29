import sbt.Keys._

lazy val root = project.in(file("."))
    .settings(scalaSettings)
    .settings(publishArtifact := false)

lazy val akkaVersion = "2.4.2"
lazy val akkaVersion23 = "2.3.14"

lazy val core = project.in(file("core"))
  .settings(
    libraryDependencies ++=
      baseDependencies ++ akkaDepenencies(akkaVersion)
  )
  .settings(common)
  .settings(scalaSettings)
  .settings(publishSettings)
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)

lazy val docs = project.in(file("docs"))
  .settings(tutSettings)
  .settings(scalaSettings)
  .settings(libraryDependencies ++=
      baseDependencies ++ akkaDepenencies(akkaVersion)
  )
  .settings(scalacOptions in (Compile, doc) ++= Seq(
    "-skip-packages", Seq(
      "net.atinu.jailcall.internal",
      "akka"
    ).mkString(":")
  ))
  .settings(ghPages)
  .settings(site.addMappingsToSiteDir(tut, "tut"))
  .settings(site.addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), "latest/api"))
  .settings(unidocSettings)
  .settings(publishArtifact := false)
  .dependsOn(core)
  .aggregate(core)

lazy val common = Seq(
  organization := "net.atinu",
  version := "0.1.0-SNAPSHOT",
  name := "jailcall"
)

lazy val scalaSettings = Seq(
  scalaVersion := "2.11.8"
)

lazy val ghPages = site.settings ++ Seq(
  git.remoteRepo := "git@github.com:tobnee/jailcall.git"
) ++ ghpages.settings

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  licenses += ("Apache 2 license", url("http://www.apache.org/licenses/LICENSE-2.0")),
  pomExtra := (
      <url>http://tobnee.github.io/jailcall/</url>
      <scm>
        <url>git@github.com:tobnee/jailcall.git</url>
        <connection>scm:git:git@github.com:tobnee/jailcall.git</connection>
      </scm>
      <developers>
        <developer>
          <id>tobnee</id>
          <name>Tobias Neef</name>
          <url>http://atinu.net/</url>
        </developer>
      </developers>)
)

lazy val coreAkka23 = project.aggregate(core).settings(
  libraryDependencies := baseDependencies ++ akkaDepenencies(akkaVersion23)
)

lazy val baseDependencies = Seq(
  "org.hdrhistogram" % "HdrHistogram" % "2.1.8",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
)

def akkaDepenencies(version: String) = Seq(
  "com.typesafe.akka" %% "akka-actor" % version % "provided",
  "com.typesafe.akka" %% "akka-testkit" % version % "test"
)