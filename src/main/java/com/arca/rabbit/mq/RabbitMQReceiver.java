package com.arca.rabbit.mq;

import com.arca.ArcaController;
import com.arca.controllers.ResponseProsess;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class RabbitMQReceiver {

	private String readingQueue;
	private Connection connection;
	private Channel channel;

	public RabbitMQReceiver() {
		this.readingQueue = ArcaController.USERNAME + "_out";
	}

	/**
	 *
	 * @throws java.io.IOException
	 * @throws java.util.concurrent.TimeoutException
	 */
	public void start() throws IOException, TimeoutException {
		connection = ConnectionUtil.getRabbitMqConnection();
		channel = connection != null ? connection.createChannel() : null;

		if (channel != null) {
			channel.basicQos(1);
			Object monitor = new Object();

			DeliverCallback deliverCallback = (consumerTag, delivery) -> {// Subscribe to the play queue. Once a message
																			// falls into the queue this part of the
																			// code will be executed automatically
				String receivedMessage = new String(delivery.getBody(), "UTF-8");
				System.out.println("Message received back "+receivedMessage);
				System.out.println(ResponseProsess.processResponse(receivedMessage));
				long deliveryTag = 0;

				deliveryTag = delivery.getEnvelope().getDeliveryTag();

				// send back an acknowledgement
				channel.basicAck(deliveryTag, true);

				// RabbitMq consumer worker thread notifies the RPC server owner thread
				synchronized (monitor) {
					monitor.notify();
				}

			};

			channel.basicConsume(readingQueue, false, deliverCallback, (consumerTag -> {
			}));
			// Wait and be prepared to consume the message from RPC client.
			while (true) {
				synchronized (monitor) {
					try {
						monitor.wait();
					} catch (InterruptedException ex) {
						ex.printStackTrace();
						System.out.println(ex.getCause().getMessage());
					}
				}
			}
		}
	}

	public String getReadingQueue() {
		return readingQueue;
	}

	public void setReadingQueue(String readingQueue) {
		this.readingQueue = readingQueue;
	}

	public Connection getConnection() {
		return connection;
	}

	public void setConnection(Connection connection) {
		this.connection = connection;
	}

	public Channel getChannel() {
		return channel;
	}

	public void setChannel(Channel channel) {
		this.channel = channel;
	}

}
