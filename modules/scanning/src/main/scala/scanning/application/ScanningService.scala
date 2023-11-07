package scanning.application.services

import scala.annotation.tailrec

import cats.*
import cats.data.NonEmptyList
import cats.effect.std.*
import cats.implicits.*
import core.domain.*
import core.domain.dependency.*
import core.domain.project.{ ProjectScanConfig, * }
import org.joda.time.DateTime
import org.legogroup.woof.{ *, given }
import scanning.domain.{ DependencySummary, ScanSummary, Source }
import advisory.Advisory
import core.domain.update.UpdateGateway
import core.domain.update.DependencyToUpdate

type CompareDependencyScanReports =
  (DependencyScanReport, DependencyScanReport) => Int

trait ScanningService[F[_]]:
  def scan(project: ProjectScanConfig): F[Unit]
  def getLatestScansTimestamps(limit: Int): F[List[DateTime]]
  def getLatestScan(
      projectName: String,
      compare: CompareDependencyScanReports
  ): F[Option[ScanSummary]]
  def getVulnerabilitiesSince(time: DateTime): F[List[ProjectVulnerability]]
  def obtainUnknownSeveritiesOfVulnerabilities: F[Unit]
  def deleteScans(timestamps: NonEmptyList[DateTime]): F[Unit]

object ScanningService:
  import Logger.*

  def make[F[_]: Monad: Logger: Parallel: Time](
      source: Source[F, ProjectScanConfig],
      scanner: DependencyScanner[F],
      repository: ScanResultRepository[F],
      advisory: Advisory[F],
      updateGateway: UpdateGateway[F]
  ): ScanningService[F] = new:
    def deleteScans(timestamps: NonEmptyList[DateTime]): F[Unit] =
      Logger[F].info(s"Deleting scans of ${timestamps.length} timestamps")
        >> repository.delete(timestamps)
        >> Logger[F].info("Successfully deleted the scan(s)")

    def scan(config: ProjectScanConfig): F[Unit] =
      val program =
        for
          _ <-
            Logger[F].info(s"Scanning dependencies of ${config.project.name}")
          projectDependencies <- source
            .extract(config)
            .map: dependencies =>
              ProjectDependencies(config.project, dependencies)
          allDependencies = projectDependencies
            .dependencies
            .flatMap(_.items)
          _ <- Logger[F].info(
            s"Checking the details of ${allDependencies.length} dependencies"
          )
          details <- scanner.getDetails(allDependencies)
          report = buildReport(buildDetailsMap(details))(projectDependencies)
          _   <- Logger[F].info("Saving the scan results")
          now <- Time[F].currentDateTime
          _   <- repository.save(List(report), now)
          _   <- Logger[F].info("Deleting the old scans")
          _   <- repository.deleteOld(config.project.name)
          _   <- Logger[F].info("Done with the scan")
        yield ()
      program.withLogContext("project", config.project.name)

    def getLatestScansTimestamps(limit: Int): F[List[DateTime]] =
      repository.getLatestScansTimestamps(limit)

    def getLatestScan(
        projectName: String,
        compare: CompareDependencyScanReports
    ): F[Option[ScanSummary]] =
      repository
        .getLatestScanReport(projectName)
        .flatMap(_.traverse: report =>
          summarize(ScanReport.sortGroups(compare, report)))

    private def summarize(report: ScanReport): F[ScanSummary] =
      report
        .dependenciesReports
        .traverse: group =>
          group
            .items
            .traverse: dependency =>
              val toUpdate = DependencyToUpdate(
                dependency.name,
                dependency.currentVersion,
                dependency.latestVersion
              )
              updateGateway
                .canUpdate(toUpdate, group.groupName)
                .map(DependencySummary(dependency, _))
            .map(Grouped(group.groupName, _))
        .map(ScanSummary(report.projectName, _))

    def obtainUnknownSeveritiesOfVulnerabilities: F[Unit] =
      repository.getVulnerabilitiesOfUnknownSeverity.flatMap: vulnerabilities =>
        Logger[F].info(
          s"Found ${vulnerabilities.length} vulnerabilities of unknown severity"
        )
          .flatMap: _ =>
            vulnerabilities
              .traverse: vulnerability =>
                advisory
                  .getVulnerabilitySeverity(vulnerability)
                  .flatTap: severity =>
                    Logger[F].info(
                      s"""$vulnerability's severity is ${severity.getOrElse(
                          "Unknown"
                        )}"""
                    )
                  .flatMap: severity =>
                    severity.fold(Monad[F].unit): severity =>
                      repository.setVulnerabilitySeverity(
                        vulnerability,
                        severity
                      )
          .flatMap: _ =>
            Logger[F].info("Done with the severity checks")

    def getVulnerabilitiesSince(time: DateTime): F[List[ProjectVulnerability]] =
      repository.getVulnerabilitiesSince(time)

  private val latestKey = "LATEST"

  private type DetailsMap = Map[String, Map[String, DependencyDetails]]

  private def getDetails(
      details: DetailsMap
  )(name: String, version: String): Option[DependencyDetails] =
    details.get(name).flatMap(_.get(version))

  private def buildDetailsMap(
      dependenciesDetails: List[DependencyDetails]
  ): DetailsMap =
    dependenciesDetails
      .groupBy(_.name)
      .map: (name, details) =>
        val orderedByVersion = details.sortWith(_.ofVersion > _.ofVersion)
        val latest           = orderedByVersion.head.copy(ofVersion = latestKey)

        name -> (latest :: orderedByVersion).map(d => d.ofVersion -> d).toMap
      .toMap

  private def buildReport(details: DetailsMap)(
      projectDependencies: ProjectDependencies
  ): ScanResult =
    val detailsOf = getDetails(details)

    @tailrec
    def inner(
        dependencies: List[Dependency],
        report: List[DependencyReport]
    ): List[DependencyReport] =
      dependencies match
        case head :: next =>
          detailsOf(head.name, head.currentVersion.getOrElse(latestKey)) match
            case Some(detail) =>
              val notes = detail.minLanguageVersion.map(version =>
                s"min. language version: $version"
              )

              inner(next, DependencyReport(head, detail, notes) :: report)

            case None => inner(next, report)

        case Nil => report

    val report = projectDependencies.dependencies.map(dependencyGroup =>
      Grouped(
        dependencyGroup.groupName,
        inner(dependencyGroup.items, List.empty)
      )
    )
    ScanResult(projectDependencies.project, report)
