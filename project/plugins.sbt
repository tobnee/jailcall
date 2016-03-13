resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2"

resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.5.1")

addSbtPlugin("org.tpolecat" % "tut-plugin" % "0.4.2")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.1.10")

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.4")
