package de.netherspace.apps.dscvry

import zio._
import zio.nio.{Buffer, ByteBuffer}

object BufferUtils {

  def newBuffer(capacity: Option[Int]): ZIO[Any, Exception, ByteBuffer] =
    Buffer
      .byte(
        capacity
          .orElse(
            Some(Constants.defaultRequestBufferSize)
          )
          .get
      )
}
