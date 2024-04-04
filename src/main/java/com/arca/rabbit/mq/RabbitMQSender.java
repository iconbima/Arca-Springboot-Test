package com.arca.rabbit.mq;

import com.arca.ArcaController;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.TimeoutException;

public class RabbitMQSender {

    private String exchangeIn;
    private String replyTo;
    private Channel channel;
    private String requestId;
    boolean clientChannelDurable = true;
    boolean clientChannelExclusive = false;
    private final Connection connection;
    

    public RabbitMQSender() throws IOException {
        exchangeIn = ArcaController.USERNAME + "_in";
        replyTo = ArcaController.USERNAME + "_out";
        connection = ConnectionUtil.getRabbitMqConnection();
        channel = connection != null ? connection.createChannel() : null;
    }

    public boolean sendMessage(String message, String correlationId) throws UnsupportedEncodingException, IOException {
        if (channel != null) {
            BasicProperties replyProps = new AMQP.BasicProperties.Builder()
                    .replyTo(replyTo)
                    .contentType("application/octet-stream")
                    .deliveryMode(2)
                    .contentEncoding("UTF-8")
                    .correlationId(correlationId)
                    .build();
            channel.basicPublish(exchangeIn, "", replyProps, message.getBytes("UTF-8"));
            return true;
        } else {
            System.out.println("Failed to send message to  " + exchangeIn);
            return false;
        }
    }

    public void closeConnection() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException | TimeoutException ex) {
            System.out.println(ex.getCause().getMessage());
        }
    }

    public String getExchangeIn() {
        return exchangeIn;
    }

    public void setExchangeIn(String exchangeIn) {
        this.exchangeIn = exchangeIn;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public boolean isClientChannelDurable() {
        return clientChannelDurable;
    }

    public void setClientChannelDurable(boolean clientChannelDurable) {
        this.clientChannelDurable = clientChannelDurable;
    }

    public boolean isClientChannelExclusive() {
        return clientChannelExclusive;
    }

    public void setClientChannelExclusive(boolean clientChannelExclusive) {
        this.clientChannelExclusive = clientChannelExclusive;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

}
