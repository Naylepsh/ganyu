package persistence

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Tag
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.util.query.*
import doobie.util.transactor.Transactor
import core.infra.resources.database.*
import core.domain.project.ProjectScanConfig
import core.domain.project.Project
import core.domain.dependency.DependencySource
import core.application.config.AppConfig
import cats.effect.kernel.Resource
import core.domain.project.ScanResult
import core.domain.dependency.DependencyReport
import core.domain.dependency.DependencyDetails
import core.domain.dependency.Dependency
import core.domain.Grouped
import persistance.ScanResultRepository
import core.infra.persistance.DependencyRepository
import org.legogroup.woof.{ *, given }
import core.domain.Time
import org.scalatest.Checkpoints.Checkpoint
import org.scalatest.Succeeded
import ScanResultRepository.ScanResultRepositorySQL.GetAllResult
import org.joda.time.DateTime
import core.domain.project.ScanReport

class ScanResultRepositorySpec extends AsyncFreeSpec with AsyncIOSpec
    with Matchers:
  import ScanResultRepositorySpec.*

  "Save, purge old and find latest scan report" taggedAs (DatabaseTest) in:
    transactor.use: xa =>
      val countScans =
        sql"SELECT COUNT(DISTINCT timestamp) FROM project_dependency"
          .query[Int]
          .unique
          .transact(xa)
      given Filter  = Filter.everything
      given Printer = NoColorPrinter()
      for
        given Logger[IO] <- DefaultLogger.makeIo(noop)
        dependencyRepository = DependencyRepository.make(xa)
        repository           = ScanResultRepository.make(xa, dependencyRepository)
        dt1                  <- Time[IO].currentDateTime
        _                    <- repository.save(List(firstResult), dt1)
        dt2                  <- Time[IO].currentDateTime
        _                    <- repository.save(List(secondResult), dt2)
        dt3                  <- Time[IO].currentDateTime
        _                    <- repository.save(List(thirdResult), dt3)
        savedCount           <- countScans
        _                    <- repository.deleteOld(project.name)
        savedCountAfterPurge <- countScans
        latestScan           <- repository.getLatestScanReport(project.name)
      yield
        val cp = Checkpoint()
        savedCount shouldBe 3
        savedCountAfterPurge shouldBe 1
        latestScan.map(_.dependenciesReports.head.items.head) shouldBe Some(
          thirdResult.dependenciesReports.head.items.head
        )
        cp.reportAll()
        Succeeded

  "GetAllResult.toDomain constructs a proper report" in:
    val scanReports = GetAllResult.toDomain(testGetAllResults)
    scanReports should contain only (expectedFirstProjectReport, expectedSecondProjectReprort)

object ScanResultRepositorySpec:
  val transactor = Resource.eval(AppConfig.load[IO]).flatMap: config =>
    makeSqliteTransactorResource[IO](config.database).evalTap: xa =>
      val freshStart =
        for
          _ <- sql"DELETE FROM project".update.run
          _ <- sql"DELETE FROM dependency".update.run
        yield ()
      freshStart.transact(xa)

  val project    = Project("420", "foo")
  val dependency = Dependency("bar", None)

  private def makeResult(latestVersion: String) =
    ScanResult(
      project,
      List(Grouped(
        "requirements.txt",
        List(
          DependencyReport(
            dependency,
            DependencyDetails(
              dependency.name,
              dependency.currentVersion.getOrElse("-"),
              latestVersion,
              None
            ),
            None
          )
        )
      ))
    )

  val firstResult  = makeResult("4.2.0")
  val secondResult = makeResult("4.2.1")
  val thirdResult  = makeResult("4.2.2")

  val noop: Output[IO] = new:
    override def output(str: String): IO[Unit]      = IO.unit
    override def outputError(str: String): IO[Unit] = IO.unit

  val now = DateTime.now()
  val expectedFirstProjectReport = ScanReport(
    projectName = "first-project",
    dependenciesReports = List(
      Grouped(
        groupName = "requirements.txt",
        items = List(DependencyReport(
          name = "Django",
          currentVersion = Some("1.2.3"),
          latestVersion = "4.5.6",
          latestReleaseDate = Some(now),
          vulnerabilities =
            List("first-vulnerability", "second-vulnerability"),
          notes = Some("requires python>=4.20")
        ))
      )
    )
  )
  val expectedSecondProjectReprort = ScanReport(
    projectName = "second-project",
    dependenciesReports = List(
      Grouped(
        groupName = "requirements.txt",
        items = List(DependencyReport(
          name = "Flask",
          currentVersion = Some("2.3.4"),
          latestVersion = "2.3.5",
          latestReleaseDate = Some(now),
          vulnerabilities = List.empty,
          notes = None
        ))
      )
    )
  )

  val testGetAllResults = List(
    GetAllResult(
      projectName = "first-project",
      groupName = "requirements.txt",
      dependencyId = "1",
      dependencyName = "Django",
      dependencyCurrentVersion = Some("1.2.3"),
      dependencyLatestVersion = "4.5.6",
      dependencyLatestReleaseDate = Some(now),
      dependencyNotes = Some("requires python>=4.20"),
      dependencyVulnerability = Some("first-vulnerability")
    ),
    GetAllResult(
      projectName = "first-project",
      groupName = "requirements.txt",
      dependencyId = "1",
      dependencyName = "Django",
      dependencyCurrentVersion = Some("1.2.3"),
      dependencyLatestVersion = "4.5.6",
      dependencyLatestReleaseDate = Some(now),
      dependencyNotes = Some("requires python>=4.20"),
      dependencyVulnerability = Some("second-vulnerability")
    ),
    GetAllResult(
      projectName = "second-project",
      groupName = "requirements.txt",
      dependencyId = "1",
      dependencyName = "Flask",
      dependencyCurrentVersion = Some("2.3.4"),
      dependencyLatestVersion = "2.3.5",
      dependencyLatestReleaseDate = Some(now),
      dependencyNotes = None,
      dependencyVulnerability = None
    )
  )