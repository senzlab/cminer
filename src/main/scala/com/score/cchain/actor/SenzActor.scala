package com.score.cchain.actor

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.score.cchain.actor.BlockCreator.Create
import com.score.cchain.actor.BlockSigner.SignBlock
import com.score.cchain.config.AppConf
import com.score.cchain.protocol.{Msg, Senz, SenzType}
import com.score.cchain.util.{RSAFactory, SenzFactory, SenzLogger, SenzParser}

import scala.util.{Failure, Success, Try}

object SenzActor {

  def props: Props = Props(new SenzActor)

}

class SenzActor extends Actor with AppConf with SenzLogger {

  import context._

  val blockCreator = context.actorSelection("/user/BlockCreator")

  // buffers
  var buffer = new StringBuffer()
  val bufferWatcher = new Thread(new BufferWatcher, "BufferWatcher")

  // connect to senz tcp
  val remoteAddress = new InetSocketAddress(InetAddress.getByName(switchHost), switchPort)
  IO(Tcp) ! Connect(remoteAddress)

  override def preStart(): Unit = {
    bufferWatcher.setDaemon(true)
    bufferWatcher.start()
  }

  override def postStop(): Unit = {
    bufferWatcher.interrupt()
  }

  override def supervisorStrategy = OneForOneStrategy() {
    case e: Exception =>
      logger.error("Exception caught, [STOP ACTOR] " + e)
      logError(e)

      // TODO send error status back

      // stop failed actors here
      Stop
  }

  override def receive: Receive = {
    case Connected(_, _) =>
      logger.debug("TCP connected")

      // tcp conn
      val connection = sender()
      connection ! Register(self)

      // send reg message
      val regSenzMsg = SenzFactory.regSenz
      val senzSignature = RSAFactory.sign(regSenzMsg.trim.replaceAll(" ", ""))
      val signedSenz = s"$regSenzMsg $senzSignature"
      connection ! Write(ByteString(s"$signedSenz;"))

      // wait register
      context.become(registering(connection))
    case CommandFailed(_: Connect) =>
      // failed to connect
      logger.error("CommandFailed[Failed to connect]")
  }

  def registering(connection: ActorRef): Receive = {
    case CommandFailed(_: Write) =>
      logger.error("CommandFailed[Failed to write]")
    case Received(data) =>
      val senzMsg = data.decodeString("UTF-8")
      logger.debug("Received senzMsg : " + senzMsg)

      if (!senzMsg.equalsIgnoreCase("TIK;")) {
        // wait for REG status
        // parse senz first
        val senz = SenzParser.parseSenz(senzMsg)
        senz match {
          case Senz(SenzType.DATA, `switchName`, _, attr, _) =>
            attr.get("#status") match {
              case Some("REG_DONE") =>
                logger.info("Registration done")

                // start block creator and signer
                blockCreator ! Create

                // senz listening
                context.become(listening(connection))
              case Some("REG_ALR") =>
                logger.info("Already registered, continue system")

                // start block creator and signer
                blockCreator ! Create

                // senz listening
                context.become(listening(connection))
              case Some("REG_FAIL") =>
                logger.error("Registration fail, stop system")
                context.stop(self)
              case other =>
                logger.error("UNSUPPORTED DATA message " + other)
            }
          case _ =>
            logger.debug(s"Not support other messages $senzMsg this stats")
        }
      }
  }

  def listening(connection: ActorRef): Receive = {
    case CommandFailed(_: Write) =>
      logger.error("CommandFailed[Failed to write]")
    case Received(data) =>
      val senzMsg = data.decodeString("UTF-8")
      logger.debug("Received senzMsg : " + senzMsg)
      buffer.append(senzMsg)
    case _: ConnectionClosed =>
      logger.debug("ConnectionClosed")
      context.stop(self)
    case Msg(msg) =>
      if (msg.equalsIgnoreCase("TUK")) {
        // directly write TUK
        connection ! Write(ByteString(s"TUK;"))
      } else {
        // sign senz
        val senzSignature = RSAFactory.sign(msg.trim.replaceAll(" ", ""))
        val signedSenz = s"$msg $senzSignature"

        logger.info("writing senz: " + signedSenz)

        connection ! Write(ByteString(s"$signedSenz;"))
      }
  }

  class BufferWatcher extends Runnable {
    override def run(): Unit = {
      listen()
    }

    private def listen(): Unit = {
      while (!Thread.currentThread().isInterrupted) {
        val index = buffer.indexOf(";")
        if (index != -1) {
          val msg = buffer.substring(0, index)
          buffer.delete(0, index + 1)
          logger.debug(s"Got senz from buffer $msg")

          // send message back to handler
          msg match {
            case "TAK" =>
              logger.debug("TAK received")
            case "TIK" =>
              logger.debug("TIK received")
              self ! Msg("TUK")
            case _ =>
              onSenz(msg)
          }
        }
      }
    }

    private def onSenz(msg: String): Unit = {
      Try(SenzParser.parseSenz(msg)) match {
        case Success(Senz(SenzType.DATA, _, _, attr, _)) =>
          // send AWA back to switch
          self ! Msg(SenzFactory.awaSenz(attr("#uid"), switchName))

          if (attr.contains("#block") && attr.contains("#sign")) {
            // todo block signed response received
            // blockCreator ! SignResp(None, Option(senz.sender), attr.get("#block"), attr("#sign").toBoolean)
          }
        case Success(Senz(SenzType.PUT, sender, _, attr, _)) =>
          // send AWA back to switch
          self ! Msg(SenzFactory.awaSenz(attr("#uid"), switchName))

          if (attr.contains("#block") && attr.contains("#sign")) {
            // block sign request received
            // start actor to sign the block
            context.actorOf(BlockSigner.props) ! SignBlock(Option(sender), Option(attr("#block")))
          }
        case Success(Senz(_, _, _, _, _)) =>
          logger.debug(s"Not support message: $msg")
        case Failure(e) =>
          logError(e)
      }
    }
  }

}

