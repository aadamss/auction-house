package com.aadamss.auctionhouse.marshaller

import akka.http.scaladsl.model.DateTime
import com.aadamss.auctionhouse.actors.AuctionHouse._
import com.aadamss.auctionhouse.actors.AuctionActor
import com.aadamss.auctionhouse.actors.AuctionActor._
import com.aadamss.auctionhouse.response.Response._
import spray.json._

/** Contains some Data Transfer Object final case classes as well as a JsonFormat for each of them as well as many domain final case classes.
  * By that a formatting/unformatting mechanism is defined for usage with Spray JSON.
  */
trait Marshaller extends DefaultJsonProtocol {

  case class CreateAuctionParams(
    item: String,
    startingPrice: Int,
    incrementPolicy: IncrementPolicy,
    startDate: DateTime,
    endDate: DateTime,
  )

  case class UpdateAuctionParams(
    startingPrice: Option[Int],
    incrementPolicy: Option[IncrementPolicy],
    startDate: Option[DateTime],
    endDate: Option[DateTime],
  )

  case class JoinAuctionParams(bidderName: String)
  case class PlaceBidParams(value: Int)

  /** A JsonFormat for marshalling a DateTime in ISO format up to seconds detail without time zone. */
  implicit object DateTimeFormat extends RootJsonFormat[DateTime] {
    def write(dateTime: DateTime): JsString = JsString(dateTime.toIsoDateTimeString)

    def read(json: JsValue): DateTime = json match {
      case JsString(value) =>
        DateTime.fromIsoDateTimeString(value) match {
          case Some(dateTime) => dateTime
          case None           => deserializationError("Parsing of string as DateTime failed!")
        }
      case _ => deserializationError("Failed to find a String for DateTime!")
    }
  }

  implicit object AuctionStateFormat extends RootJsonFormat[AuctionState] {
    def write(auctionState: AuctionState): JsString = JsString(auctionState.key)

    def read(json: JsValue): AuctionState = json match {
      case JsString(value) =>
        AuctionActor.availableAuctionStates.find(_.key == value) match {
          case Some(state) => state
          case None        => deserializationError("Auction state not found!")
        }
      case _ => deserializationError("Auction state expected!")
    }
  }

  implicit object IncrementPolicyFormat extends RootJsonFormat[IncrementPolicy] {
    def write(incrementPolicy: IncrementPolicy): JsValue = {
      val base = Map("incrementType" -> JsString(incrementPolicy.incrementType))
      incrementPolicy match {
        case FreeIncrement => JsObject(base)
        case MinimumIncrement(minimumBid) =>
          JsObject(base + ("minimumBid" -> JsNumber(minimumBid)))
      }
    }

    def read(json: JsValue): IncrementPolicy = json match {
      case JsObject(value) =>
        (value.get("incrementType"), value.get("minimumBid")) match {
          case (Some(JsString("FreeIncrement")), _) => FreeIncrement
          case (Some(JsString("MinimumIncrement")), Some(JsNumber(minimumBid))) =>
            MinimumIncrement(minimumBid.toInt)
          case (Some(JsString("MinimumIncrement")), _) =>
            deserializationError("Minimum increment must have a value set!")
          case (None, _) =>
            deserializationError("Increment policy must have a key!")
          case _ => deserializationError("The increment policy is invalid!")
        }
      case _ => deserializationError("No increment policy found!")
    }
  }

  implicit val createAuctionParamsJson: RootJsonFormat[CreateAuctionParams] =
    jsonFormat5(CreateAuctionParams)
  implicit val updateAuctionParamsJson: RootJsonFormat[UpdateAuctionParams] =
    jsonFormat4(UpdateAuctionParams)
  implicit val joinAuctionParamsJson: RootJsonFormat[JoinAuctionParams] =
    jsonFormat1(JoinAuctionParams)
  implicit val placeBidParamsJson: RootJsonFormat[PlaceBidParams] =
    jsonFormat1(PlaceBidParams)
  implicit val bidderJson: RootJsonFormat[Bidder] =
    jsonFormat1(Bidder)
  implicit val bidJson: RootJsonFormat[Bid] =
    jsonFormat2(Bid)
  implicit val winningBidJson: RootJsonFormat[WinningBid] =
    jsonFormat2(WinningBid)
  implicit val auctionJson: RootJsonFormat[Auction] =
    jsonFormat9(Auction)

  implicit object ErrorResponseWriter extends RootJsonWriter[ErrorResponse] {
    def write(errorResponse: ErrorResponse): JsObject = JsObject("message" -> JsString(errorResponse.message))
  }

  implicit object UnknownErrorWriter extends RootJsonWriter[UnknownError] {
    def write(unknownError: UnknownError): JsObject = JsObject("message" -> JsString(unknownError.message))
  }
}
