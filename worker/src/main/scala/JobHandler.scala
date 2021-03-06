/**
 *
 * JobHandler.scala
 *
 * Overview Project,June 2012
 * @author Jonas Karlsson
 */

import java.sql.Connection

import scala.annotation.tailrec
import scala.util._

import org.elasticsearch.ElasticSearchException
import org.overviewproject.clone.CloneDocumentSet
import org.overviewproject.clustering.{ DocumentSetIndexer, DocumentSetIndexerOptions }
import org.overviewproject.database.{ SystemPropertiesDatabaseConfiguration, Database, DataSource, DB }
import org.overviewproject.persistence._
import org.overviewproject.persistence.orm.finders.{ DocumentFinder, FileFinder, FileGroupFinder, GroupedFileUploadFinder }
import org.overviewproject.persistence.orm.stores.{ FileStore, FileGroupStore, GroupedFileUploadStore }
import org.overviewproject.tree.DocumentSetCreationJobType
import org.overviewproject.tree.orm.DocumentSetCreationJobState._
import org.overviewproject.util._
import org.overviewproject.util.Progress._
import com.jolbox.bonecp._
import org.overviewproject.util.SearchIndex
import org.overviewproject.nlp.DocumentVectorTypes.TermWeight

object JobHandler {
  // Run a single job
  def handleSingleJob(j: PersistentDocumentSetCreationJob): Unit = {
    try {
      j.state = InProgress
      Database.inTransaction { j.update }
      j.observeCancellation(deleteCancelledJob)

      def checkCancellation(progress: Progress): Unit = Database.inTransaction(j.checkForCancellation)

      def updateJobState(progress: Progress): Unit = {
        j.fractionComplete = progress.fraction
        j.statusDescription = Some(progress.status.toString)
        Database.inTransaction { j.update }
      }

      def logProgress(progress: Progress): Unit = {
        val logLabel = if (j.state == Cancelled) "CANCELLED"
        else "PROGRESS"

        Logger.info(s"[${j.documentSetId}] $logLabel: ${progress.fraction * 100}% done. ${progress.status}, ${if (progress.hasError) "ERROR" else "OK"}")
      }

      val progressReporter = new ThrottledProgressReporter(stateChange = Seq(updateJobState, logProgress), interval = Seq(checkCancellation))

      def progFn(progress: Progress): Boolean = {
        progressReporter.update(progress)
        j.state == Cancelled
      }

      j.jobType match {
        case DocumentSetCreationJobType.Clone => handleCloneJob(j)
        case _ => handleCreationJob(j, progFn)
      }

      Logger.info(s"Cleaning up job ${j.documentSetId}")
      Database.inTransaction {
        j.delete
        deleteFileGroupData(j)
      }

    } catch {
      case e: Exception => reportError(j, e)
      case t: Throwable => { // Rethrow (and die) if we get non-Exception throwables, such as java.lang.error
        reportError(j, t)
        DB.close()
        throw (t)
      }
    }
  }

  // Run each job currently listed in the database
  def scanForJobs: Unit = {

    val firstSubmittedJob: Option[PersistentDocumentSetCreationJob] = Database.inTransaction {
      PersistentDocumentSetCreationJob.findFirstJobWithState(NotStarted)
    }

    firstSubmittedJob.map { j =>
      Logger.info(s"Processing job: ${j.documentSetId}")
      handleSingleJob(j)
      System.gc()
    }
  }

  def restartInterruptedJobs(implicit c: Connection) {
    Database.inTransaction {
      val interruptedJobs = PersistentDocumentSetCreationJob.findJobsWithState(InProgress)
      val restarter = new JobRestarter(new DocumentSetCleaner)

      restarter.restart(interruptedJobs)
    }
  }

  def main(args: Array[String]) {
    val config = new SystemPropertiesDatabaseConfiguration()
    val dataSource = new DataSource(config)

    DB.connect(dataSource)

    connectToSearchIndex
    Logger.info("Starting to scan for jobs")
    startHandlingJobs
  }

  @tailrec
  private def connectToSearchIndex: Unit = {
    val SearchIndexRetryInterval = 5000

    Logger.info("Looking for Search Index")
    val attempt = Try {
      SearchIndex.createIndexIfNotExisting
    }

    attempt match {
      case Success(v) => Logger.info("Found Search Index")
      case Failure(e) => {
        Logger.error("Unable to create Search Index", e)
        Thread.sleep(SearchIndexRetryInterval)
        connectToSearchIndex
      }
    }
  }
  private def startHandlingJobs: Unit = {
    val pollingInterval = 500 //milliseconds

    DB.withConnection { implicit connection =>
      restartInterruptedJobs
    }

    while (true) {
      // Exit when the user enters Ctrl-D
      while (System.in.available > 0) {
        val EOF = 4
        val next = System.in.read
        if (next == EOF) {
          System.exit(0)
        }
      }
      scanForJobs
      Thread.sleep(pollingInterval)
    }
  }

