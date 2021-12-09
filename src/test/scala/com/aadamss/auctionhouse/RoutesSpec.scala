package com.aadamss.auctionhouse

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.aadamss.auctionhouse.actors.AuctionHouse
import com.aadamss.auctionhouse.actors.AuctionHouse._
import com.aadamss.auctionhouse.actors.AuctionActor._
import com.aadamss.auctionhouse.api.RequestTimeout
import com.aadamss.auctionhouse.response.Response._
import com.aadamss.auctionhouse.routes.RestRoutes
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import spray.json._

class RoutesSpec
  extends RestRoutes
    with RequestTimeout
    with AnyWordSpecLike
    with ScalaFutures
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll {
  implicit val config: Config = ConfigFactory.load()
  implicit val requestTimeout: Timeout = requestTimeoutFromConfig(config)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  override def createAuctionHouse(): ActorRef =
    system.actorOf(ForwardingActor.props(probe.ref), "testAuctionHouse")

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  val probe: TestProbe = TestProbe()
  val today: DateTime = DateTime.now
  val tomorrow: DateTime = DateTime.now.plus((1 day).toMillis)

  val defaultAuction: Auction =
    Auction(
      "test",
      100,
      FreeIncrement,
      Upcoming,
      DateTime.now.plus((1 day).toMillis),
      DateTime.now.plus((2 days).toMillis),
      Set(),
      Vector(),
      None
    )

  def checkExceptionsHandling(request: HttpRequest, msg: Any): Unit = {
    def performCheck(errorResponse: ErrorResponse, statusCode: StatusCode) = {
      val result = request ~> routes ~> runRoute
      probe.expectMsg(msg)
      probe.reply(errorResponse)
      check {
        status mustBe statusCode
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe errorResponse.toJson.toString
      }(result)
    }

    Set(
      AuctionAlreadyExists("test") -> UnprocessableEntity,
      NegativeStartingPrice(100) -> UnprocessableEntity,
      AuctionNotFound("test") -> NotFound,
      NotPermittedByState(Ended) -> Locked,
      BidderAlreadyJoined(Bidder("testBidder")) -> UnprocessableEntity,
      BidderDidNotJoin("test") -> NotFound,
      BidTooLow(100) -> UnprocessableEntity,
      UnknownError() -> InternalServerError
    ) foreach { case (errorResponse: ErrorResponse, statusCode: StatusCode) => performCheck(errorResponse, statusCode) }
  }

  "The auction house API" must {
    "forward a request to create an auction to the AuctionHouse actor" in {
      val params = (
        defaultAuction.item,
        defaultAuction.startingPrice,
        defaultAuction.incrementPolicy,
        defaultAuction.startDate,
        defaultAuction.endDate
      )
      val request =
        Post("/auctions")
          .withEntity(
            ContentTypes.`application/json`,
            CreateAuctionParams.tupled(params).toJson.toString
          )

      val result = request ~> routes ~> runRoute
      probe.expectMsg(CreateAuction.tupled(params))
      probe.reply(AuctionCreated(defaultAuction))
      check {
        status mustBe StatusCodes.Created
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe defaultAuction.toJson.toString
      }(result)

      checkExceptionsHandling(request, CreateAuction.tupled(params))
    }

    "forward a request for all auctions to the AuctionHouse actor" in {
      val request = Get("/auctions")

      val result = request ~> routes ~> runRoute
      probe.expectMsg(GetAuctions)
      probe.reply(AuctionsFound(Set(defaultAuction)))
      check {
        status mustBe StatusCodes.OK
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe Set(defaultAuction).toJson.toString
      }(result)

      checkExceptionsHandling(request, GetAuctions)
    }

    "forward a request for a specific auction to the AuctionHouse actor" in {
      val request = Get(s"/auctions/${defaultAuction.item}")

      val result = request ~> routes ~> runRoute
      probe.expectMsg(GetAuction(defaultAuction.item))
      probe.reply(AuctionFound(defaultAuction))
      check {
        status mustBe StatusCodes.OK
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe defaultAuction.toJson.toString
      }(result)

      checkExceptionsHandling(request, GetAuction(defaultAuction.item))
    }

    "forward a request to update an auction to the AuctionHouse actor" in {
      val request =
        Patch(s"/auctions/${defaultAuction.item}")
          .withEntity(
            ContentTypes.`application/json`,
            UpdateAuctionParams(None, None, None, None).toJson.toString
          )

      val result = request ~> routes ~> runRoute
      probe.expectMsg(UpdateAuction(
        defaultAuction.item,
        None,
        None,
        None,
        None
      ))
      probe.reply(AuctionUpdated(defaultAuction))
      check {
        status mustBe StatusCodes.OK
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe defaultAuction.toJson.toString
      }(result)

      checkExceptionsHandling(request, UpdateAuction(
        defaultAuction.item,
        None,
        None,
        None,
        None
      ))
    }

    "forward an auction join request to the AuctionHouse actor" in {
      val bidder = Bidder("testBidder")
      val request =
        Post(s"/auctions/${defaultAuction.item}/bidders")
          .withEntity(
            ContentTypes.`application/json`,
            JoinAuctionParams(bidder.name).toJson.toString
          )

      val result = request ~> routes ~> runRoute
      probe.expectMsg(JoinAuction(defaultAuction.item, bidder.name))
      probe.reply(AuctionJoined(bidder))
      check {
        status mustBe StatusCodes.Created
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe bidder.toJson.toString
      }(result)

      checkExceptionsHandling(
        request, JoinAuction(defaultAuction.item, bidder.name)
      )
    }

    "forward a request to place a bid to the AuctionHouse actor" in {
      val bidder = Bidder("testBidder")
      val bid = Bid("test", 200)
      val request =
        Post(s"/auctions/${defaultAuction.item}/bidders/${bidder.name}/bids")
          .withEntity(
            ContentTypes.`application/json`,
            PlaceBidParams(bid.value).toJson.toString
          )

      val result = request ~> routes ~> runRoute
      probe.expectMsg(
        AuctionHouse.PlaceBid(defaultAuction.item, bidder.name, bid.value)
      )
      probe.reply(BidPlaced(bid))
      check {
        status mustBe StatusCodes.Created
        contentType mustBe ContentTypes.`application/json`
        responseAs[String] mustBe bid.toJson.toString
      }(result)

      checkExceptionsHandling(
        request,
        AuctionHouse.PlaceBid(defaultAuction.item, bidder.name, bid.value)
      )
    }
  }
}
