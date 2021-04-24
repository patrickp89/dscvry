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

  it should "respond with hello and welcome to finish the handshake" in {
    val clientSocket = new Socket("127.0.0.1", testPort)
    clientSocket.setSoTimeout(5600)
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

  override def afterAll(): Unit = {
    println("Shutting down dscvry!")
    executor.isEmpty should be(false)
    executor.get.shutdown()
  }
}
