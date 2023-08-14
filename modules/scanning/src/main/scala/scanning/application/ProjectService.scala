package scanning.application

import core.domain.registry.ProjectScanConfig
import core.domain.registry.Registry
import cats.syntax.all.*
import cats.Applicative
import core.domain.registry.RegistryRepository

trait ProjectService[F[_]]:
  def all: F[List[ProjectScanConfig]]
  def find(name: String): F[Option[ProjectScanConfig]]

object ProjectService:
  def make[F[_]: Applicative](repository: RegistryRepository[F])
      : ProjectService[F] = new:
    def all: F[List[ProjectScanConfig]] = repository.get().map:
      case Left(_)         => List.empty
      case Right(registry) => registry.projects
    def find(name: String): F[Option[ProjectScanConfig]] =
      all.map: projects =>
        projects.find: project =>
          project.name == name