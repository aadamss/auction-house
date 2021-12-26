name := "auction-house-aadamss"

version := "1.0"

scalaVersion := "2.13.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.6.17",
  "com.typesafe.akka" %% "akka-stream" % "2.6.17",
  "com.typesafe.akka" %% "akka-http" % "10.2.7",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.7",
  "com.typesafe.akka" %% "akka-testkit" % "2.6.17" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.6.17" % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % "10.2.7" % Test,
  "org.scalatest" %% "scalatest" % "3.2.10" % Test,
)

enablePlugins(JavaAppPackaging)
