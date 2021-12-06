# Auction House

## **_Auction House_** project built for the **_Evolution Scala Bootcamp_**.

### What is an auction?
An auction is a process of buying and selling goods or services by offering them up for bids,\
taking bids, and then selling the item to the highest bidder.

### How this works?
The project has only the backend part without a frontend, so the API endpoints can be tested\
with `Postman`, for example.

### Preparing and starting the project
- Clone the repository on your local machine
- Inside the project repository, you can run it using `sbt run`
- The API server will become available at `http://localhost:9000`

### Running the tests
- In order to run the tests for the project run `sbt test`

## API Endpoints
The project has multiple API endpoints that can be tested, instructions are provided below.\
Every endpoint will be tested with `Postman`.

### Creating an auction
In order to create a new auction with the endpoint, open `Postman` and create a new request\
with a `POST` method to `http://localhost:9000/auctions` in the request URL.\
Request header should be `Content-type: application/json`\
The request body should be raw JSON with the correct format:
```
{
	"item": "BMW-M5-CS",
	"incrementPolicy": {
		"incrementType": "MinimalIncrement",
		"minimumBid": 10000
	},
	"startingPrice": 125000,
	"startDate": "2021-12-31T12:00:00",
	"endDate": "2022-01-31T12:00:00"
}
```
Providing an incorrect format, negative price or creating a new auction with an already existing item\
will cause an error.

### Retrieve currently listed auctions
In order to get a list of all currently listed and active auctions, open `Postman` and create a new\
request with a `GET` method to `http://localhost:9000/auctions` in the request URL.\
Set the request body to `none`. The response will contain all currently listed auctions or return\
an empty body if there are no auctions listed.

### Retrieve a specific auction
In order to get a specific item of an auction, open `Postman` and create a new\
request with a `GET` method to `http://localhost:9000/auctions/:item` in the request URL.\
Set the request body to `none`. The `item` field in the URL must match an existing auction.

### Updating an existing auction
In order to update a specific auction, open `Postman` and create a new\
request with a `PATCH` method to `http://localhost:9000/auctions/:item` in the request URL.\
Request header should be `Content-type: application/json`\
The request body should be raw JSON with the correct format:
```
{
	"startingPrice": 150000,
	"incrementPolicy": {
		"incrementType": "FreeIncrement"
	},
	"startDate": "2021-12-31T12:00:00",
	"endDate": "2022-01-31T12:00:00"
}
```

### Adding a bidder for an existing auction
In order to add a bidder to an existing auction, open `Postman` and create a new\
request with a `POST` method to `http://localhost:9000/auctions/:item/bidders` in the request URL.\
Request header should be `Content-type: application/json`\
The request body should be raw JSON with the correct format:
```
{
	"bidderName": "arthur"
}
```

### Placing a bid on an existing auction
In order to place a bid to an existing auction, open `Postman` and create a new\
request with a `POST` method to `http://localhost:9000/auctions/:item/bidders/:bidderName/bid` in the request URL.\
Request header should be `Content-type: application/json`\
The request body should be raw JSON with the correct format:
```
{
	"value": 200000
}
```

## Tech stack used
- **_Scala_** version `2.13.7`
- **_SBT_** version `1.5.5`
- **_Akka_** version `2.6.17`
- **_AkkaHttp_** version `10.2.7`
- **_ScalaTest_** version `3.2.10`
- **_SprayJson_** Akka Integration version `10.2.7`

## Future plans
If this project will be developed further, I am planning to add some additional features such as:
- Buyout system, where items have an additional buyout price, meaning they can be bought right away
- Some frontend/UI in order to make the application more interactive, instead of just using `Postman`
- Implement a cluster to make it possible to handle application deployments during ongoing auctions
