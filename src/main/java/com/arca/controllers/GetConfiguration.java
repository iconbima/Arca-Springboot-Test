package com.arca.controllers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import com.arca.ArcaController;
import com.arca.rabbit.mq.RabbitMQSender;
import com.google.gson.JsonObject;

public class GetConfiguration {
	private static Connection oraConn = null;
	public static Statement stmt = null;

	public GetConfiguration() {
		try {

			System.out.println("Connecting To Database");
			oraConn = CreateConnection.getOraConn();
			System.out.println("Database Connected!");

		} catch (Exception e) {
			System.out.println("Errors Connecting to Database\n" + e.getMessage());
		}

	}

	public  String sendConfigRequest(String requestCode) {

		JsonObject myResponse = new JsonObject();

		try {
			try (Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"select nvl(max(AR_ENVELOPE_ID)+1,1) correlation_id, nvl(max(AR_DOCUMENT_ID)+1,1) document_id from ARCA_REQUESTS")) {
				while (rs.next()) {

					RabbitMQSender sender = new RabbitMQSender();

					String requestXML = buildConfigRequest(requestCode, rs.getString("correlation_id"),
							rs.getString("document_id"));

					if (requestXML.startsWith("Error")) {

						myResponse.addProperty("status", "01");
						myResponse.addProperty("statusDescription", requestXML);
						return myResponse.get("statusDescription").toString();

					}
					System.out.println(requestXML);

					if (sender.sendMessage(requestXML, rs.getString("correlation_id"))) {
						PreparedStatement prepareStatement = oraConn.prepareStatement(
								"INSERT INTO ARCA_REQUESTS (AR_PL_INDEX, AR_END_INDEX, AR_ENVELOPE_ID, AR_DOCUMENT_ID, AR_REQUEST_XML,CREATED_BY,"
								+ "AR_REQUEST_TYPE )"
										+ " VALUES (?, ?, ?, ?, ?, ?, ?)");
						prepareStatement.setInt(1, 0);
						prepareStatement.setInt(2, getCodificationNumber(requestCode));
						prepareStatement.setString(3, rs.getString("correlation_id"));
						prepareStatement.setString(4, rs.getString("document_id"));
						prepareStatement.setString(5, requestXML);
						prepareStatement.setString(6, "1000000");
						prepareStatement.setString(7, "GET_CODES");
						prepareStatement.execute();

						myResponse.addProperty("status", "00");
						myResponse.addProperty("statusDescription", "Request submitted successfuly to ARCA");
					} else {

						myResponse.addProperty("status", "01");
						myResponse.addProperty("statusDescription", "Message couldnt be sent ");

					}

				}
			} catch (UnsupportedEncodingException e) {

				myResponse.addProperty("status", "02");
				myResponse.addProperty("statusDescription",
						"Error encountered (UnsupportedEncodingException)\n" + e.getMessage());
				e.printStackTrace();
			} catch (IOException e) {

				myResponse.addProperty("status", "02");
				myResponse.addProperty("statusDescription", "Error encountered (IOException)\n" + e.getMessage());
				e.printStackTrace();
			}
		} catch (SQLException e) {

			myResponse.addProperty("status", "02");
			myResponse.addProperty("statusDescription", "Error encountered (SQLException)\n" + e.getMessage());
			e.printStackTrace();
		}

		return myResponse.get("statusDescription").toString();
	}

	public  String buildConfigRequest(String requestCode, String correlationId, String documentID) {

		Document policyXML = DocumentHelper.createDocument();
		try {

			Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationId))
					.addAttribute("identifiant", ArcaController.USERNAME)
					.addAttribute("motDePasse", ArcaController.PASSWORD)
					.addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime()).addElement("documentation")
					.addAttribute("id", String.valueOf(documentID)).addAttribute("idCode", requestCode);

		} catch (Exception e) {
			e.printStackTrace();

			return "Error. Could not build codification xml " + e.getMessage();
		}
		return policyXML.asXML();

	}

	private  int getCodificationNumber(String requestCode) {
		switch (requestCode) {
		case "_pays":
			return 0;
		case "_devise":
			return 1;
		case "_erreur":
			return 2;
		case "_adresseRDC":
			return 3;
		case "_categorie":
			return 4;
		case "_codification":
			return 5;
		case "_listeProduits":
			return 6;
			
		default:
			return -1;
		}
	}

}
