package de.netherspace.apps.dscvry

case class CddbDisc(
   category: String, // e.g. "rock" (note: this is not the disc's genre!)
   discId: String, // disc ID, e.g. "f50a3b13"
   dtitle: String // artist and title, e.g. "Pink Floyd / The Dark Side of the Moon"
)
