package com.geeksville.mavlink

import java.net._
import org.mavlink.messages.MAVLinkMessage
import org.mavlink.messages.MAVLinkMessageFactory
import org.mavlink.IMAVLinkMessage
import com.geeksville.util.ThreadTools
import com.geeksville.akka.InstrumentedActor
import akka.actor.PoisonPill

/**
 * published on our eventbus when someone wants a packet sent to the outside world
 */
// case class MavlinkSend(message: MAVLinkMessage)

/**
 * Receive UDPMavlink messages and forward to actors
 * Use with mavproxy like so:
 * Following instructions are stale...
 * mavproxy.py --master=/dev/ttyACM0 --master localhost:51200 --out=localhost:51232
 *
 * FIXME - make sure we don't overrun the rate packets can be read
 */
class MavlinkUDP(destHostName: Option[String] = None,
  val destPortNumber: Option[Int] = None,
  val localPortNumber: Option[Int] = None) extends MavlinkSender with MavlinkReceiver {

  // These must be lazy - to ensure we don't do networking in the main thread (an android restriction)
  lazy val serverHost = InetAddress.getByName(destHostName.get)
  lazy val socket = localPortNumber.map { n => new DatagramSocket(n) }.getOrElse(new DatagramSocket)

  val thread = ThreadTools.createDaemon("UDPMavReceive")(worker _)

  /**
   * The app last received packets from on our wellknown port number
   */
  var remote: Option[SocketAddress] = None

  private var shuttingDown = false

  thread.start()

  protected def doSendMavlink(bytes: Array[Byte]) {
    //log.debug("UDPSend: " + msg)

    // Do we know a remote port?
    destPortNumber.map { destPort =>
      val packet = new DatagramPacket(bytes, bytes.length, serverHost, destPort)
      socket.send(packet)
    }.getOrElse {
      // Has anyone called into us?

      remote.map { r =>
        //log.debug(s"Sending via UDP to $r")
        val packet = new DatagramPacket(bytes, bytes.length, r)
        socket.send(packet)
      }.getOrElse {
        log.debug("Can't send message, we haven't heard from a peer")
      }
    }
  }

  override def postStop() {
    shuttingDown = true
    socket.close() // Force thread exit
    super.postStop()
  }

  private def receivePacket() = {

    val bytes = new Array[Byte](512)
    val packet = new DatagramPacket(bytes, bytes.length)
    socket.receive(packet)
    remote = Some(packet.getSocketAddress)

    MavlinkUtils.bytesToPacket(packet.getData)
  }

  private def worker() {
    try {
      while (!shuttingDown) {
        receivePacket.foreach(handleIncomingPacket)
      }
    } catch {
      case ex: BindException =>
        log.error("Unable to bind to port!")
        self ! PoisonPill

      case ex: SocketException =>
        if (!shuttingDown) // If we are shutting down, ignore socket exceptions
          throw ex

      case ex: Exception =>
        log.warning("exception in UDP receiver: " + ex)
    }

    log.debug("UDP receiver exiting")
  }
}

object MavlinkUDP {
  /// The standard port number people use
  val portNumber = 14550
}

