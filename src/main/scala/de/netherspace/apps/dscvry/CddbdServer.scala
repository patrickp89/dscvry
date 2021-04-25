package de.netherspace.apps.dscvry

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels._
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util

class CddbdServer {

  private val appName = "Dscvry"
  private val defaultRequestBufferSize = 1024
  private val running = true

  private def createBanner(): String = {
    val okReadOnlyStatusCode = 201
    val version = "v0.0.1"
    val dateTimeFormat = "EEE LLL dd HH:mm:ss yyyy"
    val dtf = DateTimeFormatter.ofPattern(dateTimeFormat)
    val ts = LocalDateTime.now().format(dtf)
    s"$okReadOnlyStatusCode $appName CDDBP server $version ready at $ts"
  }

  private def writeBanner(): ByteBuffer = {
    val serverBanner = createBanner()
    val bannerLength = serverBanner.length
    val buffer = ByteBuffer.allocate(bannerLength)
    buffer.put(serverBanner.getBytes(StandardCharsets.UTF_8))
  }

  private def handleRequest(rawBytes: ByteArrayOutputStream): Array[Byte] = {
    // TODO: send the real response!
    val stubResponse = "hello and welcome anonymous running testclient 0.0.1"
    val bytes = stubResponse.getBytes(StandardCharsets.UTF_8)
    println(s"Response is ${bytes.length} bytes long!")
    bytes
  }

  private def sendResponse(selector: Selector, clientChannel: SocketChannel,
                           buffer: ByteBuffer, response: Array[Byte]): Unit = {
    try {
      val responseBuffer = buffer.clear().put(response)
      clientChannel.register(
        selector,
        SelectionKey.OP_WRITE,
        responseBuffer
      )
    } catch {
      case t: Throwable => println(s"Could not register client channel for OP_WRITE! $t")
    }
  }

  private def registerNewClientConn(selector: Selector, key: SelectionKey): Unit = {
    println("registerNewClientConn()")
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
          writeBanner()
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
      println("Re-registered server channel to accept new clients")
    } catch {
      case t: Throwable => println(s"Could not re-register server channel! $t")
    }
  }

  private def readFromClientConn(selector: Selector, key: SelectionKey): Unit = {
    println("readFromClientConn()")
    val clientChannel: SocketChannel = key.channel().asInstanceOf[SocketChannel]

    if (clientChannel.isConnected) {
      try {
        val buffer = ByteBuffer.allocate(defaultRequestBufferSize)
        val i = clientChannel.read(buffer)
        println(s"I read $i bytes from buffer!")

        if (i < 0) {
          println(s"Closing client channel $clientChannel ...")
          clientChannel.close()
          println("Done closing client channel!")

        } else {
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
          val response = handleRequest(rawBytes)

          // send the response back to the client:
          sendResponse(selector, clientChannel, buffer, response)
        }
      } catch {
        case t: Throwable => println(s"Could not read from server channel! $t")
      }

    } else {
      println("Client channel is not connected!")
    }
  }

  private def writeToClientConn(selector: Selector, key: SelectionKey): Unit = {
    println("writeToClientConn()")
    val clientChannel: SocketChannel = key.channel().asInstanceOf[SocketChannel]

    try {
      if (clientChannel.isConnected) {
        val attachment = key.attachment()
        if (attachment == null) {
          println(s"No buffer attached to channel $clientChannel!")
        } else {
          val buffer: ByteBuffer = attachment.asInstanceOf[ByteBuffer]
          buffer.flip()
          while (buffer.hasRemaining) {
            val i = clientChannel.write(buffer)
            println(s"I wrote $i bytes to client channel $clientChannel!")
          }
        }
      }
    } catch {
      case t: Throwable => println(s"Could not write to channel! $t")
    }

    try {
      println(s"Registering client channel $clientChannel for OP_READ")
      clientChannel.register(
        selector,
        SelectionKey.OP_READ,
        null
      )
    } catch {
      case t: Throwable => println(s"Could not re-register client channel for OP_READ! $t")
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
      case t: Throwable => Left("Something unexpected happened!")
    }
  }
}
