package org.jetbrains.plugins.cbt.project

import java.io.File
import java.nio.file._

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.{ModuleManager, Module => IdeaModule}
import com.intellij.openapi.project.{Project => IdeaProject}
import com.intellij.openapi.roots.libraries.{Library => IdeaLibrary}
import com.intellij.openapi.roots.{ModuleRootManager, OrderRootType}
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.util.CommonProcessors.CollectProcessor
import org.jetbrains.plugins.cbt._
import org.jetbrains.plugins.cbt.project.model.CbtProjectInfo.{JarType, Library, LibraryJar, ModuleType}
import org.jetbrains.plugins.cbt.project.model.{CbtProjectConverter, CbtProjectInfo}
import org.jetbrains.plugins.cbt.project.settings.CbtExecutionSettings
import org.junit.Assert._
import org.junit.Test

import scala.collection.JavaConverters._

class ProjectImportTest {
  private val modulePrototype =   CbtProjectInfo.Module(
    name = null,
    root = "ROOT".toFile,
    sourceDirs = Seq("ROOT/src".toFile),
    scalaVersion = "2.11.8",
    target = "ROOT/target".toFile,
    moduleType = ModuleType.Default,
    binaryDependencies = Seq(
      CbtProjectInfo.BinaryDependency("org.scala-lang:scala-library:2.11.8"),
      CbtProjectInfo.BinaryDependency("CBT: cbtCore"),
      CbtProjectInfo.BinaryDependency("CBT: cbtLib")
    ),
    moduleDependencies = Seq.empty,
    classpath = Seq.empty,
    parentBuild = None,
    scalacOptions = Seq.empty
  )
  private val cbtLibs = Seq(
    Library(
      name = "CBT: cbtCore",
      jars = Seq(
        LibraryJar(
          jar = "ROOT/fake_cbt_cache/cbtCore.jar".toFile,
          jarType = JarType.Binary
        )
      )
    ),
    Library(
      name = "CBT: cbtLib",
      jars = Seq(
        LibraryJar(
          jar = "ROOT/fake_cbt_cache/cbtLib.jar".toFile,
          jarType = JarType.Binary
        )
      )
    )
  )
  @Test
  def testSimple(): Unit = {
    import CbtProjectInfo._
    val projectInfo = Project(name = "NAME",
      root = "ROOT".toFile,
      modules = Seq(
        modulePrototype.copy(
          name = "rootModule",
          moduleDependencies = Seq(CbtProjectInfo.ModuleDependency("otherModule"))
        ),
        modulePrototype.copy(
          name = "otherModule"
        )
      ),
      libraries = Seq(
        Library(
          name = "org.scala-lang:scala-library:2.11.8",
          jars = Seq(
            LibraryJar(
              jar = "ROOT/fake_cbt_cache/scala-library-2.11.8.jar".toFile,
              jarType = JarType.Binary
            ),
            LibraryJar(
              jar = "ROOT/fake_cbt_cache/scala-library-2.11.8-sources.jar".toFile,
              jarType = JarType.Source
            )
          )
        )
      ),
      cbtLibraries = cbtLibs,
      scalaCompilers = Seq.empty)

    testProject("testSimple", projectInfo)
  }

