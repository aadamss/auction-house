package com.aadamss.auctionhouse.actors

import com.aadamss.auctionhouse.actors.Auctions.IncrementPolicy
import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.DateTime
import com.aadamss.auctionhouse.responses.Responses._
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps


object Auctions {
  def props(item: String,
             startingPrice: Int,
             incrementPolicy: IncrementPolicy,
             startDate: DateTime,
             endDate: DateTime): Props = Props(
    new Auctions(item, startingPrice, incrementPolicy, startDate, endDate)
  )

  case class Bidder(name: String)

  case class Bid(bidderName: String, value: Int)

  case class WinningBid(bidder: Bidder, bid: Bid)

  sealed abstract class AuctionState(val key: String)

  case object Upcoming extends AuctionState("Upcoming")

  case object Open extends AuctionState("Open")

  case object Ended extends AuctionState("Ended")

  val availableAuctionStates: Vector[AuctionState] =
    Vector(Upcoming, Open, Ended)

  sealed abstract class IncrementPolicy(val incrementType: String)
  case object FreeIncrement extends IncrementPolicy("FreeIncrement")
  case class MinimumIncrement(minimumBid: Int)
    extends IncrementPolicy("MinimumIncrement")

  case object Get
  case object UpdateState

  case class Update(startingPrice: Option[Int] = None,
                     incrementPolicy: Option[IncrementPolicy] = None,
                     startDate: Option[DateTime] = None,
                     endDate: Option[DateTime] = None)

  case class Join(username: String)
  case class PlaceBid(username: String, bid: Int)

}

class Auctions(item: String,
               var startingPrice: Int,
               var incrementPolicy: IncrementPolicy,
               var startDate: DateTime,
               var endDate: DateTime) extends Actor {

  import Auctions._
  import context._

  var auctionState: AuctionState =
    if (DateTime.now > endDate) Ended
    else if (DateTime.now > startDate) Open
    else Upcoming

  system.scheduler.scheduleWithFixedDelay(0 seconds, 500 milliseconds, self, UpdateState)

  var bidders: Set[Bidder] = Set.empty[Bidder]
  var bids: List[Bid] = List.empty[Bid]
  var winner: Option[WinningBid] = None

  def auction: AuctionHouse.Auction =
    AuctionHouse.Auction(
      item,
      startingPrice,
      incrementPolicy,
      auctionState,
      startDate,
      endDate,
      bidders,
      bids.toVector,
      winner
    )

  def updateAuctionState(): Unit =
    auctionState match {
      case Upcoming if DateTime.now > startDate => auctionState = Open
      case Open if DateTime.now > endDate =>
        auctionState = Ended

        def notifyWinner(): Unit = ()

        @tailrec
        def selectWinner(): Unit =
          bids match {
            case head :: _ =>
              bidders.find(_.name == head.bidderName) match {
                case Some(b) =>
                  winner = Some(WinningBid(b, head))
                  notifyWinner()
                case None => selectWinner()
              }
            case _ => ()
          }

        selectWinner()
      case _ => ()
    }

  def receive: PartialFunction[Any, Unit] = {
    case Get => sender() ! AuctionFound(auction)

    case UpdateState => updateAuctionState()

    case Update(
    newStartingPrice,
    newIncrementPolicy,
    newStartDate,
    newEndDate) =>
      auctionState match {
        case Upcoming =>
          newStartingPrice match {
            case Some(newSP) if newSP < 0 =>
              sender() ! NegativeStartingPrice(newSP)
            case _ =>
              newStartingPrice.foreach(startingPrice = _)
              newIncrementPolicy.foreach(incrementPolicy = _)
              newStartDate.foreach(startDate = _)
              newEndDate.foreach(endDate = _)
              updateAuctionState()
              sender() ! AuctionUpdated(auction)
          }
        case _ => sender() ! NotPermittedByState(auctionState)
      }

    case Join(username) =>
      val newBidder = Bidder(username)

      def addBidder(): AuctionJoined = {
        bidders = bidders + newBidder
        AuctionJoined(newBidder)
      }

      auctionState match {
        case Open if !bidders.contains(newBidder) => sender() ! addBidder()
        case Open => sender() ! BidderAlreadyJoined(newBidder)
        case _      => sender() ! NotPermittedByState(auctionState)
      }

    case PlaceBid(username, bid) =>
      val bidder = Bidder(username)

      def addBid(): BidPlaced = {
        bids = Bid(bidder.name, bid) :: bids
        BidPlaced(bids.head)
      }

      auctionState match {
        case Open =>
          if (!bidders.contains(bidder))
            sender() ! BidderDidNotJoin(bidder.name)
          else
            (incrementPolicy, bids) match {
              case (FreeIncrement, highestBid :: _) if bid > highestBid.value =>
                sender() ! addBid()
              case (MinimumIncrement(minimumBid), highestBid :: _)
                if bid >= highestBid.value + minimumBid =>
                sender() ! addBid()
              case (_, List()) if bid >= startingPrice => sender() ! addBid()
              case _ => sender() ! BidTooLow(bid)
            }
        case _ => sender() ! NotPermittedByState(auctionState)
      }
  }
}
