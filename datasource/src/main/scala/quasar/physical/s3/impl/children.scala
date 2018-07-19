/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.s3.impl

import quasar.contrib.pathy._

import slamdata.Predef._

import cats.Functor
import cats.effect.Sync
import cats.instances.int._
import cats.instances.list._
import cats.instances.option._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import cats.syntax.option._
import cats.syntax.traverse._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.scalaxml.{xml => xmlDecoder}
import pathy.Path
import pathy.Path.{DirName, FileName}
import scala.xml
import scalaz.{\/-, -\/}
import shims._

object children {
  // S3 provides a recursive listing (akin to `find` or
  // `dirtree`); we filter out children that aren't direct
  // children. We can only list 1000 keys, and need pagination
  // to do more. That's 1000 *recursively listed* keys, so we
  // could conceivably list *none* of the direct children of a
  // folder without pagination, depending on the order AWS
  // sends them in.
  //
  // FIXME: dir should be ADir and pathToDir should be deleted
  def apply[F[_]: Sync](client: Client[F], bucket: Uri, dir: APath): F[Option[List[PathSegment]]] = {
    val FO = Functor[F].compose[Option]

    val filtered =
      FO.map(descendants(client, bucket, dir))(paths =>
        paths.filter(path => Path.parentDir(path) === pathToDir(dir)))

    FO.map(filtered)(paths => paths.flatMap(Path.peel(_).toList).map(_._2))
  }


  // Lists all objects and prefixes in a bucket. This needs to be filtered
  private def descendants[F[_]: Sync](client: Client[F], bucket: Uri, dir: APath): F[Option[List[APath]]] = {
    // Converts a pathy Path to an S3 object prefix.
    val objectPrefix = aPathToObjectPrefix(dir)

    // Start with the bucket URI; add an extra `/` on the end
    // so that S3 understands us.
    // `list-type=2` asks for the new version of the list api.
    // We only add the `objectPrefix` if it's not empty;
    // the S3 API doesn't understand empty `prefix`.
    val listingQuery = (bucket / "") withQueryParam ("list-type", 2)
    val queryUri =
      objectPrefix.fold(listingQuery)(prefix =>
        listingQuery
          .withQueryParam("prefix", prefix))

      for {
        // Send request to S3, parse the response as XML.
        topLevelElem <- client.expect[xml.Elem](queryUri)
        // Grab child object names from the response.
        response <- for {
          contents <- Sync[F].suspend {
            // Grab <Contents>.
            Sync[F].catchNonFatal((topLevelElem \\ "Contents"))
              .adaptError {
                case ex: Exception =>
                  new Exception("XML received from AWS API has no top-level <Contents>", ex)
              }
          }

          keyCount <- Sync[F].suspend {
            // Grab <KeyCount> to ensure this response is not empty.
            Sync[F].catchNonFatal((topLevelElem \\ "KeyCount").text.toInt)
              .adaptError {
                case ex: Exception =>
                  new Exception("XML received from AWS API has no top-level <KeyCount>", ex)
              }
          }

          // Grab all of the <Key> elements from <Contents>.
          names <- contents.toList.traverse[F, String] { elem =>
            Sync[F].catchNonFatal((elem \\ "Key").text)
              .adaptError {
                case ex: Exception =>
                  new Exception("XML received from AWS API has no <Key> elements under <Contents>", ex)
              }
          }
        } yield (names, keyCount)

        (children, keyCount) = response

        // Convert S3 object names to paths.
        childPaths <- children.traverse(s3NameToPath)
          .fold(Sync[F].raiseError[List[APath]](new Exception(s"Failed to parse object path in S3 API response")))(Sync[F].pure)

        // Deal with some S3 idiosyncrasies.
        // TODO: Pagination

        result = if (keyCount === 0) {
          None
        } else {
          // AWS includes the folder itself in the returned
          // results, so we have to remove it.
          // TODO: Report an error if there are duplicates. Remove duplicates.
          Some(childPaths.filter(path => path =!= dir))
        }
      } yield result
  }

  private def aPathToObjectPrefix(apath: APath): Option[String] = {
    // Don't provide an object prefix if listing the
    // entire bucket. Otherwise, we have to drop
    // the first `/`, because object prefixes can't
    // begin with `/`.
    if (apath != Path.rootDir) {
      Path.posixCodec.printPath(apath).drop(1).self.some // .replace("/", "%2F")
    }
    else {
      none[String]
    }
  }

  private def s3NameToPath(name: String): Option[APath] = {
    val unsandboxed: Option[Path[Path.Abs, Any, Path.Unsandboxed]] =
      // Parse S3 object paths as POSIX paths with a missing
      // slash at the beginning. Relative paths are disallowed
      // by the `_ => none` cases.
      Path.posixCodec.parsePath(
        _ => none,
        _.some,
        _ => none,
        _.some
      )("/" + name)
    // unsafeSandboxAbs is from quasar.
    // `posixCodec` parses unsandboxed ("real") paths;
    // we don't use them here.
    unsandboxed.map(unsafeSandboxAbs)
  }

  private def pathToDir(path: APath): Option[ADir] =
    Path.peel(path) match {
      case Some((d, \/-(FileName(fn)))) => (d </> Path.dir(fn)).some
      case Some((d, -\/(DirName(dn)))) => (d </> Path.dir(dn)).some
      case None if Path.depth(path) === 0 => Path.rootDir.some
      case _ => none
    }
}