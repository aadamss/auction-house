package com.aadamss.auctionhouse

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.DateTime
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.aadamss.auctionhouse.actors.AuctionHouse._
import com.aadamss.auctionhouse.actors.Auctions.{FreeIncrement, IncrementPolicy}
import com.aadamss.auctionhouse.actors.{AuctionHouse, Auctions}
import com.aadamss.auctionhouse.response.Response._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.language.postfixOps

object ForwardingActor {
  def props(receiver: ActorRef)(implicit timeout: Timeout): Props =
    Props(new ForwardingActor(receiver))
}

class ForwardingActor(val receiver: ActorRef) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case message => receiver forward message
  }
}

class AuctionHouseSpec
  extends TestKit(ActorSystem("testAuctionHouse"))
    with AnyWordSpecLike
    with Matchers
    with ImplicitSender
    with DefaultTimeout
    with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  def createWithProbe(probe: TestProbe): ActorRef =
    system.actorOf(Props(
      new AuctionHouse {
        override def createAuction(
                                    name: String,
                                    startingPrice: Int,
                                    incrementPolicy: IncrementPolicy,
                                    startDate: DateTime,
                                    endDate: DateTime
                                  ): ActorRef =
          context.actorOf(ForwardingActor.props(probe.ref), name)
      }
    ))

  "Auction House" must {
    val defaultAuction: Auction =
      Auction(
        "test",
        100,
        FreeIncrement,
        Auctions.Upcoming,
        DateTime.now.plus((1 day).toMillis),
        DateTime.now.plus((2 days).toMillis),
        Set(),
        Vector(),
        None
      )

    def createAuction(
                       auctionHouseActor: ActorRef,
                       probe: TestProbe
                     )(
                       auction: Auction
                     ) = {
      auctionHouseActor ! CreateAuction(
        auction.item,
        auction.startingPrice,
        auction.incrementPolicy,
        auction.startDate,
        auction.endDate
      )
      probe.expectMsg(Auctions.Get)
      probe.reply(AuctionFound(defaultAuction))
      expectMsg(AuctionCreated(defaultAuction))
    }


    "create an auction upon it receiving a CreateAuction message" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)

      createAuction(auctionHouseActor, probe)(defaultAuction)
    }

    "forward an AuctionActor.Get message when it receives a GetAuction message" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)

      createAuction(auctionHouseActor, probe)(defaultAuction)

      auctionHouseActor ! GetAuction(defaultAuction.item)
      probe.expectMsg(Auctions.Get)
      probe.reply(AuctionFound(defaultAuction))
      expectMsg(AuctionFound(defaultAuction))
    }

    "upon receiving a GetAuctions message, return a list of all available auctions" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val itemName2 = "item2"

      createAuction(auctionHouseActor, probe)(defaultAuction)
      createAuction(auctionHouseActor, probe)(
        defaultAuction.copy(item = itemName2)
      )

      auctionHouseActor ! GetAuctions
      probe.expectMsg(Auctions.Get)
      probe.reply(AuctionFound(defaultAuction))
      probe.expectMsg(Auctions.Get)
      probe.reply(AuctionFound(defaultAuction.copy(item = itemName2)))
      expectMsg(AuctionsFound(
        Set(defaultAuction, defaultAuction.copy(item = itemName2))
      ))
    }

    "forward an AuctionActor.Update message when it receives an UpdateAuction message" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val newPrice = 200

      createAuction(auctionHouseActor, probe)(defaultAuction)

      auctionHouseActor ! UpdateAuction(
        defaultAuction.item, Some(newPrice), None, None, None
      )
      probe.expectMsg(Auctions.Update(Some(newPrice), None, None))
      probe.reply(AuctionUpdated(
        defaultAuction.copy(startingPrice = newPrice)
      ))
      expectMsg(AuctionUpdated(defaultAuction.copy(startingPrice = newPrice)))
    }

    "forward an AuctionActor.Join message when it receives a JoinAuction message" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val testBidder = Auctions.Bidder("testBidder")

      createAuction(auctionHouseActor, probe)(defaultAuction)

      auctionHouseActor ! JoinAuction(defaultAuction.item, testBidder.name)
      probe.expectMsg(Auctions.Join(testBidder.name))
      probe.reply(AuctionJoined(testBidder))
      expectMsg(AuctionJoined(testBidder))
    }

    "forward a AuctionActor.PlaceBid message when it receives a PlaceBid message" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val testBidder = Auctions.Bidder("testBidder")
      val testBid = Auctions.Bid(testBidder.name, 100)

      createAuction(auctionHouseActor, probe)(defaultAuction)

      auctionHouseActor ! PlaceBid(
        defaultAuction.item,
        testBid.bidderName,
        testBid.value
      )
      probe.expectMsg(Auctions.PlaceBid(testBid.bidderName, testBid.value))
      probe.reply(BidPlaced(testBid))
      expectMsg(BidPlaced(testBid))
    }

    "send back an AuctionAlreadyExists message when it receives a CreateAuction message with an already listed item" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)

      createAuction(auctionHouseActor, probe)(defaultAuction)

      auctionHouseActor ! CreateAuction(
        defaultAuction.item,
        defaultAuction.startingPrice,
        defaultAuction.incrementPolicy,
        defaultAuction.startDate,
        defaultAuction.endDate
      )
      probe.expectNoMessage()
      expectMsg(AuctionAlreadyExists(defaultAuction.item))
    }

    "send back a NegativeStartingPrice message when it receives a CreateAuction message with a negative starting price" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val negativeStartingPrice = -1

      auctionHouseActor ! CreateAuction(
        defaultAuction.item,
        negativeStartingPrice,
        defaultAuction.incrementPolicy,
        defaultAuction.startDate,
        defaultAuction.endDate
      )
      probe.expectNoMessage()
      expectMsg(NegativeStartingPrice(negativeStartingPrice))
    }

    "send back an empty list upon receiving a GetAuctions message if there are no listed auctions" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)

      auctionHouseActor ! GetAuctions
      expectMsg(AuctionsFound(Set()))
    }

    "send back an AuctionNotFound message upon receiving a GetAuction message, if the auction isn't listed" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)

      auctionHouseActor ! GetAuction(defaultAuction.item)
      expectMsg(AuctionNotFound(defaultAuction.item))
    }

    "send back an AuctionNotFound message upon receiving an UpdateAuction message, if the auction isn't listed" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val newPrice = 200

      auctionHouseActor ! UpdateAuction(
        defaultAuction.item, Some(newPrice), None, None, None
      )
      expectMsg(AuctionNotFound(defaultAuction.item))
    }

    "send back an AuctionNotFound message upon receiving a JoinAuction message, if the auction isn't listed" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val testBidder = Auctions.Bidder("testBidder")

      auctionHouseActor ! JoinAuction(defaultAuction.item, testBidder.name)
      expectMsg(AuctionNotFound(defaultAuction.item))
    }

    "send back an AuctionNotFound message upon receiving a PlaceBid message, if the auction isn't listed" in {
      val probe = TestProbe()
      val auctionHouseActor: ActorRef = createWithProbe(probe)
      val testBidder = Auctions.Bidder("testBidder")
      val testBid = Auctions.Bid(testBidder.name, 100)

      auctionHouseActor ! PlaceBid(
        defaultAuction.item,
        testBid.bidderName,
        testBid.value
      )
      expectMsg(AuctionNotFound(defaultAuction.item))
    }
  }
}
