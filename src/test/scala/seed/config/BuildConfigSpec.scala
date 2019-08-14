package seed.config

import java.io.File

import minitest.SimpleTestSuite
import java.nio.file.{Files, Path, Paths}

import org.apache.commons.io.FileUtils
import seed.{Log, LogLevel}
import seed.config.util.TomlUtils
import seed.generation.util.BuildUtil
import seed.model.Build.{JavaDep, Resolvers, ScalaDep, VersionTag}
import seed.model.Platform.{JVM, JavaScript}
import BuildUtil.tempPath
import seed.cli.util.Ansi
import seed.config.BuildConfig.{Build, Result}
import seed.model.{Build, Organisation, Platform}

import scala.collection.mutable.ListBuffer

object BuildConfigSpec extends SimpleTestSuite {
  test("Set default values and inherit settings") {
    val original = Map(
      "base" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        targets = List(Platform.JVM),
        javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5"))
      ),
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        moduleDeps = List("base"),
        targets = List(Platform.JVM)
      )
    )

    val inherited = Map(
      "base" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaOrganisation = Some(Organisation.Lightbend.packageName),
        targets = List(Platform.JVM),
        javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5")),
        jvm = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5"))
          )
        )
      ),
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaOrganisation = Some(Organisation.Lightbend.packageName),
        moduleDeps = List("base"),
        targets = List(Platform.JVM),
        jvm = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            moduleDeps = List("base")
          )
        )
      )
    )

    assertEquals(
      original.mapValues(BuildConfig.inheritSettings(Build.Module())),
      inherited
    )
  }

  test("Test module") {
    val original = Map(
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        targets = List(Platform.JVM),
        jvm = Some(Build.Module(moduleDeps = List("base"))),
        test = Some(Build.Module(jvm = Some(Build.Module())))
      )
    )

    val inherited = Map(
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaOrganisation = Some(Organisation.Lightbend.packageName),
        targets = List(Platform.JVM),
        jvm = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            moduleDeps = List("base")
          )
        ),
        test = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            targets = List(Platform.JVM),
            jvm = Some(
              Build.Module(
                scalaVersion = Some("2.12.8"),
                scalaOrganisation = Some(Organisation.Lightbend.packageName),
                moduleDeps = List("base")
              )
            )
          )
        )
      )
    )

    assertEquals(
      original.mapValues(BuildConfig.inheritSettings(Build.Module())),
      inherited
    )
  }

  def parseBuild(toml: String, log: Log = Log.urgent, fail: Boolean = false)(
    f: Path => String
  ): Build = {
    val parsed = TomlUtils.parseBuildToml(Paths.get("."))(toml)
    val build = BuildConfig.processBuild(
      parsed.right.get,
      Paths.get("."), { path =>
        val build = parseBuild(f(path))(f)
        Some(Result(Paths.get("."), Resolvers(), build))
      },
      Log.urgent
    )

    val valid = build.forall {
      case (name, module) =>
        BuildConfig.checkModule(build, name, module.module, log)
    }

    assertEquals(valid, !fail)
    build
  }

  test("Minimal native build") {
    val fooToml = """
      |[module.demo.native]
      |scalaVersion       = "2.11.11"
      |scalaNativeVersion = "0.3.7"
      |sources            = ["src/"]
    """.stripMargin

    val build = parseBuild(fooToml)(_ => "")

    assertEquals(build("demo").module.targets, List(Platform.Native))
    assertEquals(build("demo").module.native.get.scalaVersion, Some("2.11.11"))
    assertEquals(
      build("demo").module.native.get.scalaNativeVersion,
      Some("0.3.7")
    )
    assertEquals(
      build("demo").module.native.get.sources,
      List(Paths.get("src/"))
    )
  }

  test("Resolve absolute project path") {
    FileUtils.write(
      tempPath.resolve("a.toml").toFile,
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.example.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    val config = BuildConfig.load(tempPath.resolve("a.toml"), Log.urgent).get
    assertEquals(config.projectPath, tempPath)
    assertEquals(config.build("example").path, tempPath)
  }

  test("Resolve relative project path") {
    FileUtils.write(
      new File("test/a.toml"),
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.example.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    val config = BuildConfig.load(Paths.get("test/a.toml"), Log.urgent).get
    assertEquals(config.projectPath, Paths.get("test"))
    assertEquals(config.build("example").path, Paths.get("test"))
    Files.delete(Paths.get("test/a.toml"))
  }

  test("Import module") {
    Files.createDirectories(tempPath.resolve("seed-root").resolve("child"))

    FileUtils.write(
      tempPath
        .resolve("seed-root")
        .resolve("child")
        .resolve("build.toml")
        .toFile,
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.child.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    FileUtils.write(
      tempPath.resolve("seed-root").resolve("build.toml").toFile,
      """
        |import = ["child"]
        |
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.root.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    val config =
      BuildConfig.load(tempPath.resolve("seed-root"), Log.urgent).get
    assertEquals(
      config.build.mapValues(_.path),
      Map(
        "root"  -> tempPath.resolve("seed-root"),
        "child" -> tempPath.resolve("seed-root").resolve("child")
      )
    )
  }

  test("Set target platforms on test modules") {
    val toml = """
      |[project]
      |scalaVersion   = "2.12.8"
      |scalaJsVersion = "0.6.26"
      |testFrameworks = ["minitest.runner.Framework"]
      |
      |[module.example]
      |sources = ["shared/src"]
      |targets = ["js", "jvm"]
      |
      |[module.example.test]
      |sources   = ["shared/test"]
      |scalaDeps = [
      |  ["io.monix", "minitest", "2.3.2"]
      |]
      |
      |[module.example.test.js]
      |sources = ["js/test"]
    """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.test.get.targets,
      List(JavaScript, JVM)
    )
    assert(build("example").module.test.get.js.isDefined)
    assert(build("example").module.test.get.jvm.isDefined)
  }

  test("Set target platforms on test modules (2)") {
    val toml =
      """
        |[project]
        |scalaVersion   = "2.13.0"
        |scalaJsVersion = "0.6.28"
        |testFrameworks = ["minitest.runner.Framework"]
        |
        |[module.example]
        |sources = ["shared/src"]
        |mainClass = "a.b"
        |scalaDeps = [
        |  ["org.scalameta", "interactive", "4.1.0", "full"]
        |]
        |
        |[module.example.js]
        |[module.example.jvm]
        |
        |[module.example.test]
        |sources = ["shared/test/"]
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.test.get.targets,
      List(JVM, JavaScript)
    )
    assert(build("example").module.test.get.js.isDefined)
    assert(build("example").module.test.get.jvm.isDefined)
    assert(build("example").module.test.get.mainClass.isEmpty)
    assert(build("example").module.test.get.scalaDeps.isEmpty)
    assert(build("example").module.test.get.js.get.mainClass.isEmpty)
    assert(build("example").module.test.get.js.get.scalaDeps.isEmpty)
  }

  test("Parse TOML with full Scala dependency") {
    val toml = """
      |[project]
      |scalaVersion = "2.12.8"
      |
      |[module.example.jvm]
      |sources = ["shared/src"]
      |scalaDeps = [
      |  ["org.scalameta", "interactive", "4.1.0", "full"]
      |]
    """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.jvm.get.scalaDeps,
      List(ScalaDep("org.scalameta", "interactive", "4.1.0", VersionTag.Full))
    )
  }

  test("Inherit compilerDeps from project definition and base modules") {
    val fooToml = """
      |import = ["bar"]
      |
      |[project]
      |scalaVersion = "2.12.8"
      |scalaJsVersion = "0.6.26"
      |compilerDeps = [
      |  ["foo", "foo", "1.0", "full"]
      |]
      |
      |[module.foo]
      |sources = ["foo/src"]
      |
      |[module.foo.js]
      |sources = ["foo-js/src"]
      |compilerDeps = [
      |  ["foo-js", "foo-js", "1.0", "full"]
      |]
      |
      |[module.foo2]
      |sources = ["foo2/src"]
      |compilerDeps = [
      |  ["foo", "foo", "2.0", "full"]
      |]
      |
      |[module.foo2.js]
      |compilerDeps = [
      |  ["foo", "foo", "3.0", "full"]
      |]
    """.stripMargin

    val barToml = """
      |[project]
      |scalaVersion = "2.12.8"
      |compilerDeps = [
      |  ["bar", "bar", "1.0", "full"]
      |]
      |
      |[module.bar]
      |scalaJsVersion = "0.6.26"
      |targets = ["js"]
      |sources = ["bar/src"]
    """.stripMargin

    val build = parseBuild(fooToml) {
      case p if p == Paths.get("bar") => barToml
    }

    assertEquals(
      build("foo").module.compilerDeps,
      List(ScalaDep("foo", "foo", "1.0", VersionTag.Full))
    )

    assertEquals(
      build("foo").module.js.get.compilerDeps,
      List(
        ScalaDep("foo", "foo", "1.0", VersionTag.Full),
        ScalaDep("foo-js", "foo-js", "1.0", VersionTag.Full)
      )
    )

    assertEquals(
      build("foo2").module.compilerDeps,
      List(ScalaDep("foo", "foo", "2.0", VersionTag.Full))
    )

    assertEquals(
      build("foo2").module.js.get.compilerDeps,
      List(ScalaDep("foo", "foo", "3.0", VersionTag.Full))
    )

    assertEquals(
      build("bar").module.compilerDeps,
      List(ScalaDep("bar", "bar", "1.0", VersionTag.Full))
    )

    assertEquals(
      build("bar").module.js.get.compilerDeps,
      List(ScalaDep("bar", "bar", "1.0", VersionTag.Full))
    )
  }

  test("Inheritance of settings") {
    val fooToml = """
      |[project]
      |scalaVersion = "2.12.8"
      |testFrameworks = ["a.b"]
      |scalaOptions = ["-deprecation"]
      |
      |[module.foo]
      |scalaVersion = "2.11.11"
      |sources = ["foo/src"]
      |
      |[module.foo.js]
      |scalaJsVersion = "0.6.26"
      |sources = ["foo-js/src"]
      |testFrameworks = ["c.d"]
      |
      |[module.bar]
      |targets = ["jvm"]
      |sources = ["foo/src"]
      |scalaOptions = ["-language:existentials"]
      |testFrameworks = ["a.b"]
    """.stripMargin

    val build = parseBuild(fooToml)(_ => "")

    assertEquals(build("foo").module.targets, List(Platform.JavaScript))
    assertEquals(build("foo").module.scalaVersion, Some("2.11.11"))
    assertEquals(build("foo").module.testFrameworks, List("a.b"))
    assertEquals(build("foo").module.scalaOptions, List("-deprecation"))

    assertEquals(build("foo").module.js.get.scalaVersion, Some("2.11.11"))
    assertEquals(build("foo").module.js.get.testFrameworks, List("a.b", "c.d"))
    assertEquals(build("foo").module.js.get.scalaOptions, List("-deprecation"))

    assertEquals(build("bar").module.scalaVersion, Some("2.12.8"))
    assertEquals(
      build("bar").module.scalaOptions,
      List("-deprecation", "-language:existentials")
    )
  }

  test("Scala version compatibility") {
    val buildToml = """
      |[module.foo.jvm]
      |scalaVersion = "2.11.11"
      |sources = ["foo/src"]
      |
      |[module.bar.jvm]
      |moduleDeps = ["foo"]
      |scalaVersion = "2.12.8"
      |sources = ["bar/src"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"Scala version of ${Ansi.italic("bar:jvm")} (2.12.8) is incompatible with ${Ansi.italic("foo:jvm")} (2.11.11)"
        )
      )
    )
  }
}
