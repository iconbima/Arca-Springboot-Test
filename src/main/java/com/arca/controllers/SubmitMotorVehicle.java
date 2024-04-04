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

	public String sendArcaMessage(int pl_index, int pl_end_index, int risk_index, String created_by) {

		JsonObject myResponse = new JsonObject();
		boolean firstRequest = true;

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"select nvl(max(AR_ENVELOPE_ID)+1,1) correlation_id, nvl(max(AR_DOCUMENT_ID)+1,1) document_id from ARCA_REQUESTS")) {
				while (rs.next()) {

					RabbitMQSender sender = new RabbitMQSender();
					String end_type = "";
					// CHECK THE TYPE OF ENDORSEMENT HERE AND CALL THE RELEVANT METHOD TO CREATE XML
					// REQUEST

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from arca_requests where AR_PL_INDEX = "
									+ pl_index + " and ar_end_index = " + pl_end_index + " and ar_risk_index = "
									+ risk_index + "  and ar_success = 'Y'")) {
						if (rs2.next()) {

							myResponse.addProperty("status", "-1");
							myResponse.addProperty("statusDescription",
									"This vehicle has already been submitted and has a successful response.");
							return myResponse.get("statusDescription").toString();

						}
					}

					// Check if this is the first request, if not then we will send an addition
					// endorsement
					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from arca_requests where AR_PL_INDEX = "
									+ pl_index + " and ar_end_index = " + pl_end_index + " and ar_success = 'Y'")) {
						if (rs2.next()) {
							firstRequest = false;

						}
					}

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2
									.executeQuery("select pl_end_internal_code from uw_policy where pl_index = "
											+ pl_index + " and pl_end_index = " + pl_end_index + " and pl_org_code = "
											+ Settings.orgCode)) {
						while (rs2.next()) {
							end_type = rs2.getString("pl_end_internal_code");
						}
					}

					String requestXML = "";

					// we need to do a renewal endorsement
					if (end_type.equals("000") && firstRequest) {
						// New business or Renewals
						requestXML = buildPolicyXML(pl_index, pl_end_index, risk_index, rs.getString("correlation_id"),
								rs.getString("document_id"));
					} else if (end_type.equals("101") || (end_type.equals("000") && !firstRequest)) {
						// Incorporation - Adding to sum insured
						requestXML = buildIncopEndorsementXML(pl_index, pl_end_index,risk_index, rs.getString("correlation_id"),
								rs.getString("document_id"));

					} else if (end_type.isEmpty()) {
						// Empty end type
						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "Endorsement type not found! ");
						return myResponse.get("statusDescription").toString();

					} else {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "No request configured for this endorsement! ");
						return myResponse.get("statusDescription").toString();

					}
					if (requestXML.startsWith("Error")) {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", requestXML);
						return myResponse.get("statusDescription").toString();

					}
					System.out.println(requestXML);

					
					
					
					if (sender.sendMessage(requestXML, rs.getString("correlation_id"))) {
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

						myResponse.addProperty("status", "00");
						myResponse.addProperty("statusDescription", "Details submitted successfuly to ARCA");
					} else {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "Message couldnt be sent ");

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
			String documentID) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD)
				.addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt22 = oraConn.createStatement();

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
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index,dl_risk_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_risk_index = "
											+ risk_index + " and dl_org_code = " + Settings.orgCode + " GROUP BY\r\n"
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

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges * 100));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax * 100));
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

									prime.addElement("fraisAccessoires")
											.addText(String.valueOf(totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax * 100));

								} while (client.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}

					String validVehicle = SubmitMotorPolicy.vehicleValidation(
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
									+ " NVL(TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
									+ " else  0 end),'N/A') weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no,\r\n"
									+ "  TO_CHAR(nvl(ai_fc_value,0)) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A')\r\n"
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A')\r\n"
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A')\r\n"
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A')\r\n"
									+ "               ai_model from AI_VEHICLE a,uw_policy_risks b\r\n"
									+ "                where \r\n"
									+ "                a.ai_risk_index = b.pl_risk_index\r\n"
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
									+ risk_index + " and ai_org_code = " + Settings.orgCode);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(
									"select ai_regn_no,pl_jurisdiction_area,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
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
											+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
											+ risk_index + " and ai_org_code = " + Settings.orgCode)) {
						while (vehicle.next()) {

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", vehicle.getString("ai_regn_no"));
							Element biens = objet.addElement("biens");
							String riskIndex = vehicle.getString("ai_risk_index");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make").split("-")[0])
									.addAttribute("nom", "MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText("2022-01-01").addAttribute("nom", "DMC");

							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");

							attributs.addElement("valeur").addText("CD").addAttribute("nom", "PAY");

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
							attributs.addElement("valeur").addText(vehicle.getString("ai_model").split("-")[0])
									.addAttribute("nom", "MOD");
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

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover\r\n"
													+ " from UW_POLICY_RISK_COVERS where sv_org_code = "
													+ Settings.orgCode + " and sv_pl_index = " + pl_index
													+ " and sv_risk_index =  " + riskIndex);) {
								while (rset.next()) {
									String arcaCover = "";
									System.err.println(rset.getString("sv_cc_code"));
									if (rset.getString("sv_cc_code").contains("TP")) {
										arcaCover = "TP";
										System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-DR");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										attributs = garantie.addElement("attributs");
										attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
												.addAttribute("nom", "DUR");
										attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										garantie.addElement("prime")
												.addText(String.valueOf(rset.getInt("sv_fc_prem") * 100));
									} else if ("0700 0800".contains(rset.getString("sv_cc_code"))) {
										System.out.println("COMP : 12005-090003-AUTO-RC");
										arcaCover = "COMP";
										for (int i = 1; i <= 7; i++) {
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

											default:
												break;
											}

											if (i == 7) {
												System.out.println("COMP : " + cover);

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
												garantie.addElement("prime")
														.addText(String.valueOf(rset.getInt("sv_fc_prem") * 100));
											}

										}

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
	
	
	
	public String buildIncopEndorsementXML(int pl_index, int pl_end_index,int risk_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();
		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD).addAttribute("timestamp", SubmitMotorPolicy.getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090003");

		try {
			try (
					Connection oraConn = CreateConnection.getOraConn();
					Statement stmt22 = oraConn.createStatement();

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
							ResultSet rs2 = stmt2.executeQuery("select COUNT(*)+1 pe_order  from arca_requests where AR_PL_INDEX = "
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
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, dl_risk_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_risk_index = "+risk_index+" and dl_org_code = "
											+ Settings.orgCode + " GROUP BY\r\n"
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

						prime.addElement("fraisAccessoires").addText(String.valueOf(totalOtherCharges * 100));
						prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax * 100));
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

									prime.addElement("fraisAccessoires")
											.addText(String.valueOf(totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax * 100));

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
							"select ai_regn_no,ai_risk_index, nvl(ai_owner,'N/A')ai_owner, TO_CHAR(nvl(ai_cv,0)) ai_cv ,TO_CHAR( nvl(AI_MANUF_YEAR,2000))AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
									+ " NVL(TO_CHAR( case when ai_weight_uom = 'Kilograme' then ai_weight \r\n"
									+ " when  ai_weight_uom = 'Tonnage' then ai_weight*1000\r\n"
									+ " else  0 end),'N/A') weight,TO_CHAR(nvl(ai_seating_capacity,1)) ai_seating_capacity, nvl(ai_chassis_no,'N/A') ai_chassis_no,\r\n"
									+ "  TO_CHAR(nvl(ai_fc_value,0)) ai_fc_value, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_USE', ai_vehicle_use),'N/A')\r\n"
									+ "               ai_vehicle_use, nvl( pkg_system_admin.get_system_desc ('AD_VHBODY_TYPE', ai_body_type),'N/A')\r\n"
									+ "               ai_body_type, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_make),'N/A')\r\n"
									+ "               ai_make, nvl(pkg_system_admin.get_system_desc ('AD_VEHICLE_MAKE', ai_model),'N/A')\r\n"
									+ "               ai_model from AI_VEHICLE a,uw_policy_risks b\r\n"
									+ "                where \r\n"
									+ "                a.ai_risk_index = b.pl_risk_index\r\n"
									+ "                and a.AI_PL_INDEX = b.PL_PL_INDEX \r\n"
									+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
									+ risk_index + " and ai_org_code = " + Settings.orgCode);
					if (!(validVehicle.isEmpty())) {
						return validVehicle;
					}
					try (Statement stmt5 = oraConn.createStatement();
							ResultSet vehicle = stmt5.executeQuery(
									"select ai_regn_no,pl_jurisdiction_area,ai_risk_index,nvl(ai_vehicle_use,'N/A') ai_vehicle_use, nvl(ai_owner,'N/A')ai_owner,nvl(ai_cc,0) ai_cc, nvl(ai_cv,0) ai_cv , nvl(AI_MANUF_YEAR,2000)AI_MANUF_YEAR,nvl(ai_fuel_type,'N/A') ai_fuel_type,\r\n"
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
											+ "              and AI_PL_INDEX = " + pl_index + " and ai_risk_index = "
											+ risk_index + " and ai_org_code = " + Settings.orgCode)) {
						while (vehicle.next()) {
							String riskIndex = vehicle.getString("ai_risk_index");

							Element objet = production.addElement("objet").addAttribute("code", "12005-090003-AUTO")
									.addAttribute("reference", vehicle.getString("ai_regn_no"));
							Element biens = objet.addElement("biens");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", "" + vehicle.getString("ai_risk_index") + "")
									.addAttribute("operation", "ajout");
							Element attributs = bien.addElement("attributs");

							attributs.addElement("valeur").addText(vehicle.getString("ai_body_type"))
									.addAttribute("nom", "TYP");
							attributs.addElement("valeur").addText(vehicle.getString("ai_make").split("-")[0])
									.addAttribute("nom", "MAR");
							// Puissance fiscale
							attributs.addElement("valeur").addText(vehicle.getString("ai_cv")).addAttribute("nom",
									"PUF");

							// Date of circulation
							attributs.addElement("valeur").addText("2022-01-01").addAttribute("nom", "DMC");

							attributs.addElement("valeur").addText(vehicle.getString("ai_vehicle_use"))
									.addAttribute("nom", "USA");

							attributs.addElement("valeur").addText("CD").addAttribute("nom", "PAY");

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
							attributs.addElement("valeur").addText(vehicle.getString("ai_model").split("-")[0])
									.addAttribute("nom", "MOD");
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

							

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover\r\n"
													+ " from UW_POLICY_RISK_COVERS where sv_org_code = "
													+ Settings.orgCode + " and sv_pl_index = " + pl_index
													+ " and sv_risk_index =  " + riskIndex);) {
								while (rset.next()) {
									String arcaCover = "";
									System.err.println(rset.getString("sv_cc_code"));
									if (rset.getString("sv_cc_code").contains("TP")) {
										arcaCover = "TP";
										System.out.println("TP : 12005-090003-AUTO-RC");

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090003-AUTO-DR");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));

										attributs = garantie.addElement("attributs");
										attributs.addElement("valeur").addText(rs.getString("PL_DURATION"))
												.addAttribute("nom", "DUR");
										attributs.addElement("valeur").addText("false").addAttribute("nom", "FRT");
										garantie.addElement("prime")
												.addText(String.valueOf(rset.getInt("sv_fc_prem") * 100));
									} else if ("0700 0800".contains(rset.getString("sv_cc_code"))) {
  										String cover = "12005-090003-AUTO-TR";

										System.out.println("COMP : " + cover);

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
										garantie.addElement("prime")
												.addText(String.valueOf(rset.getInt("sv_fc_prem") * 100));
									

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

}
