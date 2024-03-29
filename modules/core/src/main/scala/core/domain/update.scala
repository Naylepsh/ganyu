package core.domain

import java.util.UUID

import dependency.DependencyReport

object update:
  case class UpdateDependency(
      projectName: String,
      dependencyName: String,
      filePath: String,
      fromVersion: String,
      toVersion: String
  )

  case class DependencyToUpdate(
      name: String,
      currentVersion: Option[String],
      latestVersion: String
  )

  trait UpdateGateway[F[_]]:
    def canUpdate(
        dependencies: List[DependencyToUpdate],
        projectId: UUID,
        sourceFile: String
    ): F[List[(DependencyToUpdate, Boolean)]]
    def update(dependencies: List[UpdateDependency]): F[Unit]
