package de.netherspace.apps.dscvry

class CddbDatabase {

    def query(discId: String, numberOfTracks: Int,  trackOffsets: List[Int],
        totalPlayingLength: String): List[CddbDisc] = {
        // look up the disc(s):
        // TODO!

        // return some stub discs:
        return List(
            CddbDisc("rock", "f50a3b13", "Pink Floyd / The Dark Side of the Moon"),
            CddbDisc("metal", "h6k31bg1", "Iron Maiden / Brave New World")
        )
    }
}