  private def projectEquals(project: IdeaProject, projectInfo: CbtProjectInfo.Project) = {
    def moduleEquals(module: IdeaModule, moudleInfo: CbtProjectInfo.Module) = {
      assertEquals(moudleInfo.name, module.getName)
      val moduleRootManager = ModuleRootManager.getInstance(module)

      val actulaSourceDirs = moduleRootManager.getContentEntries.flatMap(_.getSourceFolders).map(_.getJpsElement.getFile).toSet
      assertEquals(moudleInfo.sourceDirs.toSet, actulaSourceDirs)

      assertEquals(moudleInfo.moduleDependencies.map(_.name).toSet, moduleRootManager.getDependencies.map(_.getName).toSet)

      def librariesEqual() = {
        val actualLibraries = {
          val collector = new CollectProcessor[IdeaLibrary]
          ApplicationManager.getApplication.runReadAction(new Runnable {
            override def run(): Unit =
              moduleRootManager.getModifiableModel.orderEntries().librariesOnly().forEachLibrary(collector)
          })
          collector.getResults.asScala
            .map { l =>
              val binaryJars = l.getFiles(OrderRootType.CLASSES)
                .map(j => (j.getCanonicalPath.stripSuffix("!/"), JarType.Binary))
                .toSet
              val sourceJars = l.getFiles(OrderRootType.SOURCES)
                .map(j => (j.getCanonicalPath.stripSuffix("!/"), JarType.Source))
                .toSet
              (l.getName.stripPrefix("CBT: "), binaryJars ++ sourceJars)
            }
            .toSet
        }
        val expectedLibraries = moudleInfo.binaryDependencies
          .map { d =>
            val jars = (projectInfo.libraries ++ projectInfo.cbtLibraries)
              .find(_.name == d.name)
              .toSeq
              .flatMap(_.jars)
              .map(j => (j.jar.getCanonicalPath, j.jarType))
              .toSet
            (d.name, jars)
          }
          .toSet
        assertEquals(expectedLibraries, actualLibraries)
      }
      librariesEqual()
    }


    val modules = ModuleManager.getInstance(project).getModules.toSeq.sortBy(_.getName)
    val mouleInfos = projectInfo.modules.sortBy(_.name)
    assertTrue(modules.length == mouleInfos.length)
    modules.zip(mouleInfos).foreach{case (m, mi) => moduleEquals(m, mi)}
  }

  private def replacePaths(project: CbtProjectInfo.Project, name: String, path: String): CbtProjectInfo.Project = {
    import CbtProjectInfo._
    def replace(obj: AnyRef): AnyRef = obj match {
      case project: Project =>
        project.copy(root = replace(project.root).asInstanceOf[File],
          modules = project.modules.map(replace(_).asInstanceOf[Module]),
          libraries = project.libraries.map(replace(_).asInstanceOf[Library])
        )
      case module: Module =>
        module.copy(root = replace(module.root).asInstanceOf[File],
          sourceDirs = module.sourceDirs.map(replace(_).asInstanceOf[File]),
          target = replace(module.target).asInstanceOf[File]
        )
      case library: Library =>
        library.copy(jars = library.jars.map(replace(_).asInstanceOf[LibraryJar]))
      case libraryJar: LibraryJar =>
        libraryJar.copy(jar = replace(libraryJar.jar).asInstanceOf[File])
      case file: File =>
        file.getPath.replaceAllLiterally("NAME", name).replaceAllLiterally("ROOT", path).toFile
    }

    replace(project).asInstanceOf[Project]
  }

  private def createJarFiles(project: CbtProjectInfo.Project): Unit = {
    for (l <- project.libraries ++ project.cbtLibraries; j <- l.jars) j match {
      case CbtProjectInfo.LibraryJar(f, _) =>
        Files.createDirectories(f.toPath.getParent)
        f.createNewFile()
    }
  }

  private def testProject(name: String, projectInfo: CbtProjectInfo.Project): Unit = {
    val testFixture = IdeaTestFixtureFactory.getFixtureFactory.createFixtureBuilder(name).getFixture
    testFixture.setUp()
    val project = testFixture.getProject
    val settings = new CbtExecutionSettings(project.getBasePath, false, true, true, Seq.empty)
    val projectInfoWithActualPaths = replacePaths(projectInfo, name, project.getBasePath)
    createJarFiles(projectInfoWithActualPaths)
    CbtProjectConverter(projectInfoWithActualPaths, settings)
      .map { projectNode =>
        ServiceManager.getService(classOf[ProjectDataManager]).importData(projectNode, project, true)
      }
      .get
    projectEquals(project, projectInfoWithActualPaths)
  }
}
