package de.netherspace.apps.dscvry

import zio.console._

object CddbdBootstrap {

  val ServerPort = 8880
  val appLogic =
    for {
      _ <- putStrLn("Server started")
      _ <- new CddbdServer().bootstrap(ServerPort).useForever
    } yield ()
}
