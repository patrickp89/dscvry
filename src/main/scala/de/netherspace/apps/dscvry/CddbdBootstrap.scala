package de.netherspace.apps.dscvry

import zio._
import zio.clock._
import zio.console._
import zio.logging._

object CddbdBootstrap {

  val ServerPort = 8880
  val nioSelectionSchedule = Schedule.forever

  val appLogic: ZIO[CddbServerEnv, Exception, Unit] =
    for {
      _ <- log.info("Starting server...")
      _ <- new CddbdServer()
        .bootstrap(ServerPort, nioSelectionSchedule)
        .useForever
    } yield ()
}
