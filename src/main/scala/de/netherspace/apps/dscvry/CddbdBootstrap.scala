package de.netherspace.apps.dscvry

import zio._
import zio.logging._
import zio.console._
import zio.clock._

object CddbdBootstrap {

  val ServerPort = 8880
  val nioSelectionSchedule =  Schedule.forever

  // TODO: what type is appLogic?
  // : ZIO[Logging & Console & Clock, Exception, Unit] ?
  // : ZIO[Logging, Nothing, Unit] ? works barebone (playground project)!
  val appLogic =
    for {
      // TODO: _ <- log.info("Server started")
      _ <- new CddbdServer().bootstrap(ServerPort, nioSelectionSchedule).useForever
    } yield ()
}
