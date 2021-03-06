package com.aadamss.auctionhouse.response

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.aadamss.auctionhouse.actors.{AuctionActor, AuctionHouse}

/** Defines all responses by which the domain actors may answer to the [[Routes]] following the "Ask Pattern".
  * Each response is either a [[Response.SuccessResponse]] or an [[Response.ErrorResponse]]
  * containing already the suitable HTTP response code.
  */
object Response {

  import StatusCodes._

  sealed trait Response

  sealed class SuccessResponse(val statusCode: StatusCode) extends Response

  final case class AuctionCreated(auction: AuctionHouse.Auction) extends SuccessResponse(Created)

  final case class AuctionsFound(auctions: Set[AuctionHouse.Auction]) extends SuccessResponse(OK)

  final case class AuctionFound(auction: AuctionHouse.Auction) extends SuccessResponse(OK)

  final case class AuctionUpdated(auction: AuctionHouse.Auction) extends SuccessResponse(OK)

  final case class AuctionJoined(bidder: AuctionActor.Bidder) extends SuccessResponse(Created)

  final case class BidPlaced(bid: AuctionActor.Bid) extends SuccessResponse(Created)

  sealed class ErrorResponse(val message: String, val statusCode: StatusCode) extends Response

  final case class AuctionAlreadyExists(item: String)
      extends ErrorResponse(
        s"The $item already exists in an auction!",
        UnprocessableEntity,
      )

  final case class NegativeStartingPrice(price: Int)
      extends ErrorResponse(
        s"The $price cannot be negative!",
        UnprocessableEntity,
      )

  final case class AuctionNotFound(item: String)
      extends ErrorResponse(s"An auction for the $item doesn't exist!", NotFound)

  final case class NotPermittedByState(auctionState: AuctionActor.AuctionState)
      extends ErrorResponse(
        s"The auction state ${auctionState.key} doesn't allow the action!",
        Locked,
      )

  final case class BidderAlreadyJoined(bidder: AuctionActor.Bidder)
      extends ErrorResponse(
        s"${bidder.name} has already joined this auction!",
        UnprocessableEntity,
      )

  final case class BidderDidNotJoin(bidderName: String)
      extends ErrorResponse(
        s"$bidderName hasn't yet joined this auction!",
        NotFound,
      )

  final case class BidTooLow(bid: Int)
      extends ErrorResponse(
        s"The bid $bid is too low for this auction!",
        UnprocessableEntity,
      )

  final case class UnknownError(details: String = "An unknown error occurred!", code: StatusCode = InternalServerError)
      extends ErrorResponse(details, code)

}
