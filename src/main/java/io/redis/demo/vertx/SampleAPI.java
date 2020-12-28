package io.redis.demo.vertx;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// To run : docker run -it --rm -p 6379:6379 --name redis1 redis
public class SampleAPI extends AbstractVerticle {


    private final static int  CALL_PER_MN = 10;

    private Redis redisClient;

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(SampleAPI.class.getName())
            .onSuccess(id -> System.out.println("Verticle started"))
            .onFailure(System.err::println);
    }

    @Override
    public void start(Promise<Void> fut) {
        redisClient = Redis.createClient(vertx, new RedisOptions());

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

        retriever.getConfig()
            .map(c -> c.getInteger("HTTP_PORT", 8080))
            .flatMap(port -> vertx
                .createHttpServer()
                .requestHandler(router)
                .listen(port))
            .<Void>mapEmpty();
    }

    private void sayHello(RoutingContext rc) {
        System.out.println("--Service --");
        rc.response()
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(new JsonObject()
                    .put("message", "Hello from API")
                    .encodePrettily());
    }

    private void rateLimiter(RoutingContext rc) {

        // Retrieve API KEY
        String apiKey = rc.request().getParam("APIKEY");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            rc.response()
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .setStatusCode(401)
                    .end(new JsonObject()
                        .put("message", "Set the APIKEY parameter")
                        .encodePrettily());
        } else {

            RedisAPI redis = RedisAPI.api(redisClient);
            long currentTime = System.currentTimeMillis();
            String minuteKey = "ratelimit:"+ apiKey + ":mn";


            redis.zremrangebyscore(minuteKey, "0", Long.toString(currentTime - 60000)); // remove older values from the set
            redis.zadd(Arrays.asList(minuteKey, Long.toString(currentTime), currentTime + ":1"));
            redis.expire(minuteKey, "61");
            Future<Response> result = redis.zrange(Arrays.asList(minuteKey, "0", "-1"));

            result
                .onSuccess(res -> {
                    int totalNbOfEntries = res.size();
                    if (totalNbOfEntries > CALL_PER_MN) {
                        rc.response()
                            .putHeader("X-MINUTES-REMAINED-CALL", "-1")
                            .putHeader("content-type", "application/json; charset=utf-8")
                            .setStatusCode(429)
                            .end(new JsonObject()
                                .put("message", "You have reach the max number of calls per minute  (" + CALL_PER_MN + ")")
                                .encodePrettily());
                    } else {
                        rc.response()
                            .putHeader("X-MINUTES-REMAINED-CALL", Integer.toString(CALL_PER_MN - totalNbOfEntries));
                        rc.next();
                    }
                })
                .onFailure(err -> rc.fail(503, err));
        }
    }
}
