import de.netherspace.apps.dscvry.CddbdServer

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Main extends App {
  val ServerPort = 8880
  val server: Unit = new CddbdServer().bootstrap(ServerPort) match {
    case Left(e) => println(s"Could not bootstrap server! $e")
    case Right(f) => f onComplete {
      case Success(_) => println("Up and running!")
      case Failure(e) => println(s"Something went wrong! ${e.getMessage}")
    }
  }

}
