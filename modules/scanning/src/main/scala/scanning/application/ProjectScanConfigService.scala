package scanning.application

import java.util.UUID

import cats.Monad
import cats.syntax.all.*
import core.domain.project.*

trait ProjectScanConfigService[F[_]]:
  def all: F[List[ExistingProjectScanConfig]]
  def find(name: String): F[Option[ExistingProjectScanConfig]]
  def add(project: ProjectScanConfig): F[Either[ProjectSaveError, Unit]]
  def setEnabled(
      name: String,
      enabled: Boolean
  ): F[Option[ExistingProjectScanConfig]]
  def delete(name: String): F[Unit]
  def setAutoUpdate(
      name: String,
      autoUpdate: Boolean
  ): F[Option[ExistingProjectScanConfig]]

object ProjectScanConfigService:
  def make[F[_]: Monad](repository: ProjectScanConfigRepository[F])
      : ProjectScanConfigService[F] = new:
    def all: F[List[ExistingProjectScanConfig]] = repository.all

    def find(name: String): F[Option[ExistingProjectScanConfig]] =
      all.map: configs =>
        configs.find: config =>
          config.project.name == name

    def add(project: ProjectScanConfig): F[Either[ProjectSaveError, Unit]] =
      repository.save(project).map(_.void)

    def setEnabled(
        name: String,
        enabled: Boolean
    ): F[Option[ExistingProjectScanConfig]] =
      repository.setEnabled(name, enabled) *> find(name)

    def setAutoUpdate(
        name: String,
        autoUpdate: Boolean
    ): F[Option[ExistingProjectScanConfig]] =
      repository.setAutoUpdate(name, autoUpdate) *> find(name)

    def delete(name: String): F[Unit] =
      find(name).flatMap:
        case None         => Monad[F].unit
        case Some(config) => repository.delete(config.project.id)
