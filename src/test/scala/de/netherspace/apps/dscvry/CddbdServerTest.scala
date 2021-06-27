package de.netherspace.apps.dscvry

import zio.{Schedule, _}
import zio.duration._
import zio.nio.core._
import zio.nio.core.channels._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.test.environment._

import java.nio.charset.StandardCharsets

object CddbdServerTest extends DefaultRunnableSpec {

  val testPort = 8881

  def spec = suite("CddbServerSuite")(
    testM("server sends banner") {
      for {
        // spin up a server instance:
        server <- new CddbdServer().bootstrap(testPort, Schedule.once).useNow.fork

        // open client conn and read our banner:
        addr <- InetSocketAddress.hostNameResolved("127.0.0.1", testPort)
        buffer <- SocketChannel.open(addr).use { client =>
          for {
            b <- Buffer.byte(65)
            _ <- client.read(b)
          } yield b
        }
        _ <- server.join

        // extract the banner as string:
        bufferPos <- buffer.position
        _ <- buffer.flip
        remainingAfterFlip <- buffer.remaining
        responseChunk <- buffer.getChunk(remainingAfterFlip)
        charset <- ZIO.succeed(zio.nio.core.charset.Charset.availableCharsets(StandardCharsets.ISO_8859_1.name))
        charsettedRequestChunk <- charset.decodeChunk(responseChunk)
        banner <- ZIO.succeed(
          charsettedRequestChunk.toList.map(c => String.valueOf(c)).mkString
        )

        // and assert that everything went according to plan:
      } yield assert(bufferPos)(not(isNull))
        && assert(remainingAfterFlip)(isGreaterThanEqualTo(65))
        && assert(bufferPos)(isGreaterThanEqualTo(65))
        && assert(banner)(not(isNull))
        && assert(banner)(startsWithString("201 Dscvry CDDBP server v0.0.1 ready at"))
    } @@ timeout(2.seconds)
  )
}
