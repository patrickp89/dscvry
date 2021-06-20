package de.netherspace.apps.dscvry

import zio._
import zio.console._

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable

type CddbSessionStateTransition = ZIO[
  Has[Console.Service],
  Exception,
  CddbSessionState3
]

class CddbdProtocol(val cddbDatabase: CddbDatabase) {

  sealed trait CddbProtocolCommand

  case class LoginHandshake() extends CddbProtocolCommand

  case class ServerProtocolLevelChange() extends CddbProtocolCommand

  case class DiscidCalculation() extends CddbProtocolCommand

  case class QueryDatabaseWithDiscId() extends CddbProtocolCommand

  case class EmptyCommand() extends CddbProtocolCommand

  case class UnknownCddbCommand() extends CddbProtocolCommand


  private val appName = "Dscvry"

  private val protocolLevelsToCharsets = Map(
    1 -> StandardCharsets.ISO_8859_1,
    2 -> StandardCharsets.ISO_8859_1,
    3 -> StandardCharsets.ISO_8859_1,
    4 -> StandardCharsets.ISO_8859_1,
    5 -> StandardCharsets.ISO_8859_1,
    6 -> StandardCharsets.UTF_8
  )

  private def createBanner(): String = {
    val okReadOnlyStatusCode = 201
    val version = "v0.0.1"
    val dateTimeFormat = "EEE LLL dd HH:mm:ss yyyy"
    val dtf = DateTimeFormatter.ofPattern(dateTimeFormat)
    val ts = LocalDateTime.now().format(dtf)
    s"$okReadOnlyStatusCode $appName CDDBP server $version ready at $ts\n"
  }

  def newBuffer(capacity: Option[Int]): zio.ZManaged[zio.Has[zio.console.Console.Service], Exception, zio.nio.core.ByteBuffer] = {
    for {
      b <- zio.Managed.fromEffect {
        for {
          buffer <- zio.nio.core.Buffer.byte(
            capacity.orElse(Some(Constants.defaultRequestBufferSize)).get
          )
        } yield (buffer)
      }
    } yield (b)
  }

  private def assembleSessionState(bufferContent: String): CddbSessionStateTransition = {
    val contentBytes = bufferContent.getBytes(
      protocolLevelsToCharsets(Constants.defaultCddbProtocolLevel)
    )
    val contentChunk = zio.Chunk.fromArray(contentBytes)
    for {
      sessionState <- newBuffer(None).use { b =>
        for {
          _ <- b.putChunk(contentChunk)
        } yield (
          CddbSessionState3(
            protocolLevel = Constants.defaultCddbProtocolLevel, // TODO: get from arg
            buffer = Some(b)
          )
          )
      }
    } yield sessionState
  }

  def createInitialSessionState(): CddbSessionStateTransition = {
    val serverBanner = createBanner()
    for {
      sessionState <- assembleSessionState(serverBanner)
    } yield sessionState
  }


  private def handleHandshake(requestParts: Array[String]): String = {
    val username = requestParts(2)
    val clientName = requestParts(4)
    val clientVersion = requestParts(5)

    s"200 Hello and welcome $username running $clientName $clientVersion"
  }

  private def setCddbProtocolLevel(requestParts: Array[String]): (Int, String) = {
    val illegalProtoLevel = "501 Illegal protocol level"
    val newProtoLevel = requestParts(1)
    // TODO: check, whether newProtoLevel is a valid CDDB protocol level!
    try {
      val newIntProtoLevel = newProtoLevel.toInt
      if (newIntProtoLevel > 0 && newIntProtoLevel <= 6) {
        (newIntProtoLevel, s"201 OK, CDDB protocol level now: $newProtoLevel")
      } else {
        (Constants.defaultCddbProtocolLevel, illegalProtoLevel)
      }
    } catch {
      case _: Throwable => (Constants.defaultCddbProtocolLevel, illegalProtoLevel)
    }
  }

