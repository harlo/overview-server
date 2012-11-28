package controllers.util

import java.util.UUID
import org.postgresql.PGConnection
import org.squeryl.PrimitiveTypeMode.using
import org.squeryl.Session
import com.jolbox.bonecp.ConnectionHandle
import models.orm.SquerylPostgreSqlAdapter
import overview.largeobject.LO
import models.upload.OverviewUpload
import play.api.db.DB
import play.api.http.HeaderNames._
import play.api.libs.iteratee.Done
import play.api.libs.iteratee.Input
import play.api.libs.iteratee.Iteratee
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import play.api.mvc.Results.InternalServerError
import play.api.Play.current
import scala.util.control.Exception._
import java.sql.SQLException

/**
 * Manages the upload of a file. Responsible for making sure the OverviewUpload object
 * is in sync with the LargeObject where the file is stored.
 */
trait FileUploadIteratee {
  private def X_MSHACK_CONTENT_RANGE: String = "X-MSHACK-Content-Range"
  private def DefaultBufferSize: Int = 1024 * 1024

  /** package for information extracted from request header */
  private case class UploadRequest(contentDisposition: String, contentType: String, start: Long, contentLength: Long)

  /** extract useful information from request header */
  private object UploadRequest {
    def apply(header: RequestHeader): Option[UploadRequest] = {
      val headers = header.headers

      val contentDisposition = headers.get(CONTENT_DISPOSITION).getOrElse("")
      val contentType = headers.get(CONTENT_TYPE).getOrElse("")
      val range = """(\d+)-(\d+)/(\d+)""".r // start-end/length
      for {
        contentRange <- headers.get(CONTENT_RANGE).orElse(headers.get(X_MSHACK_CONTENT_RANGE))
        rangeMatch <- range.findFirstMatchIn(contentRange)
      } yield {
        val List(start, end, length) = rangeMatch.subgroups.take(3)
        UploadRequest(contentDisposition, contentType, start.toLong, length.toLong)
      }
    }
  }

  /**
   * Checks the validity of the requests and processes the upload.
   */
  def store(userId: Long, guid: UUID, requestHeader: RequestHeader, bufferSize: Int = DefaultBufferSize): Iteratee[Array[Byte], Either[Result, OverviewUpload]] = {

    val uploadRequest = UploadRequest(requestHeader).toRight(BadRequest)

    uploadRequest.fold(
      errorStatus => Done(Left(errorStatus), Input.Empty),
      request => handleUploadRequest(userId, guid, request, bufferSize))
  }

  /**
   * @return an Iteratee for processing an upload request specified by info
   * The Iteratee will continue to consume the uploaded data even if an
   * error is encountered, but will not ignore the data received after the
   * error occurs.
   */
  private def handleUploadRequest(userId: Long, guid: UUID, request: UploadRequest, bufferSize: Int): Iteratee[Array[Byte], Either[Result, OverviewUpload]] = {
    val initialUpload = findValidUploadRestart(userId, guid, request)
      .getOrElse(Right(createUpload(userId, guid, request.contentDisposition, request.contentType, request.contentLength)))

    var buffer = Array[Byte]()

    Iteratee.fold[Array[Byte], Either[Result, OverviewUpload]](initialUpload) { (upload, chunk) =>
      val validUpload = upload.right.flatMap(validUploadWithChunk(_, chunk).toRight(BadRequest))
      validUpload.right.flatMap { u =>
        if ((buffer.size + chunk.size) >= bufferSize) {
          val bufferedChunk = buffer ++ chunk
          buffer = Array[Byte]()
          Right(appendChunk(u, bufferedChunk))
        } else {
          buffer ++= chunk
          Right(u)
        }
      }
    } mapDone { u =>
      if (buffer.size > 0) u.right.map(appendChunk(_, buffer))
      else u
    }
  }

  /**
   * If adding the chunk to the upload does not exceed the expected
   * size of the upload, @return Some(upload), None otherwise
   */
  private def validUploadWithChunk(upload: OverviewUpload, chunk: Array[Byte]): Option[OverviewUpload] =
    Some(upload).filter(u => u.uploadedFile.size + chunk.size <= u.size)

  /**
   * If the upload exists, verify the validity of the restart.
   * @return None if upload does not exist, otherwise an Either containing
   * an error status if request is invalid or the valid OverviewUpload.
   * If start is 0, any previously uploaded data is truncated.
   */
  private def findValidUploadRestart(userId: Long, guid: UUID, info: UploadRequest): Option[Either[Result, OverviewUpload]] =
    findUpload(userId, guid).map(u =>
      info.start match {
        case 0 => Right(truncateUpload(u))
        case n if n == u.uploadedFile.size => Right(u)
        case _ => {
          cancelUpload(u)
          Left(BadRequest)
        }
      })

  // Find an existing upload attempt
  def findUpload(userId: Long, guid: UUID): Option[OverviewUpload]

  // create a new upload attempt
  def createUpload(userId: Long, guid: UUID, contentDisposition: String, contentType: String, contentLength: Long): OverviewUpload

  // process a chunk of file data. @return the current OverviewUpload status, or None on failure	  
  def appendChunk(upload: OverviewUpload, chunk: Array[Byte]): OverviewUpload

  // Truncate the upload, deleting all previously uploaded data
  // @return the truncated OverviewUpload status, or None on failure
  def truncateUpload(upload: OverviewUpload): OverviewUpload

  // Remove all data from previously started upload
  def cancelUpload(upload: OverviewUpload)
}

/** Implementation that writes to database */
object FileUploadIteratee extends FileUploadIteratee with PgConnection {

  def findUpload(userId: Long, guid: UUID) = withPgConnection { implicit c => OverviewUpload.find(userId, guid) }

  def createUpload(userId: Long, guid: UUID, contentDisposition: String, contentType: String, contentLength: Long): OverviewUpload = withPgConnection { implicit c =>
    LO.withLargeObject { lo => OverviewUpload(userId, guid, contentDisposition, contentType, contentLength, lo.oid).save }
  }

  def appendChunk(upload: OverviewUpload, chunk: Array[Byte]): OverviewUpload = withPgConnection { implicit c =>
    LO.withLargeObject(upload.uploadedFile.contentsOid) { lo => upload.withUploadedBytes(lo.add(chunk)).save }
  }

  def truncateUpload(upload: OverviewUpload): OverviewUpload = withPgConnection { implicit c =>
    LO.withLargeObject(upload.uploadedFile.contentsOid) { lo =>
      lo.truncate
      upload.truncate.save
    }
  }

  def cancelUpload(upload: OverviewUpload) = withPgConnection { implicit c =>
    LO.delete(upload.uploadedFile.contentsOid)
    upload.uploadedFile.delete
    upload.delete
  }
}
