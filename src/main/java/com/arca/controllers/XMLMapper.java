package com.arca.controllers;

import java.io.File;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;

import com.arca.config.Settings;

public class XMLMapper {

	public static String findErrorMessage(String errorCode) {

		String error = "N/A";
		try {

			File inputFile = new File("erreur_error.xml");
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputFile);

			List<Node> errors = document
					.selectNodes("/enveloppe/reponseDocumentation/erreurs/erreurSysteme[@code = '" + errorCode + "']");

			for (Node node : errors) {
				error = node.selectSingleNode("message").getText();

			}

		} catch (Exception e) {

			e.printStackTrace();

			// TODO: handle exception
		}

		return error;
	}

	public static String findAttributeName(String tagCode) {

		String attribute = "N/A";
		try {

			File inputFile = new File("ProductsList.xml");
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputFile);

			List<Node> attributes = document.selectNodes(
					"/enveloppe/reponseDocumentation/produit/objetsGaranties/objetsGarantie/typeBien/attributs/attribut[@code = '"
							+ tagCode + "']");

			for (Node node : attributes) {
				attribute = node.selectSingleNode("libelle").getText();
			}

		} catch (Exception e) {
			e.printStackTrace();
			// TODO: handle exception
		}
		return attribute;
	}



	public static boolean saveCertificate(String p_pl_index, String p_end_index, String p_risk_index, String p_fm_date,
			String p_to_date, String p_user_code, String certNo, String p_commit) {
		boolean saved = false;
		try {

			try (Connection oraConn = CreateConnection.getOraConn();
					CallableStatement cstmt = oraConn
							.prepareCall("{call issue_arca_certificate(?,?,?,?,?,?,?,?,?,?,?,?)}");) {
//				      p_cert_book_code   in     pkg_data.v_char_255%type,
//				      p_user_code        in     pkg_data.v_char_255%type,
//				      p_user_ip          in     pkg_data.v_char_255%type,
//				      response_cert_no          in     pkg_data.v_char_255%type,
//				      p_commit           in     pkg_data.v_char_255%type default 'N',
//				      p_result              out pkg_data.v_char_2000%type)
				cstmt.setString(1, Settings.orgCode);
				cstmt.setString(2, p_pl_index);
				cstmt.setString(3, p_end_index);
				cstmt.setString(4, p_risk_index);
				cstmt.setString(5, p_fm_date);
				cstmt.setString(6, p_to_date);
				cstmt.setString(7, Settings.bookCode);
				cstmt.setString(8, p_user_code);
				cstmt.setString(9, "0.0.0.0");
				cstmt.setString(10, certNo);
				cstmt.setString(11, p_commit);
				cstmt.registerOutParameter(12, Types.VARCHAR);
				cstmt.execute();
				String p_result = cstmt.getString(12);
				System.out.println(p_result);
				saved = true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		return saved;
	}

	public static void updateCertificate(String envId, String docId, String riskIndex, String certNo) {
		try {

			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery("select AR_PL_INDEX, AR_END_INDEX from ARCA_REQUESTS"
							+ " where AR_ENVELOPE_ID = " + envId + " and AR_DOCUMENT_ID = " + docId)) {
				while (rs.next()) {
					if (riskIndex.isEmpty() || riskIndex == null) {
						try (PreparedStatement update = oraConn.prepareStatement(
								"update uw_policy_risks set pl_flex01 = ? where pl_org_code = ? and pl_pl_index = ? and PL_END_INDEX = ? ")) {

							update.setString(1, certNo);
							update.setString(2, Settings.orgCode);
							update.setString(3, rs.getString("AR_PL_INDEX"));
							update.setString(4, rs.getString("AR_END_INDEX"));
							update.execute();

						}
					} else {

						String p_pl_index = "";//
						String p_end_index = "";//
						String p_fm_date = "";//
						String p_to_date = "";//
						String p_user_code = "";//
						String p_commit = "Y";
						try (Statement stmt22 = oraConn.createStatement();
								ResultSet rs22 = stmt22.executeQuery(
										"select AR_PL_INDEX, AR_END_INDEX,CREATED_BY,CREATED_IP from ARCA_REQUESTS"
												+ " where AR_ENVELOPE_ID = " + envId + " and AR_DOCUMENT_ID = "
												+ docId)) {
							while (rs22.next()) {
								p_pl_index = rs22.getString("AR_PL_INDEX");
								p_end_index = rs22.getString("AR_END_INDEX");
								p_user_code = rs22.getString("CREATED_BY");

								try (Statement stmt2 = oraConn.createStatement();
										ResultSet rs2 = stmt2
												.executeQuery("select * from uw_policy_risks where pl_pl_index = "
														+ p_pl_index + " and pl_end_index = " + p_end_index
														+ " and pl_risk_index = " + riskIndex)) {
									while (rs2.next()) {
										p_fm_date = String.valueOf(rs2.getDate("pl_risk_fm_dt").toLocalDate());
										p_to_date = String.valueOf(rs2.getDate("pl_risk_to_dt").toLocalDate());
									}
								}

							}
						}
						saveCertificate(p_pl_index, p_end_index, riskIndex, p_fm_date, p_to_date, p_user_code, certNo,
								p_commit);

					}
				}
			}
		} catch (Exception e) {
			// TODO: handle exception
		}

	}

	public static String processResponse(String responseXML) {

		String envelopeID = "";
		String documentID = "";
		String response = "";
		String statusCode = "";
		String certNo = "";
		String riskIndex = "";
		String certBase64 = "";

		try {

			Document document = DocumentHelper.parseText(responseXML); // reader.read(inputFile);

			List<Node> errors = document.selectNodes("/enveloppe");

			for (Node node : errors) {
				envelopeID = node.valueOf("@id");

				List<Node> documentNodes = node.selectNodes("./reponseProduction");
				for (Node documentNode : documentNodes) {
					documentID = documentNode.valueOf("@id");
					switch (documentNode.selectSingleNode("statut").getText()) {
					case "ERREUR":

						Element errorElement = (Element) documentNode.selectSingleNode("erreur");
						String a = errorElement.attributeValue("code");
						response = (findErrorMessage(a) + " " + documentNode.selectSingleNode("erreur").getText());
						if (documentNode.selectSingleNode("erreur").getText().contains("Attribut")) {
							response = (findErrorMessage(a) + " "
									+ documentNode.selectSingleNode("erreur").getText().split(" : ")[0] + " "
									+ findAttributeName(
											documentNode.selectSingleNode("erreur").getText().split(" : ")[1]));

						}

						updateCertificate(envelopeID, documentID, riskIndex, certNo);
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
							certBase64 = current.getText();
							Base64DecodePdf bdf = new Base64DecodePdf();

							if (bdf.decodeString(certNo, certBase64)) {

								response = ("Certificate Saved");
								statusCode = "00";
								updateCertificate(envelopeID, documentID, riskIndex, certNo);
							} else {

								response = ("Certificate Could not be saved");
								statusCode = "01";
							}
						}

						break;
					default:

						break;
					}

				}

				if (documentID.isEmpty()) {

					Element errorElement = (Element) node.selectSingleNode("erreur");
					String a = errorElement.attributeValue("code");
					response = (findErrorMessage(a));

//					response = (findErrorMessage(a));
					statusCode = "01";
					break;
				}

			}
		} catch (DocumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					PreparedStatement update = oraConn.prepareStatement(
							"update ARCA_REQUESTS set ar_response_xml = ? , ar_success = ?, updated_on = sysdate where ar_envelope_id = ? and ar_document_id = ? ")) {
				System.out.println("update c set ar_response_xml = '" + responseXML
						+ "' , ar_success = ?, updated_on = sysdate where ar_envelope_id = " + envelopeID
						+ " and ar_document_id = " + documentID + " ");
				update.setString(1, responseXML);
				update.setString(2, statusCode == "00" ? "Y" : "N");
				update.setString(3, envelopeID);
				update.setString(4, documentID);
				update.execute();

			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return "{\"status\":\"" + statusCode + "\",\"statusDescription\":\"" + response + "\"}";

	}

	public static String findVehicleEnergy(String energy) {
		String eneryType = "N/A";
		try {

			File inputFile = new File("codification.xml");
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputFile);
			List<Node> energyTypes = document
					.selectNodes("/enveloppe/reponseDocumentation/codification[@nomCodification = 'energie_vehicule']");
			for (Node node : energyTypes) {

				if (node.selectSingleNode("libelle").getText().contains(energy)) {
					eneryType = node.selectSingleNode("code").getText();
				}

			}

		} catch (Exception e) {
			// TODO: handle exception
		}

		return eneryType;

	}

	public static String findBodyType(String bodytype) {
		String bodyType = "N/A";

		try {

			File inputFile = new File("codification.xml");
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputFile);

			List<Node> energyTypes = document
					.selectNodes("/enveloppe/reponseDocumentation/codification[@nomCodification = 'type_vehicule']");
			for (Node node : energyTypes) {
				if (node.selectSingleNode("libelle").getText().contains(bodytype)) {
					bodyType = node.selectSingleNode("code").getText();
				}
			}

		} catch (Exception e) {
			// TODO: handle exception
		}

		return bodyType;

	}

	public static String findVehicleUsage(String usage) {

		String eneryType = "N/A";
		try {

			File inputFile = new File("codification.xml");
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputFile);

			List<Node> energyTypes = document
					.selectNodes("/enveloppe/reponseDocumentation/codification[@nomCodification = 'usage_vehicule']");
			for (Node node : energyTypes) {
				if (node.selectSingleNode("libelle").getText().contains(usage)) {
					eneryType = node.selectSingleNode("code").getText();
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			// TODO: handle exception
		}

		return eneryType;

	}

	public static String findVehicleDetails(String bodyType, String make, String model) {

		String returnedBodyType = "N/A";
		String returnedMake = "N/A";
		String returnedModel = "N/A";
		try {
			File inputFile = new File("codification.xml");
			SAXReader reader = new SAXReader();
			Document document = reader.read(inputFile);

			List<Node> bodyTypes = document
					.selectNodes("/enveloppe/reponseDocumentation/codification[@nomCodification = 'type_vehicule']");

			for (Node node : bodyTypes) {

				if (bodyType.equalsIgnoreCase(node.selectSingleNode("libelle").getText())) {
					returnedBodyType = node.selectSingleNode("code").getText();
					// vehicle body type

					List<Node> vehicleMakes = node.selectNodes("./codifications/codification");

					for (Node vehicleMake : vehicleMakes) {

						// vehicle make
						if (make.equalsIgnoreCase(vehicleMake.selectSingleNode("libelle").getText())) {

							returnedMake = vehicleMake.selectSingleNode("code").getText();
							List<Node> vehicleModels = vehicleMake.selectNodes("./codifications/codification");

							for (Node vehicleModel : vehicleModels) {

								// vehicle model
								if (model.equalsIgnoreCase(vehicleModel.selectSingleNode("libelle").getText())) {
									returnedModel = vehicleModel.selectSingleNode("code").getText();
								}
							}

						}

					}
				}
			}

		} catch (DocumentException e) {
			e.printStackTrace();
		}

		if (returnedBodyType.equals("N/A")) {
			return "Error. " + " Arca Body Type value couldnt be found for body type " + bodyType;
		} else if (returnedMake.equals("N/A")) {
			return "Error. " + " Arca Make value couldnt be found for vehicle make " + make;
		} else if (returnedModel.equals("N/A")) {
			return "Error. " + " Arca Model value couldnt be found for model " + model;
		} else {
			return returnedBodyType + ":" + returnedMake + ":" + returnedModel;
		}

	}
}