  private def calculateDiscId(requestParts: Array[String]): String = {
    // TODO: Discid calculation
    "stub"
  }

  private def queryDatabase(requestParts: Array[String]): String = {
    val n = requestParts.length
    val discId = requestParts(2)
    val numberOfTracks = requestParts(3)
    val totalPlayingLength = requestParts(n - 1)
    println(s"Querying database for discId $discId...")

    // TODO: cddbDatabase.query(discId, numberOfTracks, trackOffsets, totalPlayingLength)
    "stub"
  }

  private def determineProtocolCommand(request: String): CddbProtocolCommand = {
    // commands can be written lower case:
    val cmd = request.toLowerCase

    // what is it?
    if (cmd.startsWith("cddb hello ")) return LoginHandshake()
    if (cmd.startsWith("proto ")) return ServerProtocolLevelChange()
    if (cmd.startsWith("discid ")) return DiscidCalculation()
    if (cmd.startsWith("cddb query ")) return QueryDatabaseWithDiscId()
    if (cmd.trim.isEmpty) return EmptyCommand()
    UnknownCddbCommand()
  }

  private def writeResponseToBuffer(buffer: Option[ByteBuffer],
                                    response: Array[Byte]): ByteBuffer = {
    buffer match {
      case Some(buffer) => buffer
        .clear()
        .put(response)

      case None => ByteBuffer
        .allocate(Constants.defaultRequestBufferSize)
        .put(response)
    }
  }

  def handleRequest3(requestChunk: Chunk[Byte], sessionState: CddbSessionState3): CddbSessionStateTransition = {
    for {
      charsetName <- ZIO.succeed(protocolLevelsToCharsets(sessionState.protocolLevel).name)
      charset <- ZIO.succeed(zio.nio.core.charset.Charset.availableCharsets(charsetName))
      charsettedRequestChunk <- charset.decodeChunk(requestChunk)
      requestString <- ZIO.succeed(
        charsettedRequestChunk.toList.map(c => String.valueOf(c)).mkString
      )
      _ <- putStrLn(s"Request was: '$requestString'") // TODO: use a proper logger.debug()

      cddbProtocolCommand: CddbProtocolCommand <- ZIO.succeed(determineProtocolCommand(requestString))
      _ <- putStrLn(s"CddbProtocolCommand is: '$cddbProtocolCommand'") // TODO: use a proper logger.debug()

      // TODO: replace fake response with a real one!
      newSessionState <- assembleSessionState("200 Hello and welcome anonymous running testclient 0.0.1\n")
      // TODO: change one char to fail the test ---> will yield an unhandled "java.net.SocketException: Connection reset"!
    } yield newSessionState
  }

  def handleRequest(rawRequest: ByteArrayOutputStream,
                    sessionState: CddbSessionState): CddbSessionState = {
    val request = rawRequest.toString(protocolLevelsToCharsets(sessionState.protocolLevel))

    val requestParts = request.split(" ")
    var newProtoLevel = sessionState.protocolLevel

    // handle all possible commands:
    val response: String = determineProtocolCommand(request) match {
      case LoginHandshake() => handleHandshake(requestParts)
      case ServerProtocolLevelChange() => {
        val (pl, resp) = setCddbProtocolLevel(requestParts)
        newProtoLevel = pl
        resp
      }
      case DiscidCalculation() => calculateDiscId(requestParts)
      case QueryDatabaseWithDiscId() => queryDatabase(requestParts)
      case EmptyCommand() => "error" // TODO: proper error response?
      case _ =>
        println("An unknown command was sent!")
        "error" // TODO: proper error response?
    }
    val charset = protocolLevelsToCharsets(newProtoLevel)
    val bytes: Array[Byte] = s"$response\n".getBytes(charset)

    println(s"Response is ${bytes.length} bytes long!")
    sessionState.copy(
      protocolLevel = newProtoLevel,
      buffer = Some(writeResponseToBuffer(sessionState.buffer, bytes))
    )
  }
}
