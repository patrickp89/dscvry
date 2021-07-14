package de.netherspace.apps.dscvry

object CddbUtils {

  def calculateSumOfDecimalDigits(m: Int): Int = {
    m.toString
      .toCharArray
      .map { c => c.toString.toInt }
      .sum
  }

  def calculateChecksumForTracks(numberOfTracks: Int, trackOffsets: List[Int]): Int = {
    trackOffsets
      .map { t => calculateSumOfDecimalDigits(t / 75) }
      .sum
  }

  def calculateDiscId(trackOffsets: List[Int], totalPlayingLength: Int): Either[Exception, String] = {
    val numberOfTracks = trackOffsets.size
    if (numberOfTracks < 1) {
      return Left(IllegalArgumentException("No tracks given!"))
    }

    if (totalPlayingLength < 0) {
      return Left(IllegalArgumentException("Total playing length was < 0!"))
    }

    try {
      val checksum = calculateChecksumForTracks(numberOfTracks, trackOffsets)
      val discIdBytes: Int = (((checksum % 0xff) << 24) | ((totalPlayingLength - trackOffsets(0) / 75) << 8) | numberOfTracks)
      Right(discIdBytes.toHexString)

    } catch {
      case iobe: java.lang.IndexOutOfBoundsException => Left(iobe)
    }
  }
}
