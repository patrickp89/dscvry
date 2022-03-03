val zioVersion = "2.0.0-RC2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "dscvry",
    organization := "de.netherspace.apps",
    version := "0.1.0",

    scalaVersion := "3.1.0",

    libraryDependencies ++= Seq(
      "org.mongodb" % "mongo-java-driver" % "3.12.9",
      "dev.zio" %% "zio-nio" % "2.0.0-RC2",
      "dev.zio" %% "zio-logging" % "2.0.0-RC5",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.9" % Test
    ),

    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
