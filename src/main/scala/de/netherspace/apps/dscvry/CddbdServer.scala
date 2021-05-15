package de.netherspace.apps.dscvry

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels._
import java.nio.charset.StandardCharsets
import java.util

class CddbdServer {

  private val running = true
  private val cddbdProtocol = new CddbdProtocol(new CddbDatabase())

  private def sendResponse(selector: Selector, clientChannel: SocketChannel,
                           newSessionState: CddbSessionState): Unit = {
    try {
      clientChannel.register(
        selector,
        SelectionKey.OP_WRITE,
        newSessionState
      )
    } catch {
      case t: Throwable => println(s"Could not register client channel for OP_WRITE! $t")
    }
  }

  private def registerNewClientConn(selector: Selector, key: SelectionKey): Unit = {
    val serverSocketChannel: ServerSocketChannel = key
      .channel().asInstanceOf[ServerSocketChannel]

    try {
      val clientSocketChannel = serverSocketChannel.accept()
      if (clientSocketChannel != null) {
        clientSocketChannel.configureBlocking(false)

        // the first thing we'll do (i.e. right after the connection
        // is established and before the clients sends anything) is
        // sending a server banner (see "CDDB Protocol Level 1"):
        println(s"Writing banner to client channel $clientSocketChannel")
        clientSocketChannel.register(
          selector,
          SelectionKey.OP_WRITE,
          cddbdProtocol.writeBanner()
        )
      }
    } catch {
      case t: Throwable => println(s"Could not register new client! $t")
    }

    try {
      // re-register the server channel to accept new clients:
      serverSocketChannel.register(
        selector,
        SelectionKey.OP_ACCEPT
      )
    } catch {
      case t: Throwable => println(s"Could not re-register server channel! $t")
    }
  }

  private def readRequest(selector: Selector, clientChannel: SocketChannel,
                          sessionState: CddbSessionState): Unit = {
    try {
      val buffer: ByteBuffer = sessionState.buffer.get
      val i = clientChannel.read(buffer)

      if (i < 0) {
        println(s"I read $i bytes from buffer!")
        clientChannel.close()

      } else if (i > 0) {
        println(s"I read $i bytes from buffer!")
        buffer.flip()
        val rawBytes = new ByteArrayOutputStream()
        while (buffer.hasRemaining) {
          val content = new Array[Byte](buffer.remaining())
          try {
            buffer.get(content)
            rawBytes.write(content)
          } catch {
            case t: Throwable => println(s"Could not read from client channel! $t")
          }
        }
        println(s"I read: '${rawBytes.toString(StandardCharsets.UTF_8)}'")

        // handle the request!
        val newSessionState = cddbdProtocol.handleRequest(
          rawBytes,
          sessionState.copy(buffer = Some(buffer))
        )

        // send the response back to the client:
        sendResponse(selector, clientChannel, newSessionState)
      }
    } catch {
      case t: Throwable => println(s"Could not read from server channel! $t")
    }
  }

  private def readFromClientConn(selector: Selector, key: SelectionKey): Unit = {
    val clientChannel: SocketChannel = key.channel().asInstanceOf[SocketChannel]
    key.interestOps(0)

    // is the client channel still connected?
    if (clientChannel.isConnected) {

      // is there an attachment attached to this client channel?
      val attachment = key.attachment()
      if (attachment != null) {

        // yes, lets check for the buffer:
        val sessionState: CddbSessionState = attachment.asInstanceOf[CddbSessionState]
        sessionState.buffer match {
          case Some(_) =>
            // now we have a valid CddbSessionState, let's read some bytes:
            readRequest(selector, clientChannel, sessionState)
          case None => println("CddbSessionState.Buffer was None when trying to read a request!")
        }
      } else {
        // there is no attachment - this should never happen!
        println("Something unexpected happened when reading from a client" +
          " channel: could not find channel attachment")
        CddbSessionState(
          protocolLevel = Constants.defaultCddbProtocolLevel,
          buffer = Some(ByteBuffer.allocate(Constants.defaultRequestBufferSize))
        )
      }
    } else {
      println("Client channel is not connected!")
    }
  }

  private def writeToClientConn(selector: Selector, key: SelectionKey): Unit = {
    val clientChannel: SocketChannel = key.channel().asInstanceOf[SocketChannel]
    key.interestOps(0)

    try {
      if (clientChannel.isConnected) {
        val attachment = key.attachment()
        if (attachment != null) {
          val sessionState: CddbSessionState = attachment.asInstanceOf[CddbSessionState]

          sessionState.buffer match {
            case Some(buffer) => {
              buffer.flip()
              while (buffer.hasRemaining) {
                val i = clientChannel.write(buffer)
                println(s"I wrote $i bytes to client channel $clientChannel!")
              }

              clientChannel.register(
                selector,
                SelectionKey.OP_READ,
                sessionState.copy(buffer = Some(buffer.clear()))
              )
            }
            case None => println("CddbSessionState.Buffer was None when trying to write a response!")
          }

        } else {
          // there is no attachment - this should never happen!
          println("Something unexpected happened when writing to a client" +
            " channel: could not find channel attachment")
          clientChannel.register(
            selector,
            SelectionKey.OP_READ,
            CddbSessionState(Constants.defaultCddbProtocolLevel, None)
          )
        }
      }
    } catch {
      case t: Throwable => println(s"Could not write to channel! $t")
    }
  }

  private def consumeSingleKey(selector: Selector, key: SelectionKey): Unit = {
    if (key.isAcceptable) registerNewClientConn(selector, key)
    if (key.isWritable) writeToClientConn(selector, key)
    if (key.isReadable) readFromClientConn(selector, key)
  }

  private def consumeKeys(selector: Selector,
                          selectionKeys: util.Iterator[SelectionKey]): Unit = {
    selectionKeys.forEachRemaining {
      k => consumeSingleKey(selector, k)
    }
  }

  private def selectKeys(selector: Selector,
                         serverSocketChannel: ServerSocketChannel): Unit = {
    serverSocketChannel.configureBlocking(false)
    try {
      serverSocketChannel.register(
        selector,
        SelectionKey.OP_ACCEPT
      )
    } catch {
      case t: Throwable => println(s"Could not register ServerSocketChannel for OP_ACCEPT! $t")
    }

    while (running) { // TODO: use an infinite, tail-recursive loop instead!
      selector.select()
      val selectionKeys = selector.selectedKeys().iterator()
      consumeKeys(selector, selectionKeys)
    }
  }

  def bootstrap(port: Int): Either[String, () => Unit] = {
    try {
      // create an NIO channel selector and a server socket:
      val selector = Selector.open()
      val serverSocketChannel = ServerSocketChannel.open()

      println(s"Binding on port $port...")
      serverSocketChannel.bind(
        new InetSocketAddress("localhost", port)
      )

      Right(() => selectKeys(selector, serverSocketChannel))
    } catch {
      case e: ClosedChannelException => Left(e.getMessage)
      case e: java.io.IOException => Left(e.getMessage)
      case t: Throwable => Left(s"Something unexpected happened! $t")
    }
  }
}
