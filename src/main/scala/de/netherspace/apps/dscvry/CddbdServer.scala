package de.netherspace.apps.dscvry

import zio._
import zio.blocking.Blocking
import zio.clock._
import zio.console._
import zio.duration._
import zio.nio.core.channels.SelectionKey.Operation
import zio.nio.core.channels._
import zio.nio.core.{Buffer, ByteBuffer, InetSocketAddress, SocketAddress}
import zio.stream._

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.util

type CddbdServerApp = ZManaged[
  Has[Console.Service] & Has[Clock.Service],
  Exception,
  Unit
]

class CddbdServer {

  private val cddbdProtocol = new CddbdProtocol(new CddbDatabase())

  private def registerForBannerWriting(selector: Selector, clientChannel: SocketChannel) = {
    for {
      _ <- putStrLn(s"Writing banner to client channel $clientChannel") // TODO: use a proper logger!
      initialSessionState <- cddbdProtocol.createInitialSessionState()
      _ <- clientChannel.configureBlocking(false)
      _ <- clientChannel.register(
        selector,
        Operation.Write,
        att = Some(initialSessionState)
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


  private def readRequest(selector: Selector, clientChannel: SocketChannel,
                          sessionState: CddbSessionState3, buffer: ByteBuffer): ZIO[Console, Exception, Unit] = {
    for {
      _ <- buffer.flip

      remaining <- buffer.remaining
      _ <- putStrLn(s"buffer.remaining = $remaining") // TODO: use a proper logger.trace()

      requestChunk <- buffer.getChunk(remaining)
      _ <- putStrLn(s"buffer.getChunk(remaining) = $requestChunk") // TODO: use a proper logger.trace()

      // handle the request!
      newSessionState <- cddbdProtocol.handleRequest3(requestChunk, sessionState)

      // send the response back to the client:
      _ <- clientChannel.register(
        selector,
        Operation.Write,
        att = Some(newSessionState)
      )
    } yield ()
  }


  private def extractSessionState(att: Option[AnyRef]): ZIO[Any, Exception, CddbSessionState3] = {
    val optionalSessionState: Option[CddbSessionState3] = att.map {
      (attachment: AnyRef) => attachment.asInstanceOf[CddbSessionState3]
    }
    ZIO.fromOption(optionalSessionState).mapError(_ => Exception("No attachment found!"))
  }


  private def readFromClientConn(scope: Managed.Scope, selector: Selector, key: SelectionKey,
                                 clientChannel: SocketChannel): ZIO[Console, Exception, Unit] = {
    for {
      // TODO: _ <- putStrLn(s"Key $key is readable...") // TODO: use a proper logger.trace()
      att <- key.attachment
      sessionState <- extractSessionState(att)
      buffer <- BufferUtils.newBuffer(None).use { b =>
        for {
          clientChannelIsConnected <- clientChannel.isConnected
          clientChannelIsOpen <- clientChannel.isOpen
          _ <- ZIO.when(clientChannelIsConnected && clientChannelIsOpen) {
            for {
              // TODO: _ <- putStrLn(s"Reading from client channel $clientChannel...") // TODO: use a proper logger.trace()

              // read from the client and handle (some) exceptions gracefully:
              i <- clientChannel.read(b).catchSome {
                case _: java.io.EOFException => ZIO.succeed(0)
                case _: java.net.SocketException => ZIO.succeed(-1)
              }
              // TODO: _ <- putStrLn(s"I read $i bytes from client channel $clientChannel!") // TODO: use a proper logger.trace()

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
                    Operation.Read,
                    att = Some(
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
      }
    } yield ()
  }


  private def writeToClientConn(scope: Managed.Scope, selector: Selector, key: SelectionKey,
                                clientChannel: SocketChannel): ZIO[Console, Exception, Unit] = {
    for {
      _ <- putStrLn(s"Writing to client channel $clientChannel...") // TODO: use a proper logger!
      att <- key.attachment
      sessionState <- extractSessionState(att)
      _ <- sessionState.buffer.get.flip

      remaining <- sessionState.buffer.get.remaining
      _ <- putStrLn(s"buffer.remaining = $remaining") // TODO: use a proper logger.trace()

      dupl <- sessionState.buffer.get.duplicate
      requestChunk <- dupl.getChunk(remaining)
      _ <- putStrLn(s"buffer.getChunk(remaining) = $requestChunk") // TODO: use a proper logger.trace()

      i <- clientChannel.write(
        sessionState.buffer.get
      )
      _ <- sessionState.buffer.get.clear
      _ <- clientChannel.register(
        selector,
        Operation.Read,
        att = Some(
          sessionState.copy(buffer = Some(sessionState.buffer.get))
        )
      )
      _ <- putStrLn(s"I wrote $i bytes to client channel $clientChannel!") // TODO: use a proper logger!
    } yield ()
  }


  private def consumeSingleKey(scope: Managed.Scope, selector: Selector,
                               key: SelectionKey): ZIO[Console, Exception, Unit] =
    key.matchChannel { readyOps => {
      case serverSocketChannel: ServerSocketChannel if readyOps(Operation.Accept) =>
        for {
          _ <- registerNewClientConn(scope, selector, key, serverSocketChannel)
        } yield ()

      case clientChannel: SocketChannel if readyOps(Operation.Write) =>
        for {
          _ <- writeToClientConn(scope, selector, key, clientChannel)
        } yield ()

      case clientChannel: SocketChannel if readyOps(Operation.Read) =>
        for {
          _ <- readFromClientConn(scope, selector, key, clientChannel)
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
