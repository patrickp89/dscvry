package de.netherspace.apps.dscvry

import java.net.InetSocketAddress
import java.nio.channels.{ClosedChannelException, SelectionKey, Selector, ServerSocketChannel}
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class CddbdServer {

  private def selectKeys(selector: Selector): Unit = {
    // TODO: serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT)
    // TODO: while(running) { ...}
  }

  def bootstrap(port: Int): Either[String, Future[_]] = {
    try {
      // create an NIO channel selector and a server socket:
      val selector = Selector.open()
      val serverSocketChannel = ServerSocketChannel.open()

      // listen on the server socket:
      val serverAddr = new InetSocketAddress("localhost", port)
      println(s"Binding on port $port...")
      serverSocketChannel.bind(serverAddr)
      println("register(selector, SelectionKey.OP_ACCEPT)")

      // bootstrap the server:
      println("Executors.newCachedThreadPool()")
      val executor = Executors.newCachedThreadPool()
      println("submitting new Runnable to the CachedThreadPool...")
      val javaFuture = executor.submit(new Runnable {
        override def run(): Unit = selectKeys(selector)
      })

      // and return a Scala Future:
      Right(Future { implicit ec: ExecutionContext =>
        javaFuture.get
      })

    } catch {
      case e: ClosedChannelException => Left(e.getMessage)
      case e: java.io.IOException => Left(e.getMessage)
      case t: Throwable => Left("Something unexpected happened!")
    }
  }
}
