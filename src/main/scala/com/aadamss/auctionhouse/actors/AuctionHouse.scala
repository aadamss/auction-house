package com.aadamss.auctionhouse.actors

import com.aadamss.auctionhouse.actors.Auctions.{IncrementPolicy, WinningBid}
import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.DateTime
import akka.util.Timeout
import akka.pattern.{ask, pipe}
import com.aadamss.auctionhouse.responses.Responses._
import scala.concurrent.Future
import scala.util.{Failure, Success}

object AuctionHouse {
  def props(implicit timeout: Timeout): Props = Props(new AuctionHouse())

  def name = "auctionHouse"

  case class Auction(
    item: String,
    startingPrice: Int,
    incrementPolicy: IncrementPolicy,
    auctionState: Auctions.AuctionState,
    startDate: DateTime,
    endDate: DateTime,
    bidders: Set[Auctions.Bidder],
    bids: Vector[Auctions.Bid],
    winningBid: Option[WinningBid],
  )

  case class CreateAuction(
    item: String,
    startingPrice: Int,
    incrementPolicy: IncrementPolicy,
    startDate: DateTime,
    endDate: DateTime,
  )

  case object GetAuctions

  case class GetAuction(item: String)

  case class UpdateAuction(
    item: String,
    startingPrice: Option[Int],
    incrementPolicy: Option[IncrementPolicy],
    startDate: Option[DateTime],
    endDate: Option[DateTime],
  )

  case class JoinAuction(item: String, bidderName: String)

  case class PlaceBid(item: String, bidderName: String, value: Int)

}

class AuctionHouse(implicit timeout: Timeout) extends Actor {

  import AuctionHouse._
  import context._

  def createAuction(
    item: String,
    startingPrice: Int,
    incrementPolicy: IncrementPolicy,
    startDate: DateTime,
    endDate: DateTime,
  ): ActorRef =
    context.actorOf(Auctions.props(item, startingPrice, incrementPolicy, startDate, endDate), item)

  def receive: PartialFunction[Any, Unit] = {
    case CreateAuction(item, startingPrice, incrementPolicy, startDate, endDate) =>
      if (startingPrice < 0) sender() ! NegativeStartingPrice(startingPrice)
      else
        context.child(item) match {
          case Some(_) => sender() ! AuctionAlreadyExist(item)
          case None =>
            val futureAuction =
              createAuction(
                item,
                startingPrice,
                incrementPolicy,
                startDate,
                endDate,
              ).ask(Auctions.Get)
                .mapTo[AuctionFound]
                .map(af => AuctionCreated(af.auction))

            pipe(futureAuction) to sender()
        }

    case GetAuctions =>
      val getAuctions: Future[AuctionsFound] =
        Future sequence {
          for {
            child <- context.children
          } yield self
            .ask(GetAuction(child.path.name))
            .mapTo[AuctionFound]
            .map(af => Success(af.auction))
            .recover { case e => Failure(e) }
        } map { f => AuctionsFound(f.filter(_.isSuccess).map(_.get).toSet) }

      pipe(getAuctions) to sender()

    case GetAuction(item) =>
      context
        .child(item)
        .fold(sender() ! AuctionNotFound(item))(_ forward Auctions.Get)

    case UpdateAuction(
          item,
          startingPrice,
          incrementPolicy,
          startDate,
          endDate,
        ) =>
      context
        .child(item)
        .fold(
          sender() ! AuctionNotFound(item),
        )(
          _ forward Auctions
            .Update(startingPrice, incrementPolicy, startDate, endDate),
        )

    case JoinAuction(item, username) =>
      context
        .child(item)
        .fold(
          sender() ! AuctionNotFound(item),
        )(
          _ forward Auctions.Join(username),
        )

    case PlaceBid(item, username, bid) =>
      context
        .child(item)
        .fold(
          sender() ! AuctionNotFound(item),
        )(
          _ forward Auctions.PlaceBid(username, bid),
        )
  }
}
