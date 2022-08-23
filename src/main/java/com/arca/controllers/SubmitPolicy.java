package com.arca.controllers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import com.arca.config.Settings;
import com.arca.rabbit.mq.ConnectionUtil;
import com.arca.rabbit.mq.RabbitMQSender;
import com.google.gson.JsonObject;

public class SubmitPolicy {
	private static Connection oraConn = null;
	public static Statement stmt = null;

	public SubmitPolicy() {
		try {

			System.out.println("Connecting To Database");
			oraConn = CreateConnection.getOraConn();
			System.out.println("Database Connected!");

		} catch (Exception e) {
			System.out.println("Errors Connecting to Database\n" + e.getMessage());
		}

	}

	public static String getCurrentUtcTime() {
		return Instant.now().toString();
	}

	public String sendArcaMessage(int pl_index, int pl_end_index, String created_by) {

		JsonObject myResponse = new JsonObject();

		try {
			try (Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"select nvl(max(AR_ENVELOPE_ID)+1,1) correlation_id, nvl(max(AR_DOCUMENT_ID)+1,1) document_id from ARCA_REQUESTS")) {
				while (rs.next()) {

					RabbitMQSender sender = new RabbitMQSender();
					String end_type = "";
					// CHECK THE TYPE OF ENDORSEMENT HERE AND CALL THE RELEVANT METHOD TO CREATE C
					System.out.println("select pe_int_end_code from uw_endorsements where pe_pl_index = " + pl_index
							+ " and pe_end_index = " + pl_end_index + " and pe_org_code = " + Settings.orgCode);
					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from arca_requests where AR_PL_INDEX = "
									+ pl_index + " and ar_end_index = " + pl_end_index + " and ar_success = 'Y'")) {
						if (rs2.next()) {

							myResponse.addProperty("status", "01");
							myResponse.addProperty("statusDescription",
									"This endorsement has already been submitted and has a successful response.");
							return myResponse.toString();

						}
					}

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pe_int_end_code from uw_endorsements where pe_pl_index = "
											+ pl_index + " and pe_end_index = " + pl_end_index + " and pe_org_code = "
											+ Settings.orgCode)) {
						while (rs2.next()) {
							end_type = rs2.getString("pe_int_end_code");
						}
					}

					String requestXML = "";

					if (end_type.equals("000") || end_type.equals("110")) {
						// New business or Renewals
						requestXML = buildPolicyXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));
					} else if (end_type.equals("101")) {
						// Incorporation - Adding to sum insured
						requestXML = buildIncopEndorsementXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));

					} else if (end_type.equals("103")) {
						// Extension - Extending the policy period
						requestXML = buildExtensionXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));

					} else if (end_type.equals("102") || end_type.equals("104")) {
						// Rebate - Reducing the sum insured
						requestXML = buildRebateEndorsementXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));

					} else if (end_type.equals("108")) {
						// Termination - policy termination
						requestXML = buildTerminationXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));

					} else if (end_type.equals("111")) {
						// NTU - Policy not taken up

						requestXML = buildNTUXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));

					} else if (end_type.isEmpty()) {
						// Empty end type
						myResponse.addProperty("status", "01");
						myResponse.addProperty("statusDescription", "Endorsement type not found! ");
						return myResponse.toString();

					} else {

						myResponse.addProperty("status", "01");
						myResponse.addProperty("statusDescription", "No request configured for this endorsement! ");
						return myResponse.toString();

					}
					if (requestXML.startsWith("Error")) {

						myResponse.addProperty("status", "01");
						myResponse.addProperty("statusDescription", requestXML);
						return myResponse.toString();

					}
					System.out.println(requestXML);

					if (sender.sendMessage(requestXML, rs.getString("correlation_id"))) {
						PreparedStatement prepareStatement = oraConn.prepareStatement(
								"INSERT INTO ARCA_REQUESTS (AR_PL_INDEX, AR_END_INDEX, AR_ENVELOPE_ID, AR_DOCUMENT_ID, AR_REQUEST_XML,CREATED_BY )"
										+ " VALUES (?, ?, ?, ?, ?, ?)");
						prepareStatement.setInt(1, pl_index);
						prepareStatement.setInt(2, pl_end_index);
						prepareStatement.setString(3, rs.getString("correlation_id"));
						prepareStatement.setString(4, rs.getString("document_id"));
						prepareStatement.setString(5, requestXML);
						prepareStatement.setString(6, created_by);
						prepareStatement.execute();

						myResponse.addProperty("status", "00");
						myResponse.addProperty("statusDescription", "Details submitted successfuly to ARCA");
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

	public String buildExtensionXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
		try {
			try (Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"SELECT pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE, CREATED_ON,"
									+ "pl_end_no,PL_CUR_CODE,PL_CUR_RATE,PL_FM_DT,PL_TO_DT , round(MONTHS_BETWEEN(PL_TO_DT,PL_FM_DT)) PL_DURATION, nvl(PKG_SYSTEM_ADMIN.get_column_value_three('ALL_ENTITY_ADDRESSES',"
									+ "'ADDR_VALUE','ADDR_AENT_CODE','ADDR_ENT_CODE','ADDR_TYPE',PL_ASSR_AENT_CODE,PL_ASSR_ENT_CODE,'Physical Address'),'N/A') voie from uw_policy where pl_index = "
									+ pl_index + " and PL_END_INDEX = " + pl_end_index + " and pl_org_code = "
									+ Settings.orgCode)) {
				while (rs.next()) {

					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pe_order from uw_endorsements where pe_pl_index = " + pl_index
											+ " and pe_end_index = " + pl_end_index + " and pe_org_code = "
											+ Settings.orgCode);) {
						while (rs2.next()) {
							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", "P");

						}

					}
					if (rs.getString("pl_end_no").equalsIgnoreCase("new")) {

					}

					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");
					// Check this section for static values
					Element personne = souscripteur.addElement("personne")
							.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
							.addAttribute("immatriculation", "non assujetti").addAttribute("paysEtablissement", "CD")
							.addAttribute("personneMorale", "false").addAttribute("operation", "ajout");
					Element adresse = personne.addElement("adresse");
					adresse.addElement("voie").addText(rs.getString("voie"));
					adresse.addElement("commune").addAttribute("code", "C/Gombe");

					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
							personne.addElement("prenom").addText(client.getString("ent_name").split(" ")[0]);
							personne.addElement("nom").addText(client.getString("ent_name"));
							personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
							personne.addElement("civilite").addAttribute("code", "M.");

						}
					}

					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_org_code = "
											+ Settings.orgCode + "  GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					String curCode = rs.getString("PL_CUR_CODE");

					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));
					} else {
						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", "ASS1OBJ1");
							Element biens = objet.addElement("biens");
							String validVehicle = vehicleValidation(
									"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
											+ " TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
											+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
											+ " else  0 end) weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no,\r\n"
											+ "  TO_CHAR(nvl(ai_fc_value,0)) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A')\r\n"
											+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A')\r\n"
											+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A')\r\n"
											+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A')\r\n"
											+ "               ai_model from AI_VEHICLE a,uw_policy_risks b\r\n"
											+ "                where \r\n"
											+ "                a.ai_risk_index = b.pl_risk_index\r\n"
											+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
											+ "              and AI_PL_INDEX = " + pl_index);
							if (!(validVehicle.isEmpty())) {
								return validVehicle;
							}
							try (Statement stmt5 = oraConn.createStatement();

									ResultSet vehicle = stmt5.executeQuery(
											"select ai_regn_no,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
													+ " case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
													+ "when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
													+ "else  0 end weight,  ai_weight,nvl(ai_seating_capacity,1) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, nvl(ai_fc_value,0) ai_fc_value, nvl(ai_vehicle_type,'N/A') ai_vehicle_type, nvl(ai_vehicle_use,'N/A')\r\n"
													+ "              ai_vehicle_use, nvl( ai_body_type,'N/A')\r\n"
													+ "              ai_body_type, nvl(ai_make,'N/A')\r\n"
													+ "              ai_make, nvl(ai_model,'N/A')\r\n"
													+ "              ai_model, nvl(pkg_system_admin.get_system_desc ('AD_COLOR', ai_color),'N/A')\r\n"
													+ "              ai_color from AI_VEHICLE a,uw_policy_risks b\r\n"
													+ "                where \r\n"
													+ "                a.ai_risk_index = b.pl_risk_index\r\n"
													+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
													+ "                and a.AI_ORG_CODE = " + Settings.orgCode
													+ " \r\n" + "              and AI_PL_INDEX = " + pl_index)) {
								while (vehicle.next()) {
//									return returnedBodyType + ":" + returnedMake + ":" + returnedModel;

									Element bien = biens.addElement("bien")
											.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
											.addAttribute("operation", "ajout");
									Element attributs = bien.addElement("attributs");

									attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
											.addAttribute("nom", "TYP");
									attributs.addElement("valeur").addText(vehicle.getString("ai_make"))
											.addAttribute("nom", "MAR");
									// Puissance fiscale
									attributs.addElement("valeur").addText(vehicle.getString("ai_cv"))
											.addAttribute("nom", "PUF");

									// Date of circulation
									attributs.addElement("valeur").addText("2022-01-01").addAttribute("nom", "DMC");

									attributs.addElement("valeur")
											.addText(vehicle.getString("ai_vehicle_use"))
											.addAttribute("nom", "USA");

									attributs.addElement("valeur").addText("CD")
											.addAttribute("nom", "PAY");

									// bodywork
									attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
											.addAttribute("nom", "CAR");
									attributs.addElement("valeur").addText(vehicle.getString("weight"))
											.addAttribute("nom", "PTA");
									attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
											.addAttribute("nom", "PLA");
									attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no"))
											.addAttribute("nom", "IMM");
									attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
											.addAttribute("nom", "CHA");
									attributs.addElement("valeur").addText(vehicle.getString("ai_model"))
											.addAttribute("nom", "MOD");
									attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
											.addAttribute("nom", "ANF");
									attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value"))
											.addAttribute("nom", "VAL");
									attributs.addElement("valeur").addText("false").addAttribute("nom", "REM");
									attributs.addElement("valeur").addText(vehicle.getString("ai_fuel_type"))
											.addAttribute("nom", "ENE");
									attributs.addElement("valeur").addText("C/Bandalungwa").addAttribute("nom", "GAR");

									// Driver details

									try (Statement stmt222 = oraConn.createStatement();
											ResultSet rset = stmt222.executeQuery(
													"select ai_surname||' '||ai_other_names driver_name,ai_licence_no, ai_licence_date from"
															+ " ai_vehicle_drivers where ai_org_code = '"
															+ Settings.orgCode + "' and ai_pl_index = " + pl_index
															+ " and ai_risk_index = "
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

													return "Error. Driver's name for vehicle "
															+ vehicle.getString("ai_regn_no") + " cannot be empty!";

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
														.addText(String
																.valueOf(rset.getDate("ai_licence_date").toLocalDate()))
														.addAttribute("nom", "DPC");
											} while (rset.next());
										} else {
											/*
											 * Pass static driver values if not available
											 */

											attributs.addElement("valeur").addText("00000000").addAttribute("nom",
													"NPC");
											attributs.addElement("valeur").addText("NOT APPLICABLE").addAttribute("nom",
													"COH");
											attributs.addElement("valeur").addText(String.valueOf(LocalDate.now()))
													.addAttribute("nom", "DPC");
										}

									}

								}
							}

							Element souscriptions = biens.addElement("souscriptions");
							Element garantie = souscriptions.addElement("garantie").addAttribute("code",
									"12005-090003-AUTO-DR");
							garantie.addElement("dateEffet")
									.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
							garantie.addElement("dateEcheance")
									.addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

							// check here
							Element attributs = garantie.addElement("attributs");
							attributs.addElement("valeur").addText(rs.getString("PL_DURATION")).addAttribute("nom",
									"DUR");
							attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery("  SELECT DISTINCT\n"
											+ "         sv_org_code,\n" + "         sv_pl_index,\n"
											+ "         sv_end_index,\n"
											+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1)\n"
											+ "             order_by,\n" + "         DECODE (sv_cc_code,\n"
											+ "                 '043', 'Political',\n"
											+ "                 '044', 'Earthquake',\n"
											+ "                 '061', 'Marine',\n" + "                 '065', 'War',\n"
											+ "                 'Premium')\n" + "             premium_type,\n"
											+ "         NVL (\n" + "             SUM (\n"
											+ "                 DECODE (\n" + "                    '" + curCode + "',\n"
											+ "                     NULL, NVL (DECODE ( 'Debit', 'Nil', 0, sv_lc_prem),\n"
											+ "                                0),\n"
											+ "                     NVL (DECODE ( 'Debit', 'Nil', 0, sv_fc_prem), 0))),\n"
											+ "             0)\n" + "             premium2\n"
											+ "    FROM vw_policy_risk_covers\n" + "   WHERE sv_source in 'UW'\n"
											+ "   and sv_pl_index =  '" + pl_index + "'\n" + "   and sv_end_index =  '"
											+ pl_end_index + "' and sv_org_code	 = " + Settings.orgCode + "\n"
											+ "GROUP BY sv_org_code,\n" + "         sv_pl_index,\n"
											+ "         sv_end_index,\n"
											+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1),\n"
											+ "         DECODE (sv_cc_code,\n"
											+ "                 '043', 'Political',\n"
											+ "                 '044', 'Earthquake',\n"
											+ "                 '061', 'Marine',\n" + "                 '065', 'War',\n"
											+ " 'Premium')");) {
								while (rset.next()) {
									garantie.addElement("prime").addText(rset.getString("premium2"));

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

	public String buildNTUXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
		try {
			try (Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"SELECT pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE,CREATED_ON,"
									+ "pl_end_no,PL_CUR_CODE,PL_CUR_RATE,PL_FM_DT,PL_TO_DT , round(MONTHS_BETWEEN(PL_TO_DT,PL_FM_DT)) PL_DURATION, nvl(PKG_SYSTEM_ADMIN.get_column_value_three('ALL_ENTITY_ADDRESSES',"
									+ "'ADDR_VALUE','ADDR_AENT_CODE','ADDR_ENT_CODE','ADDR_TYPE',PL_ASSR_AENT_CODE,PL_ASSR_ENT_CODE,'Physical Address'),'N/A') voie from uw_policy where pl_index = "
									+ pl_index + " and pl_end_index = " + pl_end_index + " and pl_org_code = "
									+ Settings.orgCode)) {
				while (rs.next()) {

					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pe_order from uw_endorsements where pe_pl_index = " + pl_index
											+ " and pe_end_index = " + pl_end_index + " and pe_org_code = "
											+ Settings.orgCode);) {
						while (rs2.next()) {
							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", "A");

						}

					}
					if (rs.getString("pl_end_no").equalsIgnoreCase("new")) {

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
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {

							Element personne = souscripteur.addElement("personne")
									.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
									.addAttribute("immatriculation", "non assujetti")
									.addAttribute("paysEtablissement", "CD").addAttribute("personneMorale", "false")
									.addAttribute("operation", "ajout");
							Element adresse = personne.addElement("adresse");
							adresse.addElement("voie").addText(rs.getString("voie"));
							adresse.addElement("commune").addAttribute("code", "C/Gombe");

							personne.addElement("prenom").addText(client.getString("ent_name").split(" ")[0]);
							personne.addElement("nom").addText(client.getString("ent_name"));
							personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
							personne.addElement("civilite").addAttribute("code", "M.");

						}
					}

					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_org_code = "
											+ Settings.orgCode + "  GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

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

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));
					} else {
						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

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

	// CHECK THIS ONE HERE
	public String buildRebateEndorsementXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();
		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");

		try {
			try (Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(
							"SELECT pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE,CREATED_ON,"
									+ "pl_end_no,PL_CUR_CODE,PL_CUR_RATE,PL_FM_DT,PL_TO_DT , round(MONTHS_BETWEEN(PL_TO_DT,PL_FM_DT)) PL_DURATION, nvl(PKG_SYSTEM_ADMIN.get_column_value_three('ALL_ENTITY_ADDRESSES',"
									+ "'ADDR_VALUE','ADDR_AENT_CODE','ADDR_ENT_CODE','ADDR_TYPE',PL_ASSR_AENT_CODE,PL_ASSR_ENT_CODE,'Physical Address'),'N/A') voie from uw_policy where pl_index = "
									+ pl_index + " and pl_end_index = " + pl_end_index + " and pl_org_code = "
									+ Settings.orgCode)) {
				while (rs.next()) {
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pe_order from uw_endorsements where pe_pl_index = " + pl_index
											+ " and pe_end_index = " + pl_end_index + " and pe_org_code = "
											+ Settings.orgCode);) {
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
					// Check this section for static values
					// creating a new person
					Element personne = souscripteur.addElement("personne")
							.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
							.addAttribute("immatriculation", "non assujetti").addAttribute("paysEtablissement", "CD")
							.addAttribute("personneMorale", "false").addAttribute("operation", "ajout");
					Element adresse = personne.addElement("adresse");
					adresse.addElement("voie").addText(rs.getString("voie"));
					adresse.addElement("commune").addAttribute("code", "C/Gombe");

					// Adding person details
					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
							personne.addElement("prenom").addText(client.getString("ent_name").split(" ")[0]);
							personne.addElement("nom").addText(client.getString("ent_name"));
							personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
							personne.addElement("civilite").addAttribute("code", "M.");

						}
					}

					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + "  and dl_org_code = "
											+ Settings.orgCode + " GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					String curCode = rs.getString("PL_CUR_CODE");
					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));
					} else {

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}
					// Creating the object here
					Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
							.addAttribute("reference", "ASS1OBJ1");
					Element biens = objet.addElement("biens");

					/*
					 * This is where we need to add the vehicle First we check if there is a new
					 * vehicle by comparing the vehicles in the previous endorsement in uh with what
					 * we currently have in uw.
					 */

					// checking if this endorsement is the latest one to determine which table to
					// use
					String tableName = "uw_policy_risks";
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(
									" select case when pe_order = (select max(pe_order) from uw_endorsements  where pe_pl_index = "
											+ pl_index + " and pe_org_code = " + Settings.orgCode + ")\r\n"
											+ "		              then 'uw_policy_risks' else 'uh_policy_risks' end table_name\r\n"
											+ "		 from uw_endorsements where pe_pl_index = " + pl_index
											+ " and pe_end_index = " + pl_end_index + " and pe_org_code ="
											+ Settings.orgCode)) {
						while (vehicle.next()) {
							tableName = vehicle.getString(1);
						}
					}
					String validVehicle = vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
									+ " TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
									+ " else  0 end) weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no,\r\n"
									+ "  TO_CHAR(nvl(ai_fc_value,0)) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A')\r\n"
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A')\r\n"
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A')\r\n"
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A')\r\n"
									+ "               ai_model from AI_VEHICLE a," + tableName + " b\r\n"
									+ "                where \r\n"
									+ "                a.ai_risk_index = b.pl_risk_index\r\n"
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
									+ "              and AI_PL_INDEX = " + pl_index + "\r\n"
									+ "              and AI_ORG_CODE = " + Settings.orgCode + "\r\n"
									+ "              and pl_status = 'Deleted'\r\n"
									+ "              and  pl_end_index = " + pl_end_index);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(
									"select ai_regn_no,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,"
											+ "nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
											+ " case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
											+ "when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
											+ "else  0 end weight,  ai_weight,nvl(ai_seating_capacity,1) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, nvl(ai_fc_value,0) ai_fc_value, nvl(ai_vehicle_type,'N/A') ai_vehicle_type, nvl(ai_vehicle_use,'N/A')\r\n"
											+ "              ai_vehicle_use, nvl( ai_body_type,'N/A')\r\n"
											+ "              ai_body_type, nvl(ai_make,'N/A')\r\n"
											+ "              ai_make, nvl(ai_model,'N/A')\r\n"
											+ "              ai_model, nvl(pkg_system_admin.get_system_desc ('AD_COLOR', ai_color),'N/A')\r\n"
											+ "              ai_color from AI_VEHICLE a," + tableName + " b\r\n"
											+ "                where \r\n"
											+ "                a.ai_risk_index = b.pl_risk_index\r\n"
											+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
											+ "              and AI_PL_INDEX = " + pl_index + "\r\n"
											+ "              and AI_ORG_CODE = " + Settings.orgCode + "\r\n"
											+ "              and pl_status = 'Deleted'\r\n"
											+ "              and  pl_end_index = " + pl_end_index)) {
						while (vehicle.next()) {
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "retrait");
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText("2022-01-01").addAttribute("nom", "DMC");

							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");

							attributs.addElement("valeur").addText("CD").addAttribute("nom",
									"PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_model")).addAttribute("nom",
									"MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur").addText("false").addAttribute("nom", "REM");
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

						}
					}

					Element souscriptions = biens.addElement("souscriptions");
					Element garantie = souscriptions.addElement("garantie").addAttribute("code",
							"12005-090003-AUTO-DR");
					garantie.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					garantie.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					// check here
					Element attributs = garantie.addElement("attributs");
					attributs.addElement("valeur").addText(rs.getString("PL_DURATION")).addAttribute("nom", "DUR");
					attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");

					try (Statement stmt222 = oraConn.createStatement();
							ResultSet rset = stmt222.executeQuery("  SELECT DISTINCT\n" + "         sv_org_code,\n"
									+ "         sv_pl_index,\n" + "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1)\n"
									+ "             order_by,\n" + "         DECODE (sv_cc_code,\n"
									+ "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + "                 'Premium')\n"
									+ "             premium_type,\n" + "         NVL (\n" + "             SUM (\n"
									+ "                 DECODE (\n" + "                    '" + curCode + "',\n"
									+ "                     NULL, NVL (DECODE ( 'Debit', 'Nil', 0, sv_lc_prem),\n"
									+ "                                0),\n"
									+ "                     NVL (DECODE ( 'Debit', 'Nil', 0, sv_fc_prem), 0))),\n"
									+ "             0)\n" + "             premium2\n"
									+ "    FROM vw_policy_risk_covers\n" + "   WHERE sv_source in 'UW'\n"
									+ "   and sv_pl_index =  '" + pl_index + "'\n" + "   and sv_end_index =  '"
									+ pl_end_index + "' and sv_pl_index = " + Settings.orgCode
									+ " GROUP BY sv_org_code,\n" + "         sv_pl_index,\n"
									+ "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1),\n"
									+ "         DECODE (sv_cc_code,\n" + "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + " 'Premium')");) {
						while (rset.next()) {
							garantie.addElement("prime").addText(rset.getString("premium2"));

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();

	}

	public String buildIncopEndorsementXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();
		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");

		try {
			try (Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(
							"SELECT pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE,CREATED_ON,"
									+ "pl_end_no,PL_CUR_CODE,PL_CUR_RATE,PL_FM_DT,PL_TO_DT , round(MONTHS_BETWEEN(PL_TO_DT,PL_FM_DT)) PL_DURATION, nvl(PKG_SYSTEM_ADMIN.get_column_value_three('ALL_ENTITY_ADDRESSES',"
									+ "'ADDR_VALUE','ADDR_AENT_CODE','ADDR_ENT_CODE','ADDR_TYPE',PL_ASSR_AENT_CODE,PL_ASSR_ENT_CODE,'Physical Address'),'N/A') voie from uw_policy where pl_index = "
									+ pl_index + " and pl_end_index = " + pl_end_index + " and pl_org_code = "
									+ Settings.orgCode)) {
				while (rs.next()) {
					int pe_order = 0;
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pe_order from uw_endorsements where pe_pl_index = " + pl_index
											+ " and pe_end_index = " + pl_end_index);) {
						while (rs2.next()) {
							pe_order = rs2.getInt("pe_order");
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
					Element personne = souscripteur.addElement("personne")
							.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
							.addAttribute("immatriculation", "non assujetti").addAttribute("paysEtablissement", "CD")
							.addAttribute("personneMorale", "false").addAttribute("operation", "ajout");
					Element adresse = personne.addElement("adresse");
					adresse.addElement("voie").addText(rs.getString("voie"));
					adresse.addElement("commune").addAttribute("code", "C/Gombe");

					// Adding person details
					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
							personne.addElement("prenom").addText(client.getString("ent_name").split(" ")[0]);
							personne.addElement("nom").addText(client.getString("ent_name"));
							personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
							personne.addElement("civilite").addAttribute("code", "M.");

						}
					}

					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_org_code = "
											+ Settings.orgCode + " GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					String curCode = rs.getString("PL_CUR_CODE");
					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));
					} else {

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}
					// Creating the object here
					Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
							.addAttribute("reference", "ASS1OBJ1");
					Element biens = objet.addElement("biens");

					/*
					 * This is where we need to add the vehicle First we check if there is a new
					 * vehicle by comparing the vehicles in the previous endorsement in uh with what
					 * we currently have in uw. first, we check if all required values are there
					 */
					String validVehicle = vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
									+ " TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
									+ " else  0 end) weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no,\r\n"
									+ "  TO_CHAR(nvl(ai_fc_value,0)) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A')\r\n"
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A')\r\n"
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A')\r\n"
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A')\r\n"
									+ "               ai_model from AI_VEHICLE a,uw_policy_risks b\r\n"
									+ "                where \r\n"
									+ "                a.ai_risk_index = b.pl_risk_index\r\n"
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
									+ "              and AI_PL_INDEX = " + pl_index + "\r\n"
									+ "              and AI_ORG_CODE = " + Settings.orgCode + "\r\n"
									+ "              and a.ai_risk_index not in (select pl_risk_index from uh_policy_risks where pl_pl_index = "
									+ pl_index + " and pl_end_index = \r\n"
									+ "              (select pe_end_index from uw_endorsements where pe_pl_index = "
									+ pl_index + " and pe_order = " + (pe_order - 1) + "))");
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(
									"select ai_regn_no,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
											+ " case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
											+ "when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
											+ "else  0 end weight,  ai_weight,nvl(ai_seating_capacity,1) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, nvl(ai_fc_value,0) ai_fc_value, nvl(ai_vehicle_type,'N/A') ai_vehicle_type, nvl(ai_vehicle_use,'N/A')\r\n"
											+ "              ai_vehicle_use, nvl( ai_body_type,'N/A')\r\n"
											+ "              ai_body_type, nvl(ai_make,'N/A')\r\n"
											+ "              ai_make, nvl(ai_model,'N/A')\r\n"
											+ "              ai_model, nvl(pkg_system_admin.get_system_desc ('AD_COLOR', ai_color),'N/A')\r\n"
											+ "              ai_color from AI_VEHICLE a,uw_policy_risks b\r\n"
											+ "                where \r\n"
											+ "                a.ai_risk_index = b.pl_risk_index\r\n"
											+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
											+ "              and AI_PL_INDEX = " + pl_index + "\r\n"
											+ "              and AI_ORG_CODE = " + Settings.orgCode + "\r\n"
											+ "              and a.ai_risk_index not in (select pl_risk_index from uh_policy_risks where pl_pl_index = "
											+ pl_index + " and pl_end_index = \r\n"
											+ "              (select pe_end_index from uw_endorsements where pe_pl_index = "
											+ pl_index + " and pe_order = " + (pe_order - 1) + "))")) {
						while (vehicle.next()) {
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText("2022-01-01").addAttribute("nom", "DMC");

							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");

							attributs.addElement("valeur").addText("CD").addAttribute("nom",
									"PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_model")).addAttribute("nom",
									"MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur").addText("false").addAttribute("nom", "REM");
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

						}
					}

					Element souscriptions = biens.addElement("souscriptions");
					Element garantie = souscriptions.addElement("garantie").addAttribute("code",
							"12005-090003-AUTO-DR");
					garantie.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					garantie.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					// check here
					Element attributs = garantie.addElement("attributs");
					attributs.addElement("valeur").addText(rs.getString("PL_DURATION")).addAttribute("nom", "DUR");
					attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");

					try (Statement stmt222 = oraConn.createStatement();
							ResultSet rset = stmt222.executeQuery("  SELECT DISTINCT\n" + "         sv_org_code,\n"
									+ "         sv_pl_index,\n" + "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1)\n"
									+ "             order_by,\n" + "         DECODE (sv_cc_code,\n"
									+ "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + "                 'Premium')\n"
									+ "             premium_type,\n" + "         NVL (\n" + "             SUM (\n"
									+ "                 DECODE (\n" + "                    '" + curCode + "',\n"
									+ "                     NULL, NVL (DECODE ( 'Debit', 'Nil', 0, sv_lc_prem),\n"
									+ "                                0),\n"
									+ "                     NVL (DECODE ( 'Debit', 'Nil', 0, sv_fc_prem), 0))),\n"
									+ "             0)\n" + "             premium2\n"
									+ "    FROM vw_policy_risk_covers\n" + "   WHERE sv_source in 'UW'\n"
									+ "   and sv_pl_index =  '" + pl_index + "'\n" + "   and sv_end_index =  '"
									+ pl_end_index + "'  and sv_org_code=  " + Settings.orgCode
									+ " GROUP BY sv_org_code,\n" + "         sv_pl_index,\n"
									+ "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1),\n"
									+ "         DECODE (sv_cc_code,\n" + "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + " 'Premium')");) {
						while (rset.next()) {
							garantie.addElement("prime").addText(rset.getString("premium2"));

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();
	}

	public String buildTerminationXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");
		try {
			try (Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"SELECT pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE,CREATED_ON,"
									+ "pl_end_no,PL_CUR_CODE,PL_CUR_RATE,PL_FM_DT,PL_TO_DT , round(MONTHS_BETWEEN(PL_TO_DT,PL_FM_DT)) PL_DURATION, nvl(PKG_SYSTEM_ADMIN.get_column_value_three('ALL_ENTITY_ADDRESSES',"
									+ "'ADDR_VALUE','ADDR_AENT_CODE','ADDR_ENT_CODE','ADDR_TYPE',PL_ASSR_AENT_CODE,PL_ASSR_ENT_CODE,'Physical Address'),'N/A') voie from uw_policy where pl_index = "
									+ pl_index + " and pl_end_index = " + pl_end_index)) {
				while (rs.next()) {

					String curCode = rs.getString("PL_CUR_CODE");

					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pe_order from uw_endorsements where pe_pl_index = " + pl_index
											+ " and pe_end_index = " + pl_end_index + " and pe_org_code = "
											+ Settings.orgCode);) {
						while (rs2.next()) {
							production.addElement("numeroAvenant").addText(rs2.getString("pe_order"))
									.addAttribute("type", "X");

						}

					}

					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");
					// Check this section for static values
					Element personne = souscripteur.addElement("personne")
							.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
							.addAttribute("immatriculation", "non assujetti").addAttribute("paysEtablissement", "CD")
							.addAttribute("personneMorale", "false").addAttribute("operation", "ajout");
					Element adresse = personne.addElement("adresse");
					adresse.addElement("voie").addText(rs.getString("voie"));
					adresse.addElement("commune").addAttribute("code", "C/Gombe");

					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {
						while (client.next()) {
							personne.addElement("prenom").addText(client.getString("ent_name").split(" ")[0]);
							personne.addElement("nom").addText(client.getString("ent_name"));
							personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
							personne.addElement("civilite").addAttribute("code", "M.");

						}
					}

					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_org_code = "
											+ Settings.orgCode + "  GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

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

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));
					} else {
						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}

					// Add the section here

					Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
							.addAttribute("reference", "ASS1OBJ1");
					Element biens = objet.addElement("biens");

					Element souscriptions = biens.addElement("souscriptions");
					Element garantie = souscriptions.addElement("garantie").addAttribute("code",
							"12005-090003-AUTO-DR");
					garantie.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					garantie.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rset = stmt2.executeQuery("  SELECT DISTINCT\n" + "         sv_org_code,\n"
									+ "         sv_pl_index,\n" + "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1)\n"
									+ "             order_by,\n" + "         DECODE (sv_cc_code,\n"
									+ "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + "                 'Premium')\n"
									+ "             premium_type,\n" + "         NVL (\n" + "             SUM (\n"
									+ "                 DECODE (\n" + "                    '" + curCode + "',\n"
									+ "                     NULL, NVL (DECODE ( 'Debit', 'Nil', 0, sv_lc_prem),\n"
									+ "                                0),\n"
									+ "                     NVL (DECODE ( 'Debit', 'Nil', 0, sv_fc_prem), 0))),\n"
									+ "             0)\n" + "             premium2\n"
									+ "    FROM vw_policy_risk_covers\n" + "   WHERE sv_source in 'UW'\n"
									+ "   and sv_pl_index =  '" + pl_index + "'\n" + "   and sv_end_index =  '"
									+ pl_end_index + "' and sv_org_code = " + Settings.orgCode
									+ "GROUP BY sv_org_code,\n" + "         sv_pl_index,\n" + "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1),\n"
									+ "         DECODE (sv_cc_code,\n" + "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + " 'Premium')")) {
						while (rset.next()) {
							garantie.addElement("prime").addText(rset.getString("premium2"));

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();
	}

	public String buildPolicyXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ConnectionUtil.USERNAME)
				.addAttribute("motDePasse", ConnectionUtil.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");

		try {
			try (Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(
							"SELECT pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE,CREATED_ON,"
									+ "pl_end_no,PL_CUR_CODE,PL_CUR_RATE,PL_FM_DT,PL_TO_DT , round(MONTHS_BETWEEN(PL_TO_DT,PL_FM_DT)) PL_DURATION, nvl(PKG_SYSTEM_ADMIN.get_column_value_three('ALL_ENTITY_ADDRESSES',"
									+ "'ADDR_VALUE','ADDR_AENT_CODE','ADDR_ENT_CODE','ADDR_TYPE',PL_ASSR_AENT_CODE,PL_ASSR_ENT_CODE,'Physical Address'),'N/A') voie from uw_policy where pl_index = "
									+ pl_index + " and pl_end_index = " + pl_end_index + " and pl_org_code = "
									+ Settings.orgCode)) {
				while (rs.next()) {

					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					production.addElement("numeroAvenant").addText("1").addAttribute("type", "S");

					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element souscripteur = production.addElement("souscripteur");
					// Check this section for static values
					Element personne = souscripteur.addElement("personne")
							.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
							.addAttribute("immatriculation", "non assujetti").addAttribute("paysEtablissement", "CD")
							.addAttribute("personneMorale", "false").addAttribute("operation", "ajout");
					Element adresse = personne.addElement("adresse");
					adresse.addElement("voie").addText(rs.getString("voie"));
					adresse.addElement("commune").addAttribute("code", "C/Gombe");

					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {

						while (client.next()) {
							personne.addElement("prenom").addText(client.getString("ent_name").split(" ")[0]);
							personne.addElement("nom").addText(client.getString("ent_name"));
							personne.addElement("lieuNaissance").addText("Kinshasa").addAttribute("codePays", "CD");
							personne.addElement("civilite").addAttribute("code", "M.");

						}
					}
					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet client = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_org_code = "
											+ Settings.orgCode + " GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

						while (client.next()) {
							if (client.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += client.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += client.getDouble("dl_charge_amnt");
							}

						}
					}
					String curCode = rs.getString("PL_CUR_CODE");

					if (rs.getString("PL_CUR_CODE").equalsIgnoreCase("CDF")) {
						Element prime = production.addElement("prime")
								.addAttribute("devise", rs.getString("PL_CUR_CODE"))
								.addAttribute("taux", rs.getString("PL_CUR_RATE"));

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));
					} else {
						System.out.println("select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
								+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'" + "  AND TO_DATE('"
								+ rs.getDate("PL_GL_DATE").toLocalDate().toString()
								+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date");

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet client = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (client.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", client.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}

					Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
							.addAttribute("reference", "ASS1OBJ1");
					Element biens = objet.addElement("biens");
					String validVehicle = vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
									+ " TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
									+ " else  0 end) weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no,\r\n"
									+ "  TO_CHAR(nvl(ai_fc_value,0)) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A')\r\n"
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A')\r\n"
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A')\r\n"
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A')\r\n"
									+ "               ai_model from AI_VEHICLE a,uw_policy_risks b\r\n"
									+ "                where \r\n"
									+ "                a.ai_risk_index = b.pl_risk_index\r\n"
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_org_code = "
									+ Settings.orgCode);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(
									"select ai_regn_no,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
											+ " case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
											+ "when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
											+ "else  0 end weight,  ai_weight,nvl(ai_seating_capacity,1) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no, nvl(ai_fc_value,0) ai_fc_value, nvl(ai_vehicle_type,'N/A') ai_vehicle_type, nvl(ai_vehicle_use,'N/A')\r\n"
											+ "              ai_vehicle_use, nvl( ai_body_type,'N/A')\r\n"
											+ "              ai_body_type, nvl(ai_make,'N/A')\r\n"
											+ "              ai_make, nvl(ai_model,'N/A')\r\n"
											+ "              ai_model, nvl(pkg_system_admin.get_system_desc ('AD_COLOR', ai_color),'N/A')\r\n"
											+ "              ai_color from AI_VEHICLE a,uw_policy_risks b\r\n"
											+ "                where \r\n"
											+ "                a.ai_risk_index = b.pl_risk_index\r\n"
											+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
											+ "              and AI_PL_INDEX = " + pl_index + " and ai_org_code = "
											+ Settings.orgCode)) {
						while (vehicle.next()) {
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make")).addAttribute("nom",
									"MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText("2022-01-01").addAttribute("nom", "DMC");

							attributs.addElement("valeur")
									.addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");

							attributs.addElement("valeur").addText("CD").addAttribute("nom",
									"PAY");

							// bodywork
							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_type"))
									.addAttribute("nom", "CAR");
							attributs.addElement("valeur").addText(vehicle.getString("weight")).addAttribute("nom",
									"PTA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_seating_capacity"))
									.addAttribute("nom", "PLA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_regn_no")).addAttribute("nom",
									"IMM");
							attributs.addElement("valeur").addText(vehicle.getString("ai_chassis_no"))
									.addAttribute("nom", "CHA");
							attributs.addElement("valeur").addText(vehicle.getString("ai_model")).addAttribute("nom",
									"MOD");
							attributs.addElement("valeur").addText(vehicle.getString("AI_MANUF_YEAR"))
									.addAttribute("nom", "ANF");
							attributs.addElement("valeur").addText(vehicle.getString("ai_fc_value")).addAttribute("nom",
									"VAL");
							attributs.addElement("valeur").addText("false").addAttribute("nom", "REM");
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

						}
					}

					Element souscriptions = biens.addElement("souscriptions");
					Element garantie = souscriptions.addElement("garantie").addAttribute("code",
							"12005-090003-AUTO-DR");
					garantie.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					garantie.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					Element attributs = garantie.addElement("attributs");
					attributs.addElement("valeur").addText(rs.getString("PL_DURATION")).addAttribute("nom", "DUR");
					attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");

					try (Statement stmt222 = oraConn.createStatement();

							ResultSet rset = stmt222.executeQuery("  SELECT DISTINCT\n" + "         sv_org_code,\n"
									+ "         sv_pl_index,\n" + "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1)\n"
									+ "             order_by,\n" + "         DECODE (sv_cc_code,\n"
									+ "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + "                 'Premium')\n"
									+ "             premium_type,\n" + "         NVL (\n" + "             SUM (\n"
									+ "                 DECODE (\n" + "                    '" + curCode + "',\n"
									+ "                     NULL, NVL (DECODE ( 'Debit', 'Nil', 0, sv_lc_prem),\n"
									+ "                                0),\n"
									+ "                     NVL (DECODE ( 'Debit', 'Nil', 0, sv_fc_prem), 0))),\n"
									+ "             0)\n" + "             premium2\n"
									+ "    FROM vw_policy_risk_covers\n" + "   WHERE sv_source in 'UW'\n"
									+ "   and sv_pl_index =  '" + pl_index + "'\n" + "   and sv_end_index =  '"
									+ pl_end_index + "' and sv_org_code = " + Settings.orgCode
									+ " GROUP BY sv_org_code,\n" + "         sv_pl_index,\n"
									+ "         sv_end_index,\n"
									+ "         DECODE (sv_cc_code,  '043', 3,  '044', 2,  '061', 4,  '065', 5,  1),\n"
									+ "         DECODE (sv_cc_code,\n" + "                 '043', 'Political',\n"
									+ "                 '044', 'Earthquake',\n" + "                 '061', 'Marine',\n"
									+ "                 '065', 'War',\n" + " 'Premium')");) {
						while (rset.next()) {
							garantie.addElement("prime").addText(rset.getString("premium2"));

						}
					}

				}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		}
		return policyXML.asXML();
	}

	public String vehicleValidation(String query) {
		try {

			try (Statement stmt222 = oraConn.createStatement();

					ResultSet rset = stmt222.executeQuery("select * from ( " + query + ") \r\n"
							+ "               UNPIVOT\r\n" + "(\r\n" + "  col_value\r\n"
							+ "  FOR COL IN(AI_VEHICLE_USE,AI_CV,AI_MANUF_YEAR,AI_FUEL_TYPE,WEIGHT,AI_SEATING_CAPACITY,AI_CHASSIS_NO,AI_FC_VALUE,AI_BODY_TYPE,AI_MAKE,AI_MODEL)\r\n"
							+ ")");) {
				while (rset.next()) {
					if (rset.getString("COL_VALUE").equals("N/A")) {
						return "Error. " + rset.getString("COL") + " is empty for vehicle "
								+ rset.getString("AI_REGN_NO");
					}
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		return "";
	}

}
