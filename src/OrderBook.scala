import scala.actors.Actor
import scala.collection.mutable.Map

object OrderType extends Enumeration {
  type OrderType = Value
  val BuyOrder, SellOrder = Value
}

import OrderType._
class Order(val id: Int, val username: String, val ticker: String, var amount: Int, val price: BigDecimal, val orderType: OrderType)

class OrderBook(usersAssets: Map[String, UserAssetData], routerActor: Actor) {
  private val orderBook = Map[Int, Order]()     //order ID -> order data
  private val lastPrice = Map[String, BigDecimal]() //last executed price
  private var lastOrderId = 0                   //Each order has a unique ID

  def getLastPrice(ticker: String) = lastPrice.get(ticker)

  def removeUser(username: String) {
    for ((id, order) <- orderBook if (order.username == username)) orderBook remove id
  }

  def tryCancel(orderId: Int, username: String) =
    if (!(orderBook contains orderId)) CancelFailure()
    else {
      val order = orderBook(orderId)
      if (order.username != username) CancelFailure()
      else {
        orderBook.remove(orderId)
        //Credit the frozen balance/assets back to the account
        val user = usersAssets(username)
        if (order.orderType == OrderType.BuyOrder) {
          user.balance += order.price * order.amount
        } else {
          usersAssets(username).assets(order.ticker) += order.amount
        }
        CancelSuccess()
      }
    }

  def tryBuy(amount: Int, price: BigDecimal, userData: UserAssetData, username: String, ticker: String) =
    if (amount <= 0 || price <= 0 || amount * price > userData.balance) OrderFailure()
    else {
      orderBook += (lastOrderId -> new Order(lastOrderId, username, ticker, amount, price, BuyOrder))
      userData.balance -= amount * price //Freeze the balance
      lastOrderId += 1
      OrderSuccess(lastOrderId - 1)
    }

  def trySell(userData: UserAssetData, ticker: String, amount: Int, username: String, price: BigDecimal) =
    if (amount <= 0 || price <= 0 || userData.assets(ticker) < amount) OrderFailure()
    else {
      orderBook += (lastOrderId -> new Order(lastOrderId, username, ticker, amount, price, SellOrder))
      userData.assets(ticker) -= amount //Freeze the asset being sold
      lastOrderId += 1
      OrderSuccess(lastOrderId - 1)
    }

  def getOrdersList(username: String) = orderBook.values.filter(o => o.username == username)

  def matchOrders() {
    //Organize orders per-commodity
    val buys, sells = Map[String, Set[Order]]().withDefaultValue(Set())

    for (order <- orderBook.values) {
      if (order.orderType == BuyOrder) {
        buys(order.ticker) += order
      } else {
        sells(order.ticker) += order
      }
    }

    for (ticker <- buys.keys if sells contains ticker) {
      var bids = buys(ticker).toList.sortBy(o => o.price).reverse
      var asks = sells(ticker).toList.sortBy(o => o.price).reverse

      while (bids.nonEmpty && asks.nonEmpty && bids.head.price >= asks.head.price) {
        val bid = bids.head
        val ask = asks.head
        val bidUser = usersAssets(bid.username)
        val askUser = usersAssets(ask.username)

        val amount = bid.amount min ask.amount
        val price = (bid.price + ask.price) / 2.0

        //Update the users' balances
        //We already subtracted the bidding price from the bidder's balance, so now we
        //just need to add back the amount of money saved on the trade from the bid-ask spread
        bidUser.balance += (bid.price * amount - price * amount)
        askUser.balance += price * amount

        //Update the users' assets (the seller has already had their asset removed)
        bidUser.assets(bid.ticker) += amount
        if (askUser.assets(bid.ticker) == 0) askUser.assets.remove(bid.ticker)

        //Notify the users about the execution (partial or not) if they are online
        routerActor ! (bid.username, Executed(bid.id, amount, price))
        routerActor ! (ask.username, Executed(ask.id, amount, price))

        //Update the order amounts (order book is updated automatically)
        bid.amount -= amount
        ask.amount -= amount

        //If an order has been completely filled, remove it from the order book.
        if (bid.amount == 0) {
          orderBook.remove(bid.id)
          bids = bids.tail
        }

        if (ask.amount == 0) {
          orderBook.remove(ask.id)
          asks = asks.tail
        }

        //Update the last price at which the stock was traded
        lastPrice += (ticker -> price)
      }
    }
  }
}
