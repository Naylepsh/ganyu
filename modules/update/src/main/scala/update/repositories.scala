package update

import java.util.UUID

import cats.effect.MonadCancelThrow
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.util.query.*
import doobie.util.transactor.Transactor
import update.domain.{ UpdateAttempt, UpdateRepository }
import update.domain.UpdateRequest

object repositories:
  object UpdateRepository:
    def make[F[_]: MonadCancelThrow: UUIDGen](xa: Transactor[F])
        : UpdateRepository[F] =
      new:
        def exists(
            projectId: UUID,
            dependencyName: String,
            toVersion: String
        ): F[Boolean] =
          UpdateRepositorySQL
            .exists(projectId, dependencyName, toVersion)
            .unique
            .transact(xa)

        def exist(requests: List[UpdateRequest]): F[List[UpdateRequest]] =
          UpdateRepositorySQL.exist(requests).to[List].transact(xa)

        def save(attempt: UpdateAttempt): F[UUID] =
          UUIDGen[F].randomUUID.flatMap: id =>
            UpdateRepositorySQL.save(id, attempt).run.transact(xa).as(id)

  private object UpdateRepositorySQL:
    import persistence.sqlmappings.given

    def exists(
        projectId: UUID,
        dependencyName: String,
        toVersion: String
    ): Query0[Boolean] =
      sql"""
      SELECT COUNT(*)
      FROM update_request
      WHERE project_id = $projectId
      AND dependency_name = $dependencyName
      AND update_to_version = $toVersion
      """.query[Int].map(_ > 0)

    def exist(requests: List[UpdateRequest]) =
      val frags = requests.map: request =>
        fr"(project_id = ${request.projectId} AND dependency_name = ${request.dependencyName} AND update_to_version = ${request.toVersion})"
      (sql"""
      SELECT project_id, dependency_name, update_to_version
      FROM update_request 
      """ ++ Fragments.whereOr(frags*)).query[UpdateRequest]

    def save(
        id: UUID,
        attempt: UpdateAttempt
    ): Update0 =
      sql"""
      INSERT INTO update_request (id, project_id, dependency_name, update_to_version, url)
      VALUES ($id, ${attempt.projectId}, ${attempt.dependencyName}, ${attempt.toVersion}, ${attempt.mergeRequestUrl})
      """.update
