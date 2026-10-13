ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "gamestats"

val circeVersion = "0.14.10"

lazy val root = (project in file("."))
  .settings(
    name := "gamestats-analytics",
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-core"   % circeVersion,
      "io.circe"      %% "circe-parser" % circeVersion,
      "org.scalatest" %% "scalatest"    % "3.2.19" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    Test / parallelExecution := false,
    // Fat-jar para el Dockerfile: `sbt assembly`
    assembly / mainClass      := Some("gamestats.Main"),
    assembly / assemblyJarName := "gamestats-analytics.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    }
  )
