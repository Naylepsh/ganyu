package persistence

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.MonadCancelThrow
import cats.effect.std.UUIDGen
import cats.effect.std.UUIDGen.randomUUID
import cats.implicits.*
import core.domain.dependency.DependencySource
import core.domain.dependency.DependencySource.{ TomlSource, TxtSource }
import core.domain.project.*
import doobie.*
import doobie.implicits.*
import doobie.util.query.*
import doobie.util.transactor.Transactor

object ProjectScanConfigRepository:
  def make[F[_]: MonadCancelThrow: UUIDGen](xa: Transactor[F])
      : ProjectScanConfigRepository[F] = new:
    def all: F[List[ExistingProjectScanConfig]] =
      for
        configs <- SQL.allConfigs.to[List].transact(xa)
        configIds = NonEmptyList.fromList(configs.map(_.configId))
        txtSources <- configIds
          .map(SQL.getTxtSources.andThen(_.to[List].transact(xa)))
          .getOrElse(List.empty.pure)
        tomlSources <- configIds
          .map(SQL.getTomlSources.andThen(_.to[List].transact(xa)))
          .getOrElse(List.empty.pure)
      yield SQL.RawConfig.toDomain(configs, txtSources, tomlSources)

    def findByProjectName(projectName: String)
        : F[Option[ExistingProjectScanConfig]] =
      SQL.findByProjectName(projectName).option.transact(xa).flatMap:
        case None => None.pure
        case Some(config) =>
          val ids = NonEmptyList.of(config.configId)
          (SQL.getTomlSources(ids).to[List], SQL.getTxtSources(ids).to[List])
            .tupled
            .transact(xa)
            .map: (tomlSources, txtSources) =>
              SQL.RawConfig.toDomain(
                config :: Nil,
                txtSources,
                tomlSources
              ).headOption

    def save(config: ProjectScanConfig): F[Either[ProjectSaveError, UUID]] =
      findByProjectName(config.project.name).flatMap:
        case Some(cfg) =>
          ProjectSaveError.ProjectAlreadyExists(cfg.project.name).asLeft.pure
        case None =>
          for
            projectId <- randomUUID
            configId  <- randomUUID
            sources <- config.sources.traverse: source =>
              randomUUID.map(id => id -> source)
            _ <-
              val inserts =
                for
                  _ <- SQL.insertProject(projectId, config.project).run
                  _ <- SQL.insertConfig(configId, config, projectId).run
                  // terrible performance, but it doesn't get called that much, so low priority
                  // TODO: Improve perf. by updating in batch
                  _ <- sources.traverse:
                    case (id, txt @ TxtSource(_)) =>
                      SQL.insertTxtSource(id, configId, txt).run
                    case (id, toml @ TomlSource(_, _)) =>
                      SQL.insertTomlSource(id, configId, toml).run
                yield ()
              inserts.transact(xa)
          yield configId.asRight

    def setEnabled(name: String, enabled: Boolean): F[Unit] =
      SQL.findScanId(name).option.transact(xa).flatMap:
        case None => MonadCancelThrow[F].unit
        case Some(scanId) =>
          SQL.setEnabled(scanId, enabled).run.transact(xa).void

    def setAutoUpdate(name: String, autoUpdate: Boolean): F[Unit] =
      SQL.findScanId(name).option.transact(xa).flatMap:
        case None => MonadCancelThrow[F].unit
        case Some(scanId) =>
          SQL.setAutoUpdate(scanId, autoUpdate).run.transact(xa).void

    def delete(projectId: UUID): F[Unit] =
      SQL.deleteProject(projectId).run.transact(xa).void

