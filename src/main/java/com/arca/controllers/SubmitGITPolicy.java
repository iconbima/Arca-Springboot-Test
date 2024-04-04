package com.arca.controllers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import com.arca.ArcaController;
import com.arca.config.Settings;
import com.arca.rabbit.mq.RabbitMQSender;
import com.google.gson.JsonObject;

public class SubmitGITPolicy {
	private static Connection oraConn = null;
	public static Statement stmt = null;

	public SubmitGITPolicy() {
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
					// CHECK THE TYPE OF ENDORSEMENT HERE AND CALL THE RELEVANT METHOD TO CREATE XML
					// REQUEST

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from arca_requests where AR_PL_INDEX = "
									+ pl_index + " and ar_end_index = " + pl_end_index + " and ar_success = 'Y'")) {
						if (rs2.next()) {

							myResponse.addProperty("status", "-1");
							myResponse.addProperty("statusDescription",
									"This endorsement has already been submitted and has a successful response.");
							// return myResponse.get("statusDescription").toString();

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

					if (end_type.equals("000") || end_type.equals("110") || end_type.equals("100")) {
						// New business or Renewals
						requestXML = buildPolicyXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"));
					}

					/*
					 * else if (end_type.equals("101")) { // Incorporation - Adding to sum insured
					 * requestXML = buildIncopEndorsementXML(pl_index, pl_end_index,
					 * rs.getString("correlation_id"), rs.getString("document_id"));
					 * 
					 * } else if (end_type.equals("103")) { // Extension - Extending the policy
					 * period requestXML = buildExtensionXML(pl_index, pl_end_index,
					 * rs.getString("correlation_id"), rs.getString("document_id"));
					 * 
					 * } else if (end_type.equals("102") || end_type.equals("104")) { // Rebate -
					 * Reducing the sum insured requestXML = buildRebateEndorsementXML(pl_index,
					 * pl_end_index, rs.getString("correlation_id"), rs.getString("document_id"));
					 * 
					 * } else if (end_type.equals("108")) { // Termination - policy termination
					 * requestXML = buildTerminationXML(pl_index, pl_end_index,
					 * rs.getString("correlation_id"), rs.getString("document_id"));
					 * 
					 * } else if (end_type.equals("111")) { // NTU - Policy not taken up
					 * 
					 * requestXML = buildNTUXML(pl_index, pl_end_index,
					 * rs.getString("correlation_id"), rs.getString("document_id"));
					 * 
					 * } else if (end_type.isEmpty()) { // Empty end type
					 * myResponse.addProperty("status", "01");
					 * myResponse.addProperty("statusDescription", "Endorsement type not found! ");
					 * return myResponse.get("statusDescription").toString();
					 * 
					 * }
					 */
					else {

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
								"INSERT INTO ARCA_REQUESTS (AR_PL_INDEX, AR_END_INDEX, AR_ENVELOPE_ID, AR_DOCUMENT_ID, AR_REQUEST_XML,CREATED_BY,AR_REQUEST_TYPE )"
										+ " VALUES (?, ?, ?, ?, ?, ?,?)");
						prepareStatement.setInt(1, pl_index);
						prepareStatement.setInt(2, pl_end_index);
						prepareStatement.setString(3, rs.getString("correlation_id"));
						prepareStatement.setString(4, rs.getString("document_id"));
						prepareStatement.setString(5, requestXML);
						prepareStatement.setString(6, created_by);
						prepareStatement.setString(7, "GIT_CERT");
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

	// this is the first request
	public String buildPolicyXML(int pl_index, int pl_end_index, String correlationID, String documentID) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

		Element production = enveloppe.addElement("production").addAttribute("id", String.valueOf(documentID));

		production.addElement("assureur").addAttribute("numeroAgrement", "12005");

		production.addElement("produit").addAttribute("version", "1").addText("12005-090006");

		try {
			try (Statement stmt22 = oraConn.createStatement();

					ResultSet rs = stmt22.executeQuery(
							"SELECT decode(pl_end_index,0,pl_no,pl_end_no) pl_no,pl_index,PL_ASSR_AENT_CODE,PL_GL_DATE,PL_ASSR_ENT_CODE,CREATED_ON, (select nvl(ent_licence_no,' ')  from all_entity where ent_aent_code = pl_assr_aent_code and ent_code = pl_assr_ent_code) ent_licence_no,"
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

					if (rs.getString("ent_licence_no").equals(" ")) {

						return "Error. Please provide Client NIF Number";
					}
					Element souscripteur = production.addElement("souscripteur");
					// Check this section for static values
					Element personne = souscripteur.addElement("personne")
							.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
							.addAttribute("immatriculation", "non assujetti").addAttribute("paysEtablissement", "CD")
							.addAttribute("personneMorale", "false").addAttribute("operation", "ajout")
							.addAttribute("nif", rs.getString("ent_licence_no"));
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
							ResultSet charges = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = 'UW' and dl_pl_index =  "
											+ pl_index + " and dl_end_index = " + pl_end_index + " and dl_org_code = "
											+ Settings.orgCode + " GROUP BY\r\n"
											+ "  dl_org_code, dl_pl_index,dl_end_index, b.tax_name ORDER BY dl_charge_type")) {

						while (charges.next()) {
							if (charges.getString("dl_charge_type").equalsIgnoreCase("VAT @ 16%")) {
								totalTax += charges.getDouble("dl_charge_amnt");

							} else {
								totalOtherCharges += charges.getDouble("dl_charge_amnt");
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

								ResultSet curRate = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN rate_fm_date and rate_to_date")) {
							if (curRate.next()) {
								do {

									Element prime = production.addElement("prime")
											.addAttribute("devise", rs.getString("PL_CUR_CODE"))
											.addAttribute("taux", curRate.getString("RATE_BUYING"));

									prime.addElement("fraisAccessoires")
											.addText(String.valueOf(totalOtherCharges * 100));
									prime.addElement("taxeValeurAjoutee").addText(String.valueOf(totalTax * 100));

								} while (curRate.next());
							} else {
								return "Error. Exchange rate for this date "
										+ rs.getDate("PL_GL_DATE").toLocalDate().toString() + " not found!";

							}

						}
					}
					System.out.println("select gi_risk_index, gi_goods_desc, --DESC \r\n"
							+ "NVL(TO_CHAR( case when gi_weight_uom = 'Kilograme' then gi_weight\r\n"
							+ "									 when  gi_weight_uom = 'Tonnage' then gi_weight\r\n"
							+ "									else  gi_weight end),'N/A') weight, --PMC \r\n"
							+ "                  NVL(TO_CHAR( case when gi_weight_uom = 'Kilograme' then 'Kilogramme'\r\n"
							+ "									 when  gi_weight_uom = 'Tonnage' then 'Tonnes'\r\n"
							+ "									else  'Not Defined' end),'N/A') weight_uom, --UNSTA \r\n"
							+ "                  nvl(pl_fc_si,0) pl_fc_si, --VLA \r\n"
							+ "                  nvl(gi_cover_ref,'N/A') gi_cover_ref, --NCM \r\n"
							+ "                  nvl(gi_conveyance,'N/A') gi_conveyance, --MTR \r\n"
							+ "                  nvl(gi_bol_ref,'N/A') gi_bol_ref, --DTR \r\n"
							+ "                  nvl(gi_registration_no,'NA') gi_registration_no, --MAR \r\n"
							+ "                  nvl(PKG_SYSTEM_ADMIN.GET_SYSTEM_DESC('ALL_PORTS',gi_destination),  nvl((select gt_remarks from ai_gIt_mode where gt_pl_index = gi_pl_index and gt_source = 'DISCHPORT'),'N/A')) gi_destination, --VOA \r\n"
							+ "                  pkg_system_admin.spell_amounts(nvl(pl_fc_si,0)) si_words, --VLA \r\n"
							+ "                  to_char(gi_act_depature_date,'RRRR-MM-DD') gi_act_depature_date --DDV \r\n"
							+ "                  \r\n" + "                  from ai_git a,uw_policy_risks b \r\n"
							+ "                  where  a.gi_risk_index = b.pl_risk_index\r\n"
							+ "                  and a.GI_PL_INDEX = b.PL_PL_INDEX\r\n"
							+ "                  and a.gi_org_code = b.pl_org_code\r\n"
							+ "                  and a.gi_org_code = " + Settings.orgCode + " \r\n"
							+ "                  and gi_pl_index = " + pl_index);

					try (Statement stmt5 = oraConn.createStatement();
							ResultSet risk = stmt5.executeQuery("select gi_risk_index, gi_goods_desc, --DESC \r\n"
									+ "NVL(TO_CHAR( case when gi_weight_uom = 'Kilograme' then gi_weight\r\n"
									+ "									 when  gi_weight_uom = 'Tonnage' then gi_weight\r\n"
									+ "									else  gi_weight end),'N/A') weight, --PMC \r\n"
									+ "                  NVL(TO_CHAR( case when gi_weight_uom = 'Kilograme' then 'Kilogramme'\r\n"
									+ "									 when  gi_weight_uom = 'Tonnage' then 'Tonnes'\r\n"
									+ "									else  'Not Defined' end),'N/A') weight_uom, --UNSTA \r\n"
									+ "                  nvl(pl_fc_si,0) pl_fc_si, --VLA \r\n"
									+ "                  nvl(gi_cover_ref,'N/A') gi_cover_ref, --NCM \r\n"
									+ "                  nvl(gi_conveyance,'N/A') gi_conveyance, --MTR \r\n"
									+ "                  nvl(gi_bol_ref,'N/A') gi_bol_ref, --DTR \r\n"
									+ "                  nvl(gi_registration_no,'NA') gi_registration_no, --MAR \r\n"
									+ "                  nvl(PKG_SYSTEM_ADMIN.GET_SYSTEM_DESC('ALL_PORTS',gi_destination),  nvl((select gt_remarks from ai_gIt_mode where gt_pl_index = gi_pl_index and gt_source = 'DISCHPORT'),'N/A')) gi_destination, --VOA \r\n"
									+ "                  pkg_system_admin.spell_amounts(nvl(pl_fc_si,0)) si_words, --VLA \r\n"
									+ "                  to_char(gi_act_depature_date,'RRRR-MM-DD') gi_act_depature_date  --DDV \r\n"
									+ "                  \r\n"
									+ "                  from ai_git a,uw_policy_risks b \r\n"
									+ "                  where  a.gi_risk_index = b.pl_risk_index\r\n"
									+ "                  and a.GI_PL_INDEX = b.PL_PL_INDEX\r\n"
									+ "                  and a.gi_org_code = b.pl_org_code\r\n"
									+ "                  and a.gi_org_code = " + Settings.orgCode + " \r\n"
									+ "                  and gi_pl_index = " + pl_index)) {
						while (risk.next()) {

							Element objet = production.addElement("objet").addAttribute("code", "12005-090006-FAC")
									.addAttribute("reference", risk.getString("gi_risk_index"));
							Element biens = objet.addElement("biens");
							String riskIndex = risk.getString("gi_risk_index");
							Element bien = biens.addElement("bien")
									.addAttribute("reference", risk.getString("gi_risk_index"))
									.addAttribute("operation", "ajout");
							Element attributs = bien.addElement("attributs");

							if (risk.getString("weight").equals("N/A")) {
								return "Error. Please enter goods weight first!";
							}

							// All attributes go here
							attributs.addElement("valeur").addText(risk.getString("gi_goods_desc")).addAttribute("nom",
									"DESC");
							attributs.addElement("valeur").addText(risk.getString("weight")).addAttribute("nom", "PMC");
							attributs.addElement("valeur").addText(risk.getString("weight_uom")).addAttribute("nom",
									"UNSTA");
							attributs.addElement("valeur").addText(risk.getString("pl_fc_si")).addAttribute("nom",
									"VLA");

							attributs.addElement("valeur").addText(risk.getString("gi_cover_ref")).addAttribute("nom",
									"NCM");

							attributs.addElement("valeur").addText(risk.getString("si_words")).addAttribute("nom",
									"VLAL");
							attributs.addElement("valeur").addText(risk.getString("gi_act_depature_date"))
									.addAttribute("nom", "DDV");
							// adding the conveyance number
							attributs.addElement("valeur").addText(risk.getString("gi_conveyance")).addAttribute("nom",
									"MTR");
							// adding the bill of lading no
							attributs.addElement("valeur").addText(risk.getString("gi_bol_ref")).addAttribute("nom",
									"DTR");
							// adding the destination
							attributs.addElement("valeur").addText(risk.getString("gi_destination")).addAttribute("nom",
									"VOA");
							// adding the registration no
							attributs.addElement("valeur").addText(risk.getString("gi_registration_no"))
									.addAttribute("nom", "MAR");

							Element souscriptions = biens.addElement("souscriptions");

							try (Statement stmt222 = oraConn.createStatement();
									ResultSet rset = stmt222.executeQuery(
											"select sv_cc_code, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover\r\n"
													+ " from UW_POLICY_RISK_COVERS where sv_org_code = "
													+ Settings.orgCode + " and sv_pl_index = " + pl_index
													+ " and sv_risk_index =  " + riskIndex);) {
								while (rset.next()) {

									if (rset.getString("sv_cc_code").equalsIgnoreCase("ICC(A)")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("062")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("061")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("ICC(B)")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("ICC(C)")) {

										Element garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090006-FAC-MV");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
										garantie.addElement("prime")
												.addText(String.valueOf(rset.getInt("sv_fc_prem") * 100));

										garantie = souscriptions.addElement("garantie").addAttribute("code",
												"12005-090006-FAC-DP");
										garantie.addElement("dateEffet")
												.addText(String.valueOf(rset.getDate("sv_fm_dt").toLocalDate()));
										garantie.addElement("dateEcheance")
												.addText(String.valueOf(rset.getDate("sv_to_dt").toLocalDate()));
										garantie.addElement("prime").addText(String.valueOf(0));

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

	public String cancelCertificate(String cert_no, String cancel_reason, String created_by) {
		int correlationID = 0;
		int pl_index = -1;
		int end_index = -1;
		String codeReason = "-1";

		Document cancellationXML = DocumentHelper.createDocument();
		JsonObject myResponse = new JsonObject();

		try {

			RabbitMQSender sender = new RabbitMQSender();
			try (Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery("select nvl(max(AR_ENVELOPE_ID)+1,1) correlation_id,"
							+ " nvl(max(AR_DOCUMENT_ID)+1,1) document_id from ARCA_REQUESTS")) {
				while (rs.next()) {
					correlationID = rs.getInt("correlation_id");

				}
			}

			try (Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery("select sys_name codeReason from  ad_system_codes "
							+ "where sys_code = '"+cancel_reason+"' "
							+ "and sys_type = 'CERT_REP_R'  ")) {
				while (rs.next()) {
					codeReason = rs.getString("codeReason");

				}
			}

			try (Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery(
							"select ai_pl_index, pl_end_index from AI_VEHICLE_CERTIFICATES a, uw_policy_risks b\r\n"
									+ "where a.ai_org_code = b.pl_org_code\r\n"
									+ "and a.ai_pl_index = b.pl_pl_index\r\n"
									+ "and a.ai_risk_index = b.pl_risk_index\r\n" + "and ai_cert_no = '" + cert_no
									+ "'")) {
				while (rs.next()) {

					pl_index = rs.getInt("ai_pl_index");
					end_index = rs.getInt("pl_end_index");
				}
			}
			if (pl_index == -1 || end_index == -1) {

				try (Statement stmt = oraConn.createStatement();
						ResultSet rs = stmt
								.executeQuery("select ai_pl_index, AI_END_INDEX from ai_marine_certificates a "
										+ "where a.ai_org_code ='" + Settings.orgCode + "' " + " and ai_cert_no = '"
										+ cert_no + "'")) {
					while (rs.next()) {

						pl_index = rs.getInt("ai_pl_index");
						end_index = rs.getInt("AI_END_INDEX");

					}
				}
			}

			if (pl_index == -1 || end_index == -1) {

				myResponse.addProperty("status", "-1");
				myResponse.addProperty("statusDescription", "Cant find the risk details ");

			} else {
				Element enveloppe = cancellationXML.addElement("enveloppe")
						.addAttribute("id", String.valueOf(correlationID))
						.addAttribute("identifiant", ArcaController.USERNAME)
						.addAttribute("motDePasse", ArcaController.PASSWORD)
						.addAttribute("timestamp", getCurrentUtcTime());

				Element annulationCertificat = enveloppe.addElement("annulationCertificat")
						.addAttribute("numeroCertificat", cert_no);

				annulationCertificat.addElement("motifAnnulation").addText(codeReason);

				System.out.println(cancellationXML.asXML());

				if (sender.sendMessage(cancellationXML.asXML(), String.valueOf(correlationID))) {
					PreparedStatement prepareStatement = oraConn.prepareStatement(
							"INSERT INTO ARCA_REQUESTS (AR_PL_INDEX, AR_END_INDEX, AR_ENVELOPE_ID, AR_DOCUMENT_ID,"
									+ " AR_REQUEST_XML,CREATED_BY,AR_REQUEST_TYPE,AR_CERT_NO )"
									+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
					prepareStatement.setInt(1, pl_index);
					prepareStatement.setInt(2, end_index);
					prepareStatement.setString(3, String.valueOf(correlationID));
					prepareStatement.setString(4, "00");
					prepareStatement.setString(5, cancellationXML.asXML());
					prepareStatement.setString(6, created_by);
					prepareStatement.setString(7, "CANCELLATION");
					prepareStatement.setString(8, cert_no);
					prepareStatement.execute();

					myResponse.addProperty("status", "00");
					myResponse.addProperty("statusDescription", "Details submitted successfuly to ARCA");
				} else {

					myResponse.addProperty("status", "-1");
					myResponse.addProperty("statusDescription", "Message couldnt be sent ");

				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			myResponse.addProperty("status", "-1");
			myResponse.addProperty("statusDescription", "An error was encountered! " + e.getMessage());
			// return myResponse.get("statusDescription").toString();

		}

		return myResponse.toString();

	}

}
