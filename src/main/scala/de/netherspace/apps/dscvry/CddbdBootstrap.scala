package de.netherspace.apps.dscvry

import zio._
import zio.Clock
import zio.logging._

object CddbdBootstrap {

  val ServerPort = 8880
  val nioSelectionSchedule = Schedule.forever

  val appLogic: ZIO[CddbServerEnv, Exception, Unit] =
    for {
      _ <- ZIO.logInfo("Starting server...")
      _ <- new CddbdServer()
        .bootstrap(ServerPort, nioSelectionSchedule)
        .useForever
    } yield ()
}
