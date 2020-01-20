package io.redis.demo.vertx;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class SampleAPI extends AbstractVerticle {


    private final static int  CALL_PER_MN = 10;
    private final static int  CALL_PER_HOUR = 40;
    private final static int  CALL_PER_DAY = 100;

    Redis redisClient;

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(SampleAPI.class.getName());
    }

    @Override
    public void start(Future fut) {


        // initialize Redis Connection
        RedisOptions options = new RedisOptions();

        Redis.createClient(vertx, options)
                .connect(onConnect -> {
                    if (onConnect.succeeded()) {
                        redisClient = onConnect.result();
                    }
                });



        // Create a router object.
        Router router = Router.router(vertx);

        // Bind "/" to our hello message - so we are
        // still compatible with out tests.
        router.route("/").handler(rc -> {
            HttpServerResponse response = rc.response();
            response
                    .putHeader("content-type", "text/html")
                    .end("<h1>Hello from my first Vert.x 3 app</h1>");
        });


        router.get("/api/hello")
                .handler(this::rateLimiter)
                .handler(this::sayHello);

        ConfigRetriever retriever = ConfigRetriever.create(vertx);
        retriever.getConfig(
                config -> {
                    if (config.failed()) {
                        fut.fail(config.cause());
                    } else {
                        // Create the HTTP server and pass the
                        // "accept" method to the request handler.
                        vertx
                                .createHttpServer()
                                .requestHandler(router::accept)
                                .listen(
                                        // Retrieve the port from the
                                        // configuration, default to 8080.
                                        config().getInteger("HTTP_PORT", 8080),
                                        result -> {
                                            if (result.succeeded()) {
                                                fut.complete();
                                            } else {
                                                fut.fail(result.cause());
                                            }
                                        }
                                );
                    }
                }
        );
    }


    private void sayHello(RoutingContext rc) {
        System.out.println("--Service --");
        rc.response()
                .putHeader("content-type",
                        "application/json; charset=utf-8")
                .end(Json.encodePrettily("HELLO"));
    }

    private void rateLimiter(RoutingContext rc) {

        // Retrieve API KEY
        String apiKey = rc.request().getParam("APIKEY");

        if (apiKey == null || apiKey.trim().isEmpty()) {

            Map message = new HashMap<>();
            message.put("message", "Set the APIKEY parameter");
            rc.response()
                    .putHeader("content-type",
                            "application/json; charset=utf-8")
                    .setStatusCode(401)
                    .end(Json.encodePrettily(message));
        } else {

            RedisAPI redis = RedisAPI.api(redisClient);
            long currentTime = System.currentTimeMillis();
            String minuteKey = "ratelimit:"+ apiKey + ":mn";
            String hourlyKey = "ratelimit:"+ apiKey + ":hourly";

            redis.zremrangebyscore(minuteKey, "0", Long.toString(currentTime - 60000), send -> {}); // remove older values from the set
            redis.zadd(Arrays.asList(minuteKey, Long.toString(currentTime), Long.toString(currentTime) + ":1"), send -> {
            });
            redis.expire(minuteKey, "61", send -> {
            });
            redis.zrange(Arrays.asList(minuteKey, "0", "-1"), send -> {
                int totalNbOfEntries = send.result().size();
                if (totalNbOfEntries > 0 && totalNbOfEntries > CALL_PER_MN) {
                    Map message = new HashMap<>();
                    message.put("message", "You have reach the max number of calls per minute  (" + CALL_PER_MN + ")");
                    rc.response()
                            .putHeader("X-MINUTES-REMAINED-CALL", "-1")
                            .putHeader("content-type", "application/json; charset=utf-8")
                            .setStatusCode(429)
                            .end(Json.encodePrettily(message));
                } else {
                    rc.response()
                            .putHeader("X-MINUTES-REMAINED-CALL", Integer.toString(CALL_PER_MN - totalNbOfEntries) );
                    rc.next();
                }
            });

        }
    }


}
