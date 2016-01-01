name := """akka-defender"""

version := "0.1"

scalaVersion := "2.11.7"

Defaults.itSettings

lazy val root = project.in(file(".")).configs(IntegrationTest)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.1",
  "org.hdrhistogram" % "HdrHistogram" % "2.1.8",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.0" % "test, it",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test, it")
