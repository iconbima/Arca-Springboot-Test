package com.arca.controllers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import com.arca.ArcaController;
import com.arca.config.Settings;
import com.arca.rabbit.mq.RabbitMQSender;
import com.google.gson.JsonObject;

public class SubmitMotorVehicle {
	String sourceTable = "UW";

	public String sendArcaMessage(int pl_index, int pl_end_index, int risk_index, String created_by) {

		JsonObject myResponse = new JsonObject();
		boolean firstRequest = true;
		boolean firstEndRequest = true;
		boolean cancelledCert = false;
		boolean firstRenewalRequest = false;

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"select ARCA_ENVELOPE_ID_SEQ.nextval correlation_id, ARCA_DOCUMENT_ID_SEQ.nextval document_id from dual")) {
				while (rs.next()) {

					RabbitMQSender sender = new RabbitMQSender();
					String endType = "";
					// CHECK THE TYPE OF ENDORSEMENT HERE AND CALL THE RELEVANT METHOD TO CREATE XML
					// REQUEST

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("SELECT * FROM arca_requests a WHERE AR_PL_INDEX = "
									+ pl_index + " AND ar_end_index = " + pl_end_index + " AND ar_risk_index = "
									+ risk_index + " AND ar_success = 'Y' "
									+ "and AR_REQUEST_TYPE != 'CANCELLATION' AND NOT EXISTS "
									+ "(SELECT 1 FROM arca_requests b WHERE     b.AR_PL_INDEX = a.AR_PL_INDEX "
									+ "AND b.ar_end_index = a.ar_end_index AND b.ar_risk_index = a.ar_risk_index "
									+ "   AND b.ar_success = 'Y' AND b.created_on > a.created_on AND b.AR_REQUEST_TYPE = 'CANCELLATION') "
									+ "AND a.ar_success = 'Y'")) {
						if (rs2.next()) {

							myResponse.addProperty("status", "-1");
							myResponse.addProperty("statusDescription",
									"This vehicle has already been submitted and has a successful response.");
							return myResponse.get("statusDescription").toString();

						}
					}

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("SELECT * FROM arca_requests a WHERE AR_PL_INDEX = "
									+ pl_index + " AND ar_end_index = " + pl_end_index + " AND ar_risk_index = "
									+ risk_index
									+ " AND ar_success = 'Y' and AR_REQUEST_TYPE = 'CANCELLATION' and created_on =  "
									+ " (select max(created_on) from arca_requests where  ar_success = 'Y' and AR_REQUEST_TYPE = 'CANCELLATION'"
									+ " and AR_PL_INDEX = " + pl_index + " and ar_end_index = " + pl_end_index
									+ " and ar_risk_index = " + risk_index + ") ")) {
						if (rs2.next()) {

							cancelledCert = true;

						}
					}

					// Check if this is the first policy request
					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from arca_requests where AR_PL_INDEX = "
									+ pl_index + " and ar_success = 'Y'")) {
						if (rs2.next()) {
							firstRequest = false;

						}
					}

					// Check if this is the first endorsement request, if not then we will send an
					// addition
					// endorsement
					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from arca_requests where AR_PL_INDEX = "
									+ pl_index + " and ar_end_index = " + pl_end_index + " and ar_success = 'Y'")) {
						if (rs2.next()) {
							firstEndRequest = false;

						}
					}

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery(
									"select pl_end_internal_code,decode(pl_status,'Active','UH','UW') pl_status from uw_policy where pl_index = "
											+ pl_index + " and pl_end_index = " + pl_end_index + " and pl_org_code = "
											+ Settings.orgCode)) {
						while (rs2.next()) {
							endType = rs2.getString("pl_end_internal_code");
							sourceTable = rs2.getString("pl_status");

						}
					}

					System.err.println("endType " + endType);
					// check if this is the first request for this car and the endorsement is a
					// renewal
					if (endType.equals("110")) {
						firstRenewalRequest = true;
						try (Statement stmt2 = oraConn.createStatement();
								ResultSet rs2 = stmt2.executeQuery("SELECT * FROM ARCA_REQUESTS WHERE AR_PL_INDEX = "
										+ pl_index + " AND ar_risk_index = " + risk_index + " and AR_SUCCESS = 'Y'")) {
							if (rs2.next()) {
								firstRenewalRequest = false;

							}
						}

					}
					if (sourceTable.equals("UW") && endType.equals("110")) {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "Please activate the renewal before submitting.");
						return myResponse.get("statusDescription").toString();
					}

					String requestXML = "";
					System.err.println("firstRequest " + firstRequest);
					System.err.println("cancelledCert " + cancelledCert);
					System.err.println("firstRenewalRequest " + firstRenewalRequest);
					if (firstRequest) {
						// If it is the first request then change to build policy xml
						requestXML = buildPolicyXML(pl_index, pl_end_index, risk_index, rs.getString("correlation_id"),
								rs.getString("document_id"), "000");

					} else if (cancelledCert || firstRenewalRequest) {
						// Incorporation - Adding to sum insured
						requestXML = buildIncopEndorsementXML(pl_index, pl_end_index, risk_index,
								rs.getString("correlation_id"), rs.getString("document_id"));
					} else {
						/*
						 * If this is a new business or renewal, additional or extension and is first
						 * request then do a new business request
						 */

						// Remove this area
						/*
						 * if (1 == 1) {
						 * 
						 * firstEndRequest = false; }
						 */
						if (!firstEndRequest) {

							// Incorporation - Adding to sum insured
							requestXML = buildIncopEndorsementXML(pl_index, pl_end_index, risk_index,
									rs.getString("correlation_id"), rs.getString("document_id"));
						} else {
							if (endType.equals("110")) {
								// New business or Renewals
								requestXML = buildPolicyXML(pl_index, pl_end_index, risk_index,
										rs.getString("correlation_id"), rs.getString("document_id"), endType);
							}
							/*
							 * If this is a an addition or new business and is not first request then do an
							 * addition
							 */
							else if (endType.equals("101")) {
								// Incorporation - Adding to sum insured
								requestXML = buildIncopEndorsementXML(pl_index, pl_end_index, risk_index,
										rs.getString("correlation_id"), rs.getString("document_id"));

							} else if (endType.equals("103")) {
								// Extension - Extending the policy period
								requestXML = buildExtensionXML(pl_index, pl_end_index, risk_index,
										rs.getString("correlation_id"), rs.getString("document_id"));

							} else if (endType.equals("102") || endType.equals("104")) {
								// Rebate - Reducing the sum insured
								requestXML = buildRebateEndorsementXML(pl_index, pl_end_index, risk_index,
										rs.getString("correlation_id"), rs.getString("document_id"));

							} else if (endType.isEmpty()) {
								// Empty end type
								myResponse.addProperty("status", "-1");
								myResponse.addProperty("statusDescription", "Endorsement type not found! ");
								return myResponse.get("statusDescription").toString();

							} else {

								myResponse.addProperty("status", "-1");
								myResponse.addProperty("statusDescription",
										"No request configured for this endorsement! ");
								return myResponse.get("statusDescription").toString();

							}
						}
					}
					if (requestXML.startsWith("Error")) {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", requestXML);
						return myResponse.get("statusDescription").toString();

					}
					System.out.println(requestXML);

					PreparedStatement prepareStatement = oraConn.prepareStatement(
							"INSERT INTO ARCA_REQUESTS (AR_PL_INDEX, AR_END_INDEX, AR_ENVELOPE_ID, AR_DOCUMENT_ID, AR_REQUEST_XML,CREATED_BY,AR_REQUEST_TYPE,AR_RISK_INDEX )"
									+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
					prepareStatement.setInt(1, pl_index);
					prepareStatement.setInt(2, pl_end_index);
					prepareStatement.setString(3, rs.getString("correlation_id"));
					prepareStatement.setString(4, rs.getString("document_id"));
					prepareStatement.setString(5, requestXML);
					prepareStatement.setString(6, created_by);
					prepareStatement.setString(7, "SINGLE_MOTOR_CERT");
					prepareStatement.setInt(8, risk_index);
					prepareStatement.execute();

					if (sender.sendMessage(requestXML, rs.getString("correlation_id"))) {

						myResponse.addProperty("status", "00");
						myResponse.addProperty("statusDescription", "Details submitted successfuly to ARCA");
					} else {
						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "Message couldn't be sent ");
						return myResponse.get("statusDescription").toString();

					}

				}
			} catch (UnsupportedEncodingException e) {

				myResponse.addProperty("status", "-1");
				myResponse.addProperty("statusDescription",
						"Error encountered (UnsupportedEncodingException)\n" + e.getMessage());
				e.printStackTrace();
			} catch (IOException e) {

				myResponse.addProperty("status", "-1");
				myResponse.addProperty("statusDescription", "Error encountered (IOException)\n" + e.getMessage());
				e.printStackTrace();
			}
		} catch (SQLException e) {

			myResponse.addProperty("status", "-1");
			myResponse.addProperty("statusDescription", "Error encountered (SQLException)\n" + e.getMessage());
			e.printStackTrace();
		}

		return myResponse.get("statusDescription").toString();
	}

	public String buildPolicyXML(int pl_index, int pl_end_index, int risk_index, String correlationID,
			String documentID, String endType) {

		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD)
				.addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(SubmitMotorPolicy.policyHeaderQuery(pl_index, pl_end_index))) {
				while (rs.next()) {
					production.addElement("ville").addText(rs.getString("pl_city"));
					production.addElement("assureur").addAttribute("numeroAgrement", "12005");
					if (rs.getString("arca_code") != null) {

						production.addElement("intermediaire").addAttribute("numeroAgrement",
								rs.getString("arca_code"));
						production.addElement("tauxCommission")
								.addText(String.format("%.2f", rs.getDouble("comm_rate")));
					}
					production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select COUNT(*)+1 pe_order  from arca_requests where AR_PL_INDEX = "
											+ pl_index + " and ar_success = 'Y'")) {
						while (rs2.next()) {

							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", endType.equals("000") ? "S" : "T");

						}

					}

					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");
					// Check this section for static values

					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on,ent_email,ent_cellphone"
											+ " from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {

						while (client.next()) {
							Element personne = souscripteur.addElement("personne")
									.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
									.addAttribute("immatriculation", "non assujetti")
									.addAttribute("paysEtablissement", "CD")
									.addAttribute("personneMorale",
											client.getString("ent_type").equalsIgnoreCase("Individual") ? "false"
													: "true")
									.addAttribute("operation", "ajout");
							if (!(rs.getString("voie") == null && rs.getString("commune") == null
									&& rs.getString("quartier") == null && rs.getString("complement") == null)) {

								Element adresse = personne.addElement("adresse");

								if (rs.getString("voie") != null)
									adresse.addElement("voie").addText(rs.getString("voie"));
								if (rs.getString("commune") != null)
									adresse.addElement("commune").addAttribute("code", rs.getString("commune"));
								if (rs.getString("quartier") != null)
									adresse.addElement("quartier").addText(rs.getString("quartier"));
								if (rs.getString("complement") != null)
									adresse.addElement("complement").addText(rs.getString("complement"));
							}
							if (client.getString("ent_email") == null && client.getString("ent_cellphone") == null) {

								return "Error. Both Email and Phone Cannot be Empty!";
							}

							if (client.getString("ent_cellphone") != null) {
								personne.addElement("telephone").addText(client.getString("ent_cellphone"));
							}
							if (client.getString("ent_email") != null) {
								personne.addElement("email").addText(client.getString("ent_email"));
							}
							// the prenom should be the last name, then nom should be the rest of the name
							String[] nameParts = client.getString("ent_name").split("\\s+");

							// Get the last name
							String lastName = nameParts[nameParts.length - 1];
							// Get the other names
							StringBuilder restOfNames = new StringBuilder();
							for (int i = 0; i < nameParts.length - 1; i++) {
								restOfNames.append(nameParts[i]);
								if (i < nameParts.length - 2) {
									restOfNames.append(" "); // Add space between names
								}
							}

							if (client.getString("ent_type").equalsIgnoreCase("Individual")) {
								personne.addElement("prenom").addText(lastName);
								personne.addElement("nom").addText(restOfNames.toString());
								personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
								personne.addElement("civilite").addAttribute("code", "M.");
							} else {

								personne.addElement("denominationSociale").addText(client.getString("ent_name"));

							}

						}
					}
					double totalOtherCharges = 0.0;
					double totalTax = 0.0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index,dl_risk_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0), "
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code       "
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = '"
											+ sourceTable + "' and dl_pl_index =  " + pl_index + " and dl_end_index = "
											+ pl_end_index + " and dl_risk_index = " + risk_index
											+ " and dl_org_code = " + Settings.orgCode + " GROUP BY "
											+ "  dl_org_code, dl_pl_index,dl_end_index,dl_risk_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}

					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.format("%.0f", totalOtherCharges * 100));
						prime.addElement("taxeValeurAjoutee").addText(String.format("%.0f", totalTax * 100));
					} else {
						/*
						 * System.out.
						 * println("select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
						 * + rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'" +
						 * "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString() +
						 * "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date");
						 */
						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN trunc(rate_fm_date) and trunc(rate_to_date)")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires")
											.addText(String.format("%.0f", totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee")
											.addText(String.format("%.0f", totalTax * 100));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}

					String validVehicle = SubmitMotorPolicy.vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type, "
									+ " NVL(TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight  "
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000 "
									+ " else  0 end),'N/A') weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, "
									+ "  TO_CHAR(nvl(ai_fc_value,0)*100) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A') "
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A') "
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A') "
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A') "
									+ "               ai_model,TO_CHAR(A.AI_REGN_DT,'RRRR-MM-DD') AI_REGN_DT from AI_VEHICLE a,uw_policy_risks b "
									+ "                where  " + "                a.ai_risk_index = b.pl_risk_index "
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX  "
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
									+ risk_index + " and ai_org_code = " + Settings.orgCode);
					if (!(validVehicle.isEmpty())) {

						return validVehicle;

					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(vehicleQuery(pl_index, risk_index))) {
						while (vehicle.next()) {

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", vehicle.getString("ai_regn_no"));
							Element biens = objet.addElement("biens");
							String riskIndex = vehicle.getString("ai_risk_index");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							if (vehicle.getString("cancelled_cert") != null) {

								bien.addAttribute("certificat", vehicle.getString("cancelled_cert"));
							}
							String validateMakeModel = validateMakeModel(vehicle.getString("ai_regn_no"),vehicle.getString("ai_make"),
									vehicle.getString("ai_model"));
							if (!validateMakeModel.equals("Complete")) {
								return validateMakeModel;
							}

							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText(vehicle.getString("AI_REGN_DT")).addAttribute("nom",
									"DMC");

							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_cc")).addAttribute("nom",
									"CYL");

							attributs.addElement("valeur").addText("CD").addAttribute("nom", "PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							if ("CAT04 CAT05".contains(vehicle.getString("ai_vehicle_use")))
								attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
										"PAV");

							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_model").contains("-")
											? vehicle.getString("ai_model").split("-")[1]
											: vehicle.getString("ai_model"))
									.addAttribute("nom", "MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_body_type").equals("4") ? "true" : "false")
									.addAttribute("nom", "REM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fuel_type"))
									.addAttribute("nom", "ENE");
							attributs.addElement("valeur").addText("C/Bandalungwa").addAttribute("nom", "GAR");

							// Driver details
							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select ai_surname||' '||ai_other_names driver_name,ai_licence_no, ai_licence_date from"
													+ " ai_vehicle_drivers where ai_org_code = '" + Settings.orgCode
													+ "' and ai_pl_index = " + pl_index + " and ai_risk_index = "
													+ vehicle.getString("ai_risk_index"));) {
								if (rset.next()) {
									do {
										// check if any of the values are null.
										if (rset.getString("ai_licence_no").isEmpty()
												|| rset.getString("ai_licence_no") == null) {

											return "Error. Driver license for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										} else if (rset.getString("driver_name").isEmpty()
												|| rset.getString("driver_name") == null) {

											return "Error. Driver's name for vehicle " + vehicle.getString("ai_regn_no")
													+ " cannot be empty!";

										} else if (rset.getDate("ai_licence_date") == null) {
											return "Error. Driver's license date of issue for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										}

										// License number

										attributs.addElement("valeur").addText(rset.getString("ai_licence_no"))
												.addAttribute("nom", "NPC");
										attributs.addElement("valeur").addText(rset.getString("driver_name"))
												.addAttribute("nom", "COH");
										// Driving license date
										attributs.addElement("valeur")
												.addText(String.valueOf(rset.getDate("ai_licence_date").toLocalDate()))
												.addAttribute("nom", "DPC");
									} while (rset.next());
								} else {
									/*
									 * Pass static driver values if not available
									 */

									attributs.addElement("valeur").addText("00000000").addAttribute("nom", "NPC");
									attributs.addElement("valeur").addText("NOT APPLICABLE").addAttribute("nom", "COH");
									attributs.addElement("valeur").addText(String.valueOf(LocalDate.now()))
											.addAttribute("nom", "DPC");
								}

							}

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover "
													+ " from VW_POLICY_RISK_COVERS WHERE sv_source = '" + sourceTable
													+ "' AND sv_org_code = " + Settings.orgCode + " and sv_pl_index = "
													+ pl_index + " and sv_end_index = " + pl_end_index
													+ " and sv_risk_index =  " + riskIndex
													+ " and sv_cc_code != '0890' ");) {
								while (rset.next()) {
									// we need to check for IPT cover here, if it is there submit it and reduce the
									// total premium for main cover
									double iptPrem = SubmitMotorPolicy.getIPT(pl_index, pl_end_index, riskIndex,
											rset.getString("sv_cc_code"), sourceTable);
									double yellowCover = SubmitMotorPolicy.getYellowCover(pl_index, pl_end_index,
											riskIndex, sourceTable);
									double tpPrem = SubmitMotorPolicy.getTpPremium(
											vehicle.getString("ai_vehicle_type"), vehicle.getInt("ai_cv"),vehicle.getInt("AI_SEATING_CAPACITY"),vehicle.getInt("ai_weight"),vehicle.getInt("ai_cc"));
									double totalPremium = rset.getDouble("sv_fc_prem") - iptPrem + yellowCover - tpPrem;
									double reducedPremium = 0.0;
									System.err.println("totalPremium " + (totalPremium + tpPrem));
									if (tpPrem == 0 && "0700 0800".contains(rset.getString("sv_cc_code"))) {

										return "Error. Cannot Find TP Tarrif for Vehicle Type "
												+ vehicle.getString("ai_vehicle_type") + " and CV "
												+ vehicle.getString("ai_cv") + " Contact IT";

									}

									// System.err.println(rset.getString("sv_cc_code"));
									if ("TP 0703 0803".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-RC");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										attributs = garantie.addElement("attributs");
										attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
												.addAttribute("nom", "DUR");
										attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if (rset.getString("sv_cc_code").contains("OD")) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-DTA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if ("0700 0800".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("COMP : 12005-090003-AUTO-RC");
										for (int i = 1; i <= 8; i++) {
											String cover = "";
											switch (i) {
											case 1:
												cover = "12005-090003-AUTO-DR";
												break;
											case 2:
												cover = "12005-090003-AUTO-DTA";
												break;
											case 3:
												cover = "12005-090003-AUTO-INC";
												break;
											case 4:
												cover = "12005-090003-AUTO-VA";
												break;
											case 5:
												cover = "12005-090003-AUTO-BG";
												break;
											case 6:
												cover = "12005-090003-AUTO-IOA";
												break;
											case 7:
												cover = "12005-090003-AUTO-TR";
												break;
											case 8:
												cover = "12005-090003-AUTO-RC";
												break;

											default:
												break;
											}

											if (i == 7) {
												// System.out.println("COMP : " + cover);
												System.err.println("comp prem " + (totalPremium - reducedPremium));
												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												attributs = garantie.addElement("attributs");
												attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
														.addAttribute("nom", "DUR");
												attributs.addElement("valeur").addText("false").addAttribute("nom",
														"FRT");
												garantie.addElement("prime").addText(
														String.format("%.0f", (totalPremium - reducedPremium) * 100));
											} else if (i == 8) {

												System.err.println("tp prem" + tpPrem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (tpPrem) * 100));

											}

											else {
												double prem = Math.round((totalPremium) * 10 / 100);
												System.err.println("reduced " + prem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (prem) * 100));
												reducedPremium += Math.round(totalPremium * 10 / 100);

											}

										}

									}
									if (iptPrem > 0) {

										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-IOA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										/*
										 * attributs = garantie.addElement("attributs");
										 * attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
										 * .addAttribute("nom", "DUR");
										 * attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										 */
										garantie.addElement("prime").addText(String.format("%.0f", iptPrem * 100));

									}

								}
							}

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();
	}

	public String buildIncopEndorsementXML(int pl_index, int pl_end_index, int risk_index, String correlationID,
			String documentID) {
		Document policyXML = DocumentHelper.createDocument();
		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD)
				.addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(SubmitMotorPolicy.policyHeaderQuery(pl_index, pl_end_index))) {
				while (rs.next()) {
					production.addElement("ville").addText(rs.getString("pl_city"));
					production.addElement("assureur").addAttribute("numeroAgrement", "12005");
					if (rs.getString("arca_code") != null) {

						production.addElement("intermediaire").addAttribute("numeroAgrement",
								rs.getString("arca_code"));
						production.addElement("tauxCommission")
								.addText(String.format("%.2f", rs.getDouble("comm_rate")));
					}
					production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select COUNT(*)+1 pe_order  from arca_requests where AR_PL_INDEX = "
											+ pl_index + " and ar_success = 'Y'")) {
						while (rs2.next()) {

							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", "I");

						}

					}
					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");
					// Check this section for static values
					// creating a new person

					// Adding person details
					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on,ent_email,ent_cellphone from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
							Element personne = souscripteur.addElement("personne")
									.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
									.addAttribute("immatriculation", "non assujetti")
									.addAttribute("paysEtablissement", "CD")
									.addAttribute("personneMorale",
											client.getString("ent_type").equalsIgnoreCase("Individual") ? "false"
													: "true")
									.addAttribute("operation", "ajout");
							if (!(rs.getString("voie") == null && rs.getString("commune") == null
									&& rs.getString("quartier") == null && rs.getString("complement") == null)) {
								Element adresse = personne.addElement("adresse");

								if (rs.getString("voie") != null)
									adresse.addElement("voie").addText(rs.getString("voie"));
								if (rs.getString("commune") != null)
									adresse.addElement("commune").addAttribute("code", rs.getString("commune"));
								if (rs.getString("quartier") != null)
									adresse.addElement("quartier").addText(rs.getString("quartier"));
								if (rs.getString("complement") != null)
									adresse.addElement("complement").addText(rs.getString("complement"));
							}
							if (client.getString("ent_email") == null && client.getString("ent_cellphone") == null) {

								return "Error. Both Email and Phone Cannot be Empty!";
							}

							if (client.getString("ent_cellphone") != null) {
								personne.addElement("telephone").addText(client.getString("ent_cellphone"));
							}
							if (client.getString("ent_email") != null) {
								personne.addElement("email").addText(client.getString("ent_email"));
							}
							// the prenom should be the last name, then nom should be the rest of the name
							String[] nameParts = client.getString("ent_name").split("\\s+");

							// Get the last name
							String lastName = nameParts[nameParts.length - 1];
							// Get the other names
							StringBuilder restOfNames = new StringBuilder();
							for (int i = 0; i < nameParts.length - 1; i++) {
								restOfNames.append(nameParts[i]);
								if (i < nameParts.length - 2) {
									restOfNames.append(" "); // Add space between names
								}
							}

							if (client.getString("ent_type").equalsIgnoreCase("Individual")) {
								personne.addElement("prenom").addText(lastName);
								personne.addElement("nom").addText(restOfNames.toString());
								personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
								personne.addElement("civilite").addAttribute("code", "M.");
							} else {

								personne.addElement("denominationSociale").addText(client.getString("ent_name"));

							}

						}
					}

					double totalOtherCharges = 0.0;
					double totalTax = 0.0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, dl_risk_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0), "
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code       "
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = '"
											+ sourceTable + "' and dl_pl_index =  " + pl_index + " and dl_end_index = "
											+ pl_end_index + " and dl_risk_index = " + risk_index
											+ " and dl_org_code = " + Settings.orgCode + " GROUP BY "
											+ "  dl_org_code, dl_pl_index,dl_end_index,dl_risk_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.format("%.0f", totalOtherCharges * 100));
						prime.addElement("taxeValeurAjoutee").addText(String.format("%.0f", totalTax * 100));
					} else {

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN trunc(rate_fm_date) and trunc(rate_to_date)")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires")
											.addText(String.format("%.0f", totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee")
											.addText(String.format("%.0f", totalTax * 100));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}
					// Creating the object here

					/*
					 * This is where we need to add the vehicle First we check if there is a new
					 * vehicle by comparing the vehicles in the previous endorsement in uh with what
					 * we currently have in uw. first, we check if all required values are there
					 */
					String validVehicle = SubmitMotorPolicy.vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type, "
									+ " NVL(TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight  "
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000 "
									+ " else  0 end),'N/A') weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, "
									+ "  TO_CHAR(nvl(ai_fc_value,0)*100) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A') "
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A') "
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A') "
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A') "
									+ "               ai_model,TO_CHAR(A.AI_REGN_DT,'RRRR-MM-DD') AI_REGN_DT from AI_VEHICLE a,uw_policy_risks b "
									+ "                where  " + "                a.ai_risk_index = b.pl_risk_index "
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX  "
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
									+ risk_index + " and ai_org_code = " + Settings.orgCode);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(vehicleQuery(pl_index, risk_index))) {
						while (vehicle.next()) {
							String riskIndex = vehicle.getString("ai_risk_index");

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", vehicle.getString("ai_regn_no"));
							Element biens = objet.addElement("biens");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							if (vehicle.getString("cancelled_cert") != null) {

								bien.addAttribute("certificat", vehicle.getString("cancelled_cert"));
							}

							String validateMakeModel = validateMakeModel(vehicle.getString("ai_regn_no"),vehicle.getString("ai_make"),
									vehicle.getString("ai_model"));
							if (!validateMakeModel.equals("Complete")) {
								return validateMakeModel;
							}
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText(vehicle.getString("AI_REGN_DT")).addAttribute("nom",
									"DMC");

							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_cc")).addAttribute("nom",
									"CYL");

							attributs.addElement("valeur").addText("CD").addAttribute("nom", "PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							if ("CAT04 CAT05".contains(vehicle.getString("ai_vehicle_use")))
								attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
										"PAV");
							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_model").contains("-")
											? vehicle.getString("ai_model").split("-")[1]
											: vehicle.getString("ai_model"))
									.addAttribute("nom", "MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_body_type").equals("4") ? "true" : "false")
									.addAttribute("nom", "REM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fuel_type"))
									.addAttribute("nom", "ENE");
							attributs.addElement("valeur").addText("C/Bandalungwa").addAttribute("nom", "GAR");

							// Driver details
							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select ai_surname||' '||ai_other_names driver_name,ai_licence_no, ai_licence_date from"
													+ " ai_vehicle_drivers where ai_org_code = '" + Settings.orgCode
													+ "' and ai_pl_index = " + pl_index + " and ai_risk_index = "
													+ vehicle.getString("ai_risk_index"));) {
								if (rset.next()) {
									do {
										// check if any of the values are null.
										if (rset.getString("ai_licence_no").isEmpty()
												|| rset.getString("ai_licence_no") == null) {

											return "Error. Driver license for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										} else if (rset.getString("driver_name").isEmpty()
												|| rset.getString("driver_name") == null) {

											return "Error. Driver's name for vehicle " + vehicle.getString("ai_regn_no")
													+ " cannot be empty!";

										} else if (rset.getDate("ai_licence_date") == null) {
											return "Error. Driver's license date of issue for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										}

										// License number

										attributs.addElement("valeur").addText(rset.getString("ai_licence_no"))
												.addAttribute("nom", "NPC");
										attributs.addElement("valeur").addText(rset.getString("driver_name"))
												.addAttribute("nom", "COH");
										// Driving license date
										attributs.addElement("valeur")
												.addText(String.valueOf(rset.getDate("ai_licence_date").toLocalDate()))
												.addAttribute("nom", "DPC");
									} while (rset.next());
								} else {
									/*
									 * Pass static driver values if not available
									 */

									attributs.addElement("valeur").addText("00000000").addAttribute("nom", "NPC");
									attributs.addElement("valeur").addText("NOT APPLICABLE").addAttribute("nom", "COH");
									attributs.addElement("valeur").addText(String.valueOf(LocalDate.now()))
											.addAttribute("nom", "DPC");
								}

							}

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover "
													+ " from VW_POLICY_RISK_COVERS WHERE sv_source = '" + sourceTable
													+ "' AND sv_org_code = " + Settings.orgCode + " and sv_pl_index = "
													+ pl_index + " and sv_end_index = " + pl_end_index
													+ " and sv_risk_index =  " + riskIndex
													+ " and sv_cc_code != '0890' ");) {
								while (rset.next()) {
									// we need to check for IPT cover here, if it is there submit it and reduce the
									// total premium for main cover
									double iptPrem = SubmitMotorPolicy.getIPT(pl_index, pl_end_index, riskIndex,
											rset.getString("sv_cc_code"), sourceTable);
									double yellowCover = SubmitMotorPolicy.getYellowCover(pl_index, pl_end_index,
											riskIndex, sourceTable);
									double tpPrem = SubmitMotorPolicy.getTpPremium(
											vehicle.getString("ai_vehicle_type"), vehicle.getInt("ai_cv"),vehicle.getInt("AI_SEATING_CAPACITY"),vehicle.getInt("ai_weight"),vehicle.getInt("ai_cc"));
									double totalPremium = rset.getDouble("sv_fc_prem") - iptPrem + yellowCover - tpPrem;
									double reducedPremium = 0.0;
									System.err.println("totalPremium " + (totalPremium + tpPrem));
									if (tpPrem == 0 && "0700 0800".contains(rset.getString("sv_cc_code"))) {

										return "Error. Cannot Find TP Tarrif for Vehicle Type "
												+ vehicle.getString("ai_vehicle_type") + " and CV "
												+ vehicle.getString("ai_cv") + " Contact IT";

									}

									// System.err.println(rset.getString("sv_cc_code"));
									if ("TP 0703 0803".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-RC");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										attributs = garantie.addElement("attributs");
										attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
												.addAttribute("nom", "DUR");
										attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if (rset.getString("sv_cc_code").contains("OD")) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-DTA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if ("0700 0800".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("COMP : 12005-090003-AUTO-RC");
										for (int i = 1; i <= 8; i++) {
											String cover = "";
											switch (i) {
											case 1:
												cover = "12005-090003-AUTO-DR";
												break;
											case 2:
												cover = "12005-090003-AUTO-DTA";
												break;
											case 3:
												cover = "12005-090003-AUTO-INC";
												break;
											case 4:
												cover = "12005-090003-AUTO-VA";
												break;
											case 5:
												cover = "12005-090003-AUTO-BG";
												break;
											case 6:
												cover = "12005-090003-AUTO-IOA";
												break;
											case 7:
												cover = "12005-090003-AUTO-TR";
												break;
											case 8:
												cover = "12005-090003-AUTO-RC";
												break;

											default:
												break;
											}

											if (i == 7) {
												// System.out.println("COMP : " + cover);
												System.err.println("comp prem " + (totalPremium - reducedPremium));

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												attributs = garantie.addElement("attributs");
												attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
														.addAttribute("nom", "DUR");
												attributs.addElement("valeur").addText("false").addAttribute("nom",
														"FRT");
												garantie.addElement("prime").addText(
														String.format("%.0f", (totalPremium - reducedPremium) * 100));
											} else if (i == 8) {

												System.err.println("tp prem " + tpPrem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (tpPrem) * 100));

											}

											else {
												double prem = Math.round((totalPremium) * 10 / 100);
												System.err.println("reduced " + prem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (prem) * 100));
												reducedPremium += Math.round(totalPremium * 10 / 100);

											}

										}

									}
									if (iptPrem > 0) {

										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-IOA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										/*
										 * attributs = garantie.addElement("attributs");
										 * attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
										 * .addAttribute("nom", "DUR");
										 * attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										 */
										garantie.addElement("prime").addText(String.format("%.0f", iptPrem * 100));

									}

								}
							}

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();
	}

	public String buildExtensionXML(int pl_index, int pl_end_index, int risk_index, String correlationID,
			String documentID) {

		Document policyXML = DocumentHelper.createDocument();
		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD)
				.addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(SubmitMotorPolicy.policyHeaderQuery(pl_index, pl_end_index))) {
				while (rs.next()) {
					production.addElement("ville").addText(rs.getString("pl_city"));
					production.addElement("assureur").addAttribute("numeroAgrement", "12005");
					if (rs.getString("arca_code") != null) {

						production.addElement("intermediaire").addAttribute("numeroAgrement",
								rs.getString("arca_code"));
						production.addElement("tauxCommission")
								.addText(String.format("%.2f", rs.getDouble("comm_rate")));
					}
					production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select COUNT(*)+1 pe_order  from arca_requests where AR_PL_INDEX = "
											+ pl_index + " and ar_success = 'Y'")) {
						while (rs2.next()) {

							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", "P");

						}

					}
					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");

					// Adding person details
					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on,ent_email,ent_cellphone from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