private object SQL:
  import persistence.sqlmappings.given

  private[persistence] case class RawConfig(
      projectId: UUID,
      projectName: String,
      configId: UUID,
      gitlabId: Int,
      enabled: Boolean,
      branch: String,
      autoUpdate: Boolean
  )
  private[persistence] object RawConfig:
    def toDomain(
        configs: List[RawConfig],
        txtSources: List[RawTxtSource],
        tomlSources: List[RawTomlSource]
    ): List[ExistingProjectScanConfig] =
      val txtMap = txtSources
        .groupBy(_.configId)
        .map: (projectId, sources) =>
          projectId -> sources.map: source =>
            TxtSource(source.path)
        .toMap
      val tomlMap = tomlSources
        .groupBy(_.configId)
        .map: (projectId, sources) =>
          projectId -> sources.map: source =>
            TomlSource(source.path, source.group)
        .toMap
      configs.map: config =>
        val sources = txtMap.getOrElse(config.configId, List.empty)
          ++ tomlMap.getOrElse(config.configId, List.empty)
        ExistingProjectScanConfig(
          config.configId,
          ExistingProject(
            config.projectId,
            config.gitlabId.toString,
            config.projectName
          ),
          sources,
          config.enabled,
          config.branch,
          config.autoUpdate
        )

  private[persistence] case class RawTxtSource(configId: UUID, path: String)
  private[persistence] case class RawTomlSource(
      configId: UUID,
      path: String,
      group: Option[String]
  )

  def allConfigs =
    sql"""
      SELECT project.id, project.name, config.id as configId, gitlab_id, enabled, branch, auto_update
      FROM project_scan_config config
      JOIN project ON project.id = config.project_id
      """.query[RawConfig]

  def getTxtSources(configIds: NonEmptyList[UUID]) =
    val s =
      sql"""
      SELECT config_id, path
      FROM txt_source
      WHERE """ ++ Fragments.in(fr"config_id", configIds)
    s.query[RawTxtSource]

  def getTomlSources(configIds: NonEmptyList[UUID]) =
    val s =
      sql"""
      SELECT config_id, path, target_group
      FROM toml_source
      WHERE """ ++ Fragments.in(fr"config_id", configIds)
    s.query[RawTomlSource]

  def insertProject(id: UUID, project: Project) =
    sql"""
    INSERT INTO project (id, name)
    VALUES ($id, ${project.name})
    """.update

  def deleteProject(id: UUID) =
    sql"""
    DELETE FROM project
    WHERE id = $id
    """.update

  def insertConfig(id: UUID, config: ProjectScanConfig, projectId: UUID) =
    sql"""
    INSERT INTO project_scan_config (id, gitlab_id, enabled, branch, project_id, auto_update)
    VALUES ($id, ${config.project.repositoryId}, ${config.enabled}, ${config.branch}, $projectId, ${config.autoUpdate})
    """.update

  def insertTxtSource(id: UUID, configId: UUID, source: TxtSource) =
    sql"""
    INSERT INTO txt_source (id, config_id, path)
    VALUES ($id, $configId, ${source.path})
    """.update

  def insertTomlSource(id: UUID, configId: UUID, source: TomlSource) =
    sql"""
    INSERT INTO toml_source (id, config_id, path, target_group)
    VALUES ($id, $configId, ${source.path}, ${source.group})
    """.update

  def findScanId(projectName: String) =
    sql"""
    SELECT project_scan_config.id
    FROM project_scan_config
    JOIN project on project.id = project_scan_config.project_id
    WHERE project.name = $projectName
    """.query[UUID]

  def findByProjectName(projectName: String) =
    sql"""
      SELECT project.id, project.name, config.id, gitlab_id, enabled, branch, auto_update
      FROM project_scan_config config
      JOIN project ON project.id = config.project_id
      WHERE project.name = $projectName
    """.query[RawConfig]

  def setEnabled(id: UUID, enabled: Boolean) =
    sql"""
    UPDATE project_scan_config
    SET enabled = $enabled
    WHERE id = $id
    """.update

  def setAutoUpdate(id: UUID, autoUpdate: Boolean) =
    sql"""
    UPDATE project_scan_config
    SET auto_update = $autoUpdate
    WHERE id = $id
    """.update
