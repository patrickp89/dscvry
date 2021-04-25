package de.netherspace.apps.dscvry

import org.scalatest._
import org.scalatest.flatspec._
import org.scalatest.matchers._

import java.io.{InputStreamReader, PrintWriter}
import java.net.Socket
import java.util.concurrent.{ExecutorService, Executors}

class CddbdServerSpec extends AnyFlatSpec with should.Matchers with BeforeAndAfterAll {

  private val testPort = 9234
  private val expBannerLength = 64

  private var executor: Option[ExecutorService] = None


  override def beforeAll(): Unit = {
    println("Booting dscvry for tests...")
    executor = Some(Executors.newCachedThreadPool())
    new CddbdServer().bootstrap(testPort).map(
      f => executor.get.submit(new Runnable {
        override def run(): Unit = f.apply()
      })
    )
  }

  private def openNewClientConn(): Socket = {
    val clientSocket = new Socket("127.0.0.1", testPort)
    clientSocket.setSoTimeout(5200)
    clientSocket
  }

  private def readBanner(isr: InputStreamReader): String = {
    val sb = new StringBuilder
    for (_ <- Seq.range(0, expBannerLength)) {
      val c = isr.read()
      sb.append(c.asInstanceOf[Char])
    }

    sb.length() should be > 0
    val banner = sb.toString()
    println(s"Server sent banner: '$banner'")
    banner
  }

  "A CDDBd server" should "boot properly" in {
    executor.isEmpty should be(false)
  }

  it should "send a banner when a client connects" in {
    val clientSocket = openNewClientConn()
    val isr = new InputStreamReader(clientSocket.getInputStream)

    val banner = readBanner(isr)
    isr.close()
    clientSocket.close()

    banner.length should be(expBannerLength)
    banner should startWith("201 Dscvry CDDBP server v0.0.1 ready at ")
  }

  it should "respond with hello and welcome to finish the handshake" in {
    val clientSocket = openNewClientConn()
    val out = clientSocket.getOutputStream
    val isr = new InputStreamReader(clientSocket.getInputStream)

    readBanner(isr)

    val clientHelloMessage = "cddb hello anonymous localhost testclient 0.0.1"
    val printWriter = new PrintWriter(out)
    printWriter.write(clientHelloMessage)
    printWriter.flush()
    out.flush()

    val expResponseLength = 52
    val sb = new StringBuilder
    for (_ <- Seq.range(0, expResponseLength)) {
      val c = isr.read()
      sb.append(c.asInstanceOf[Char])
    }

    isr.close()
    out.close()
    clientSocket.close()

    sb.length() should be > 0
    val handshakeResponse = sb.toString()

    handshakeResponse.length should be(expResponseLength)
    handshakeResponse should startWith("hello and welcome anonymous running testclient 0.0.1")
  }

  it should "allow multiple client connections" in {
    // open a first connection and read the banner:
    val clientSocket1 = openNewClientConn()
    val isr1 = new InputStreamReader(clientSocket1.getInputStream)
    val banner1 = readBanner(isr1)

    // open a second connection and read the banner:
    val clientSocket2 = openNewClientConn()
    val isr2 = new InputStreamReader(clientSocket2.getInputStream)
    val banner2 = readBanner(isr2)

    // close all connections:
    clientSocket1.close()
    isr1.close()
    clientSocket2.close()
    isr2.close()

    // did we receive our banners?
    banner1.length should be(expBannerLength)
    banner2.length should be(expBannerLength)

    // open a third connection (AFTER the first two
    // were closed!) and read the banner:
    val clientSocket3 = openNewClientConn()
    val isr3 = new InputStreamReader(clientSocket3.getInputStream)
    val banner3 = readBanner(isr3)

    clientSocket3.close()
    isr3.close()
    banner3.length should be(expBannerLength)
  }

  override def afterAll(): Unit = {
    println("Shutting down dscvry!")
    executor.isEmpty should be(false)
    executor.get.shutdown()
  }
}