package org.overviewproject.jobhandler.filegroup

import org.specs2.mutable.Specification
import scala.concurrent.Future
import scala.util.Try
import scala.util.Success
import org.overviewproject.test.ActorSystemContext
import akka.testkit.TestActorRef
import org.overviewproject.jobhandler.filegroup.FileGroupMessageHandlerProtocol._
import akka.actor._
import org.overviewproject.test.ForwardingActor
import akka.testkit.TestProbe
import org.overviewproject.jobhandler.filegroup.FileHandlerProtocol.ExtractText
import org.overviewproject.jobhandler.MessageServiceComponent
import org.overviewproject.jobhandler.JobDone
import org.specs2.mock.Mockito

class DummyActor extends Actor {
  def receive = {
    case _ =>
  }
}

class FileGroupJobHandlerSpec extends Specification with Mockito {

  "FileGroupJobHandler" should {

    class TestMessageHandler extends FileGroupMessageHandler with TextExtractorComponent {

      val actorCreator = mock[ActorCreator]
      actorCreator.produceTextExtractor returns Props[DummyActor]

    }

    "start file handler on incoming command" in new ActorSystemContext {
      val command = ProcessFileCommand(1l, 10l)

      val messageHandler = TestActorRef(new TestMessageHandler)

      messageHandler ! command
      val actorCreator = messageHandler.underlyingActor.actorCreator

      there was one(actorCreator).produceTextExtractor
    }

    "forward JobDone to parent" in new ActorSystemContext {
      val fileGroupId = 1l

      val parentProbe = TestProbe()
      val messageHandler = TestActorRef(Props(new TestMessageHandler), parentProbe.ref, "message handler")

      messageHandler ! JobDone(fileGroupId)

      parentProbe.expectMsg(JobDone(fileGroupId))
    }
  }
}