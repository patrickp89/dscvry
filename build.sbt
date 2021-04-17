scalaVersion := "2.13.3"

name := "dscvry"
organization := "de.netherspace.apps"
version := "1.0"

val zioVersion = "1.0.5"
libraryDependencies ++= Seq(
  "dev.zio" %% "zio"          % zioVersion,
  "dev.zio" %% "zio-test"     % zioVersion % "test",
  "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
  "org.scalatest" %% "scalatest" % "3.2.7" % "test",
)
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
