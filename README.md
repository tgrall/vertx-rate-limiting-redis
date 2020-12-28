# Rate Limiting with Vert.x

When you are exposing API to consumers it is key to implement a rate limiter to protect and improve the availability of your API.

Redis make it easy to implement the rate limiting logic and keep the system fast and scalable, no/reduced impact on the overall API performance.

You have 2 simple pattern that you can use to implement a rate limiting with Redis:

* very simple one with a counter with a TTL (expiration),with the following pseudo code:
    ```shell script
    
    GET [user-api-key]:[current minute number]
  
    => test if the result is less than the authorized number of call for the resource/api key
  
    MULTI
      INCR [api-key]:[current minute number]
      INCR [api-key]:[current minute number] 59
    EXEC
    ```
    
    This is not an exact match since you have have two Redis entries for the same API (2 overlapping minutes). 
    It is possible to read these two keys to get the overall result to have a better result.
    
* It is possible to control the number of calls in sliding window using Redis Sorted Sets, to count the exact number of 
  calls for the time window. This is what is used in this demonstration.
  
  

### Sliding window with Sorted Set

For this you will use a Sorted Set where:

* the key is the API token
* the score is the timestamp of the call 
* the key will expire for the sliding window length (60 seconds the demo)

So when calling the Rate Limiter the logic will be:

    ```
    MULTI
        ZREMRANGEBYSCORE $apiToken 0 ($currentTime - $slidingwindow)
        ZADD $apiToken $$currentTime $currentTime
        ZRANGE $apiToken 0 -1
        EXPIRE $apiToken $slidingwindow
    EXEC
    ```
 
 Let's look at the logic:
 
 * The rate limiter is using a [Redis Transaction](https://redis.io/topics/transactions) (`MULTI` / `EXEC` commands) to ensure that all commands are executed together.
 * `ZREMRANGEBYSCORE` to remove all the calls that have been done before the time window
 * `ZADD` to add the call to the set with the current timestamp as score
 * `ZRANGE` to get the list of calls, the number of entries represents the number of call of the API for the time window
 * `EXPIRE` to reset expiry.
 
 The application has to count the number of entries returned by the `ZRANGE` call and check if it is lower than the limit.
 
### Implementation

This sample project use [Vert.x Web](https://vertx.io/docs/vertx-web/java/) to simulate a simple REST API.

Before calling the API function a [Handler](https://vertx.io/docs/vertx-web/java/#_handling_requests_and_calling_the_next_handler) is called.

This handler is the `SampleAPI.rateLimiter()` method, calls Redis with the commands described above.


## Run the application

1. Build and Run

    ``
    > mvn clean package
    
    
    > java -jar ./target/vertx-rate-limiting-1.0-SNAPSHOT-fat.jar 
    
    ``

1. Call the URL `http://localhost:8080/api/hello?APIKEY=1`

1. Call it more than 10 times per minutes

1. Change the key and to more tests

