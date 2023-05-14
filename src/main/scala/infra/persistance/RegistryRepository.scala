package infra.persistance

import scala.io.Source

import cats.effect.*
import cats.implicits.*
import domain.registry.{ Project, Registry, RegistryRepository }
import io.circe.parser.decode
import cats.Applicative

object RegistryRepository:
  def fileBased(pathToFile: String): RegistryRepository[IO] = new:
    def get(): IO[Either[Throwable, Registry]] = IO {
      val content = Source.fromFile(pathToFile).getLines.mkString("\n")
      val decoded = decode[Registry](content)
      decoded.leftMap(error => RuntimeException(error.getMessage()))
    }