// Check this section for static values
							// creating a new person
							Element personne = souscripteur.addElement("personne")
									.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
									.addAttribute("immatriculation", "non assujetti")
									.addAttribute("paysEtablissement", "CD")
									.addAttribute("personneMorale",
											client.getString("ent_type").equalsIgnoreCase("Individual") ? "false"
													: "true")
									.addAttribute("operation", "ajout");
							if (!(rs.getString("voie") == null && rs.getString("commune") == null
									&& rs.getString("quartier") == null && rs.getString("complement") == null)) {
								Element adresse = personne.addElement("adresse");

								if (rs.getString("voie") != null)
									adresse.addElement("voie").addText(rs.getString("voie"));
								if (rs.getString("commune") != null)
									adresse.addElement("commune").addAttribute("code", rs.getString("commune"));
								if (rs.getString("quartier") != null)
									adresse.addElement("quartier").addText(rs.getString("quartier"));
								if (rs.getString("complement") != null)
									adresse.addElement("complement").addText(rs.getString("complement"));
							}
							if (client.getString("ent_email") == null && client.getString("ent_cellphone") == null) {

								return "Error. Both Email and Phone Cannot be Empty!";
							}

							if (client.getString("ent_cellphone") != null) {
								personne.addElement("telephone").addText(client.getString("ent_cellphone"));
							}
							if (client.getString("ent_email") != null) {
								personne.addElement("email").addText(client.getString("ent_email"));
							}
							// the prenom should be the last name, then nom should be the rest of the name
							String[] nameParts = client.getString("ent_name").split("\\s+");

							// Get the last name
							String lastName = nameParts[nameParts.length - 1];
							// Get the other names
							StringBuilder restOfNames = new StringBuilder();
							for (int i = 0; i < nameParts.length - 1; i++) {
								restOfNames.append(nameParts[i]);
								if (i < nameParts.length - 2) {
									restOfNames.append(" "); // Add space between names
								}
							}

							if (client.getString("ent_type").equalsIgnoreCase("Individual")) {
								personne.addElement("prenom").addText(lastName);
								personne.addElement("nom").addText(restOfNames.toString());
								personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
								personne.addElement("civilite").addAttribute("code", "M.");
							} else {

								personne.addElement("denominationSociale").addText(client.getString("ent_name"));

							}

						}
					}

					double totalOtherCharges = 0.0;
					double totalTax = 0.0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, dl_risk_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0), "
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code       "
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = '"
											+ sourceTable + "' and dl_pl_index =  " + pl_index + " and dl_end_index = "
											+ pl_end_index + " and dl_risk_index = " + risk_index
											+ " and dl_org_code = " + Settings.orgCode + " GROUP BY "
											+ "  dl_org_code, dl_pl_index,dl_end_index,dl_risk_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.format("%.0f", totalOtherCharges * 100));
						prime.addElement("taxeValeurAjoutee").addText(String.format("%.0f", totalTax * 100));
					} else {

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN trunc(rate_fm_date) and trunc(rate_to_date)")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires")
											.addText(String.format("%.0f", totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee")
											.addText(String.format("%.0f", totalTax * 100));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}
					// Creating the object here

					/*
					 * This is where we need to add the vehicle First we check if there is a new
					 * vehicle by comparing the vehicles in the previous endorsement in uh with what
					 * we currently have in uw. first, we check if all required values are there
					 */
					String validVehicle = SubmitMotorPolicy.vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type, "
									+ " NVL(TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight  "
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000 "
									+ " else  0 end),'N/A') weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, "
									+ "  TO_CHAR(nvl(ai_fc_value,0)*100) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A') "
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A') "
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A') "
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A') "
									+ "               ai_model,TO_CHAR(A.AI_REGN_DT,'RRRR-MM-DD') AI_REGN_DT from AI_VEHICLE a,uw_policy_risks b "
									+ "                where  " + "                a.ai_risk_index = b.pl_risk_index "
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX  "
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
									+ risk_index + " and ai_org_code = " + Settings.orgCode);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(vehicleQuery(pl_index, risk_index))) {
						while (vehicle.next()) {
							String riskIndex = vehicle.getString("ai_risk_index");

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", vehicle.getString("ai_regn_no"));
							Element biens = objet.addElement("biens");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							if (vehicle.getString("cancelled_cert") != null) {

								bien.addAttribute("certificat", vehicle.getString("cancelled_cert"));
							}

							String validateMakeModel = validateMakeModel(vehicle.getString("ai_regn_no"),vehicle.getString("ai_make"),
									vehicle.getString("ai_model"));
							if (!validateMakeModel.equals("Complete")) {
								return validateMakeModel;
							}
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText(vehicle.getString("AI_REGN_DT")).addAttribute("nom",
									"DMC");

							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_cc")).addAttribute("nom",
									"CYL");

							attributs.addElement("valeur").addText("CD").addAttribute("nom", "PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							if ("CAT04 CAT05".contains(vehicle.getString("ai_vehicle_use")))
								attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
										"PAV");
							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_model").contains("-")
											? vehicle.getString("ai_model").split("-")[1]
											: vehicle.getString("ai_model"))
									.addAttribute("nom", "MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_body_type").equals("4") ? "true" : "false")
									.addAttribute("nom", "REM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fuel_type"))
									.addAttribute("nom", "ENE");
							attributs.addElement("valeur").addText("C/Bandalungwa").addAttribute("nom", "GAR");

							// Driver details
							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select ai_surname||' '||ai_other_names driver_name,ai_licence_no, ai_licence_date from"
													+ " ai_vehicle_drivers where ai_org_code = '" + Settings.orgCode
													+ "' and ai_pl_index = " + pl_index + " and ai_risk_index = "
													+ vehicle.getString("ai_risk_index"));) {
								if (rset.next()) {
									do {
										// check if any of the values are null.
										if (rset.getString("ai_licence_no").isEmpty()
												|| rset.getString("ai_licence_no") == null) {

											return "Error. Driver license for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										} else if (rset.getString("driver_name").isEmpty()
												|| rset.getString("driver_name") == null) {

											return "Error. Driver's name for vehicle " + vehicle.getString("ai_regn_no")
													+ " cannot be empty!";

										} else if (rset.getDate("ai_licence_date") == null) {
											return "Error. Driver's license date of issue for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										}

										// License number

										attributs.addElement("valeur").addText(rset.getString("ai_licence_no"))
												.addAttribute("nom", "NPC");
										attributs.addElement("valeur").addText(rset.getString("driver_name"))
												.addAttribute("nom", "COH");
										// Driving license date
										attributs.addElement("valeur")
												.addText(String.valueOf(rset.getDate("ai_licence_date").toLocalDate()))
												.addAttribute("nom", "DPC");
									} while (rset.next());
								} else {
									/*
									 * Pass static driver values if not available
									 */

									attributs.addElement("valeur").addText("00000000").addAttribute("nom", "NPC");
									attributs.addElement("valeur").addText("NOT APPLICABLE").addAttribute("nom", "COH");
									attributs.addElement("valeur").addText(String.valueOf(LocalDate.now()))
											.addAttribute("nom", "DPC");
								}

							}

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover "
													+ " from VW_POLICY_RISK_COVERS WHERE sv_source = '" + sourceTable
													+ "' AND sv_org_code = " + Settings.orgCode + " and sv_pl_index = "
													+ pl_index + " and sv_end_index = " + pl_end_index
													+ " and sv_risk_index =  " + riskIndex
													+ " and sv_cc_code != '0890' ");) {
								while (rset.next()) {
									// we need to check for IPT cover here, if it is there submit it and reduce the
									// total premium for main cover
									double iptPrem = SubmitMotorPolicy.getIPT(pl_index, pl_end_index, riskIndex,
											rset.getString("sv_cc_code"), sourceTable);
									double yellowCover = SubmitMotorPolicy.getYellowCover(pl_index, pl_end_index,
											riskIndex, sourceTable);
									double tpPrem = SubmitMotorPolicy.getTpPremium(
											vehicle.getString("ai_vehicle_type"), vehicle.getInt("ai_cv"),vehicle.getInt("AI_SEATING_CAPACITY"),vehicle.getInt("ai_weight"),vehicle.getInt("ai_cc"));
									double totalPremium = rset.getDouble("sv_fc_prem") - iptPrem + yellowCover - tpPrem;
									double reducedPremium = 0.0;
									System.err.println("totalPremium " + (totalPremium + tpPrem));
									if (tpPrem == 0 && "0700 0800".contains(rset.getString("sv_cc_code"))) {

										return "Error. Cannot Find TP Tarrif for Vehicle Type "
												+ vehicle.getString("ai_vehicle_type") + " and CV "
												+ vehicle.getString("ai_cv") + " Contact IT";

									}

									// System.err.println(rset.getString("sv_cc_code"));
									if ("TP 0703 0803".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-RC");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										attributs = garantie.addElement("attributs");
										attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
												.addAttribute("nom", "DUR");
										attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if (rset.getString("sv_cc_code").contains("OD")) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-DTA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if ("0700 0800".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("COMP : 12005-090003-AUTO-RC");
										for (int i = 1; i <= 8; i++) {
											String cover = "";
											switch (i) {
											case 1:
												cover = "12005-090003-AUTO-DR";
												break;
											case 2:
												cover = "12005-090003-AUTO-DTA";
												break;
											case 3:
												cover = "12005-090003-AUTO-INC";
												break;
											case 4:
												cover = "12005-090003-AUTO-VA";
												break;
											case 5:
												cover = "12005-090003-AUTO-BG";
												break;
											case 6:
												cover = "12005-090003-AUTO-IOA";
												break;
											case 7:
												cover = "12005-090003-AUTO-TR";
												break;
											case 8:
												cover = "12005-090003-AUTO-RC";
												break;

											default:
												break;
											}

											if (i == 7) {
												// System.out.println("COMP : " + cover);
												System.err.println("comp prem " + (totalPremium - reducedPremium));
												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												attributs = garantie.addElement("attributs");
												attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
														.addAttribute("nom", "DUR");
												attributs.addElement("valeur").addText("false").addAttribute("nom",
														"FRT");
												garantie.addElement("prime").addText(
														String.format("%.0f", (totalPremium - reducedPremium) * 100));
											} else if (i == 8) {

												System.err.println("tp prem" + tpPrem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (tpPrem) * 100));

											}

											else {
												double prem = Math.round((totalPremium) * 10 / 100);
												System.err.println("reduced " + prem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (prem) * 100));
												reducedPremium += Math.round(totalPremium * 10 / 100);

											}

										}

									}
									if (iptPrem > 0) {

										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-IOA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										/*
										 * attributs = garantie.addElement("attributs");
										 * attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
										 * .addAttribute("nom", "DUR");
										 * attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										 */
										garantie.addElement("prime").addText(String.format("%.0f", iptPrem * 100));

									}

								}
							}

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();

	}

	public String buildRebateEndorsementXML(int pl_index, int pl_end_index, int risk_index, String correlationID,
			String documentID) {

		Document policyXML = DocumentHelper.createDocument();
		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD)
				.addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(SubmitMotorPolicy.policyHeaderQuery(pl_index, pl_end_index))) {
				while (rs.next()) {
					production.addElement("ville").addText(rs.getString("pl_city"));
					production.addElement("assureur").addAttribute("numeroAgrement", "12005");
					if (rs.getString("arca_code") != null) {

						production.addElement("intermediaire").addAttribute("numeroAgrement",
								rs.getString("arca_code"));
						production.addElement("tauxCommission")
								.addText(String.format("%.2f", rs.getDouble("comm_rate")));
					}
					production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select COUNT(*)+1 pe_order  from arca_requests where AR_PL_INDEX = "
											+ pl_index + " and ar_success = 'Y'")) {
						while (rs2.next()) {

							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", "R");

						}

					}
					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");

					// Adding person details
					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on,ent_email,ent_cellphone from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
							// Check this section for static values
							// creating a new person
							Element personne = souscripteur.addElement("personne")
									.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
									.addAttribute("immatriculation", "non assujetti")
									.addAttribute("paysEtablissement", "CD")
									.addAttribute("personneMorale",
											client.getString("ent_type").equalsIgnoreCase("Individual") ? "false"
													: "true")
									.addAttribute("operation", "ajout");
							if (!(rs.getString("voie") == null && rs.getString("commune") == null
									&& rs.getString("quartier") == null && rs.getString("complement") == null)) {
								Element adresse = personne.addElement("adresse");

								if (rs.getString("voie") != null)
									adresse.addElement("voie").addText(rs.getString("voie"));
								if (rs.getString("commune") != null)
									adresse.addElement("commune").addAttribute("code", rs.getString("commune"));
								if (rs.getString("quartier") != null)
									adresse.addElement("quartier").addText(rs.getString("quartier"));
								if (rs.getString("complement") != null)
									adresse.addElement("complement").addText(rs.getString("complement"));
							}
							if (client.getString("ent_email") == null && client.getString("ent_cellphone") == null) {

								return "Error. Both Email and Phone Cannot be Empty!";
							}

							if (client.getString("ent_cellphone") != null) {
								personne.addElement("telephone").addText(client.getString("ent_cellphone"));
							}
							if (client.getString("ent_email") != null) {
								personne.addElement("email").addText(client.getString("ent_email"));
							}
							// the prenom should be the last name, then nom should be the rest of the name
							String[] nameParts = client.getString("ent_name").split("\\s+");

							// Get the last name
							String lastName = nameParts[nameParts.length - 1];
							// Get the other names
							StringBuilder restOfNames = new StringBuilder();
							for (int i = 0; i < nameParts.length - 1; i++) {
								restOfNames.append(nameParts[i]);
								if (i < nameParts.length - 2) {
									restOfNames.append(" "); // Add space between names
								}
							}

							if (client.getString("ent_type").equalsIgnoreCase("Individual")) {
								personne.addElement("prenom").addText(lastName);
								personne.addElement("nom").addText(restOfNames.toString());
								personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
								personne.addElement("civilite").addAttribute("code", "M.");
							} else {

								personne.addElement("denominationSociale").addText(client.getString("ent_name"));

							}

						}
					}

					double totalOtherCharges = 0.0;
					double totalTax = 0.0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, dl_risk_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0), "
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code       "
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = '"
											+ sourceTable + "' and dl_pl_index =  " + pl_index + " and dl_end_index = "
											+ pl_end_index + " and dl_risk_index = " + risk_index
											+ " and dl_org_code = " + Settings.orgCode + " GROUP BY "
											+ "  dl_org_code, dl_pl_index,dl_end_index,dl_risk_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.format("%.0f", totalOtherCharges * 100));
						prime.addElement("taxeValeurAjoutee").addText(String.format("%.0f", totalTax * 100));
					} else {

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN trunc(rate_fm_date) and trunc(rate_to_date)")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires")
											.addText(String.format("%.0f", totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee")
											.addText(String.format("%.0f", totalTax * 100));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}
					// Creating the object here

					/*
					 * This is where we need to add the vehicle First we check if there is a new
					 * vehicle by comparing the vehicles in the previous endorsement in uh with what
					 * we currently have in uw. first, we check if all required values are there
					 */
					String validVehicle = SubmitMotorPolicy.vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type, "
									+ " NVL(TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight  "
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000 "
									+ " else  0 end),'N/A') weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, "
									+ "  TO_CHAR(nvl(ai_fc_value,0)*100) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A') "
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A') "
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A') "
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A') "
									+ "               ai_model,TO_CHAR(A.AI_REGN_DT,'RRRR-MM-DD') AI_REGN_DT from AI_VEHICLE a,uw_policy_risks b "
									+ "                where  " + "                a.ai_risk_index = b.pl_risk_index "
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX  "
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
									+ risk_index + " and ai_org_code = " + Settings.orgCode);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(vehicleQuery(pl_index, risk_index))) {
						while (vehicle.next()) {
							String riskIndex = vehicle.getString("ai_risk_index");

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", vehicle.getString("ai_regn_no"));
							Element biens = objet.addElement("biens");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							if (vehicle.getString("cancelled_cert") != null) {

								bien.addAttribute("certificat", vehicle.getString("cancelled_cert"));
							}

							String validateMakeModel = validateMakeModel(vehicle.getString("ai_regn_no"),vehicle.getString("ai_make"),
									vehicle.getString("ai_model"));
							if (!validateMakeModel.equals("Complete")) {
								return validateMakeModel;
							}
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText(vehicle.getString("AI_REGN_DT")).addAttribute("nom",
									"DMC");

							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_cc")).addAttribute("nom",
									"CYL");

							attributs.addElement("valeur").addText("CD").addAttribute("nom", "PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							if ("CAT04 CAT05".contains(vehicle.getString("ai_vehicle_use")))
								attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
										"PAV");
							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_model").contains("-")
											? vehicle.getString("ai_model").split("-")[1]
											: vehicle.getString("ai_model"))
									.addAttribute("nom", "MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_body_type").equals("4") ? "true" : "false")
									.addAttribute("nom", "REM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fuel_type"))
									.addAttribute("nom", "ENE");
							attributs.addElement("valeur").addText("C/Bandalungwa").addAttribute("nom", "GAR");

							// Driver details
							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select ai_surname||' '||ai_other_names driver_name,ai_licence_no, ai_licence_date from"
													+ " ai_vehicle_drivers where ai_org_code = '" + Settings.orgCode
													+ "' and ai_pl_index = " + pl_index + " and ai_risk_index = "
													+ vehicle.getString("ai_risk_index"));) {
								if (rset.next()) {
									do {
										// check if any of the values are null.
										if (rset.getString("ai_licence_no").isEmpty()
												|| rset.getString("ai_licence_no") == null) {

											return "Error. Driver license for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										} else if (rset.getString("driver_name").isEmpty()
												|| rset.getString("driver_name") == null) {

											return "Error. Driver's name for vehicle " + vehicle.getString("ai_regn_no")
													+ " cannot be empty!";

										} else if (rset.getDate("ai_licence_date") == null) {
											return "Error. Driver's license date of issue for vehicle "
													+ vehicle.getString("ai_regn_no") + " cannot be empty!";

										}

										// License number

										attributs.addElement("valeur").addText(rset.getString("ai_licence_no"))
												.addAttribute("nom", "NPC");
										attributs.addElement("valeur").addText(rset.getString("driver_name"))
												.addAttribute("nom", "COH");
										// Driving license date
										attributs.addElement("valeur")
												.addText(String.valueOf(rset.getDate("ai_licence_date").toLocalDate()))
												.addAttribute("nom", "DPC");
									} while (rset.next());
								} else {
									/*
									 * Pass static driver values if not available
									 */

									attributs.addElement("valeur").addText("00000000").addAttribute("nom", "NPC");
									attributs.addElement("valeur").addText("NOT APPLICABLE").addAttribute("nom", "COH");
									attributs.addElement("valeur").addText(String.valueOf(LocalDate.now()))
											.addAttribute("nom", "DPC");
								}

							}

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover "
													+ " from VW_POLICY_RISK_COVERS WHERE sv_source = '" + sourceTable
													+ "' AND sv_org_code = " + Settings.orgCode + " and sv_pl_index = "
													+ pl_index + " and sv_end_index = " + pl_end_index
													+ " and sv_risk_index =  " + riskIndex
													+ " and sv_cc_code != '0890' ");) {
								while (rset.next()) {
									// we need to check for IPT cover here, if it is there submit it and reduce the
									// total premium for main cover
									double iptPrem = SubmitMotorPolicy.getIPT(pl_index, pl_end_index, riskIndex,
											rset.getString("sv_cc_code"), sourceTable);
									double yellowCover = SubmitMotorPolicy.getYellowCover(pl_index, pl_end_index,
											riskIndex, sourceTable);
									double tpPrem = SubmitMotorPolicy.getTpPremium(
											vehicle.getString("ai_vehicle_type"), vehicle.getInt("ai_cv"),vehicle.getInt("AI_SEATING_CAPACITY"),vehicle.getInt("ai_weight"),vehicle.getInt("ai_cc"));
									double totalPremium = rset.getDouble("sv_fc_prem") - iptPrem + yellowCover - tpPrem;
									double reducedPremium = 0.0;
									System.err.println("totalPremium " + (totalPremium + tpPrem));
									if (tpPrem == 0 && "0700 0800".contains(rset.getString("sv_cc_code"))) {

										return "Error. Cannot Find TP Tarrif for Vehicle Type "
												+ vehicle.getString("ai_vehicle_type") + " and CV "
												+ vehicle.getString("ai_cv") + " Contact IT";

									}

									// System.err.println(rset.getString("sv_cc_code"));
									if ("TP 0703 0803".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-RC");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										attributs = garantie.addElement("attributs");
										attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
												.addAttribute("nom", "DUR");
										attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if (rset.getString("sv_cc_code").contains("OD")) {
										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-DTA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
										garantie.addElement("prime").addText(String.format("%.0f",
												(rset.getDouble("sv_fc_prem") - iptPrem + yellowCover) * 100));
									} else if ("0700 0800".contains(rset.getString("sv_cc_code"))) {
										// System.out.println("COMP : 12005-090003-AUTO-RC");
										for (int i = 1; i <= 8; i++) {
											String cover = "";
											switch (i) {
											case 1:
												cover = "12005-090003-AUTO-DR";
												break;
											case 2:
												cover = "12005-090003-AUTO-DTA";
												break;
											case 3:
												cover = "12005-090003-AUTO-INC";
												break;
											case 4:
												cover = "12005-090003-AUTO-VA";
												break;
											case 5:
												cover = "12005-090003-AUTO-BG";
												break;
											case 6:
												cover = "12005-090003-AUTO-IOA";
												break;
											case 7:
												cover = "12005-090003-AUTO-TR";
												break;
											case 8:
												cover = "12005-090003-AUTO-RC";
												break;

											default:
												break;
											}

											if (i == 7) {
												// System.out.println("COMP : " + cover);
												System.err.println("comp prem " + (totalPremium - reducedPremium));
												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												attributs = garantie.addElement("attributs");
												attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
														.addAttribute("nom", "DUR");
												attributs.addElement("valeur").addText("false").addAttribute("nom",
														"FRT");
												garantie.addElement("prime").addText(
														String.format("%.0f", (totalPremium - reducedPremium) * 100));
											} else if (i == 8) {

												System.err.println("tp prem" + tpPrem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (tpPrem) * 100));

											}

											else {
												double prem = Math.round((totalPremium) * 10 / 100);
												System.err.println("reduced " + prem);

												Element garantie = souscriptions.addElement("garantie")
														.addAttribute("code", cover);
												garantie.addElement("dateEffet").addText(
														String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
												garantie.addElement("dateEcheance").addText(
														String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
												garantie.addElement("prime")
														.addText(String.format("%.0f", (prem) * 100));
												reducedPremium += Math.round(totalPremium * 10 / 100);

											}

										}

									}
									if (iptPrem > 0) {

										// System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-IOA");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										/*
										 * attributs = garantie.addElement("attributs");
										 * attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
										 * .addAttribute("nom", "DUR");
										 * attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										 */
										garantie.addElement("prime").addText(String.format("%.0f", iptPrem * 100));

									}

								}
							}

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();

	}

	public static String vehicleQuery(int pl_index, int risk_index) {

		return "select ai_regn_no,pl_jurisdiction_area,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type, "
				+ " case when ai_weight_uom = 'Kilograme' then ai_weight  "
				+ "when  ai_weight_uom = 'Tonnage' then ai_weight*1000 "
				+ "else  0 end weight,  ai_weight,nvl(ai_seating_capacity,1) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, nvl(ai_fc_value,0)*100 ai_fc_value, nvl(ai_vehicle_type,'N/A') ai_vehicle_type, nvl(ai_vehicle_use,'N/A') "
				+ "              ai_vehicle_use, nvl( ai_body_type,'N/A') "
				+ "              ai_body_type, nvl(ai_make,'N/A') " + "              ai_make, nvl(ai_model,'N/A') "
				+ "              ai_model,TO_CHAR(A.AI_REGN_DT,'RRRR-MM-DD') AI_REGN_DT, nvl(pkg_system_admin.get_system_desc ('AD_COLOR', ai_color),'N/A') "
				+ "              ai_color, (SELECT AR_CERT_NO\r\n" + "  FROM arca_requests\r\n"
				+ " WHERE     ar_request_type = 'CANCELLATION'\r\n" + "       AND ar_success = 'Y'\r\n"
				+ "       AND AR_RISK_INDEX = pl_risk_index\r\n" + "       AND AR_PL_INDEX = pl_pl_index\r\n"
				+ "       AND AR_END_INDEX = pl_end_index\r\n"
				+ "       FETCH FIRST ROW ONLY) cancelled_cert from AI_VEHICLE a,uw_policy_risks b "
				+ "                where  " + "                a.ai_risk_index = b.pl_risk_index "
				+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX  " + "              and AI_PL_INDEX = " + pl_index
				+ " and ai_risk_index = " + risk_index + " and ai_org_code = " + Settings.orgCode;

	}

	public static String validateMakeModel(String regnNo, String make, String model) {
		String response = "Complete";
		try {
			String query = "SELECT CASE WHEN EXISTS (SELECT *  FROM AD_SYSTEM_CODES  WHERE     SYS_TYPE = 'AD_VEHICLE_MAKE'  AND SYS_CODE = '"
					+ make + "'  \r\n"
					+ "  AND sys_flex_01 = 'Arca'  AND sys_grouping IS NULL) THEN 'Y' ELSE 'N' END VALID_MAKE, CASE WHEN EXISTS \r\n"
					+ "(SELECT *  FROM AD_SYSTEM_CODES  WHERE     SYS_TYPE = 'AD_VEHICLE_MAKE'  AND SYS_CODE = '"
					+ model + "'  AND sys_flex_01 = 'Arca'  \r\n" + "  AND sys_grouping = '" + make
					+ "') THEN 'Y' ELSE 'N' END VALID_MODEL FROM DUAL";
			System.err.println(query);
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt5 = oraConn.createStatement();
					ResultSet rs = stmt5.executeQuery(query)) {
				while (rs.next()) {
					if (rs.getString("VALID_MAKE").equals("N")) {
						return "Error. Invalid Vehicle Make For "+regnNo+", Pick Make from Arca's Provided List";
					}

					if (rs.getString("VALID_MODEL").equals("N")) {
						return "Error. Invalid Vehicle Model For "+regnNo+", Pick Model from Arca's Provided List, It must belong to the vehicle make picked above!";
					}

				}

			}
		} catch (Exception e) {
			e.printStackTrace();
			return "Error. " + e.getLocalizedMessage() + " Contact IT";
		}
		return response;
	}
}
