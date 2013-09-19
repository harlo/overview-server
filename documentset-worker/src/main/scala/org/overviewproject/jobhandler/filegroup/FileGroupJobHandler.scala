package org.overviewproject.jobhandler.filegroup

import akka.actor._

import org.overviewproject.jobhandler.{ MessageHandling, MessageQueueActor, MessageServiceComponentImpl }
import org.overviewproject.jobhandler.MessageQueueActor
import org.overviewproject.jobhandler.MessageServiceComponentImpl
import org.overviewproject.jobhandler.filegroup.FileHandlerProtocol._

import FileGroupMessageHandlerProtocol._

class FileGroupJobHandler extends MessageQueueActor[Command] with MessageServiceComponentImpl with MessageHandling[Command] {
  override val messageService = new MessageServiceImpl("/queue/file-group-commands")
  override def createMessageHandler: Props = FileGroupMessageHandler()
  override def convertMessage(message: String): Command = ConvertFileGroupMessage(message)
}

object FileGroupJobHandler {
  def apply(): Props = Props[FileGroupJobHandler]
}