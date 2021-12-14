package com.aadamss.auctionhouse.routes

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.server.Route
import com.aadamss.auctionhouse.actors.AuctionHouse

/** Defines all routes this application offers. Currently these are only the Auction House API routes in trait [[Routes]].
  * They may get augmented by administrative routes, for example.
  */
trait RestRoutes extends Routes {
  implicit val system: ActorSystem

  /** Creates the actor for the complete auction house and returns a reference to it.
    * This method should be called only once during execution.
    */
  override def createAuctionHouse(): ActorRef =
    system.actorOf(AuctionHouse.props, AuctionHouse.name)

  /** Defines all routes this application offers. */
  def routes: Route = auctionHouseAPIRoutes
}
