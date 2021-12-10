package com.aadamss.auctionhouse.actors

import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.DateTime
import com.aadamss.auctionhouse.actors.AuctionActor.IncrementPolicy
import com.aadamss.auctionhouse.response.Response._
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps

object AuctionActor {
  def props(
    item: String,
    startingPrice: Int,
    incrementPolicy: IncrementPolicy,
    startDate: DateTime,
    endDate: DateTime,
  ): Props = Props(
    new AuctionActor(item, startingPrice, incrementPolicy, startDate, endDate),
  )

  case class Bidder(name: String)
  case class Bid(bidderName: String, value: Int)
  case class WinningBid(bidder: Bidder, bid: Bid)

  sealed abstract class AuctionState(val key: String)

  case object Upcoming extends AuctionState("Upcoming")
  case object Open extends AuctionState("Open")
  case object Ended extends AuctionState("Ended")

  val availableAuctionStates: Vector[AuctionState] = Vector(Upcoming, Open, Ended)

  sealed abstract class IncrementPolicy(val incrementType: String)
  case object FreeIncrement extends IncrementPolicy("FreeIncrement")
  case class MinimumIncrement(minimumBid: Int) extends IncrementPolicy("MinimumIncrement")

  case object Get
  case object UpdateState
  case class Update(
    startingPrice: Option[Int] = None,
    incrementPolicy: Option[IncrementPolicy] = None,
    startDate: Option[DateTime] = None,
    endDate: Option[DateTime] = None,
  )
  case class Join(username: String)
  case class PlaceBid(username: String, bid: Int)

}

class AuctionActor(
  item: String,
  startingPrice: Int,
  incrementPolicy: IncrementPolicy,
  startDate: DateTime,
  endDate: DateTime,
) extends Actor {

  import AuctionActor._
  import context._

  private case class State(
    startingPrice: Int = startingPrice,
    incrementPolicy: IncrementPolicy = incrementPolicy,
    startDate: DateTime = startDate,
    endDate: DateTime = endDate,
    auctionState: AuctionState = initialAuctionState,
    bidders: Set[Bidder] = Set.empty[Bidder],
    bids: List[Bid] = List.empty[Bid],
    winner: Option[WinningBid] = None,
  )

  system.scheduler.scheduleWithFixedDelay(0 seconds, 500 milliseconds, self, UpdateState)

  private def auction(state: State): AuctionHouse.Auction =
    AuctionHouse.Auction(
      item,
      state.startingPrice,
      state.incrementPolicy,
      state.auctionState,
      state.startDate,
      state.endDate,
      state.bidders,
      state.bids.toVector,
      state.winner,
    )

  private def updateAuctionState(state: State): State =
    state.auctionState match {
      case Upcoming if DateTime.now > state.startDate => state.copy(auctionState = Open)
      case Open if DateTime.now > state.endDate =>
        val auctionEndedState = state.copy(auctionState = Ended)

        def notifyWinner(): Unit = ()

        @tailrec
        def selectWinner(): Option[WinningBid] =
          state.bids match {
            case head :: _ =>
              state.bidders.find(_.name == head.bidderName) match {
                case Some(b) =>
                  val winner = Some(WinningBid(b, head))
                  notifyWinner()
                  winner
                case None => selectWinner()
              }
            case _ => None
          }
        auctionEndedState.copy(winner = selectWinner())
      case _ => state
    }

  def receive: Receive = stateDependentBehavior(State())

  private def stateDependentBehavior(state: State): Receive = {
    case Get => sender() ! AuctionFound(auction(state))

    case UpdateState =>
      val newState = updateAuctionState(state)
      become(stateDependentBehavior(newState))

    case Update(newStartingPrice, newIncrementPolicy, newStartDate, newEndDate) =>
      state.auctionState match {
        case Upcoming =>
          newStartingPrice match {
            case Some(newStartPrice) if newStartPrice < 0 =>
              sender() ! NegativeStartingPrice(newStartPrice)
            case _ =>
              val startingPriceSate = newStartingPrice match {
                case Some(startPrice) => state.copy(startingPrice = startPrice)
                case None             => state
              }
              val incrementPolicyState = newIncrementPolicy match {
                case Some(incPolicy) => startingPriceSate.copy(incrementPolicy = incPolicy)
                case None            => startingPriceSate
              }
              val startDateState = newStartDate match {
                case Some(startingDate) => incrementPolicyState.copy(startDate = startingDate)
                case None               => incrementPolicyState
              }
              val endDateState = newEndDate match {
                case Some(endingDate) => startDateState.copy(endDate = endingDate)
                case None             => startDateState
              }
              val newState = endDateState
              updateAuctionState(state)
              sender() ! AuctionUpdated(auction(newState))
              become(stateDependentBehavior(newState))
          }
        case _ => sender() ! NotPermittedByState(state.auctionState)
      }

    case Join(username) =>
      val newBidder = Bidder(username)

      def addBidder(): State =
        state.copy(bidders = state.bidders + newBidder)

      state.auctionState match {
        case Open if !state.bidders.contains(newBidder) =>
          sender() ! AuctionJoined(newBidder)
          become(stateDependentBehavior(addBidder()))
        case Open => sender() ! BidderAlreadyJoined(newBidder)
        case _    => sender() ! NotPermittedByState(state.auctionState)
      }

    case PlaceBid(username, bid) =>
      val bidder = Bidder(username)

      def addBidAndAnswer(): State = {
        val newBid = Bid(bidder.name, bid)
        sender() ! BidPlaced(newBid)
        state.copy(bids = newBid :: state.bids)
      }

      state.auctionState match {
        case Open =>
          if (!state.bidders.contains(bidder))
            sender() ! BidderDidNotJoin(bidder.name)
          else
            (state.incrementPolicy, state.bids) match {
              case (FreeIncrement, highestBid :: _) if bid > highestBid.value =>
                become(stateDependentBehavior(addBidAndAnswer()))
              case (MinimumIncrement(minimumBid), highestBid :: _) if bid >= highestBid.value + minimumBid =>
                become(stateDependentBehavior(addBidAndAnswer()))
              case (_, List()) if bid >= state.startingPrice =>
                become(stateDependentBehavior(addBidAndAnswer()))
              case _ => sender() ! BidTooLow(bid)
            }
        case _ => sender() ! NotPermittedByState(state.auctionState)
      }
  }

  private def initialAuctionState: AuctionState =
    if (DateTime.now > endDate) Ended
    else if (DateTime.now > startDate) Open
    else Upcoming

}
