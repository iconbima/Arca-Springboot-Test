package com.arca.controllers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.Instant;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import com.arca.rabbit.mq.ConnectionUtil;
import com.arca.rabbit.mq.RabbitMQReceiver;
import com.arca.rabbit.mq.RabbitMQSender;

public class Test {

	public static void main(String[] args) {

		System.out.println();
		try {
			RabbitMQReceiver receiver = new RabbitMQReceiver();
//			receiver.start();
			if (sendMessage(19, 5142421317L, "type_vehicule")) {
				System.out.println("message has been sent");
			} else {
				System.err.println("Message couldnt send");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static boolean sendMessage(int correlationID, long documentID, String idCode)
			throws UnsupportedEncodingException, IOException {

		Document document = DocumentHelper.createDocument();
		RabbitMQSender test;

		Element enveloppe = document.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		enveloppe.addElement("documentation").addAttribute("id", String.valueOf(documentID)).addAttribute("idCode",
				idCode);

		test = new RabbitMQSender();

		String tests = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><enveloppe id=\"" + correlationID
				+ "\" identifiant=\"" + ConnectionUtil.USERNAME + "\" motDePasse=\"" + ConnectionUtil.PASSWORD
				+ "\" timestamp=\"" + getCurrentUtcTime() + "\">\r\n" + "	<production id=\"" + documentID + "\">\r\n"
				+ "		<assureur numeroAgrement=\"12005\" />\r\n"
				+ "		<produit  version=\"1\" >12005-090003</produit>\r\n" + "		<exercice>2021</exercice>\r\n"
				+ "		<numeroPolice>" + String.valueOf("123456") + "</numeroPolice>\r\n"
				+ "		<numeroAvenant type=\"I\">2</numeroAvenant>\r\n"
				+ "		<dateEmission>2021-08-18</dateEmission>\r\n" + "		<dateEffet>2021-08-18</dateEffet>\r\n"
				+ "		<dateEcheance>2022-08-31</dateEcheance>\r\n" + "		<souscripteur>\r\n"
				+ "			<personne reference=\"CLIENT42\" immatriculation=\"non assujetti\" paysEtablissement=\"CD\" personneMorale=\"false\" operation=\"ajout\">\r\n"
				+ "				<adresse>\r\n" + "					<voie>10 avenue Justice</voie>\r\n"
				+ "					<commune code=\"C/Gombe\" />\r\n" + "				</adresse>\r\n"
				+ "				<prenom>Stephane</prenom>\r\n" + "				<nom>Nempeche</nom>\r\n"
				+ "				<lieuNaissance codePays=\"CD\">Kinshasa</lieuNaissance>\r\n"
				+ "				<civilite code=\"M.\" />\r\n" + "			</personne>\r\n"
				+ "		</souscripteur>\r\n" + "		<prime devise=\"USD\" taux=\"2000\">\r\n"
				+ "			<fraisAccessoires>356</fraisAccessoires>\r\n"
				+ "			<taxeValeurAjoutee>17600</taxeValeurAjoutee>\r\n" + "		</prime>\r\n"
				+ "		<objet code=\"12005-090003-AUTO\" reference=\"ASS1OBJ1\">\r\n" + "			<biens>\r\n"
				+ "				<bien reference=\"ASS1BIEN1\" operation=\"ajout\">\r\n" + "					<attributs>"
				+ "\r\n" + "						<valeur nom=\"TYP\">1</valeur>\r\n"
				+ "						<valeur nom=\"MAR\">143</valeur>\r\n"
				+ "						<valeur nom=\"PUF\">5</valeur>\r\n"
				+ "						<valeur nom=\"DMC\">2005-10-21</valeur>\r\n"
				+ "						<valeur nom=\"USA\">PRV</valeur>\r\n"
				+ "						<valeur nom=\"PAY\">CD</valeur>\r\n"
				+ "						<valeur nom=\"CAR\">CI</valeur>\r\n"
				+ "						<valeur nom=\"PTA\">2500</valeur>\r\n"
				+ "						<valeur nom=\"PLA\">5</valeur>\r\n"
				+ "						<valeur nom=\"COH\">St�phane Nemp�che</valeur>\r\n"
				+ "						<valeur nom=\"DPC\">1996-09-22</valeur>\r\n"
				+ "						<valeur nom=\"IMM\">KCT857G</valeur>\r\n"
				+ "						<valeur nom=\"CHA\">5142321301</valeur>\r\n"
				+ "						<valeur nom=\"MOD\">20513</valeur>\r\n"
				+ "						<valeur nom=\"ANF\">2005</valeur>\r\n"
				+ "						<valeur nom=\"VAL\">1000000</valeur>\r\n"
				+ "						<valeur nom=\"REM\">false</valeur>\r\n"
				+ "						<valeur nom=\"ENE\">ES</valeur>\r\n"
				+ "						<valeur nom=\"GAR\">C/Bandalungwa</valeur>\r\n"
				+ "						<valeur nom=\"NPC\">12345</valeur>" + "</attributs>\r\n"
				+ "				</bien>\r\n" + "" + "\r\n" + "			" + "	<souscriptions>\r\n"
				+ "					<garantie code=\"12005-090003-AUTO-DR\">\r\n"
				+ "						<dateEffet>2021-08-18</dateEffet>\r\n"
				+ "						<dateEcheance>2022-08-18</dateEcheance>\r\n"
				+ "						<attributs>\r\n"
				+ "							<valeur nom=\"CVT\">RDC</valeur>\r\n"
				+ "							<valeur nom=\"DUR\">12</valeur>\r\n"
				+ "							<valeur nom=\"FRT\">false</valeur>\r\n"
				+ "						</attributs>\r\n" + "						<prime>100000</prime>\r\n"
				+ "					</garantie>\r\n" + "				</souscriptions>\r\n"
				+ "			</biens>\r\n" + "		</objet>\r\n" + "	</production>\r\n" + "</enveloppe>";

		System.err.println(tests);
//		return test.sendMessage(document.asXML(), String.valueOf(correlationID));

		return test.sendMessage(tests, String.valueOf(correlationID));

	}

	public static String getCurrentUtcTime() {
		return Instant.now().toString();
	}

}
