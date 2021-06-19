package de.netherspace.apps.dscvry

import java.nio.ByteBuffer

case class CddbSessionState(
     protocolLevel: Int,
     buffer: Option[ByteBuffer]
)

case class CddbSessionState3(
     protocolLevel: Int,
     buffer: Option[zio.nio.core.ByteBuffer]
)