  def deleteCancelledJob(job: PersistentDocumentSetCreationJob) {
    import scala.language.postfixOps
    import anorm._
    import anorm.SqlParser._
    import org.overviewproject.persistence.orm.Schema._
    import org.squeryl.PrimitiveTypeMode._

    Logger.info(s"[${job.documentSetId}] Deleting cancelled job")
    Database.inTransaction {
      implicit val connection = Database.currentConnection

      val id = job.documentSetId
      SQL("SELECT lo_unlink(contents_oid) FROM document_set_creation_job WHERE document_set_id = {id} AND contents_oid IS NOT NULL").on('id -> id).as(scalar[Int] *)
      SQL("DELETE FROM document_set_creation_job WHERE document_set_id = {id}").on('id -> id).executeUpdate()

      deleteFileGroupData(job)
    }
  }

  private def handleCreationJob(job: PersistentDocumentSetCreationJob, progressFn: ProgressAbortFn) {
    val documentSet = DB.withConnection { implicit connection =>
      DocumentSetLoader.load(job.documentSetId)
    }

    def documentSetInfo(documentSet: Option[DocumentSet]): String = documentSet.map { ds =>
      val query = ds.query.map(q => s"Query: $q").getOrElse("")
      val uploadId = ds.uploadedFileId.map(u => s"UploadId: $u").getOrElse("")

      s"Creating DocumentSet: ${job.documentSetId} Title: ${ds.title} $query $uploadId Splitting: ${job.splitDocuments}".trim
    }.getOrElse(s"Creating DocumentSet: Could not load document set id: ${job.documentSetId}")

    // Converts "important words" options string into a map of regex->weight
    // splits on runs of spaces, fixed weight
    def makeEmphasizedWords(s: Option[String]): Map[String, TermWeight] = {
      if (s.isEmpty) {
        Map[String, TermWeight]()
      } else {
        val extraWeight: TermWeight = 5
        "[ \t\n\r\u00A0]+".r.replaceAllIn(s.get, " ").split(' ').filter(!_.isEmpty).map(w => (w, extraWeight)).toMap
      }
    }

    Logger.info(documentSetInfo(documentSet))

    documentSet.map { ds =>
      val nodeWriter = new NodeWriter(job.documentSetId)

      val opts = new DocumentSetIndexerOptions
      opts.lang = job.lang
      opts.suppliedStopWords = job.suppliedStopWords
      opts.emphasizedWordsRegex = makeEmphasizedWords(job.importantWords)
      val indexer = new DocumentSetIndexer(nodeWriter, opts, progressFn)
      val producer = DocumentProducerFactory.create(job, ds, indexer, progressFn)

      producer.produce()
    }

  }

  private def handleCloneJob(job: PersistentDocumentSetCreationJob) {
    import org.overviewproject.clone.{ JobProgressLogger, JobProgressReporter }

    val jobProgressReporter = new JobProgressReporter(job)
    val progressObservers: Seq[Progress => Unit] = Seq(
      jobProgressReporter.updateStatus _,
      JobProgressLogger.apply(job.documentSetId, _: Progress))

    job.sourceDocumentSetId.map { sourceDocumentSetId =>
      Logger.info(s"Creating DocumentSet: ${job.documentSetId} Cloning Source document set id: $sourceDocumentSetId")
      CloneDocumentSet(sourceDocumentSetId, job.documentSetId, job, progressObservers)
    }
  }

  private def failOnUploadedDocumentSets(documentSetId: Long): Boolean = Database.inTransaction {
    DocumentFinder.byDocumentSet(documentSetId).headOption.map { document =>
      document.fileId.isDefined
    }.getOrElse(false)
  }

  private def reportError(job: PersistentDocumentSetCreationJob, t: Throwable): Unit = {
    Logger.error(s"Job for DocumentSet id ${job.documentSetId} failed: $t\n${t.getStackTrace.mkString("\n")}")
    job.state = Error
    job.statusDescription = Some(ExceptionStatusMessage(t))
    Database.inTransaction {
      job.update
      if (job.state == Cancelled) job.delete
    }
  }

  private def deleteFileGroupData(job: PersistentDocumentSetCreationJob): Unit = {
    job.fileGroupId.map { fileGroupId =>
      FileStore.delete(FileFinder.byFileGroup(fileGroupId).toQuery)
      GroupedFileUploadStore.delete(GroupedFileUploadFinder.byFileGroup(fileGroupId).toQuery)

      FileGroupStore.delete(FileGroupFinder.byId(fileGroupId).toQuery)
    }

  }
}

