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
	public static Statement stmt = null;
	String sourceTable = "UW";
	/*
	 * public SubmitGITPolicy() { try {
	 * 
	 * System.out.println("Connecting To Database"); oraConn =
	 * CreateConnection.getOraConn(); System.out.println("Database Connected!");
	 * 
	 * } catch (Exception e) { System.out.println("Errors Connecting to Database\n"
	 * + e.getMessage()); }
	 * 
	 * }
	 */

	public static String getCurrentUtcTime() {
		return Instant.now().toString();
	}

	public String sendArcaMessage(int pl_index, int pl_end_index, String created_by) {

		JsonObject myResponse = new JsonObject();

		try {
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery(
							"select nvl(max(AR_ENVELOPE_ID)+1,1) correlation_id, nvl(max(AR_DOCUMENT_ID)+1,1) document_id from ARCA_REQUESTS")) {
				while (rs.next()) {

					String endType = "";
					// CHECK THE TYPE OF ENDORSEMENT HERE AND CALL THE RELEVANT METHOD TO CREATE XML
					// REQUEST

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("SELECT * FROM arca_requests a WHERE AR_PL_INDEX = "
									+ pl_index + " AND ar_end_index = " + pl_end_index + " AND ar_success = 'Y' \r\n"
									+ "and AR_REQUEST_TYPE != 'CANCELLATION' AND NOT EXISTS\r\n"
									+ "(SELECT 1 FROM arca_requests b WHERE     b.AR_PL_INDEX = a.AR_PL_INDEX AND b.ar_end_index = a.ar_end_index\r\n"
									+ "   AND b.ar_success = 'Y' AND b.created_on > a.created_on AND b.AR_REQUEST_TYPE = 'CANCELLATION')\r\n"
									+ "AND a.ar_success = 'Y'")) {
						if (rs2.next()) {

							myResponse.addProperty("status", "-1");
							myResponse.addProperty("statusDescription",
									"This endorsement has already been submitted and has a successful response.");
							// return myResponse.get("statusDescription").toString();

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

					if (sourceTable.equals("UW")

							&& endType.equals("110")) {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "Please approve the policy before submitting.");
						return myResponse.get("statusDescription").toString();
					}

					String requestXML = "";
					System.out.println(endType);
					if (endType.equals("000") || endType.equals("110") || endType.equals("100")) {
						// New business or Renewals
						requestXML = buildPolicyXML(pl_index, pl_end_index, rs.getString("correlation_id"),
								rs.getString("document_id"), endType);
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

					RabbitMQSender sender = new RabbitMQSender();
					if (sender.sendMessage(requestXML, rs.getString("correlation_id"))) {

						myResponse.addProperty("status", "00");
						myResponse.addProperty("statusDescription", "Details submitted successfuly to ARCA");
					} else {

						myResponse.addProperty("status", "-1");
						myResponse.addProperty("statusDescription", "Message couldn't be sent ");

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
	public String buildPolicyXML(int pl_index, int pl_end_index, String correlationID, String documentID,
			String endType) {
		Document policyXML = DocumentHelper.createDocument();

		Element enveloppe = policyXML.addElement("enveloppe").addAttribute("id", String.valueOf(correlationID))
				.addAttribute("identifiant", ArcaController.USERNAME)
				.addAttribute("motDePasse", ArcaController.PASSWORD).addAttribute("timestamp", getCurrentUtcTime());

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
					production.addElement("produit").addAttribute("version", "1").addText("12005-090006");
					production.addElement("exercice")
							.addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate().getYear()));
					production.addElement("numeroPolice")
							.addText(String.valueOf(rs.getString("pl_no")).replaceAll("/", "-"));

					production.addElement("numeroAvenant").addText("1").addAttribute("type",
							endType.equals("000") ? "S" : "T");

					production.addElement("dateEmission")
							.addText(String.valueOf(rs.getDate("CREATED_ON").toLocalDate()));
					production.addElement("dateEffet").addText(String.valueOf(rs.getDate("PL_FM_DT").toLocalDate()));
					production.addElement("dateEcheance").addText(String.valueOf(rs.getDate("PL_TO_DT").toLocalDate()));

					if (rs.getString("ent_licence_no").equals(" ")) {

						return "Error. Please provide Client NIF Number";
					}
					Element souscripteur = production.addElement("souscripteur");

					try (Statement stmt2 = oraConn.createStatement();

							ResultSet client = stmt2
									.executeQuery("select ent_type,ent_salutation,ent_surname,ent_name,ent_other_names,"
											+ "ENt_id_no,ent_gender,ent_sector,ent_profession,created_on,ent_email,"
											+ "ent_cellphone from all_entity where ENT_AENT_CODE = '"
											+ rs.getString("PL_ASSR_AENT_CODE") + "' and ent_code = '"
											+ rs.getString("PL_ASSR_ENT_CODE") + "'")) {

						while (client.next()) {

							// Check this section for static values
							Element personne = souscripteur.addElement("personne")
									.addAttribute("reference", rs.getString("PL_ASSR_ENT_CODE"))
									.addAttribute("immatriculation", "non assujetti")
									.addAttribute("paysEtablissement", "CD")
									.addAttribute("personneMorale",
											client.getString("ent_type").equalsIgnoreCase("Individual") ? "false"
													: "true")
									.addAttribute("operation", "ajout")
									.addAttribute("nif", rs.getString("ent_licence_no"));
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
					int totalOtherCharges = 0;
					int totalTax = 0;
					try (Statement stmt3 = oraConn.createStatement();
							ResultSet charges = stmt3.executeQuery(
									"  SELECT  dl_org_code, dl_pl_index, dl_end_index, b.tax_name dl_charge_type, SUM ( NVL ( (DECODE ('', NULL, NVL (DECODE ( 'Debit', 'Nil', 0, dl_lc_amt), 0),\r\n"
											+ "  NVL (DECODE ( 'Debit', 'Nil', 0, dl_fc_amt), 0))),  0))    dl_charge_amnt     FROM vw_policy_charges a, gl_tax_types b WHERE     a.dl_org_code = b.tax_org_code      \r\n"
											+ "  AND a.dl_type = b.tax_type  AND a.dl_code = b.tax_code AND dl_source = '"
											+ sourceTable + "' and dl_pl_index =  " + pl_index + " and dl_end_index = "
											+ pl_end_index + " and dl_org_code = " + Settings.orgCode + " GROUP BY\r\n"
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
								+ "','yyyy/mm/dd') BETWEEN trunc(rate_fm_date) and trunc(rate_to_date)");

						try (Statement stmt3 = oraConn.createStatement();

								ResultSet curRate = stmt3.executeQuery(
										"select RATE_BUYING from GL_CURRENCY_RATES where RATE_FM_CUR_CODE = '"
												+ rs.getString("PL_CUR_CODE") + "' AND RATE_TO_CUR_CODE  = 'CDF'"
												+ "  AND TO_DATE('" + rs.getDate("PL_GL_DATE").toLocalDate().toString()
												+ "','yyyy/mm/dd') BETWEEN trunc(rate_fm_date) and trunc(rate_to_date)")) {
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

					try (Statement stmt5 = oraConn.createStatement();
							ResultSet risk = stmt5.executeQuery("select gi_risk_index,gi_mode, gi_goods_desc, --DESC \r\n"
									+ "NVL(TO_CHAR( case when gi_weight_uom = 'Kilograme' then gi_weight\r\n"
									+ "									 when  gi_weight_uom = 'Tonnage' then gi_weight\r\n"
									+ "									else  gi_weight end),'N/A') weight, --PMC \r\n"
									+ "                  NVL(TO_CHAR( case when gi_weight_uom = 'Kilograme' then 'Kilogramme'\r\n"
									+ "									 when  gi_weight_uom = 'Tonnage' then 'Tonnes'\r\n"
									+ "									else  'Not Defined' end),'N/A') weight_uom, --UNSTA \r\n"
									+ "                  nvl(pl_fc_si,0) pl_fc_si, --VLA \r\n"
									+ "                  nvl(gi_cover_ref,'N/A') gi_cover_ref, --NCM \r\n"
									+ "                   gi_origin , --PEM \r\n"
									+ "                   nvl(GI_SHIP_AGE,GI_IDF_NO) GI_SHIP_AGE , --AGN \r\n"
									+ "                   PKG_SYSTEM_ADMIN.GET_SYSTEM_DESC('ALL_PORTS',GI_DISH_PORT) GI_DISH_PORT , --VDEB \r\n"
									+ "                   gi_destination gi_destination_code , --PDEB \r\n"
									+ "                   PKG_SYSTEM_ADMIN.GET_SYSTEM_DESC('ALL_PORTS',GI_LOADING_AT) GI_LOADING_AT , --VEM \r\n"
									+ "                  nvl(gi_conveyance,'N/A') gi_conveyance, --MTR \r\n"
									+ "                  nvl(gi_bol_ref,'N/A') gi_bol_ref, --DTR \r\n"
									+ "                  nvl(gi_registration_no,'NA') gi_registration_no, --MAR \r\n"
									+ "                  nvl(PKG_SYSTEM_ADMIN.GET_SYSTEM_DESC('ALL_PORTS',gi_destination),  nvl((select gt_remarks from ai_gIt_mode where gt_pl_index = gi_pl_index and gt_source = 'DISCHPORT'),'N/A')) gi_destination, --VOA \r\n"
									+ "                  pkg_system_admin.spell_amounts(nvl(pl_fc_si,0)) si_words, --VLA \r\n"
									+ "                  to_char(gi_act_depature_date,'RRRR-MM-DD') gi_act_depature_date,  --DDV \r\n"
									+ "                  (SELECT AR_CERT_NO FROM arca_requests\r\n"
									+ "					 WHERE ar_request_type = 'CANCELLATION'\r\n"
									+ "					 AND ar_success = 'Y' AND AR_RISK_INDEX = pl_risk_index\r\n"
									+ "					 AND AR_PL_INDEX = pl_pl_index AND AR_END_INDEX = pl_end_index fetch first 1 row only) cancelled_cert,"
									+ "					(CASE WHEN LENGTH(GI_ORIGIN) = 2\r\n"
									+ "					AND NVL((SELECT SYS_FLEX_01 FROM AD_SYSTEM_CODES WHERE SYS_TYPE = 'ALL_PORTS' AND SYS_CODE = GI_ORIGIN),'0') = 'Arca'\r\n"
									+ "					THEN 'Y' ELSE 'N' END\r\n" + ") GI_ORIGIN_VALID,\r\n"
									+ "					(CASE WHEN LENGTH(GI_DESTINATION) = 2\r\n"
									+ "					AND NVL((SELECT SYS_FLEX_01 FROM AD_SYSTEM_CODES WHERE SYS_TYPE = 'ALL_PORTS' AND SYS_CODE = GI_DESTINATION),'0') = 'Arca'\r\n"
									+ "					THEN 'Y' ELSE 'N' END\r\n" + ") GI_DESTINATION_VALID"
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

							if (risk.getString("cancelled_cert") != null) {

								bien.addAttribute("certificat", risk.getString("cancelled_cert"));
							}
							Element attributs = bien.addElement("attributs");

							if (risk.getString("weight").equals("N/A")) {
								return "Error. Please enter goods weight first!";
							}

							if (risk.getString("gi_origin") == null) {
								return "Error. Please enter country of origin first!";
							}
							if (risk.getString("GI_DISH_PORT") == null) {
								return "Error. Please enter discharge port first!";
							}
							if (risk.getString("GI_MODE") == null) {
								return "Error. Please enter mode first!";
							}

							if (risk.getString("GI_LOADING_AT") == null) {
								return "Error. Please enter loading at port first!";
							}
							if (risk.getString("gi_destination_code") == null) {
								return "Error. Please enter destination country first!";
							}
							if (risk.getString("GI_SHIP_AGE") == null) {
								return "Error. Please enter age of ship!";
							}
							if (risk.getString("gi_goods_desc") == null) {
								return "Error. Goods description cannot be empty!";
							}
							

							if (risk.getString("GI_ORIGIN_VALID").equals("N")) {
								return "Error. Invalid Country of Origin, Pick One that Matches ARCA List of Values!";
							}
							if (risk.getString("GI_DESTINATION_VALID").equals("N")) {
								return "Error. Invalid Destination Country, Pick One that Matches ARCA List of Values!";
							}
							String idmt = "";
							switch (risk.getString("gi_mode")) {
							case "ROAD":
								idmt = "T";
								break;
							case "SEA":

								idmt = "M";
								break;
							case "AIR":

								idmt = "A";
								break;

							default:
								break;
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

							attributs.addElement("valeur").addText(risk.getString("gi_origin")).addAttribute("nom",
									"PEM");

							attributs.addElement("valeur").addText(risk.getString("GI_LOADING_AT")).addAttribute("nom",
									"VEM");

							attributs.addElement("valeur").addText(risk.getString("gi_destination_code"))
									.addAttribute("nom", "PDEB");

							attributs.addElement("valeur").addText(risk.getString("GI_DISH_PORT")).addAttribute("nom",
									"VDEB");

							attributs.addElement("valeur").addText(risk.getString("GI_SHIP_AGE")).addAttribute("nom",
									"AGN");

							attributs.addElement("valeur").addText(risk.getString("si_words")).addAttribute("nom",
									"VLAL");
							attributs.addElement("valeur").addText(risk.getString("gi_act_depature_date"))
									.addAttribute("nom", "DDV");
							// adding the conveyance number
							attributs.addElement("valeur").addText(risk.getString("gi_conveyance")).addAttribute("nom",
									"IDMT");
							attributs.addElement("valeur").addText(idmt).addAttribute("nom",
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
											"select sv_cc_code,PKG_UW.GET_COVER_NAME(sv_org_code,sv_mc_code,sv_cc_code) cover_name, sv_fm_dt,sv_to_dt,sv_fc_prem,sv_main_cover\r\n"
													+ " from UW_POLICY_RISK_COVERS where sv_org_code = "
													+ Settings.orgCode + " and sv_pl_index = " + pl_index
													+ " and sv_risk_index =  " + riskIndex);) {
								while (rset.next()) {

									if (rset.getString("sv_cc_code").equalsIgnoreCase("ICC(A)")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("062")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("061")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("ICC(B)")
											|| rset.getString("sv_cc_code").equalsIgnoreCase("ICC(C)")) {

										attributs.addElement("valeur").addText(rset.getString("cover_name"))
												.addAttribute("nom", "COG");
										attributs.addElement("valeur").addText("claims@mayfair.cd").addAttribute("nom",
												"CAV");
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

//							marchandises
							Element marchandises = bien.addElement("marchandises");

							try (Statement stmt2222 = oraConn.createStatement();
									ResultSet rset2 = stmt2222.executeQuery(
											"SELECT SM_ARCA_CODE, a.PM_SMI_DESC, sm_name,pm_fc_si FROM uw_policy_risk_smi  a\r\n"
													+ "INNER JOIN uw_class_smi b ON sm_org_code = pm_org_code\r\n"
													+ "AND sm_mc_code = pm_mc_code AND sm_code = pm_sm_code\r\n"
													+ "AND pm_pl_index = " + pl_index + " AND pm_end_index = "
													+ pl_end_index + " AND pm_risk_index = " + riskIndex);) {
								while (rset2.next()) {
									if (rset2.getString("SM_ARCA_CODE") == null) {

										return "Error. ARCA Code for this SMI Not Found, contact IT!";

									} else {
										Element marchandise = marchandises.addElement("marchandise");
										marchandise.addElement("code").addText(rset2.getString("SM_ARCA_CODE"));
										marchandise.addElement("valeur").addText(rset2.getString("pm_fc_si"));
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

	public String cancelCertificate(String cert_no, String cancel_reason, String created_by) {
		int correlationID = 0;
		int pl_index = -1;
		int end_index = -1;
		int risk_index = -1;
		String codeReason = "-1";

		Document cancellationXML = DocumentHelper.createDocument();
		JsonObject myResponse = new JsonObject();

		try {

			RabbitMQSender sender = new RabbitMQSender();
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery("select nvl(max(AR_ENVELOPE_ID)+1,1) correlation_id,"
							+ " nvl(max(AR_DOCUMENT_ID)+1,1) document_id from ARCA_REQUESTS")) {
				while (rs.next()) {
					correlationID = rs.getInt("correlation_id");

				}
			}

			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery("select sys_name codeReason from  ad_system_codes "
							+ "where sys_code = '" + cancel_reason + "' " + "and sys_type = 'CERT_REP_R'  ")) {
				while (rs.next()) {
					codeReason = rs.getString("codeReason");

				}
			}

			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery(
							"select ai_pl_index, pl_end_index,ai_risk_index from AI_VEHICLE_CERTIFICATES a, uw_policy_risks b\r\n"
									+ "where a.ai_org_code = b.pl_org_code\r\n"
									+ "and a.ai_pl_index = b.pl_pl_index\r\n"
									+ "and a.ai_risk_index = b.pl_risk_index\r\n" + "and ai_cert_no = '" + cert_no
									+ "'")) {
				while (rs.next()) {

					pl_index = rs.getInt("ai_pl_index");
					end_index = rs.getInt("pl_end_index");
					risk_index = rs.getInt("ai_risk_index");
				}
			}
			if (pl_index == -1 || end_index == -1) {

				try (Connection oraConn = CreateConnection.getOraConn();
						Statement stmt = oraConn.createStatement();
						ResultSet rs = stmt.executeQuery(
								"select ai_pl_index, AI_END_INDEX,ai_risk_index from ai_marine_certificates a "
										+ "where a.ai_org_code ='" + Settings.orgCode + "' " + " and ai_cert_no = '"
										+ cert_no + "'")) {
					while (rs.next()) {

						pl_index = rs.getInt("ai_pl_index");
						end_index = rs.getInt("AI_END_INDEX");
						risk_index = rs.getInt("ai_risk_index");

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
				String certNo = cert_no.contains("_")?cert_no.split("_")[1]:cert_no;
				Element annulationCertificat = enveloppe.addElement("annulationCertificat")
						.addAttribute("numeroCertificat", certNo);

				annulationCertificat.addElement("motifAnnulation").addText("EMA");
				annulationCertificat.addElement("description").addText(codeReason);

				System.out.println(cancellationXML.asXML());
				try (Connection oraConn = CreateConnection.getOraConn();) {
					PreparedStatement prepareStatement = oraConn.prepareStatement(
							"INSERT INTO ARCA_REQUESTS (AR_PL_INDEX, AR_END_INDEX, AR_ENVELOPE_ID, AR_DOCUMENT_ID,"
									+ " AR_REQUEST_XML,CREATED_BY,AR_REQUEST_TYPE,AR_CERT_NO,AR_RISK_INDEX )"
									+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
					prepareStatement.setInt(1, pl_index);
					prepareStatement.setInt(2, end_index);
					prepareStatement.setString(3, String.valueOf(correlationID));
					prepareStatement.setString(4, "00");
					prepareStatement.setString(5, cancellationXML.asXML());
					prepareStatement.setString(6, created_by);
					prepareStatement.setString(7, "CANCELLATION");
					prepareStatement.setString(8, cert_no);
					prepareStatement.setInt(9, risk_index);
					prepareStatement.execute();
				}
				if (sender.sendMessage(cancellationXML.asXML(), String.valueOf(correlationID))) {

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
