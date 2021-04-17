package de.netherspace.apps.dscvry

import org.scalatest._
import org.scalatest.flatspec._
import org.scalatest.matchers._

import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.{ExecutorService, Executors}

class CddbdServerSpec extends AnyFlatSpec with should.Matchers with BeforeAndAfterAll {

  private val testPort = 9234
  private var executor: Option[ExecutorService] = None

  override def beforeAll(): Unit = {
    executor = Some(Executors.newCachedThreadPool())
    new CddbdServer().bootstrap(testPort).map(
      f => executor.get.submit(new Runnable {
        override def run(): Unit = f.apply()
      })
    )
  }

  "A CDDBd server" should "boot properly" in {
    executor.isEmpty should be(false)
  }

  it should "send a banner when a client connects" in {
    val clientSocket = new Socket("127.0.0.1", testPort)
    clientSocket.setSoTimeout(5600)
    val out = clientSocket.getOutputStream
    val isr = new InputStreamReader(clientSocket.getInputStream)

    val expBannerLength = 64
    val sb = new StringBuilder

    for (_ <- Seq.range(0, expBannerLength)) {
      val c = isr.read()
      sb.append(c.asInstanceOf[Char])
    }
    isr.close()
    clientSocket.close()

    sb.length() should be > 0
    val banner = sb.toString()
    banner.length should be(expBannerLength)
    banner should startWith("201 Dscvry CDDBP server v0.0.1 ready at ")
  }

  override def afterAll(): Unit = {
    executor.isEmpty should be(false)
    executor.get.shutdown()
  }
}
