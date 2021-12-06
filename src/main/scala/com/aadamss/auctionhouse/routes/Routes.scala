package com.aadamss.auctionhouse.routes

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.pattern.ask
import akka.util.Timeout
import com.aadamss.auctionhouse.actors.AuctionHouse._
import com.aadamss.auctionhouse.marshaller.Marshaller
import com.aadamss.auctionhouse.response.Response._
import scala.concurrent.ExecutionContext

trait Routes extends Marshaller {

  implicit def executionContext: ExecutionContext

  implicit def requestTimeout: Timeout

  def createAuctionHouse(): ActorRef

  lazy val auctionHouse: ActorRef = createAuctionHouse()

  def auctionHouseAPIRoutes: Route =
    auctionsRoute ~ auctionRoute ~ bidderRoute ~ bidRoute

  def handleExceptions(response: Response): StandardRoute =
    response match {
      case errorResponse: ErrorResponse => complete(errorResponse.statusCode, errorResponse)
      case _                => complete(UnknownError().statusCode, UnknownError().message)
    }

  def auctionsRoute: Route =
    pathPrefix("auctions") {
      pathEndOrSingleSlash {
        post {
          entity(as[CreateAuctionParams]) { params =>
            onSuccess(
              auctionHouse
                .ask(
                  CreateAuction(
                    params.item,
                    params.startingPrice,
                    params.incrementPolicy,
                    params.startDate,
                    params.endDate,
                  ),
                )
                .mapTo[Response],
            ) {
              case auctionCreated: AuctionCreated => complete(auctionCreated.statusCode, auctionCreated.auction)
              case errorResponse                 => handleExceptions(errorResponse)
            }
          }
        } ~
          get {
            onSuccess(auctionHouse.ask(GetAuctions).mapTo[Response]) {
              case auctionsFound: AuctionsFound => complete(auctionsFound.statusCode, auctionsFound.auctions)
              case errorResponse                => handleExceptions(errorResponse)
            }
          }
      }
    }

  def auctionRoute: Route =
    pathPrefix("auctions" / Segment) { item =>
      pathEndOrSingleSlash {
        get {
          onSuccess(auctionHouse.ask(GetAuction(item)).mapTo[Response]) {
            case auctionsFound: AuctionFound => complete(auctionsFound.statusCode, auctionsFound.auction)
            case errorResponse               => handleExceptions(errorResponse)
          }
        } ~
          patch {
            entity(as[UpdateAuctionParams]) { params =>
              onSuccess(
                auctionHouse
                  .ask(
                    UpdateAuction(
                      item,
                      params.startingPrice,
                      params.incrementPolicy,
                      params.startDate,
                      params.endDate,
                    ),
                  )
                  .mapTo[Response],
              ) {
                case auctionUpdated: AuctionUpdated => complete(auctionUpdated.statusCode, auctionUpdated.auction)
                case errorResponse                 => handleExceptions(errorResponse)
              }
            }
          }
      }
    }

  def bidderRoute: Route =
    pathPrefix("auctions" / Segment / "bidders") { item =>
      pathEndOrSingleSlash {
        post {
          entity(as[JoinAuctionParams]) { params =>
            onSuccess(
              auctionHouse
                .ask(JoinAuction(item, params.bidderName))
                .mapTo[Response],
            ) {
              case auctionJoined: AuctionJoined => complete(auctionJoined.statusCode, auctionJoined.bidder)
              case errorResponse                => handleExceptions(errorResponse)
            }
          }
        }
      }
    }

  def bidRoute: Route =
    pathPrefix("auctions" / Segment / "bidders" / Segment / "bids") { (item, bidder) =>
      pathEndOrSingleSlash {
        post {
          entity(as[PlaceBidParams]) { params =>
            onSuccess(
              auctionHouse
                .ask(PlaceBid(item, bidder, params.value))
                .mapTo[Response],
            ) {
              case bidPlaced: BidPlaced => complete(bidPlaced.statusCode, bidPlaced.bid)
              case errorResponse            => handleExceptions(errorResponse)
            }
          }
        }
      }
    }
}
