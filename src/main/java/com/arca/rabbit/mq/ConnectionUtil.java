package com.arca.rabbit.mq;

import com.arca.ArcaController;
import com.arca.controllers.CreateConnection;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.net.ssl.SSLContext;

public class ConnectionUtil {

	private static Connection rabbitMqConnection;

	/**
	 *
	 * @return
	 */
	public static Connection getRabbitMqConnection() {
		if (rabbitMqConnection == null) {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost(ArcaController.ADDRESS);
			factory.setPort(5671);
			factory.setVirtualHost(ArcaController.HOST);
			try {
				factory.setUsername(ArcaController.USERNAME);
				factory.setPassword(ArcaController.PASSWORD);

				// rootFolder = "D:\\Api\\Arca\\Certs\\";

				SSLContext sSLContext = new ClientSSL(ArcaController.ROOTFOLDER + "Mayfair.p12",
						ArcaController.SSL_PASSWORD).getSSLContext("rabbitmq");
				factory.useSslProtocol(sSLContext);
				factory.enableHostnameVerification();

				rabbitMqConnection = factory.newConnection();
				System.out.println("Connected!!");
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		return rabbitMqConnection;
	}
}
