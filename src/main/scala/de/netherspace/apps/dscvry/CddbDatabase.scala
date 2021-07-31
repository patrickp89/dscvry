package de.netherspace.apps.dscvry

import com.mongodb.{MongoClientSettings, MongoException}
import com.mongodb.client.{MongoClient, MongoClients, MongoDatabase}
import com.mongodb.client.model.Filters
import org.bson.{BsonDocument, BsonInt64, Document}
import org.bson.conversions.Bson

class CddbDatabase {

  private def stubResults(discId: String): List[CddbDisc] = {
    return if (discId == "6a097308") {
      List(
        CddbDisc("rock", "6a097308", "Iron Maiden / The Number Of The Beast")
      )
    } else {
      List(
        CddbDisc("rock", "f50a3b13", "Pink Floyd / The Dark Side of the Moon"),
        CddbDisc("metal", "h6k31bg1", "Iron Maiden / Brave New World")
      )
    }
  }


  def query(discId: String, trackOffsets: List[Int],
            totalPlayingLength: Int): List[CddbDisc] = {
    try {
      // connect to Mongo
      // TODO

      // return some stub discs:
      return stubResults(discId)
    } catch {
      case e: com.mongodb.MongoSocketException => {
        println(e); return List()
      }
    }
  }
}
