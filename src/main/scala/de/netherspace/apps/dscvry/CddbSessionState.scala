package de.netherspace.apps.dscvry

import java.nio.ByteBuffer

case class CddbSessionState(
     protocolLevel: Int,
     buffer: Option[ByteBuffer]
)