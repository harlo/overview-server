package org.overviewproject.csv

import org.overviewproject.test.DbSpecification
import org.specs2.execute.PendingUntilFixed
import org.overviewproject.postgres.LO
import org.overviewproject.database.DB
import org.overviewproject.test.DbSetup._
import java.io.InputStreamReader
import java.nio.charset.Charset
import persistence.EncodedUploadFile
import java.io.Reader

class UploadReaderSpec extends DbSpecification {

  step(setupDb)

  "UploadReader" should {

    trait UploadContext extends DbTestContext {
      def uploadSize: Int
      def data: Array[Byte]
      def contentType: String

      var uploadId: Long = _
      var uploadReader: UploadReader = _
      var reader: Reader = _

      override def setupWithDb = {
        implicit val pgc = DB.pgConnection
        val loid = LO.withLargeObject { lo =>
          lo.add(data)
          lo.oid
        }
        uploadId = insertUploadedFile("content-disposition", contentType, uploadSize)
        val uploadedFile = EncodedUploadFile.load(uploadId)
        
        uploadReader = new UploadReader
        
        reader = uploadReader.reader(loid, uploadedFile)
      }
    }

    trait LargeData extends UploadContext {
      def uploadSize = 10000
      def data: Array[Byte] = Array.fill(uploadSize)(74)
      def contentType = "application/octet-stream"
    }

    implicit def b(x: Int): Byte = x.toByte

    trait Windows1252Data extends UploadContext {
      def data: Array[Byte] = Array[Byte](159, 128, 154)
      val windows1252Text = new String(data, "windows-1252")
      def contentType = "application/octet-stream ; charset=windows-1252"
      def uploadSize = data.size
    }

    trait InvalidEncoding extends UploadContext {
      def uploadSize = 5
      def data: Array[Byte] = Array.fill(uploadSize)(74)
      def contentType = "application/octet-stream ; charset=notArealCharSet"
    }

    trait InvalidInput extends UploadContext {
      def uploadSize = 1
      def data: Array[Byte] = Array[Byte](255)
      def contentType = "application/octet-stream ; charset=utf-8"
    }

    "create reader from UploadedFile" in new LargeData {
      val buffer = new Array[Char](20480)
      val numRead = reader.read(buffer)
      buffer.take(numRead) must be equalTo (data.map(_.toChar).take(numRead))
    }
    
    "return bytesRead" in new LargeData {
      uploadReader.bytesRead must be equalTo (0)

      val buffer = new Array[Char](20480)
      val numRead = reader.read(buffer)
      uploadReader.bytesRead must be equalTo (numRead)
      reader.read(buffer)
      uploadReader.bytesRead must be equalTo (uploadSize)
    }

    "read non-default encoding" in new Windows1252Data {
      val buffer = new Array[Char](uploadSize)
      reader.read(buffer)

      buffer must be equalTo (windows1252Text.toCharArray())
    }

    "default to UTF-8 if specified encoding is not valid" in new InvalidEncoding {
      val buffer = new Array[Char](uploadSize)
      reader.read(buffer)
      buffer must be equalTo data.map(_.toChar)
    }

    "insert replacement character for invalid input" in new InvalidInput {
      val replacement = Charset.forName("UTF-8").newDecoder.replacement.toCharArray()

      val buffer = new Array[Char](uploadSize)
      reader.read(buffer)

      buffer must be equalTo (replacement)
    }
  }

  step(shutdownDb)
}