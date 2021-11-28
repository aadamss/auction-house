package com.aadamss.auctionhouse.routes

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.server.Route
import com.aadamss.auctionhouse.actors.AuctionHouse

trait RestRoutes extends Routes {
  implicit val system: ActorSystem

  override def createAuctionHouse(): ActorRef =
    system.actorOf(AuctionHouse.props, AuctionHouse.name)

  def routes: Route = auctionHouseAPIRoutes
}
