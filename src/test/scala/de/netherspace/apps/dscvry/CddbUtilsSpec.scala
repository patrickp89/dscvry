package de.netherspace.apps.dscvry

import org.scalatest._
import org.scalatest.flatspec._
import org.scalatest.matchers._

class CddbUtilsSpec extends AnyFlatSpec with should.Matchers {

  "The CDDB helper utility" should "properly calculate the sums of digits" in {
    CddbUtils.calculateSumOfDecimalDigits(1) should be(1)
    CddbUtils.calculateSumOfDecimalDigits(12) should be(3)
    CddbUtils.calculateSumOfDecimalDigits(123) should be(6)
    CddbUtils.calculateSumOfDecimalDigits(1234) should be(10)
    CddbUtils.calculateSumOfDecimalDigits(12345) should be(15)
  }


  it should "calculate proper checksums" in {
    // According to https://gnudb.org/gnudb/rock/970ada0c
    // Pearl Jam's 'Vs.'' has 12 tracks in total, the first starting at frame[0]=150,
    // the 12th track starting at frame[11]=185792:
    val pearlJamVsOffsets = List(
      150, 14672, 27367, 45030,
      60545, 76707, 103645, 116430,
      137730, 156887, 171577, 185792
    )

    // compute the total playing length, just for kicks:
    val numberOfTracks = pearlJamVsOffsets.size
    numberOfTracks should be(12)

    // now compute the checksum over all tracks:
    val checksum = CddbUtils.calculateChecksumForTracks(numberOfTracks, pearlJamVsOffsets)
    checksum should be(151)
  }


  it should "calculate a proper disc ID for Maiden's 'Number of the Beast'" in {
    // According to https://gnudb.org/gnudb/rock/6a097308
    // Iron Maiden's 'Number of the Beast' has 8 tracks:
    val maidenBeastOffsets = List(
      150, 15375, 35992,
      63142, 92865, 114667,
      132115, 149265
    )

    val numberOfTracks = maidenBeastOffsets.size
    numberOfTracks should be(8)

    // ...and the disc length is:
    val totalPlayingLength = 2421

    // now compute the disc ID:
    val discId = CddbUtils.calculateDiscId(maidenBeastOffsets, totalPlayingLength)
    discId should be(Right("6a097308"))
  }


  it should "calculate a proper disc ID for Pink Floyd's 'Dark Side of the Moon'" in {
    // According to https://gnudb.org/gnudb/rock/740a1f09
    // Pink Floyd's 'Dark Side of the Moon' has 9 tracks:
    val floydDarkSideOffsets = List(
      150, 18137, 34414,
      66426, 88108, 116945,
      152367, 167944, 185383
    )

    val numberOfTracks = floydDarkSideOffsets.size
    numberOfTracks should be(9)

    // ...and the disc length is:
    val totalPlayingLength = 2593

    // now compute the disc ID:
    val discId = CddbUtils.calculateDiscId(floydDarkSideOffsets, totalPlayingLength)
    discId should be(Right("740a1f09"))
  }
}
