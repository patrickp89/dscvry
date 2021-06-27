package de.netherspace.apps.dscvry

import zio._
import zio.nio.core.{Buffer, ByteBuffer}

object BufferUtils {

  def newBuffer(capacity: Option[Int]): ZManaged[Any, Exception, ByteBuffer] = {
    for {
      b <- Managed.fromEffect {
        for {
          buffer <- Buffer.byte(
            capacity.orElse(Some(Constants.defaultRequestBufferSize)).get
          )
        } yield (buffer)
      }
    } yield (b)
  }
}
