package net.trustgames.toolkit.managers.rabbit;

import com.rabbitmq.client.*;
import lombok.Getter;
import net.trustgames.toolkit.managers.rabbit.config.RabbitExchange;
import net.trustgames.toolkit.managers.rabbit.config.RabbitQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class RabbitManager {

    private ConnectionFactory factory;
    private final Connection connection;

    @Getter
    private final Channel channel;

    /**
     * Sets parameters and creates new channel and queue.
     * (is run async)
     */
    public RabbitManager(@NotNull String user,
                         @NotNull String password,
                         @NotNull String ip,
                         @NotNull Integer port) {
        this.factory = new ConnectionFactory();
        this.factory.setUsername(user);
        this.factory.setPassword(password);
        this.factory.setHost(ip);
        this.factory.setPort(port);
        try {
            this.connection = factory.newConnection();
            this.channel = connection.createChannel();
            declareExchanges();
            declareQueues();
        } catch (IOException | TimeoutException e) {
            System.out.println("RUNTIME EXCEPTION 12");
            throw new RuntimeException("Failed to declare Channels or Exchanges in RabbitMQ", e);
        }
    }

    /**
     * @see RabbitExchange
     */
    private void declareExchanges() {
        for (RabbitExchange exchange : RabbitExchange.values()) {
            try {
                channel.exchangeDeclare(exchange.getName(), exchange.getType(), false);
                for (RabbitExchange bound : exchange.getBoundExchanges()){
                    channel.exchangeBind(exchange.getName(), bound.getName(), exchange.getRoutingKey());
                }
            } catch (IOException e) {
                System.out.println("RUNTIME EXCEPTION 13");
                throw new RuntimeException("Failed to declare Exchanges in RabbitMQ", e);
            }
        }
    }

    /**
     * @see RabbitQueue
     */
    private void declareQueues() {
        try {
            for (RabbitQueue queue : RabbitQueue.values()) {
                channel.queueDeclare(queue.getName(), false, false, false, null);
                for (RabbitExchange exchange : queue.exchanges) {
                    if (exchange == null) return;
                    channel.queueBind(queue.getName(), exchange.getName(), queue.getRoutingKey());
                }
            }
        } catch (IOException  e) {
            System.out.println("RUNTIME EXCEPTION 14");
            throw new RuntimeException("Failed to declare Queues in RabbitMQ", e);
        }
    }

    /**
     * Send a json message to the specified exchange or queue in fire-and-forget mode
     * (is run sync)
     *
     * @param exchange The exchange to send the message to. Contains data about the exchanges, names, types
     * @param properties Properties of the message (type, ttl, ...)
     * @param json      JSON to send as body of the message

     * @see RabbitManager#fireAndForgetAsync(RabbitExchange, AMQP.BasicProperties, JSONObject)
     */
    public void fireAndForget(@NotNull RabbitExchange exchange,
                              @Nullable AMQP.BasicProperties properties,
                              @NotNull JSONObject json) {
        try {
            for (RabbitExchange bound : exchange.getBoundExchanges()) {
                channel.basicPublish(bound.getName(), exchange.getRoutingKey(), properties, json.toString().getBytes());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Send a json message to the specified queue in fire-and-forget mode
     * (is run async)
     *
     * @param exchange The exchange to send the message to. Contains data about the exchanges, names, types
     * @param properties Properties of the message (type, ttl, ...)
     * @param json      JSON to send as body of the message
     * @see RabbitManager#fireAndForget(RabbitExchange, AMQP.BasicProperties, JSONObject)
     */
    public void fireAndForgetAsync(@NotNull RabbitExchange exchange,
                                   @NotNull AMQP.BasicProperties properties,
                                   @NotNull JSONObject json) {
        CompletableFuture.runAsync(() -> fireAndForget(exchange, properties, json));
    }

    /**
     * Handle the delivery of the message in the queue and everytime a
     * message is received, a callback is run.
     *
     * @param queueName Name of the queue to consume messages from
     * @param callback  Callback to be run everytime a message is received
     */

    public void onDelivery(@NotNull String queueName,
                           Consumer<JSONObject> callback) {
        try {
            channel.basicConsume(queueName, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
                    String fullMessage = new String(body, StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(fullMessage);
                    callback.accept(json);
                }
            });
        } catch (IOException e) {
            System.out.println("RUNTIME EXCEPTION 15");
            throw new RuntimeException(e);
        }
    }


    /**
     * @param callback When channel is initialized
     */
    public void onChannelInitialized(Runnable callback) {
        CompletableFuture.runAsync(() -> {
                    if (channel != null) {
                        callback.run();
                    } else {
                        // if channel is null, schedule the callback to be run when it is initialized
                        try {
                            Thread.sleep(500L);
                            onChannelInitialized(callback);
                        } catch (InterruptedException e) {
                            System.out.println("RUNTIME EXCEPTION 16");
                            throw new RuntimeException(e);
                        }
                    }
                }).orTimeout(10L, TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    System.out.println("RUNTIME EXCEPTION 17");
                    throw new RuntimeException("RabbitMQ channel initialization timed out!", throwable);
                });
    }

    /**
     * @return
     * true - if channel is initialized<p>
     * false - if channel is not initialized
     */
    public boolean isChannelInitialized() {
        return channel != null;
    }

    /**
     * Close all connections, channels
     * and set factory to null
     */
    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (TimeoutException | IOException e) {
                System.out.println("RUNTIME EXCEPTION 18");
                throw new RuntimeException(e);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException e) {
                System.out.println("RUNTIME EXCEPTION 19");
                throw new RuntimeException(e);
            }
        }
        factory = null;
    }
}
