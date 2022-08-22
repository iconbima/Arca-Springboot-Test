package com.arca.rabbit.mq;

import com.arca.controllers.CreateConnection;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.net.ssl.SSLContext;

public class ConnectionUtil {

	private static Connection rabbitMqConnection;
	public static final String USERNAME = "SXEONZTC";
	public static final String PASSWORD = "h82D2OrRLA";

	/**
	 *
	 * @return
	 */
	public static Connection getRabbitMqConnection() {
		if (rabbitMqConnection == null) {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost("mq1.extranet.arca.cd");
			factory.setPort(5671);
			factory.setVirtualHost("production");
			factory.setUsername(USERNAME);
			factory.setPassword(PASSWORD);

			try {
				String rootFolder = "./";
				try (Statement stmt = CreateConnection.getOraConn().createStatement();
						ResultSet rs = stmt.executeQuery(
								"select sys_name from ad_system_codes where sys_type = 'API_DETAILS' and sys_code = 'ARCA_CERT_PATH'");) {
					while (rs.next()) {
						rootFolder = rs.getString("sys_name");
					}

				} catch (SQLException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}

				SSLContext sSLContext = new ClientSSL(rootFolder + "Mayfair.p12", "MayFair@201")
						.getSSLContext("rabbitmq");
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
