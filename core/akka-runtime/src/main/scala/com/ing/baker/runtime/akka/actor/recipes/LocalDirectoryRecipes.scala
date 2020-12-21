package com.ing.baker.runtime.akka.actor.recipes

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.file.{FileSystems, Files, Path}
import java.util.Base64
import java.util.zip.{GZIPInputStream, ZipException}

import akka.Done
import akka.stream.alpakka.file.DirectoryChange
import akka.stream.alpakka.file.scaladsl.DirectoryChangesSource
import akka.stream.scaladsl.{Keep, RestartSource, Sink, Source}
import akka.stream.{KillSwitches, Materializer, RestartSettings, UniqueKillSwitch}
import cats.effect.{ContextShift, IO, Resource, Sync, Timer}
import cats.implicits._
import com.ing.baker.il.CompiledRecipe
import com.ing.baker.runtime.akka.actor.protobuf
import com.ing.baker.runtime.serialization.ProtoMap
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

object LocalDirectoryRecipes extends LazyLogging {

  def apply(path: String = sys.env.getOrElse("CONFIG_DIRECTORY", "/opt/docker/conf")): LocalDirectoryRecipes
  =  {
    val recipes = new LocalDirectoryRecipes()
    recipes.initialLoad(path).unsafeRunSync()
    recipes
  }

  def resource(path: String)(implicit contextShift: ContextShift[IO], timer: Timer[IO], materializer: Materializer): Resource[IO, LocalDirectoryRecipes] = {
    def watchSource: Source[(Path, DirectoryChange), UniqueKillSwitch] = {

      RestartSource.withBackoff(RestartSettings(
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2, // adds 20% "noise" to vary the intervals slightly
      )) { () =>
        DirectoryChangesSource(
          FileSystems.getDefault.getPath(path),
          10 seconds, 8192).filter({
          case (p, _) => p.endsWith(".recipe")
        }
        ).mapError { case e =>
          logger.error("Interaction discovery watch stream error: " + e.getMessage, e)
          e
        }
      }.viaMat(KillSwitches.single)(Keep.right)
    }

    def updateSink(recipes: LocalDirectoryRecipes): Sink[(Path, DirectoryChange), Future[Done]] = {
      Sink.foreach[(Path, DirectoryChange)]({
        case (p, DirectoryChange.Creation | DirectoryChange.Modification) =>
          logger.info(s"Recipe file $p changed")
          recipes.update(p).unsafeToFuture()
        case (p, DirectoryChange.Deletion)     =>
          logger.info(s"Recipe file $p deletion ignored")
          Future.successful(())
      })
    }

    val recipes = LocalDirectoryRecipes(path)

    Resource.make(
      IO(watchSource.toMat(updateSink(recipes))(Keep.left)
        .run())
        .map(killSwitch => (recipes, killSwitch))
    ) {
      case (_, hook) => IO(hook.shutdown())
    }.map(_._1)
  }
}

class LocalDirectoryRecipes()(implicit val sync: Sync[IO]) extends MutableRecipes[IO] with LazyLogging {

  def initialLoad(path: String): IO[Unit] = {
    for {
      files <- recipeFiles(path)
      recipes <- files.traverse(fromFile)
    } yield recipes
  }

  private def recipeFiles(path: String): IO[List[File]] = IO {
    val d = new File(path)
    if (d.exists && d.isDirectory) {
      d
        .listFiles
        .filter(_.isFile)
        .filter(_.getName.endsWith(".recipe"))
        .toList
    } else {
      List.empty[File]
    }
  }

  def update(path: Path): IO[Unit] = for {
    recipe <- fromFile(path.toFile)
    _ <- update(recipe, System.currentTimeMillis())
  } yield ()

  private[baker] def decode(bytes: Array[Byte]): Try[Array[Byte]] = Try {
    Base64.getDecoder.decode(new String(bytes))
  } recover {
    case _: IllegalArgumentException => bytes
  }

  private[baker] def unzip(bytes: Array[Byte]): Try[Array[Byte]] = Try {
    val inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))
    Stream.continually(inputStream.read()).takeWhile(_ != -1).map(_.toByte).toArray
  } recover {
    case _: ZipException =>
      bytes
  }

  private def inputStreamToBytes(is: InputStream): Array[Byte] =
    Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray

  private def fromFile(f: File): IO[CompiledRecipe] = for {
    rawBytes <- IO(inputStreamToBytes(Files.newInputStream(f.toPath)))
    decodedBytes <- IO.fromTry(decode(rawBytes))
    payload <- IO.fromTry(unzip(decodedBytes))
    protoRecipe <- IO.fromTry(protobuf.CompiledRecipe.validate(payload))
    recipe <- IO.fromTry(ProtoMap.ctxFromProto(protoRecipe))
  } yield recipe

}
