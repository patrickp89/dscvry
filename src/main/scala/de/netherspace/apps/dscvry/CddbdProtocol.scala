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
    s"$okReadOnlyStatusCode $appName CDDBP server $version ready at $ts"
  }


  private def assembleSessionState(bufferContent: String,
                                   newProtoLevel: Int): CddbSessionStateTransition = {
    // add a linebreak to every response:
    val contentBytes = s"$bufferContent\n".getBytes(
      // ...and use the charset corresponding the CDDB protocol level:
      protocolLevelsToCharsets(newProtoLevel)
    )
    val contentChunk = zio.Chunk.fromArray(contentBytes)
    for {
      sessionState <- BufferUtils.newBuffer(None).use { b =>
        for {
          _ <- b.putChunk(contentChunk)
        } yield (
          CddbSessionState3(
            protocolLevel = newProtoLevel,
            buffer = Some(b)
          )
          )
      }
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


  private def processCddbCommand(cddbProtocolCommand: CddbProtocolCommand, request: String,
                                 oldSessionState: CddbSessionState3): ZIO[Console, Exception, Tuple2[Int, String]] = {
    val requestParts = request.split(" ")
    var newProtoLevel = oldSessionState.protocolLevel

    val response: String = cddbProtocolCommand match {
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
        // TODO: println("An unknown command was sent!")
        "error" // TODO: proper error response?
    }
    for {
      result <- ZIO.succeed((newProtoLevel, response))
    } yield result
  }


  def createInitialSessionState(): CddbSessionStateTransition = {
    val serverBanner = createBanner()
    for {
      sessionState <- assembleSessionState(
        serverBanner,
        Constants.defaultCddbProtocolLevel
      )
    } yield sessionState
  }


  def handleRequest3(requestChunk: Chunk[Byte], oldSessionState: CddbSessionState3): CddbSessionStateTransition = {
    for {
      // apply the charset from the given session to our request chunk:
      charsetName <- ZIO.succeed(protocolLevelsToCharsets(oldSessionState.protocolLevel).name)
      charset <- ZIO.succeed(zio.nio.core.charset.Charset.availableCharsets(charsetName))
      charsettedRequestChunk <- charset.decodeChunk(requestChunk)
      requestString <- ZIO.succeed(
        charsettedRequestChunk.toList.map(c => String.valueOf(c)).mkString
      )
      _ <- putStrLn(s"Request was: '$requestString'") // TODO: use a proper logger.debug()

      // determine what should be done:
      cddbProtocolCommand: CddbProtocolCommand <- ZIO.succeed(determineProtocolCommand(requestString))
      _ <- putStrLn(s"CddbProtocolCommand is: '$cddbProtocolCommand'") // TODO: use a proper logger.debug()

      // ...and do it:
      result <- processCddbCommand(cddbProtocolCommand, requestString, oldSessionState)
      (newProtoLevel, response) = result

      newSessionState <- assembleSessionState(response, newProtoLevel)
    } yield newSessionState
  }
}
