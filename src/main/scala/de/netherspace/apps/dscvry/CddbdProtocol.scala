package de.netherspace.apps.dscvry

import zio._
import zio.console._
import zio.logging._

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable


type CddbSessionStateTransition = ZIO[
  CddbServerEnv,
  Exception,
  CddbSessionState
]

class CddbdProtocol(val cddbDatabase: CddbDatabase) {

  sealed trait CddbProtocolCommand
  case class LoginHandshake() extends CddbProtocolCommand
  case class ServerProtocolLevelChange() extends CddbProtocolCommand
  case class DiscidCalculation() extends CddbProtocolCommand
  case class QueryDatabaseWithDiscId() extends CddbProtocolCommand
  case class EmptyCommand() extends CddbProtocolCommand
  case class UnknownCddbCommand() extends CddbProtocolCommand

  sealed trait CddbResponse
  case class FoundExactMatch() extends CddbResponse
  case class FoundInexactMatches() extends CddbResponse
  case class NoMatchFound() extends CddbResponse
  case class CommandSyntaxError() extends CddbResponse

  private val cddbResponsesToResponseCodes = Map(
    FoundExactMatch -> 200,
    FoundInexactMatches -> 211,
    NoMatchFound -> 202,
    CommandSyntaxError -> 500
  )

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
          CddbSessionState(
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


  /**
   * Processes a 'discid' command like the following one
   * 'discid 11 150 28690 51102 75910 102682 121522 149040 175772 204387 231145 268065 3822'
   * by calculating the disc ID for the given disc TOC.
   *
   * Its arguments are
   * 'discid ntrks off_1 off_2 ... off_n nsecs'
   * where
   * ntrks is the total number of tracks on the disc,
   * off_1, off_2, to off_n are the tracks' offsets, and
   * nsecs is the disc's total playing length in seconds.
   *
   * Note that, oddly, this command does not start with a leading "cddb ".
   */
  private def calculateDiscId(requestParts: Array[String]): ZIO[CddbServerEnv, Exception, String] = {
    val n = requestParts.length
    val numberOfTracks = requestParts(1).toInt
    val trackOffsets: List[Int] = requestParts
      .slice(2, n - 1)
      .toList
      .map { s => s.toInt }
    val totalPlayingLength = requestParts(n - 1).toInt

    // did we get the correct number of offsets?
    if (numberOfTracks != (trackOffsets.size)) {
      return for {
        _ <- log.warn("Provided number of tracks did not match the track offsets provided!")
      } yield s"${cddbResponsesToResponseCodes(CommandSyntaxError)} Command Syntax error"
    }

    // calculate the disc ID:
    val discId = CddbUtils.calculateDiscId(trackOffsets, totalPlayingLength)

    return for {
      id <- ZIO
        .fromEither(discId)
        .catchSome {
          case iae: IllegalArgumentException => {
            val responseCode = cddbResponsesToResponseCodes(CommandSyntaxError)
            return for {
              _ <- log.error("Could not calculate disc ID!")
            } yield s"$responseCode Command Syntax error"
          }

          case e => {
            val responseCode = cddbResponsesToResponseCodes(CommandSyntaxError)
            return for {
              _ <- log.error("Something went wrong when calculating a disc ID!")
            } yield s"$responseCode Command Syntax error" // TODO: use a generic error message instead!
          }
        }
    } yield s"200 $id"
  }


  private def toString(disc: CddbDisc) =
    s"${disc.category} ${disc.discId} ${disc.dtitle}"


  private def toResponse(discs: List[CddbDisc]): (String, String) = {
    return if (discs.isEmpty) {
      // no disc found => return "202":
      val responseCode = cddbResponsesToResponseCodes(NoMatchFound)
      val response = s"$responseCode"
      val m = "No matching discs found"
      (response, m)

    } else if (discs.size == 1) {
      // only one disc => simply return it:
      val responseCode = cddbResponsesToResponseCodes(FoundExactMatch)
      val d = toString(discs(0))
      val response = s"$responseCode $d"
      val m = s"Found exactly one matching disc:\n$d"
      (response, m)

    } else {
      // return a big string where matching discs are separated by a linebreak:
      val responseCode = cddbResponsesToResponseCodes(FoundInexactMatches)
      val foldedDiscs = discs
        .map(d => toString(d))
        .mkString("\n")
      val response = s"$responseCode close matches found\n$foldedDiscs\n."
      val m = s"Found ${discs.size} matching discs:\n$foldedDiscs"
      (response, m)
    }
  }


  /**
   * Processes a 'cddb query' command like the following one
   * 'cddb query 920eec0b 11 150 28690 51102 75910 102682 121522 149040 175772 204387 231145 268065 3822'
   * by querying Dscvry's database for the given TOC.
   *
   * Its arguments are
   * 'cddb query discid ntrks off_1 off_2 ... off_n nsecs'
   * where
   * discid is the disc's ID (see e.g. calculateDiscId() above)
   * ntrks is the total number of tracks on the disc,
   * off_1, off_2, to off_n are the tracks' offsets, and
   * nsecs is the disc's total playing length in seconds.
   */
  private def queryDatabase(requestParts: Array[String]): ZIO[CddbServerEnv, Exception, String] = {
    val n = requestParts.length
    val discId = requestParts(2)
    val numberOfTracks = requestParts(3).toInt
    val trackOffsets: List[Int] = requestParts
      .slice(4, n - 1)
      .toList
      .map { s => s.toInt }
    val totalPlayingLength = requestParts(n - 1).toInt

    // did we get the correct number of offsets?
    if (numberOfTracks != (trackOffsets.size)) {
      return for {
        _ <- log.warn("Provided number of tracks did not match the track offsets provided!")
      } yield s"${cddbResponsesToResponseCodes(CommandSyntaxError)} Command Syntax error"
    }

    // okay, let's query the database:
    return for {
      _ <- log.debug(s"Querying database for discId $discId...")
      (response, m) <- ZIO
        .succeed(cddbDatabase.query(discId, trackOffsets, totalPlayingLength))
        .map(matchingDiscs => toResponse(matchingDiscs))

      _ <- log.debug(m)
    } yield response
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
                                 oldSessionState: CddbSessionState):
  ZIO[CddbServerEnv, Exception, Tuple2[Int, String]] = {
    val requestParts = request.split(" ")

    for {
      result <- cddbProtocolCommand match {
        case LoginHandshake() => ZIO.succeed(
          (oldSessionState.protocolLevel, handleHandshake(requestParts))
        )

        case ServerProtocolLevelChange() => ZIO.succeed(
          setCddbProtocolLevel(requestParts)
        )

        case QueryDatabaseWithDiscId() => {
          for {
            matchingDiscs <- queryDatabase(requestParts)
            pl <- ZIO.succeed(oldSessionState.protocolLevel)
            t: Tuple2[Int, String] <- ZIO.succeed(
              (pl, matchingDiscs)
            )
          } yield t
        }

        case DiscidCalculation() => {
          for {
            discIdResponse <- calculateDiscId(requestParts)
            pl <- ZIO.succeed(oldSessionState.protocolLevel)
            t: Tuple2[Int, String] <- ZIO.succeed(
              (pl, discIdResponse)
            )
          } yield t
        }

        case EmptyCommand() => ZIO.succeed(
          (oldSessionState.protocolLevel, "error") // TODO: proper error response!
        )

        case _ => ZIO.succeed(
          (oldSessionState.protocolLevel, "error") // TODO: proper error response!
        )
      }
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


  def handleRequest(requestChunk: Chunk[Byte], oldSessionState: CddbSessionState): CddbSessionStateTransition = {
    for {
      // apply the charset from the given session to our request chunk:
      charsetName <- ZIO.succeed(protocolLevelsToCharsets(oldSessionState.protocolLevel).name)
      charset <- ZIO.succeed(zio.nio.core.charset.Charset.availableCharsets(charsetName))
      charsettedRequestChunk <- charset.decodeChunk(requestChunk)
      requestString <- ZIO.succeed(
        charsettedRequestChunk.toList.map(c => String.valueOf(c)).mkString
      )
      _ <- log.debug(s"Request was: '$requestString'")

      // determine what should be done:
      cddbProtocolCommand: CddbProtocolCommand <- ZIO.succeed(determineProtocolCommand(requestString))
      _ <- log.info(s"CddbProtocolCommand is: '$cddbProtocolCommand'")

      // ...and do it:
      result <- processCddbCommand(cddbProtocolCommand, requestString, oldSessionState)
      (newProtoLevel, response) = result

      newSessionState <- assembleSessionState(response, newProtoLevel)
    } yield newSessionState
  }
}
