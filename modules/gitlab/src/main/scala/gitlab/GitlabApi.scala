package gitlab

import java.net.URI

import scala.concurrent.duration.*

import cats.*
import cats.implicits.*
import io.circe.derivation.{
  Configuration,
  ConfiguredDecoder,
  ConfiguredEncoder
}
import io.circe.{ Decoder, Encoder }
import sttp.capabilities.WebSockets
import sttp.client3.*
import sttp.client3.circe.*
import sttp.model.Uri

case class RepositoryTreeFile(name: String, path: String) derives Decoder

type RepositoryTree = List[RepositoryTreeFile]

case class RepositoryFile(content: String) derives Decoder

case class CreateMergeRequestResponse(
    webUrl: URI
)

enum Action:
  case Create, Delete, Move, Update, Chmod

case class CommitAction(
    action: Action,
    filePath: String,
    content: String
)

trait GitlabApi[F[_]]:
  def getFile(
      id: String,
      branch: String,
      filePath: String
  ): F[Either[String, RepositoryFile]]
  def createBranch(
      projectId: String,
      baseBranch: String,
      newBranchName: String
  ): F[Either[String, Unit]]
  def createCommit(
      projectId: String,
      branch: String,
      commitMessage: String,
      actions: List[CommitAction]
  ): F[Either[String, Unit]]
  def createMergeRequest(
      projectId: String,
      sourceBranch: String,
      targetBranch: String,
      title: String
  ): F[Either[String, CreateMergeRequestResponse]]
  def getFileContent(
      projectId: String,
      branch: String,
      pathToFile: String
  ): F[Either[String, String]]

object GitlabApi:
  type RequestResult[F[_]] = ApplicativeError[F, Throwable]

  def make[F[_]: Applicative: RequestResult](
      backend: SttpBackend[F, WebSockets],
      host: String,
      token: Option[String]
  ): GitlabApi[F] = new:
    given Configuration = Configuration.default.withSnakeCaseMemberNames
    given ConfiguredDecoder[CreateMergeRequestResponse] =
      ConfiguredDecoder.derived
    given Encoder[Action] = Encoder[String].contramap {
      case Action.Create => "create"
      case Action.Delete => "delete"
      case Action.Move   => "move"
      case Action.Update => "update"
      case Action.Chmod  => "chmod"
    }
    given ConfiguredEncoder[CommitAction] = ConfiguredEncoder.derived

    private case class CreateCommitPayload(
        branch: String,
        commitMessage: String,
        actions: List[CommitAction]
    ) derives ConfiguredEncoder

    private case class CreateMergeRequestPayload(
        sourceBranch: String,
        targetBranch: String,
        title: String,
        removeSourceBranch: Boolean,
        squashOnMerge: Boolean
    ) derives ConfiguredEncoder

    override def createMergeRequest(
        projectId: String,
        sourceBranch: String,
        targetBranch: String,
        title: String
    ): F[Either[String, CreateMergeRequestResponse]] =
      val endpoint =
        uri"https://$host/api/v4/projects/$projectId/merge_requests"

      basicRequest
        .post(endpoint)
        .readTimeout(10.seconds)
        .header("PRIVATE-TOKEN", token)
        .body(CreateMergeRequestPayload(
          sourceBranch,
          targetBranch,
          title,
          removeSourceBranch = true,
          squashOnMerge = true
        ))
        .response(asJson[CreateMergeRequestResponse])
        .send(backend)
        .map(_.body.leftMap(buildErrorMessage(endpoint)))

    override def createCommit(
        projectId: String,
        branch: String,
        commitMessage: String,
        actions: List[CommitAction]
    ): F[Either[String, Unit]] =
      val endpoint =
        uri"https://$host/api/v4/projects/$projectId/repository/commits"

      basicRequest
        .post(endpoint)
        .readTimeout(10.seconds)
        .header("PRIVATE-TOKEN", token)
        .body(CreateCommitPayload(branch, commitMessage, actions))
        .send(backend)
        .map(_.body.void)

    override def createBranch(
        projectId: String,
        baseBranch: String,
        newBranchName: String
    ): F[Either[String, Unit]] =
      val queryParams = Map(
        "ref"    -> baseBranch,
        "branch" -> newBranchName
      )
      val endpoint =
        uri"https://$host/api/v4/projects/$projectId/repository/branches?$queryParams"

      basicRequest
        .post(endpoint)
        .readTimeout(10.seconds)
        .header("PRIVATE-TOKEN", token)
        .send(backend)
        .map(_.body.void)

    def getFile(
        id: String,
        branch: String,
        filePath: String
    ): F[Either[String, RepositoryFile]] =
      val queryParams = Map("ref" -> branch)
      val projectFileEndpoint =
        uri"https://$host/api/v4/projects/$id/repository/files/$filePath?$queryParams"

      basicRequest
        .get(projectFileEndpoint)
        .readTimeout(10.seconds)
        .header("PRIVATE-TOKEN", token)
        .response(asJson[RepositoryFile])
        .send(backend)
        .map(_.body.leftMap(buildErrorMessage(projectFileEndpoint)))

    def getFileContent(
        projectId: String,
        branch: String,
        pathToFile: String
    ): F[Either[String, String]] =
      getFile(projectId, branch, pathToFile)
        .map: fileResult =>
          fileResult.flatMap: file =>
            GitlabApi.decodeContent(file.content)

  def decodeContent(encodedContent: String): Either[String, String] =
    Either
      .catchNonFatal(
        new String(java.util.Base64.getDecoder.decode(encodedContent))
      )
      .leftMap(_.toString)

  private def buildErrorMessage(url: sttp.model.Uri)(
      exception: ResponseException[
        String,
        io.circe.Error
      ]
  ): String =
    s"url: ${url.toString}, ${exception.getMessage()}"
