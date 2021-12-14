package com.aadamss.auctionhouse.actors

import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.DateTime
import com.aadamss.auctionhouse.actors.AuctionActor.IncrementPolicy
import com.aadamss.auctionhouse.response.Response._
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps

/** Creates a [[Props]] object for configuring an [[AuctionActor]]. */
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

  /** Model of a bidder bidding in an auction. */
  case class Bidder(name: String)

  /** Model of a bid placed in an auction. */
  case class Bid(bidderName: String, value: Int)

  /** Model of the fact that the given `bidder` has won an auction with the given `bid`. */
  case class WinningBid(bidder: Bidder, bid: Bid)

  /** Logical state of an auction */
  sealed abstract class AuctionState(val key: String)

  /** The auction did not yet begin, as the `startDate` is not reached. */
  case object Upcoming extends AuctionState("Upcoming")

  /** The auction is in progress, as the `startDate` has passed, but the `endDate` has not yet been reached. */
  case object Open extends AuctionState("Open")

  /** The auction has ended, as the `endDate` has passed. */
  case object Ended extends AuctionState("Ended")

  val availableAuctionStates: Vector[AuctionState] = Vector(Upcoming, Open, Ended)

  /** Model of possible increment policies for an auction. */
  sealed abstract class IncrementPolicy(val incrementType: String)

  /** Model of a free increment policy (by any value >0) for an auction. */
  case object FreeIncrement extends IncrementPolicy("FreeIncrement")

  /** Model of a minimum increment policy for an auction. */
  case class MinimumIncrement(minimumBid: Int) extends IncrementPolicy("MinimumIncrement")

  /** Query for the state of the auction managed by the current actor. */
  case object Get

  /** Command forcing the auction actor to recompute its [[AuctionState]] based on the current time. */
  case object UpdateState

  /** Command to update the given attributes of the auction. */
  case class Update(
    startingPrice: Option[Int] = None,
    incrementPolicy: Option[IncrementPolicy] = None,
    startDate: Option[DateTime] = None,
    endDate: Option[DateTime] = None,
  )

  /** Command to register the given `username` as bidder for the auction. */
  case class Join(username: String)

  /** Command to register that the bidder with the  given `username` offers to pay `bid` units for the auctionated item. */
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

  /** Holds all values which are part of the actor's state.
    * All are optional given with their initial value as default value.
    * So [[State()]] will create the initial state for the actor.
    */
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

  /** Creates a data transfer object containing the current complete state of the auction. */
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

  /** Returns the next state of the auction depending on the passed `state` and the current time. */
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

  /** Function defining the initial behavior of this actor.
    * The actor will initially and later accept messages of types [[Get]], [[UpdateState]], [[Update]], [[Join]], and [[PlaceBid]].
    */
  def receive: Receive = stateDependentBehavior(State())

  /** Helper function for defining the behavior depending on the current actor state without using variables.
    * Initially it was expressed by variables in the actor and now is passed as a modified `state` argument.
    */
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

  /** Computes the initial logical [[AuctionState]] depending on the current time. */
  private def initialAuctionState: AuctionState =
    if (DateTime.now > endDate) Ended
    else if (DateTime.now > startDate) Open
    else Upcoming

}
