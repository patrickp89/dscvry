scalaVersion := "2.13.3"

name := "dscvry"
organization := "de.netherspace.apps"
version := "1.0"

val zioVersion = "1.0.5"
libraryDependencies ++= Seq(
  "dev.zio" %% "zio"          % zioVersion,
  "dev.zio" %% "zio-test"     % zioVersion % "test",
  "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
)
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
