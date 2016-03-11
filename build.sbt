
lazy val root = project.in(file("core"))
  .settings(name := "jailcall")
  .settings(version := "0.1")
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.4.2",
      "org.hdrhistogram" % "HdrHistogram" % "2.1.8",
      "com.typesafe.akka" %% "akka-testkit" % "2.4.0" % "test, it",
      "org.scalatest" %% "scalatest" % "2.2.6" % "test, it")
  )
  .settings(common)
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)

lazy val docs = project.in(file("docs"))
  .settings(tutSettings)
  .settings(common)
  .dependsOn(root)

lazy val common = Seq(
  scalaVersion := "2.11.8"
)