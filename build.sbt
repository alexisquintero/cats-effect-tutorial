name := "cats-effect-tutorial"

version := "2.0.0"

scalaVersion := "2.13.1"

libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.1" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds")
