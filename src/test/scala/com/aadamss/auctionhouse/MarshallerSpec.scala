package com.aadamss.auctionhouse

import akka.http.scaladsl.model.DateTime
import com.aadamss.auctionhouse.actors.AuctionHouse.Auction
import com.aadamss.auctionhouse.actors.AuctionActor.{FreeIncrement, _}
import com.aadamss.auctionhouse.marshaller.Marshaller
import com.aadamss.auctionhouse.response.Response._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.language.postfixOps
import spray.json._

class MarshallerSpec extends Marshaller
  with AnyWordSpecLike
  with Matchers {
  val today: DateTime = DateTime.now
  val tomorrow: DateTime = DateTime.now.plus((1 day).toMillis)

  def incrementToJson(incrementPolicy: IncrementPolicy): JsValue =
    incrementPolicy.toJson

  "Marshaller" must {
    def checkFormat[T](objectToTest: T, jsValue: JsValue)
                      (implicit jsonFormat: JsonFormat[T]) = {
      objectToTest.toJson mustBe jsValue
      jsValue.convertTo[T] mustBe objectToTest
    }

    def checkWriting[T](objectToTest: T, jsValue: JsValue)
                       (implicit jsonWriter: JsonWriter[T]) = {
      objectToTest.toJson mustBe jsValue
    }

    "successfully convert DateTime to and from json" in {
      checkFormat(today, JsString(today.toIsoDateTimeString))
    }

    "successfully convert AuctionState to and from json" in {
      checkFormat[AuctionState](Upcoming, JsString(Upcoming.key))
      checkFormat[AuctionState](Open, JsString(Open.key))
      checkFormat[AuctionState](Ended, JsString(Ended.key))
    }

    "successfully convert IncrementPolicy to and from json" in {
      checkFormat[IncrementPolicy](
        FreeIncrement,
        JsObject("incrementType" -> JsString("FreeIncrement"))
      )
      checkFormat[IncrementPolicy](
        MinimumIncrement(100),
        JsObject(
          "incrementType" -> JsString("MinimumIncrement"),
          "minimumBid" -> JsNumber(100)
        )
      )
    }

    "successfully convert CreateAuctionParams to and from json" in {
      checkFormat(
        CreateAuctionParams("test", 100, FreeIncrement, today, tomorrow),
        JsObject(
          "item" -> JsString("test"),
          "startingPrice" -> JsNumber(100),
          "incrementPolicy" -> incrementToJson(FreeIncrement),
          "startDate" -> today.toJson,
          "endDate" -> tomorrow.toJson
        )
      )
    }

    "successfully convert UpdateAuctionParams to and from json" in {
      checkFormat(
        UpdateAuctionParams(
          Some(100), Some(FreeIncrement), Some(today), Some(tomorrow)
        ),
        JsObject(
          "startingPrice" -> JsNumber(100),
          "incrementPolicy" -> incrementToJson(FreeIncrement),
          "startDate" -> today.toJson,
          "endDate" -> tomorrow.toJson
        )
      )

      checkFormat(
        UpdateAuctionParams(None, Some(FreeIncrement), Some(today), Some(tomorrow)),
        JsObject(
          "incrementPolicy" -> incrementToJson(FreeIncrement),
          "startDate" -> today.toJson,
          "endDate" -> tomorrow.toJson
        )
      )

      checkFormat(
        UpdateAuctionParams(None, None, Some(today), Some(tomorrow)),
        JsObject("startDate" -> today.toJson, "endDate" -> tomorrow.toJson)
      )

      checkFormat(
        UpdateAuctionParams(None, None, None, Some(tomorrow)),
        JsObject("endDate" -> tomorrow.toJson)
      )

      checkFormat(UpdateAuctionParams(None, None, None, None), JsObject())
    }

    "successfully convert JoinAuctionParams to and from json" in {
      checkFormat(
        JoinAuctionParams("test"),
        JsObject("bidderName" -> JsString("test"))
      )
    }

    "successfully convert PlaceBidParams to and from json" in {
      checkFormat(
        PlaceBidParams(100),
        JsObject("value" -> JsNumber(100))
      )
    }

    "successfully convert Bidder to and from json" in {
      checkFormat(
        Bidder("testBidder"),
        JsObject("name" -> JsString("testBidder"))
      )
    }

    "successfully convert Bid to and from json" in {
      checkFormat(
        Bid("testBidder", 100),
        JsObject(
          "bidderName" -> JsString("testBidder"),
          "value" -> JsNumber(100)
        )
      )
    }

    "successfully convert WinningBid to and from json" in {
      checkFormat(
        WinningBid(Bidder("testBidder"), Bid("test", 100)),
        JsObject(
          "bidder" -> Bidder("testBidder").toJson,
          "bid" -> Bid("test", 100).toJson
        )
      )
    }

    "successfully convert Auction to and from json" in {
      val state: AuctionState = Upcoming
      checkFormat(
        Auction(
          "test",
          100,
          FreeIncrement,
          state,
          today,
          tomorrow,
          Set(),
          Vector(),
          None
        ),
        JsObject(
          "item" -> JsString("test"),
          "startingPrice" -> JsNumber(100),
          "incrementPolicy" -> incrementToJson(FreeIncrement),
          "auctionState" -> state.toJson,
          "startDate" -> today.toJson,
          "endDate" -> tomorrow.toJson,
          "bidders" -> JsArray(),
          "bids" -> JsArray()
        )
      )

      val bidders = Set(Bidder("testBidder"), Bidder("test2"))
      val bids = Vector(Bid("test", 200), Bid("test2", 100))
      val winningBid = WinningBid(Bidder("testBidder"), Bid("test", 200))
      checkFormat(
        Auction(
          "test",
          100,
          MinimumIncrement(10),
          state,
          today,
          tomorrow,
          bidders,
          bids,
          Some(winningBid)
        ),
        JsObject(
          "item" -> JsString("test"),
          "startingPrice" -> JsNumber(100),
          "incrementPolicy" -> incrementToJson(MinimumIncrement(10)),
          "auctionState" -> state.toJson,
          "startDate" -> today.toJson,
          "endDate" -> tomorrow.toJson,
          "bidders" -> JsArray(bidders.map(_.toJson).toVector),
          "bids" -> JsArray(bids.map(_.toJson)),
          "winningBid" -> winningBid.toJson
        )
      )
    }

    "successfully convert ErrorResponse to and from json" in {
      Set(
        AuctionAlreadyExists("test"),
        NegativeStartingPrice(100),
        AuctionNotFound("test"),
        NotPermittedByState(Ended),
        BidderAlreadyJoined(Bidder("testBidder")),
        BidderDidNotJoin("test"),
        BidTooLow(100)
      ) foreach { errorResponse: ErrorResponse =>
        checkWriting[ErrorResponse](
          errorResponse,
          JsObject("message" -> JsString(errorResponse.message))
        )
      }
    }

    "successfully convert UnknownError to and from json" in {
      checkWriting[UnknownError](
        UnknownError(),
        JsObject("message" -> JsString(UnknownError().message))
      )
    }

    "throw DeserializationException if the state key is invalid" in {
      a[DeserializationException] must be thrownBy {
        JsString("invalidKey").convertTo[AuctionState]
      }
    }
  }
}
