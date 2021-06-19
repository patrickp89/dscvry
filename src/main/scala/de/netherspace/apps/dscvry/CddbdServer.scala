package de.netherspace.apps.dscvry

import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.nio.core.channels.SelectionKey.Operation
import zio.nio.core.channels._
import zio.nio.core.{Buffer, ByteBuffer, InetSocketAddress, SocketAddress}
import zio.stream._

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.util

type CddbdServerApp = zio.ZManaged[
  zio.Has[zio.console.Console.Service] & zio.Has[zio.clock.Clock.Service],
  Exception,
  Unit
]

class CddbdServer {

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

  private def registerForBannerWriting(selector: Selector, clientChannel: SocketChannel) = {
    for {
      _ <- putStrLn(s"Writing banner to client channel $clientChannel") // TODO: use a proper logger!
      banner <- cddbdProtocol.writeBanner3()
      _ <- clientChannel.configureBlocking(false)
        *> clientChannel.register(
        selector,
        Operation.Write,
        att = Some(banner)
      )
    } yield ()
  }

  private def registerNewClientConn(scope: Managed.Scope, selector: Selector, key: SelectionKey,
                                    serverSocketChannel: ServerSocketChannel): ZIO[Console, Exception, Unit] = {
    for {
      // accept a new client connection:
      _ <- putStrLn("Accepting new client connection...") // TODO: use a proper logger!
      scopedAccept <- scope(serverSocketChannel.accept)
      (_, clientSocketChannelOption) = scopedAccept

      // the first thing we'll do (i.e. right after the connection
      // is established and before the clients sends anything) is
      // sending a server banner (see "CDDB Protocol Level 1"):
      _ <- ZIO.whenCase(clientSocketChannelOption) {
        case Some(clientChannel) => registerForBannerWriting(
          selector,
          clientChannel
        )
      }
    } yield ()
  }

  private def readRequest(selector: java.nio.channels.Selector, clientChannel: java.nio.channels.SocketChannel,
                          sessionState: CddbSessionState): Unit = {
    try {
      val buffer: java.nio.ByteBuffer = sessionState.buffer.get
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


  private def readFromClientConn3(scope: Managed.Scope, selector: Selector, key: SelectionKey,
                                  clientChannel: SocketChannel): ZIO[Console, Exception, Unit] = {
    for {
      _ <- putStrLn(s"Key $key is readable...") // TODO: use a proper logger!
      att <- key.attachment
      buffer <- cddbdProtocol.newBuffer().use { b => // TODO: use unwrapBufferOrNew(scope, att)
        for {
          clientChannelIsConnected <- clientChannel.isConnected
          clientChannelIsOpen <- clientChannel.isOpen
          _ <- ZIO.when(clientChannelIsConnected && clientChannelIsOpen) {
            for {
              _ <- putStrLn(s"Reading from client channel $clientChannel...") // TODO: use a proper logger!
              i <- ZIO.succeed(-1) // TODO: clientChannel.read(buffer)
              _ <- putStrLn(s"I read $i bytes from client channel $clientChannel!") // TODO: use a proper logger!
              _ <- ZIO.when(i < 0) {
                for {
                  _ <- clientChannel.close
                } yield ()
              }
            } yield ()
          }
        } yield (b)
      }

      /*_ <- clientChannel.register(
        selector,
        Operation.Write,
        // TODO: sessionState.copy(buffer = Some(buffer.clear()))
      )*/
    } yield ()
  }

  /*private def readFromClientConn(selector: java.nio.channels.Selector, key: java.nio.channels.SelectionKey): Unit = {
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
          buffer = Some(java.nio.ByteBuffer.allocate(Constants.defaultRequestBufferSize))
        )
      }
    } else {
      println("Client channel is not connected!")
    }
  }*/


  private def writeToClientConn3(scope: Managed.Scope, selector: Selector, key: SelectionKey,
                                 clientChannel: SocketChannel): ZIO[Console, IOException, Unit] = {
    for {
      _ <- putStrLn(s"Writing to client channel $clientChannel...") // TODO: use a proper logger!
      att <- key.attachment
      _ <- ZIO.whenCase(att) {
        case Some(attachment) => {
          val sessionState: CddbSessionState3 = attachment.asInstanceOf[CddbSessionState3]
          for {
            _ <- sessionState.buffer.get.flip
            i <- clientChannel.write(
              sessionState.buffer.get
            )
            _ <- clientChannel.register(
              selector,
              Operation.Read,
              // TODO: sessionState.copy(buffer = Some(buffer.clear()))
            )
            _ <- putStrLn(s"I wrote $i bytes to client channel $clientChannel!") // TODO: use a proper logger!
          } yield ()
        }
      }
    } yield ()
  }

  /*private def writeToClientConn(selector: java.nio.channels.Selector, key: java.nio.channels.SelectionKey): Unit = {
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
  }*/

  private def consumeSingleKey(scope: Managed.Scope, selector: Selector,
                               key: SelectionKey): ZIO[Console, Exception, Unit] =
    key.matchChannel { readyOps => {
      case serverSocketChannel: ServerSocketChannel if readyOps(Operation.Accept) =>
        for {
          _ <- registerNewClientConn(scope, selector, key, serverSocketChannel)
        } yield ()

      case clientChannel: SocketChannel if readyOps(Operation.Write) =>
        for {
          _ <- writeToClientConn3(scope, selector, key, clientChannel)
        } yield ()

      case clientChannel: SocketChannel if readyOps(Operation.Read) =>
        for {
          _ <- readFromClientConn3(scope, selector, key, clientChannel)
        } yield ()
    }
    } *> selector.removeKey(key)


  private def selectKeys(scope: Managed.Scope, selector: Selector): zio.ZIO[Console, Exception, Unit] = {
    for {
      _ <- selector.select
      selectionKeys <- selector.selectedKeys
      _ <- ZIO.foreach_(selectionKeys) { k =>
        consumeSingleKey(scope, selector, k)
      }
    } yield ()
  }


  def bootstrap(port: Int): CddbdServerApp = {
    for {
      scope <- Managed.scope
      selector <- Selector.open
      serverSocketChannel <- ServerSocketChannel.open
      _ <- Managed.fromEffect {
        for {
          _ <- putStrLn(s"Binding on port $port...") // TODO: use a proper logger!
          serverSocket <- InetSocketAddress.hostNameResolved("127.0.0.1", port)
          _ <- serverSocketChannel.bindTo(serverSocket)
          _ <- serverSocketChannel.configureBlocking(false)
          _ <- serverSocketChannel.register(selector, Operation.Accept)
          _ <- selectKeys(scope, selector).repeat(Schedule.forever)
        } yield ()
      }
    } yield ()
  }
}
