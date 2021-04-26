package de.netherspace.apps.dscvry

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CddbdProtocol {

  sealed trait CddbProtocolCommand
  case class LoginHandshake() extends CddbProtocolCommand
  case class ServerProtocolLevelChange() extends CddbProtocolCommand
  case class DiscidCalculation() extends CddbProtocolCommand
  case class UnknownCddbCommand() extends CddbProtocolCommand

  private val appName = "Dscvry"

  private def createBanner(): String = {
    val okReadOnlyStatusCode = 201
    val version = "v0.0.1"
    val dateTimeFormat = "EEE LLL dd HH:mm:ss yyyy"
    val dtf = DateTimeFormatter.ofPattern(dateTimeFormat)
    val ts = LocalDateTime.now().format(dtf)
    s"$okReadOnlyStatusCode $appName CDDBP server $version ready at $ts"
  }

  def writeBanner(): ByteBuffer = {
    val serverBanner = createBanner()
    val bannerLength = serverBanner.length
    val buffer = ByteBuffer.allocate(bannerLength)
    buffer.put(serverBanner.getBytes(StandardCharsets.UTF_8))
  }

  private def handleHandshake(requestParts: Array[String]): Array[Byte] = {
    val username = requestParts(2)
    val clientName = requestParts(4)
    val clientVersion = requestParts(5)

    val response = s"200 Hello and welcome $username running $clientName $clientVersion"
    response.getBytes(StandardCharsets.UTF_8)
  }

  private def setCddbProtocolLevel(requestParts: Array[String]): Array[Byte] = {
    val newProtoLevel = requestParts(1)
    // TODO: check, whether newProtoLevel is a valid CDDB protocol level!
    val response = s"201 OK, CDDB protocol level now: $newProtoLevel"
    response.getBytes(StandardCharsets.UTF_8)
  }

  private def calculateDiscId(requestParts: Array[String]): Array[Byte] = {
    // TODO: Discid calculation
    "stub".getBytes(StandardCharsets.UTF_8)
  }

  private def determineProtocolCommand(request: String) : CddbProtocolCommand = {
    // commands can be written lower case:
    val cmd = request.toLowerCase

    // what is it?
    if (cmd.startsWith("cddb hello ")) return LoginHandshake()
    if (cmd.startsWith("proto ")) return ServerProtocolLevelChange()
    if (cmd.startsWith("discid ")) return DiscidCalculation()
    UnknownCddbCommand()
  }

  def handleRequest(rawBytes: ByteArrayOutputStream): Array[Byte] = {
    val request = rawBytes.toString(StandardCharsets.UTF_8)
    println(s"Handling request: '$request'")

    val requestParts = request.split(" ")

    // handle all possible commands:
    val bytes = determineProtocolCommand(request) match {
      case LoginHandshake() => handleHandshake(requestParts)
      case ServerProtocolLevelChange() => setCddbProtocolLevel(requestParts)
      case DiscidCalculation() => calculateDiscId(requestParts)
      case _ =>
        println("An unknown command was sent!")
        "error".getBytes(StandardCharsets.UTF_8) // TODO: proper error response?
    }

    println(s"Response is ${bytes.length} bytes long!")
    bytes
  }
}
