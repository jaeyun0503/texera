/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package edu.uci.ics.amber.engine.architecture.pythonworker

import akka.actor.Props
import com.twitter.util.Promise
import edu.uci.ics.amber.config.{StorageConfig, UdfConfig}
import edu.uci.ics.amber.core.virtualidentity.ChannelIdentity
import edu.uci.ics.amber.engine.architecture.common.WorkflowActor
import edu.uci.ics.amber.engine.architecture.common.WorkflowActor.NetworkAck
import edu.uci.ics.amber.engine.architecture.messaginglayer.{
  NetworkInputGateway,
  NetworkOutputGateway
}
import edu.uci.ics.amber.engine.architecture.pythonworker.WorkerBatchInternalQueue.{
  EmbeddedControlMessageElement,
  DataElement
}
import edu.uci.ics.amber.engine.architecture.rpc.controlcommands.EmbeddedControlMessage
import edu.uci.ics.amber.engine.architecture.scheduling.config.WorkerConfig
import edu.uci.ics.amber.engine.common.actormessage.{Backpressure, CreditUpdate}
import edu.uci.ics.amber.engine.common.ambermessage.WorkflowMessage.getInMemSize
import edu.uci.ics.amber.engine.common.ambermessage._
import edu.uci.ics.amber.engine.common.{CheckpointState, Utils}

import java.nio.file.Path
import java.util.concurrent.{ExecutorService, Executors}
import scala.sys.process.{BasicIO, Process}

object PythonWorkflowWorker {
  def props(workerConfig: WorkerConfig): Props = Props(new PythonWorkflowWorker(workerConfig))
}

class PythonWorkflowWorker(
    workerConfig: WorkerConfig
) extends WorkflowActor(replayLogConfOpt = None, actorId = workerConfig.workerId) {

  // For receiving the Python server port number that will be available later
  private lazy val portNumberPromise = Promise[Int]()
  // Proxy Server and Client
  private lazy val serverThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor
  private lazy val clientThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor
  private var pythonProxyServer: PythonProxyServer = _
  private lazy val pythonProxyClient: PythonProxyClient =
    new PythonProxyClient(portNumberPromise, workerConfig.workerId)

  val pythonSrcDirectory: Path = Utils.amberHomePath
    .resolve("src")
    .resolve("main")
    .resolve("python")
  val pythonENVPath: String = UdfConfig.pythonPath.trim
  val RENVPath: String = UdfConfig.rPath.trim

  // Python process
  private var pythonServerProcess: Process = _

  private val networkInputGateway = new NetworkInputGateway(workerConfig.workerId)
  private val networkOutputGateway = new NetworkOutputGateway(
    workerConfig.workerId,
    // handler for output messages
    msg => {
      logManager.sendCommitted(Right(msg))
    }
  )

  override def handleInputMessage(messageId: Long, workflowMsg: WorkflowFIFOMessage): Unit = {
    val channel = networkInputGateway.getChannel(workflowMsg.channelId)
    channel.acceptMessage(workflowMsg)
    while (channel.isEnabled && channel.hasMessage) {
      val msg = channel.take
      msg.payload match {
        case payload: DirectControlMessagePayload =>
          pythonProxyClient.enqueueCommand(payload, workflowMsg.channelId)
        case payload: DataPayload =>
          pythonProxyClient.enqueueData(DataElement(payload, workflowMsg.channelId))
        case ecm: EmbeddedControlMessage =>
          pythonProxyClient.enqueueData(EmbeddedControlMessageElement(ecm, workflowMsg.channelId))
        case p => logger.error(s"unhandled control payload: $p")
      }
    }
    sender() ! NetworkAck(
      messageId,
      getInMemSize(workflowMsg),
      getQueuedCredit(workflowMsg.channelId)
    )
  }

  override def receiveCreditMessages: Receive = {
    case WorkflowActor.CreditRequest(channel) =>
      pythonProxyClient.enqueueActorCommand(CreditUpdate())
      sender() ! WorkflowActor.CreditResponse(channel, getQueuedCredit(channel))
    case WorkflowActor.CreditResponse(channel, credit) =>
      transferService.updateChannelCreditFromReceiver(channel, credit)
  }

  /** flow-control */
  override def getQueuedCredit(channelId: ChannelIdentity): Long = {
    pythonProxyClient.getQueuedCredit(channelId) + pythonProxyClient.getQueuedCredit
  }

  override def handleBackpressure(enableBackpressure: Boolean): Unit = {
    pythonProxyClient.enqueueActorCommand(Backpressure(enableBackpressure))
  }

  override def postStop(): Unit = {
    super.postStop()
    try {
      // try to send shutdown command so that it can gracefully shutdown
      pythonProxyClient.close()

      clientThreadExecutor.shutdown()

      serverThreadExecutor.shutdown()

      // destroy python process
      pythonServerProcess.destroy()
    } catch {
      case e: Exception =>
        logger.error(s"$e - happened during shutdown")
    }
  }

  override def initState(): Unit = {
    startProxyServer()
    startPythonProcess()
    startProxyClient()
  }

  private def startProxyServer(): Unit = {
    // Try to start the server until it succeeds
    var serverStart = false
    while (!serverStart) {
      pythonProxyServer =
        new PythonProxyServer(networkOutputGateway, workerConfig.workerId, portNumberPromise)
      val future = serverThreadExecutor.submit(pythonProxyServer)
      try {
        future.get()
        serverStart = true
      } catch {
        case e: Exception =>
          future.cancel(true)
          logger.info("Failed to start the server: " + e.getMessage + ", will try again")
      }
    }
  }

  private def startProxyClient(): Unit = {
    clientThreadExecutor.submit(pythonProxyClient)
  }

  private def startPythonProcess(): Unit = {
    val udfEntryScriptPath: String =
      pythonSrcDirectory.resolve("texera_run_python_worker.py").toString
    pythonServerProcess = Process(
      Seq(
        if (pythonENVPath.isEmpty) "python3"
        else pythonENVPath, // add fall back in case of empty
        "-u",
        udfEntryScriptPath,
        workerConfig.workerId.name,
        Integer.toString(pythonProxyServer.getPortNumber.get()),
        UdfConfig.pythonLogStreamHandlerLevel,
        RENVPath,
        StorageConfig.icebergPostgresCatalogUriWithoutScheme,
        StorageConfig.icebergPostgresCatalogUsername,
        StorageConfig.icebergPostgresCatalogPassword,
        StorageConfig.icebergTableResultNamespace,
        StorageConfig.fileStorageDirectoryPath.toString,
        StorageConfig.icebergTableCommitBatchSize.toString
      )
    ).run(BasicIO.standard(false))
  }

  override def loadFromCheckpoint(chkpt: CheckpointState): Unit = ???
}
