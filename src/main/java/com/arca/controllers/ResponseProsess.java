package com.arca.controllers;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;

import com.arca.ArcaController;

public class ResponseProsess {

	

	public static String processResponse(String responseXML) {

		String envelopeID = "";
		String documentID = "";
		String response = "";
		String statusCode = "";
		String certNo = "";
		String riskIndex = "";
		String certUrl = "";
		String certBase64 = "";
		
		

		try {

			Document document = DocumentHelper.parseText(responseXML); // reader.read(inputFile);

			List<Node> envelope = document.selectNodes("/enveloppe");

			for (Node node : envelope) {
				envelopeID = node.valueOf("@id");

				// save the response first to a file
				//System.out.println(ArcaController.ROOTFOLDER + "Responses\\" + envelopeID + ".xml");
				Path path = Paths.get(ArcaController.ROOTFOLDER + "Responses\\" + envelopeID + ".xml");

				/* Check if this is a cancellation */

				// Try block to check for exceptions
				try {

					// Converting string to byte array
					// using getBytes() method
					Document doc = DocumentHelper.parseText(responseXML);
					StringWriter sw = new StringWriter();
					OutputFormat format = OutputFormat.createPrettyPrint();
					XMLWriter xw = new XMLWriter(sw, format);
					xw.write(doc);
					String result = sw.toString();

					byte[] arr = result.getBytes();
					Files.write(path, arr);
				} catch (IOException ex) {
					// Print message as exception occurred when
					// invalid path of local machine is passed
					System.out.print("Invalid Path "+ArcaController.ROOTFOLDER + "Responses\\" + envelopeID + ".xml");
				}

				String responseType = "documentation";
				List<Node> documentNodes = node.selectNodes("./reponseDocumentation");

				if (documentNodes.size() < 1) {
					// this is a response for certificate cancellation
					documentNodes = node.selectNodes("./annulationCertificat");
					responseType = "cancellation";
				}
				if (documentNodes.size() < 1) {
					// this is a production response for certificate issuance
					documentNodes = node.selectNodes("./reponseProduction");
					responseType = "production";
				}
				for (Node documentNode : documentNodes) {
					documentID = documentNode.valueOf("@id");

					if (responseType.equals("production")) {

						switch (documentNode.selectSingleNode("statut").getText()) {
						case "ERREUR":

							Element errorElement = (Element) documentNode.selectSingleNode("erreur");
							String a = errorElement.attributeValue("code");
							response = (XMLMapper.findErrorMessage(a) + " " + documentNode.selectSingleNode("erreur").getText());
							if (documentNode.selectSingleNode("erreur").getText().contains("Attribut")) {
								response = (XMLMapper.findErrorMessage(a) + " "
										+ documentNode.selectSingleNode("erreur").getText().split(" : ")[0] + " "
										+ XMLMapper.findAttributeName(
												documentNode.selectSingleNode("erreur").getText().split(" : ")[1]));

							}
							if (documentID.isEmpty()) {

								errorElement = (Element) node.selectSingleNode("erreur");
								a = errorElement.attributeValue("code");
								response = (XMLMapper.findErrorMessage(a));
								statusCode = "01";
								break;
							}

							SaveResponse.updateCertificate(envelopeID, documentID, riskIndex, response, "01",null);
							statusCode = "01";
							break;
						case "OK":

							response = ("Success");
							statusCode = "00";
							List<Node> certNodes = documentNode.selectNodes("./documents/certificat");
							for (Node certNode : certNodes) {

								Element current = (Element) certNode;
								certNo = certNode.valueOf("@numero");
								riskIndex = certNode.valueOf("@idBien");
 								certUrl = current.getText();
 								//certBase64 = current.getText();
								Base64DecodePdf bdf = new Base64DecodePdf();

								/* This was phased out by ARCA, certs are now URLs
								if (bdf.decodeString(certNo, certBase64)) {

									response = ("Certificate Saved");
									statusCode = "00";

									SaveResponse.updateCertificate(envelopeID, documentID, riskIndex, certNo, "00");
								} else {

									response = ("Certificate Could not be saved");
									statusCode = "01";
								}
								*/
								if (bdf.saveCertFromUrl(certNo, certUrl)) {

									response = ("Certificate Saved");
									statusCode = "00";

									SaveResponse.updateCertificate(envelopeID, documentID, riskIndex, certNo, "00",certUrl);
								} else {

									response = ("Certificate Could not be saved");
									statusCode = "01";
								}
							}

							break;
						default:

							break;
						}

						try {
							try (Connection oraConn = CreateConnection.getOraConn();
									PreparedStatement update = oraConn.prepareStatement(
											"update ARCA_REQUESTS set ar_response_xml = ? , ar_success = ?,"
													+ " updated_on = sysdate where ar_envelope_id = ? and ar_document_id = ?"
													+ "and AR_REQUEST_TYPE != 'CANCELLATION' ")) {

								update.setString(1, responseXML);
								update.setString(2, statusCode == "00" ? "Y" : "N");
								update.setString(3, envelopeID);
								update.setString(4, documentID);
								update.execute();

							}

						} catch (Exception e) {
							e.printStackTrace();
						}

					} else if (responseType.equals("cancellation")) {
						//if the response is a cancellation
						certNo = documentNode.valueOf("@numeroCertificat");
						String newCert = "";
						switch (documentNode.selectSingleNode("statut").getText()) {
						case "ERREUR":

							Element errorElement = (Element) documentNode.selectSingleNode("erreur");
							String code = errorElement.attributeValue("code");
							response = (XMLMapper.findErrorMessage(code) + " "
									+ documentNode.selectSingleNode("erreur").getText());
							newCert = response;
							statusCode = "01";
							break;
						case "OK":
							newCert = "CANCELLED "+certNo;
							response = ("Success");
							statusCode = "00";
							
							break;
						default:

							break;
						}
						/*Updating the response if it is a cancellation*/
						try {
							try (Connection oraConn = CreateConnection.getOraConn();
									PreparedStatement update = oraConn.prepareStatement(
											"update ARCA_REQUESTS set ar_response_xml = ? , ar_success = ?,"
													+ " updated_on = sysdate where ar_envelope_id = ? "
													+ "and AR_REQUEST_TYPE = 'CANCELLATION' ")) {

								update.setString(1, responseXML);
								update.setString(2, statusCode == "00" ? "Y" : "N");
								update.setString(3, envelopeID);
								update.execute();

							}
							String dbCertNo = "-1";
							

							try (Connection oraConn = CreateConnection.getOraConn();
									Statement stmt = oraConn.createStatement();

									ResultSet rs = stmt.executeQuery("select ar_cert_no  from ARCA_REQUESTS"
											+ " where AR_ENVELOPE_ID = " + envelopeID + " AND  AR_REQUEST_TYPE = 'CANCELLATION' ")) {

								dbCertNo = rs.getString("ar_cert_no");

							}
							
							dbCertNo = dbCertNo.equalsIgnoreCase("-1")?certNo:dbCertNo;
							
							SaveResponse.updateCancelledCertificate(envelopeID, "0", riskIndex, dbCertNo, "01",newCert);


						} catch (Exception e) {
							e.printStackTrace();
						}

					}

				}

			}
		} catch (DocumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return "{\"status\":\"" + statusCode + "\",\"statusDescription\":\"" + response + "\"}";

	}
}
