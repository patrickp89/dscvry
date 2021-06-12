package de.netherspace.apps.dscvry

import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.nio.core.channels.SelectionKey.Operation
import zio.nio.core.channels._
import zio.nio.core.{InetSocketAddress, SocketAddress}
import zio.stream._

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util

class CddbdServer {

  private val running = true
  private val cddbdProtocol = new CddbdProtocol(new CddbDatabase())

  private def sendResponse(selector: java.nio.channels.Selector, clientChannel: java.nio.channels.SocketChannel,
                           newSessionState: CddbSessionState): Unit = {
    try {
      clientChannel.register(
        selector,
        java.nio.channels.SelectionKey.OP_WRITE,
        newSessionState
      )
    } catch {
      case t: Throwable => println(s"Could not register client channel for OP_WRITE! $t")
    }
  }

  private def registerNewClientConn(selector: java.nio.channels.Selector, key: java.nio.channels.SelectionKey): Unit = {
    val serverSocketChannel: java.nio.channels.ServerSocketChannel = key
      .channel().asInstanceOf[java.nio.channels.ServerSocketChannel]

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
          java.nio.channels.SelectionKey.OP_WRITE,
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
        java.nio.channels.SelectionKey.OP_ACCEPT
      )
    } catch {
      case t: Throwable => println(s"Could not re-register server channel! $t")
    }
  }

  private def readRequest(selector: java.nio.channels.Selector, clientChannel: java.nio.channels.SocketChannel,
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

  private def readFromClientConn(selector: java.nio.channels.Selector, key: java.nio.channels.SelectionKey): Unit = {
    val clientChannel: java.nio.channels.SocketChannel = key.channel().asInstanceOf[java.nio.channels.SocketChannel]
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

  private def writeToClientConn(selector: java.nio.channels.Selector, key: java.nio.channels.SelectionKey): Unit = {
    val clientChannel: java.nio.channels.SocketChannel = key.channel().asInstanceOf[java.nio.channels.SocketChannel]
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
                java.nio.channels.SelectionKey.OP_READ,
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
            java.nio.channels.SelectionKey.OP_READ,
            CddbSessionState(Constants.defaultCddbProtocolLevel, None)
          )
        }
      }
    } catch {
      case t: Throwable => println(s"Could not write to channel! $t")
    }
  }

  private def consumeSingleKey3(selector: Selector, key: SelectionKey) = //: zio.ZIO[Blocking, Exception, Unit] ?
    key.matchChannel { readyOps => {
      case channel: ServerSocketChannel if readyOps(Operation.Accept) =>
        for {
          _ <- channel.close
        } yield ()
      case client: SocketChannel if readyOps(Operation.Read) =>
        for {

          _ <- client.close
        } yield ()
    }
    } *> selector.removeKey(key)

  private def consumeSingleKey(selector: java.nio.channels.Selector, key: java.nio.channels.SelectionKey): Unit = {
    if (key.isAcceptable) registerNewClientConn(selector, key)
    if (key.isWritable) writeToClientConn(selector, key)
    if (key.isReadable) readFromClientConn(selector, key)
  }

  private def consumeKeys(selector: java.nio.channels.Selector,
                          selectionKeys: util.Iterator[java.nio.channels.SelectionKey]): Unit = {
    selectionKeys.forEachRemaining {
      k => consumeSingleKey(selector, k)
    }
  }

  def selectKeys3(scope: Managed.Scope, selector: Selector) = { //: ZIO[Blocking, Exception, Unit] ?
    for {
      _ <- console.putStrLn("Selecting keys...").ignore
      _ <- selector.select
      selectionKeys <- selector.selectedKeys
      _ <- IO.foreach_(selectionKeys) { k =>
        consumeSingleKey3(selector, k)
      }
    } yield ()
  }

  private def selectKeys(selector: java.nio.channels.Selector,
                         serverSocketChannel: java.nio.channels.ServerSocketChannel): Unit = {
    serverSocketChannel.configureBlocking(false)
    try {
      serverSocketChannel.register(
        selector,
        java.nio.channels.SelectionKey.OP_ACCEPT
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

  def bootstrap3(port: Int) = {
    for {
      scope <- Managed.scope
      selector <- Selector.open
      serverSocketChannel <- ServerSocketChannel.open
      _ <- Managed.fromEffect {
        for {
          _ <- putStrLn(s"Binding on port $port...")
          _ <- serverSocketChannel.bindAuto(port)
          _ <- serverSocketChannel.configureBlocking(false)
          _ <- serverSocketChannel.register(selector, Operation.Accept)
          _ <- selectKeys3(scope, selector)
        } yield ()
      }
    } yield ()
  }

  def bootstrap(port: Int): Either[String, () => Unit] = {
    try {
      // create an NIO channel selector and a server socket:
      val selector = java.nio.channels.Selector.open()
      val serverSocketChannel = java.nio.channels.ServerSocketChannel.open()

      println(s"Binding on port $port...")
      serverSocketChannel.bind(
        new java.net.InetSocketAddress("localhost", port)
      )

      Right(() => selectKeys(selector, serverSocketChannel))
    } catch {
      case e: java.nio.channels.ClosedChannelException => Left(e.getMessage)
      case e: java.io.IOException => Left(e.getMessage)
      case t: Throwable => Left(s"Something unexpected happened! $t")
    }
  }
}
