package streamcomponents

import java.io.File

import akka.stream.{Attributes, FlowShape, Inlet, Materializer, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, AsyncCallback, GraphStage, GraphStageLogic}
import com.om.mxs.client.japi.{UserInfo, Vault}
import helpers.Copier
import models.{CopyReport, IncomingListEntry}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/**
  * perform a single file copy in a bulk file copy operation.  This will spin up an entire substream to perform
  * the file copy.
  * @param vault
  * @param chunkSize
  * @param checksumType
  * @param mat
  */
class ListCopyFile(userInfo:UserInfo, vault:Vault,chunkSize:Int, checksumType:String, implicit val mat:Materializer)
  extends GraphStage[FlowShape[IncomingListEntry,CopyReport]] {
  private final val in:Inlet[IncomingListEntry] = Inlet.create("ListCopyFile.in")
  private final val out:Outlet[CopyReport] = Outlet.create("ListCopyFile.out")

  override def shape: FlowShape[IncomingListEntry, CopyReport] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val logger = LoggerFactory.getLogger(getClass)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        logger.debug(s"listCopyFile: onPush")
        val entry = grab(in)

        logger.info(s"Starting copy of ${entry.filepath}")
        val completedCb = createAsyncCallback[CopyReport](report=>push(out, report))
        val failedCb = createAsyncCallback[Throwable](err=>failStage(err))

        Copier.copyFromLocal(userInfo, vault, Some(entry.filePath), entry.filepath, chunkSize, checksumType).onComplete({
          case Success(Right( (oid,checksum) ))=>
            logger.info(s"Copied ${entry.filepath} to $oid")
            completedCb.invoke(CopyReport(entry.filePath, oid, Some(checksum), entry.size, preExisting = false))
          case Success(Left(copyProblem))=>
            logger.warn(s"Could not copy file: $copyProblem")
            completedCb.invoke(CopyReport(entry.filePath, copyProblem.filepath.oid, None, entry.size, preExisting = true))
          case Failure(err)=>
            logger.info(s"Failed copying ${entry.filepath}", err)
            failedCb.invoke(err)
        })
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        logger.debug("listCopyFile: onPull")
        pull(in)
      }
    })
  }
}
