package infra.exporters

import cats.*
import cats.implicits.*
import domain.Exporter
import domain.dependency.DependencyReport
import domain.project.ExportProjectDependencies
import domain.semver.*
import domain.severity.*
import org.apache.poi.ss.usermodel.Cell
import spoiwo.model.*
import spoiwo.model.enums.CellFill
import spoiwo.natures.xlsx.Model2XlsxConversions.*

object ExcelExporter:
  def make[F[_]: Applicative, A](
      toSheet: A => Sheet,
      path: String
  ): Exporter[F, A] = new Exporter[F, A]:

    override def exportData(data: List[A]): F[Unit] =
      val workbook = Workbook().withSheets(data.map(toSheet))
      workbook.saveAsXlsx(path).pure

  object dependencies:
    def toSheet(repoDependencies: ExportProjectDependencies): Sheet =
      val rows = repoDependencies.dependenciesReports.flatMap { group =>
        val groupName =
          Row(style = headerStyle).withCellValues("Source:", group.groupName)
        val tableDescription = Row(style = headerStyle)
          .withCellValues(
            "Name",
            "Current Version",
            "Latest Version",
            "Vulnerabilities",
            "Notes"
          )
        (groupName :: tableDescription :: group.items
          .map(dependencyReport =>
            Row(style = chooseStyle(dependencyReport)).withCellValues(
              dependencyReport.name,
              dependencyReport.currentVersion.getOrElse(""),
              dependencyReport.latestVersion,
              dependencyReport.vulnerabilities.mkString(",\n"),
              dependencyReport.notes.getOrElse("")
            )
          )) :+ Row() // Add pseudo "margin-bottom"
      }
      Sheet(name = repoDependencies.project.name).withRows(rows*)

    private val headerStyle = CellStyle(font = Font(bold = true))

    private val chooseStyle = determineSeverity.andThen(matchSeverityToStyle)

    private def matchSeverityToStyle(severity: Severity): CellStyle =
      severity match
        case Severity.Unknown => CellStyle()

        case Severity.None =>
          CellStyle(
            fillForegroundColor = Color.Green,
            fillPattern = CellFill.Solid
          )

        case Severity.Low =>
          CellStyle(
            fillForegroundColor = Color.LightGreen,
            fillPattern = CellFill.Solid
          )

        case Severity.Medium =>
          CellStyle(
            fillForegroundColor = Color.Yellow,
            fillPattern = CellFill.Solid
          )

        case Severity.High =>
          CellStyle(
            fillForegroundColor = Color.Red,
            fillPattern = CellFill.Solid
          )
