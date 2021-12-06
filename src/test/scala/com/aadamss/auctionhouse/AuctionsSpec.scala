package com.aadamss.auctionhouse

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.DateTime
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import com.aadamss.auctionhouse.actors.AuctionHouse.Auction
import com.aadamss.auctionhouse.actors.Auctions
import com.aadamss.auctionhouse.actors.Auctions._
import com.aadamss.auctionhouse.response.Response._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.language.postfixOps

class AuctionsSpec extends TestKit(ActorSystem("testAuctions"))
  with AnyWordSpecLike
  with Matchers
  with ImplicitSender
  with DefaultTimeout
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  def createAuctionActor(a: Auction): ActorRef =
    system.actorOf(
      Auctions.props(
        a.item,
        a.startingPrice,
        a.incrementPolicy,
        a.startDate,
        a.endDate
      )
    )

  "An upcoming auction" must {
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

    "get returned upon receiving a get event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "get updated upon receiving an update event" in {
      val auctionActor: ActorRef =
        createAuctionActor(defaultAuction)

      val newPrice = 200
      val newIncrementPolicy = MinimumIncrement(100)
      val newStart = DateTime.now.plus((3 days).toMillis)
      val newEnd = DateTime.now.plus((5 days).toMillis)

      def checkUpdated(event: Update, result: Auction) = {
        auctionActor ! event
        expectMsg(AuctionUpdated(result))
        auctionActor ! Get
        expectMsg(AuctionFound(result))
      }

      checkUpdated(
        event = Update(),
        result = defaultAuction
      )
      checkUpdated(
        event = Update(startingPrice = Some(newPrice)),
        result = defaultAuction.copy(startingPrice = newPrice)
      )
      checkUpdated(
        event = Update(incrementPolicy = Some(newIncrementPolicy)),
        result = defaultAuction.copy(
          startingPrice = newPrice, incrementPolicy = newIncrementPolicy
        )
      )
      checkUpdated(
        event = Update(startDate = Some(newStart)),
        result = defaultAuction.copy(
          startingPrice = newPrice,
          incrementPolicy = newIncrementPolicy,
          startDate = newStart
        )
      )
      checkUpdated(
        event = Update(endDate = Some(newEnd)),
        result = defaultAuction.copy(
          startingPrice = newPrice,
          incrementPolicy = newIncrementPolicy,
          startDate = newStart,
          endDate = newEnd
        )
      )
      checkUpdated(
        event = Update(
          startingPrice = Some(defaultAuction.startingPrice),
          incrementPolicy = Some(defaultAuction.incrementPolicy),
          startDate = Some(defaultAuction.startDate),
          endDate = Some(defaultAuction.endDate)
        ),
        result = defaultAuction
      )
    }

    "become open when startDate is reached" in {
      val newStart = DateTime.now.plus(50)
      val auctionActor: ActorRef =
        createAuctionActor(defaultAuction.copy(startDate = newStart))

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(startDate = newStart)))

      awaitAssert({
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          startDate = newStart,
          auctionState = Open
        )))
      }, 2 seconds, 500 millis)
    }

    "send back a NegativeStartingPrice message upon receiving an update message with a negative starting price" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val negativeStartingPrice = -1

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! Update(startingPrice = Some(negativeStartingPrice))
      expectMsg(NegativeStartingPrice(negativeStartingPrice))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a NotPermittedByState message when a bidder tries to join" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! Join("testBidder")
      expectMsg(NotPermittedByState(Upcoming))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a NotPermittedByState message upon receiving a bid placement" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! PlaceBid("testBidder", 300)
      expectMsg(NotPermittedByState(Upcoming))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }
  }

  "An open auction" must {
    val defaultAuction: Auction =
      Auction(
        "test",
        100,
        FreeIncrement,
        Open,
        DateTime.now.minus((1 day).toMillis),
        DateTime.now.plus((1 day).toMillis),
        Set(),
        Vector(),
        None
      )

    "get returned when receiving a get event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "subscribe a user when upon receiving a join event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val testBidder = Bidder("testBidder")

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(bidders = Set(testBidder))))
    }

    "place a bid for a user upon receiving a place bid event when the bid is greater than the previous with free increment policy" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val testBidder = Bidder("testBidder")
      val testBidder2 = Bidder("testBidder2")
      val testBid = Bid(testBidder.name, defaultAuction.startingPrice + 100)
      val testBid2 = Bid(testBidder.name, testBid.value + 100)
      val testBid3 = Bid(testBidder2.name, testBid2.value + 100)

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      auctionActor ! Join(testBidder2.name)
      expectMsg(AuctionJoined(testBidder2))

      def checkBidPlaced(bid: Bid, expectBidsState: Vector[Bid]) = {
        auctionActor ! PlaceBid(bid.bidderName, bid.value)
        expectMsg(BidPlaced(bid))
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          bidders = Set(testBidder, testBidder2),
          bids = expectBidsState
        )))
      }
      checkBidPlaced(testBid, Vector(testBid))
      checkBidPlaced(testBid2, Vector(testBid2, testBid))
      checkBidPlaced(testBid3, Vector(testBid3, testBid2, testBid))
    }

    "place a bid for a user upon receiving a place bid event when the bid is greater than or equal to the previous plus a minimum with a minimum increment policy" in {
      val minIncrement = 100
      val auctionActor: ActorRef = createAuctionActor(
        defaultAuction.copy(incrementPolicy = MinimumIncrement(minIncrement))
      )
      val testBidder = Bidder("testBidder")
      val testBidder2 = Bidder("testBidder2")
      val testBid = Bid(
        testBidder.name, defaultAuction.startingPrice + minIncrement
      )
      val testBid2 = Bid(testBidder.name, testBid.value + minIncrement * 2)
      val testBid3 = Bid(testBidder2.name, testBid2.value + minIncrement * 2)
      val testBid4 = Bid(testBidder.name, testBid3.value + minIncrement)
      val testBid5 = Bid(testBidder2.name, testBid4.value + minIncrement)

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      auctionActor ! Join(testBidder2.name)
      expectMsg(AuctionJoined(testBidder2))

      def checkBidPlaced(bid: Bid, expectBidsState: Vector[Bid]) = {
        auctionActor ! PlaceBid(bid.bidderName, bid.value)
        expectMsg(BidPlaced(bid))
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          bidders = Set(testBidder, testBidder2),
          bids = expectBidsState,
          incrementPolicy = MinimumIncrement(minIncrement)
        )))
      }

      checkBidPlaced(testBid, Vector(testBid))
      checkBidPlaced(testBid2, Vector(testBid2, testBid))
      checkBidPlaced(testBid3, Vector(testBid3, testBid2, testBid))
      checkBidPlaced(testBid4, Vector(testBid4, testBid3, testBid2, testBid))
      checkBidPlaced(
        testBid5,
        Vector(testBid5, testBid4, testBid3, testBid2, testBid)
      )
    }

    "place a bid for a user upon receiving a place bid event when the first bid is equal to the starting price with any increment policy" in {
      val minIncrement = 100
      val freeAuctionActor: ActorRef = createAuctionActor(defaultAuction)
      val minimalAuctionActor: ActorRef = createAuctionActor(
        defaultAuction.copy(incrementPolicy = MinimumIncrement(minIncrement))
      )
      val testBidder = Bidder("testBidder")
      val testBid = Bid(testBidder.name, defaultAuction.startingPrice)

      freeAuctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      minimalAuctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))

      freeAuctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidPlaced(testBid))
      freeAuctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(
        bidders = Set(testBidder),
        bids = Vector(testBid)
      )))

      minimalAuctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidPlaced(testBid))
      minimalAuctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(
        bidders = Set(testBidder),
        bids = Vector(testBid),
        incrementPolicy = MinimumIncrement(minIncrement)
      )))
    }

    "change to ended with no winner when the endDate is reached if no one placed a bid" in {
      val newEnd = DateTime.now.plus(10)
      val auctionActor: ActorRef =
        createAuctionActor(defaultAuction.copy(endDate = newEnd))

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(endDate = newEnd)))

      awaitAssert({
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          endDate = newEnd,
          auctionState = Ended
        )))
      }, 2 seconds, 500 millis)
    }

    "change to ended with a winner when the endDate is reached" in {
      val newEnd = DateTime.now.plus(50)
      val auctionActor: ActorRef =
        createAuctionActor(defaultAuction.copy(endDate = newEnd))
      val testBidder = Bidder("testBidder")
      val testBid = Bid(testBidder.name, defaultAuction.startingPrice)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(endDate = newEnd)))
      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      auctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidPlaced(testBid))

      awaitAssert({
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          endDate = newEnd,
          auctionState = Ended,
          bidders = Set(testBidder),
          bids = Vector(testBid),
          winningBid = Some(WinningBid(testBidder, testBid))
        )))
      }, 2 seconds, 500 millis)
    }

    "send back a NotPermittedByState message upon receiving an update event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val newPrice = 200

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! Update(startingPrice = Some(newPrice))
      expectMsg(NotPermittedByState(Open))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a BidderAlreadyJoined message upon receiving a join event with an already existing user" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val testBidder = Bidder("testBidder")

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(bidders = Set(testBidder))))
      auctionActor ! Join(testBidder.name)
      expectMsg(BidderAlreadyJoined(testBidder))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(bidders = Set(testBidder))))
    }

    "send back a BidderDidNotJoin message if the bidder didn't join upon receiving a place bid event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val testBid = Bid("testBidder", defaultAuction.startingPrice)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidderDidNotJoin(testBid.bidderName))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a BidTooLow message if no bid is made upon receiving a place bid event with a bid smaller than the starting price with any increment policy" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val testBidder = Bidder("testBidder")
      val testBid = Bid(testBidder.name, 0)

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(bidders = Set(testBidder))))
      auctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidTooLow(testBid.value))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(bidders = Set(testBidder))))
    }

    "send back a BidTooLow message if the bid placed is lower than the highest bid upon receiving a place bid event with any increment policy" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val testBidder = Bidder("testBidder")
      val testBidder2 = Bidder("testBidder2")
      val testBid = Bid(testBidder.name, defaultAuction.startingPrice)

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      auctionActor ! Join(testBidder2.name)
      expectMsg(AuctionJoined(testBidder2))
      auctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidPlaced(testBid))

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(
        bidders = Set(testBidder, testBidder2), bids = Vector(testBid)
      )))

      def expectBidTooLow(bid: Bid) = {
        auctionActor ! PlaceBid(bid.bidderName, bid.value)
        expectMsg(BidTooLow(bid.value))
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          bidders = Set(testBidder, testBidder2),
          bids = Vector(testBid)
        )))
      }
      expectBidTooLow(testBid)
      expectBidTooLow(Bid(testBidder2.name, defaultAuction.startingPrice))
      expectBidTooLow(Bid(testBidder.name, 0))
      expectBidTooLow(Bid(testBidder2.name, 0))
    }

    "send back a BidTooLow message if a bid is placed upon receiving a place bid event with a bid smaller than the highest bid plus the minimum with minimum increment policy" in {
      val minIncrement = 100
      val auctionActor: ActorRef = createAuctionActor(
        defaultAuction.copy(incrementPolicy = MinimumIncrement(minIncrement))
      )
      val testBidder = Bidder("testBidder")
      val testBidder2 = Bidder("testBidder2")
      val testBid = Bid(testBidder.name, defaultAuction.startingPrice)

      auctionActor ! Join(testBidder.name)
      expectMsg(AuctionJoined(testBidder))
      auctionActor ! Join(testBidder2.name)
      expectMsg(AuctionJoined(testBidder2))
      auctionActor ! PlaceBid(testBid.bidderName, testBid.value)
      expectMsg(BidPlaced(testBid))

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction.copy(
        bidders = Set(testBidder, testBidder2),
        bids = Vector(testBid),
        incrementPolicy = MinimumIncrement(minIncrement)
      )))

      def expectBidTooLow(bid: Bid) = {
        auctionActor ! PlaceBid(bid.bidderName, bid.value)
        expectMsg(BidTooLow(bid.value))
        auctionActor ! Get
        expectMsg(AuctionFound(defaultAuction.copy(
          bidders = Set(testBidder, testBidder2),
          bids = Vector(testBid),
          incrementPolicy = MinimumIncrement(minIncrement)
        )))
      }
      expectBidTooLow(Bid(
        testBidder.name,
        defaultAuction.startingPrice + minIncrement - 1
      ))
      expectBidTooLow(Bid(
        testBidder2.name,
        defaultAuction.startingPrice + minIncrement - 1
      ))
    }
  }

  "An ended auction" must {
    val defaultAuction: Auction =
      Auction(
        "test",
        100,
        FreeIncrement,
        Ended,
        DateTime.now.minus((2 days).toMillis),
        DateTime.now.minus((1 day).toMillis),
        Set(),
        Vector(),
        None
      )
    "get returned upon receiving a get event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a NotPermittedByState message upon receiving an update event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)
      val newPrice = 200

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! Update(startingPrice = Some(newPrice))
      expectMsg(NotPermittedByState(Ended))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a NotPermittedByState message upon a user joining" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! Join("testBidder")
      expectMsg(NotPermittedByState(Ended))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }

    "send back a NotPermittedByState message upon receiving a place bid event" in {
      val auctionActor: ActorRef = createAuctionActor(defaultAuction)

      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
      auctionActor ! PlaceBid("testBidder", 300)
      expectMsg(NotPermittedByState(Ended))
      auctionActor ! Get
      expectMsg(AuctionFound(defaultAuction))
    }
  }
}
