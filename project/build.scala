import sbt._, Keys._, KeyRanks._
import sbtassembly.Plugin._
import AssemblyKeys._
import com.ambiata.promulgate.project.ProjectPlugin._
import scoverage.ScoverageSbtPlugin._

object build extends Build {
  type Settings = Def.Setting[_]

  lazy val notion = Project(
    id = "notion"
  , base = file(".")
  , settings =
    standardSettings ++
    promulgate.library("com.ambiata.notion", "ambiata-oss")
  , aggregate =
      Seq(core, distcopy, cli)
  )
  .dependsOn(core, distcopy)

  lazy val standardSettings =
    Defaults.coreDefaultSettings ++
    projectSettings          ++
    compilationSettings      ++
    testingSettings


  lazy val projectSettings: Seq[Settings] = Seq(
    name := "notion"
  , version in ThisBuild := s"""0.0.1-${Option(System.getenv("HADOOP_VERSION")).getOrElse("cdh5")}"""
  , organization := "com.ambiata"
  , scalaVersion := "2.11.2"
  , crossScalaVersions := Seq(scalaVersion.value)
  , fork in run := true
  , resolvers := depend.resolvers
  ) ++ Seq(prompt)

  lazy val core = Project(
    id = "core"
    , base = file("notion-core")
    , settings = standardSettings ++ lib("core") ++ Seq[Settings](
      name := "notion-core"
    ) ++ Seq[Settings](libraryDependencies ++=
      depend.scalaz  ++
      depend.mundane ++
      depend.poacher(version.value) ++
      depend.saws    ++
      depend.specs2
    )
  )

  lazy val distcopy = Project(
    id = "distcopy"
    , base = file("notion-distcopy")
    , settings = standardSettings ++ lib("distcopy") ++ Seq[Settings](
      name := "notion-distcopy"
    ) ++ Seq[Settings](libraryDependencies ++=
      depend.scalaz ++
      depend.saws ++
      depend.mundane ++
      depend.scalaz ++
      depend.specs2 ++
      depend.argonaut ++
      depend.poacher(version.value) ++
      depend.hadoop(version.value))
  )
  .dependsOn(core)

  lazy val cli = Project(
    id = "cli"
  , base = file("notion-cli")
  , settings = standardSettings ++ app("cli") ++ Seq[Settings](
    name := "notion-cli"
  ) ++ Seq[Settings](libraryDependencies ++=
      depend.scopt ++
      depend.saws ++
      depend.mundane ++
      depend.hadoop(version.value))
  ).dependsOn(core, distcopy)


  lazy val compilationSettings: Seq[Settings] = Seq(
    javaOptions ++= Seq(
      "-Xmx3G"
    , "-Xms512m"
    , "-Xss4m"
    )
  , javacOptions ++= Seq(
      "-source"
    , "1.6"
    , "-target"
    , "1.6"
    )
  , maxErrors := 20
  , scalacOptions ++= Seq(
      "-target:jvm-1.6"
    , "-deprecation"
    , "-unchecked"
    , "-feature"
    , "-language:_"
    , "-Ywarn-unused-import"
    , "-Ywarn-value-discard"
    , "-Yno-adapted-args"
    , "-Xlint"
    , "-Xfatal-warnings"
    , "-Yinline-warnings"
    )
  , scalacOptions in (Compile,console) := Seq("-language:_", "-feature")
  , scalacOptions in (Test,console) := Seq("-language:_", "-feature")
  , scalacOptions in ScoverageCompile := Seq("-language:_", "-feature")
  )

  def lib(name: String) =
    promulgate.library(s"com.ambiata.notion.$name", "ambiata-oss")

  def app(name: String) =
    promulgate.all(s"com.ambiata.notion.$name", "ambiata-oss", "ambiata-dist")


  lazy val testingSettings: Seq[Settings] = Seq(
    initialCommands in console := "import org.specs2._"
  , logBuffered := false
  , cancelable := true
  , fork in test := true
  , testOptions in Test += Tests.Setup(() => System.setProperty("log4j.configuration", "file:etc/log4j-test.properties"))
  , testOptions in Test ++= (if (Option(System.getenv("FORCE_AWS")).isDefined || Option(System.getenv("AWS_ACCESS_KEY")).isDefined)
                               Seq()
                             else
                               Seq(Tests.Argument("--", "exclude", "aws")))
  ) ++ instrumentSettings ++ Seq(ScoverageKeys.highlighting := true)

  lazy val prompt = shellPrompt in ThisBuild := { state =>
    val name = Project.extract(state).currentRef.project
    (if (name == "notion") "" else name) + "> "
  }
}
