package de.netherspace.apps.dscvry

import zio._
import zio.Clock
import zio.Duration
import zio.logging._
import zio.nio.channels.SelectionKey.Operation
import zio.nio.channels._
import zio.nio.{Buffer, ByteBuffer, InetSocketAddress, SocketAddress}
import zio.stream._

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.util

type CddbServerEnv =
  zio.Console
    & zio.Clock

type CddbdServerApp = ZManaged[
  CddbServerEnv,
  Exception,
  Unit
]

type NioChannelOperation = ZIO[
  CddbServerEnv,
  Exception,
  Unit
]

class CddbdServer {

  private val cddbdProtocol = new CddbdProtocol(new CddbDatabase())

  private def registerForBannerWriting(selector: Selector,
                                       clientChannel: SocketChannel): NioChannelOperation = {
    for {
      _ <- ZIO.logDebug(s"Writing banner to client channel $clientChannel")
      initialSessionState <- cddbdProtocol.createInitialSessionState()
      _ <- clientChannel.configureBlocking(false)
      _ <- clientChannel.register(
        selector,
        Set(Operation.Write),
        attachment =  Some(initialSessionState)
      )
    } yield ()
  }

  private def registerNewClientConn(scope: Managed.Scope, selector: Selector,
      key: SelectionKey, serverSocketChannel: ServerSocketChannel) = {
    for {
      // accept a new client connection:
      _ <- ZIO.logInfo("Accepting new client connection...")
      scopedAccept <- scope(serverSocketChannel.useNonBlockingManaged(_.accept))
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


  private def readRequest(selector: Selector, clientChannel: SocketChannel,
                          sessionState: CddbSessionState, buffer: ByteBuffer) = {
    for {
      _ <- buffer.flip

      remaining <- buffer.remaining
      _ <- ZIO.logTrace(s"buffer.remaining = $remaining")

      requestChunk <- buffer.getChunk(remaining)
      _ <- ZIO.logTrace(s"buffer.getChunk(remaining) = $requestChunk")

      // handle the request!
      newSessionState <- cddbdProtocol.handleRequest(requestChunk, sessionState)

      // send the response back to the client:
      _ <- clientChannel.register(
        selector,
        Set(Operation.Write),
        attachment = Some(newSessionState)
      )
    } yield ()
  }


  private def extractSessionState(att: Option[AnyRef]): ZIO[Any, Exception, CddbSessionState] = {
    val optionalSessionState: Option[CddbSessionState] = att.map {
      (attachment: AnyRef) => attachment.asInstanceOf[CddbSessionState]
    }
    ZIO.fromOption(optionalSessionState).mapError(_ => Exception("No attachment found!"))
  }


  private def readFromClientConn(selector: Selector, key: SelectionKey,
      clientChannel: SocketChannel): NioChannelOperation = {
    for {
      _ <- ZIO.logTrace(s"Key $key is readable...")
      att <- key.attachment
      sessionState <- extractSessionState(att)
      b <- BufferUtils.newBuffer(None)
      buffer <-
        for {
          clientChannelIsConnected <- clientChannel.isConnected
          clientChannelIsOpen <- clientChannel.isOpen
          _ <- ZIO.when(clientChannelIsConnected && clientChannelIsOpen) {
            for {
              _ <- ZIO.logTrace(s"Reading from client channel $clientChannel...")

              // read from the client and handle (some) exceptions gracefully:
              i <- clientChannel.useNonBlocking { client =>
                for {
                  x <- client.read(b).catchSome {
                    case _: java.io.EOFException => ZIO.succeed(0)
                    case _: java.net.SocketException => ZIO.succeed(-1)
                  }
                } yield (x)
              }
              _ <- ZIO.logTrace(s"I read $i bytes from client channel $clientChannel!")

              // the client closed the connection:
              _ <- ZIO.when(i < 0) {
                for {
                  _ <- clientChannel.close
                } yield ()
              }

              // the client did not close the connection but sent 0 bytes:
              _ <- ZIO.when(i == 0) {
                for {
                  _ <- clientChannel.register(
                    selector,
                    Set(Operation.Read),
                    attachment = Some(
                      sessionState.copy(buffer = Some(sessionState.buffer.get))
                    )
                  )
                } yield ()
              }

              // we did receive some bytes:
              _ <- ZIO.when(i > 0) {
                for {
                  _ <- readRequest(selector, clientChannel, sessionState, b)
                } yield ()
              }

            } yield ()
          }
        } yield (b)
    } yield ()
  }


  private def writeToClientConn(selector: Selector, key: SelectionKey,
      clientChannel: SocketChannel): NioChannelOperation = {
    for {
      _ <- ZIO.logDebug(s"Writing to client channel $clientChannel...")
      att <- key.attachment
      sessionState <- extractSessionState(att)
      _ <- sessionState.buffer.get.flip

      remaining <- sessionState.buffer.get.remaining
      _ <- ZIO.logTrace(s"buffer.remaining = $remaining")

      dupl <- sessionState.buffer.get.duplicate
      requestChunk <- dupl.getChunk(remaining)
      _ <- ZIO.logTrace(s"buffer.getChunk(remaining) = $requestChunk")

      i <- clientChannel.useNonBlocking { client =>
        for {
          x <- client.write(sessionState.buffer.get)
        } yield (x)
      }
      _ <- sessionState.buffer.get.clear
      _ <- clientChannel.register(
        selector,
        Set(Operation.Read),
        attachment = Some(
          sessionState.copy(buffer = Some(sessionState.buffer.get))
        )
      )
      _ <- ZIO.logDebug(s"I wrote $i bytes to client channel $clientChannel!")
    } yield ()
  }


  private def consumeSingleKey(scope: Managed.Scope, selector: Selector, key: SelectionKey) =
    key.matchChannel { readyOps => {
      case serverSocketChannel: ServerSocketChannel if readyOps(Operation.Accept) =>
        for {
          _ <- registerNewClientConn(scope, selector, key, serverSocketChannel)
        } yield ()

      case clientChannel: SocketChannel if readyOps(Operation.Write) =>
        for {
          _ <- writeToClientConn(selector, key, clientChannel)
        } yield ()

      case clientChannel: SocketChannel if readyOps(Operation.Read) =>
        for {
          _ <- readFromClientConn(selector, key, clientChannel)
        } yield ()
    }
    } *> selector.removeKey(key)


  private def selectKeys(scope: Managed.Scope, selector: Selector) = {
    for {
      _ <- selector.select
      selectionKeys <- selector.selectedKeys
      _ <- ZIO.foreach_(selectionKeys) { k =>
        consumeSingleKey(scope, selector, k)
      }
    } yield ()
  }

  def bootstrap(port: Int, nioSelectionSchedule: Schedule[Any, Any, Any]): CddbdServerApp = {
    for {
      scope <- Managed.scope
      selector <- Selector.open
      serverSocketChannel <- ServerSocketChannel.open
      _ <- Managed.fromZIO {
        for {
          _ <- ZIO.logInfo(s"Binding on port $port...")
          serverSocket <- InetSocketAddress.hostNameResolved("127.0.0.1", port)
          _ <- serverSocketChannel.bindTo(serverSocket)
          _ <- serverSocketChannel.configureBlocking(false)
          _ <- serverSocketChannel.register(selector, Set(Operation.Accept))
          _ <- selectKeys(scope, selector).repeat(nioSelectionSchedule)
        } yield ()
      }
    } yield ()
  }
}
