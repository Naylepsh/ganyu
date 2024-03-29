package scanning.infra.sources

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

import cats.*
import cats.implicits.*
import core.domain.Grouped
import core.domain.dependency.DependencySource.{ TomlSource, TxtSource }
import core.domain.dependency.*
import core.domain.project.ProjectScanConfig
import gitlab.{ GitlabApi, RepositoryFile }
import org.legogroup.woof.{ *, given }
import parsers.python.{ PyProjectToml, RequirementsTxt }
import scanning.domain.Source

object GitlabSource:
  def make[F[_]: Monad: Logger](
      api: GitlabApi[F],
      contentParser: DependencySource => String => List[Dependency] =
        defaultContentParser
  ): Source[F, ProjectScanConfig] = new:
    def extract(project: ProjectScanConfig): F[List[Grouped[Dependency]]] =
      project.sources
        .traverse(source =>
          extractFromFile(project, source.path, contentParser(source))
            .map(dependencies => Grouped(source.groupName, dependencies))
        )

    private def extractFromFile(
        config: ProjectScanConfig,
        filePath: String,
        contentExtractor: String => List[Dependency]
    ): F[List[Dependency]] =
      api
        .getFile(config.project.repositoryId, config.branch, filePath)
        .flatMap {
          case Left(reason) =>
            Logger[F].error(
              s"Could not get the file contents of ${config.project.name} and $filePath due to $reason"
            ) *> List.empty.pure

          case Right(RepositoryFile(content)) =>
            GitlabApi.decodeContent(content) match
              case Left(_) =>
                Logger[F].error(
                  s"Could not decode content of ${config.project.name}'s $filePath"
                ) *> List.empty.pure

              case Right(decodedContent) =>
                contentExtractor(decodedContent).pure
        }

  def defaultContentParser(source: DependencySource)
      : String => List[Dependency] =
    source match
      case TxtSource(path) => RequirementsTxt.extract
      case TomlSource(path, group) =>
        PyProjectToml.extract(group).andThen(_.getOrElse(List.empty))
