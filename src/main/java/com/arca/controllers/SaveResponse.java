package com.arca.controllers;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.format.DateTimeFormatter;

import com.arca.ArcaController;
import com.arca.config.Settings;

public class SaveResponse {

	/*
	 * This method will save the certificate returned using a procedure. If the
	 * class is 06, it will only insert it into the certificate in the all files
	 * table, else it will save the certificate using the issue_arca_certificate
	 * procedure
	 * 
	 */

	public static boolean saveCertificate(String p_pl_index, String p_end_index, String p_risk_index, String p_fm_date,
			String p_to_date, String p_user_code, String certNo, String p_commit) {

		System.out.println(p_fm_date);
		System.out.println(p_to_date);
		String pl_mc_code = "";

		boolean saved = false;
		try {

			String policy_no = "";
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery("select pl_no from uw_policy where pl_index = " + p_pl_index)) {
				while (rs.next()) {
					policy_no = rs.getString("pl_no");
				}
			}

			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();
					ResultSet rs = stmt.executeQuery("select pc_mc_code from uw_policy_class where pc_org_code = '"
							+ Settings.orgCode + "' and  pc_pl_index = " + p_pl_index)) {
				while (rs.next()) {
					pl_mc_code = rs.getString("pc_mc_code");
				}
			}

			if (pl_mc_code.equals("06")) {

				try (Connection oraConn = CreateConnection.getOraConn();
						PreparedStatement update = oraConn.prepareStatement("Insert into ALL_FILES "
								+ "   (FL_INDEX, FL_ORG_CODE, FL_DOC_TYPE, FL_DOC_NO, FL_DOC_INDEX, FL_NAME, FL_PATH, FL_CONTENT_TYPE, FL_UPLOADED_ON, CREATED_BY, CREATED_ON, CREATED_IP) "
								+ " Values "
								+ "   (all_files_seq.nextval, ?, ?, ?, ?, ?, ?, ?, SYSDATE, ?, SYSDATE, ?)")) {

					update.setString(1, Settings.orgCode);
					update.setString(2, "UW-POLICY");
					update.setString(3, policy_no);
					update.setString(4, p_pl_index);
					update.setString(5, certNo + ".pdf");
					update.setString(6, ArcaController.ROOTFOLDER + certNo + ".pdf");
					update.setString(7, "application/pdf");
					update.setString(8, p_user_code);
					update.setString(9, "0.0.0.0");
					update.execute();

				}
				saved = true;
			}
			try (Connection oraConn = CreateConnection.getOraConn();
					CallableStatement cstmt = oraConn
							.prepareCall("{call issue_arca_certificate(?,?,?,?,?,?,?,?,?,?,?,?)}");) {

				// update all_files

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

	/*
	 * This method will update the uw_policy_risk table with the response from ARCA.
	 * If the response is a success, it will go ahead to call the saveCertificate
	 * method that will call issue_arca_certificate in the database to actually save
	 * the certificate.
	 * 
	 */
	public static void updateCertificate(String envId, String docId, String riskIndex, String certNo,
			String statusCode) {

		try {

			String p_pl_index = "";
			String p_end_index = "";
			String p_user_code = "";
			String pl_mc_code = "";
			/* Selecting the policy and end index */
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery("select AR_PL_INDEX, AR_END_INDEX,CREATED_BY from ARCA_REQUESTS"
							+ " where AR_ENVELOPE_ID = " + envId + " and AR_DOCUMENT_ID = " + docId)) {
				while (rs.next()) {

					p_pl_index = rs.getString("AR_PL_INDEX");
					p_end_index = rs.getString("AR_END_INDEX");
					p_user_code = rs.getString("CREATED_BY");
				}

				/* Selecting the policy class */
				try (Statement stmt2 = oraConn.createStatement();
						ResultSet rs2 = stmt2
								.executeQuery("select pc_mc_code from uw_policy_class where pc_org_code = '"
										+ Settings.orgCode + "' and  pc_pl_index = " + p_pl_index)) {
					while (rs2.next()) {
						pl_mc_code = rs2.getString("pc_mc_code");
					}
				}
				/* If the response is a success */
				if (statusCode.equals("00")) {
					/* Update the request row with the cert no */
					try (PreparedStatement update = oraConn.prepareStatement(
							"update arca_requests set ar_cert_no = ?  where ar_pl_index = ? and AR_END_INDEX = ? ")) {

						update.setString(1, certNo);
						update.setString(2, p_pl_index);
						update.setString(3, p_end_index);
						update.execute();

					}
					/* If it is marine and success we update the flex 01 and 02 */
					if (pl_mc_code.equals("06")) {

						try (PreparedStatement update = oraConn.prepareStatement(
								"update uw_policy_risks set  pl_flex01 =?, pl_flex02 = ? where pl_org_code = ? and "
										+ "pl_pl_index = ? and PL_END_INDEX = ? and pl_risk_index = ? ")) {

							update.setString(1, certNo);
							update.setString(2, statusCode == "00" ? "Y" : "N");
							update.setString(3, Settings.orgCode);
							update.setString(4, p_pl_index);
							update.setString(5, p_end_index);
							update.setString(6, riskIndex);
							update.execute();

						}

					}
					/* if it is motor we just update the flex01 */
					else {

						try (PreparedStatement update = oraConn
								.prepareStatement("update uw_policy_risks set pl_flex01 = ?  where pl_org_code = ? and"
										+ " pl_pl_index = ? and PL_END_INDEX = ? and pl_risk_index = ? ")) {

							update.setString(1, certNo);
							update.setString(2, Settings.orgCode);
							update.setString(3, p_pl_index);
							update.setString(4, p_end_index);
							update.setString(5, riskIndex);
							update.execute();

						}
					}

				}
				/*
				 * If the response is a failure we do not get the risk index, thus, we remove it
				 * from the update query
				 */
				else {

					/* If it is marine and success we update the flex 01 and 02 */
					if (pl_mc_code.equals("06")) {

						try (PreparedStatement update = oraConn.prepareStatement(
								"update uw_policy_risks set  pl_flex01 =?, pl_flex02 = ? where pl_org_code = ? and "
										+ "pl_pl_index = ? and PL_END_INDEX = ?   ")) {

							update.setString(1, certNo);
							update.setString(2, statusCode == "00" ? "Y" : "N");
							update.setString(3, Settings.orgCode);
							update.setString(4, p_pl_index);
							update.setString(5, p_end_index);
							update.execute();

						}

					}
					/* if it is motor we just update the flex01 */
					else {

						try (PreparedStatement update = oraConn
								.prepareStatement("update uw_policy_risks set pl_flex01 = ?  where pl_org_code = ? and"
										+ " pl_pl_index = ? and PL_END_INDEX = ?  ")) {

							update.setString(1, certNo);
							update.setString(2, Settings.orgCode);
							update.setString(3, p_pl_index);
							update.setString(4, p_end_index);
							update.execute();

						}
					}

				}

				/* If a risk index is provided */
				if (!(riskIndex.isEmpty() || riskIndex == null)) {

					String p_fm_date = "";//
					String p_to_date = "";//
					String p_commit = "Y";
					DateTimeFormatter formatters = DateTimeFormatter.ofPattern("dd/MM/uuuu");

					try (Statement stmt2 = oraConn.createStatement();
							ResultSet rs2 = stmt2.executeQuery("select * from uw_policy_risks where pl_pl_index = "
									+ p_pl_index + " and pl_end_index = " + p_end_index + " and pl_risk_index = "
									+ riskIndex)) {
						while (rs2.next()) {
							p_fm_date = String.valueOf(rs2.getDate("pl_risk_fm_dt").toLocalDate().format(formatters));
							p_to_date = String.valueOf(rs2.getDate("pl_risk_to_dt").toLocalDate().format(formatters));
						}
					}

					saveCertificate(p_pl_index, p_end_index, riskIndex, p_fm_date, p_to_date, p_user_code, certNo,
							p_commit);

				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	
	
	
	

	public static void updateCancelledCertificate(String envId, String docId, String riskIndex, String certNo,
			String statusCode,String newCert) {

		try {

			String p_pl_index = "";
			String p_end_index = "";
			String p_user_code = "";
			String pl_mc_code = "";
			/* Selecting the policy and end index */
			try (Connection oraConn = CreateConnection.getOraConn();
					Statement stmt = oraConn.createStatement();

					ResultSet rs = stmt.executeQuery("select AR_PL_INDEX, AR_END_INDEX,CREATED_BY from ARCA_REQUESTS"
							+ " where AR_ENVELOPE_ID = " + envId + " and AR_DOCUMENT_ID = " + docId)) {
				while (rs.next()) {

					p_pl_index = rs.getString("AR_PL_INDEX");
					p_end_index = rs.getString("AR_END_INDEX");
					p_user_code = rs.getString("CREATED_BY");
				}

				/* Selecting the policy class */
				try (Statement stmt2 = oraConn.createStatement();
						ResultSet rs2 = stmt2
								.executeQuery("select pc_mc_code from uw_policy_class where pc_org_code = '"
										+ Settings.orgCode + "' and  pc_pl_index = " + p_pl_index)) {
					while (rs2.next()) {
						pl_mc_code = rs2.getString("pc_mc_code");
					}
				}

					/* If it is marine and success we update the flex 01 and 02 */
					if (pl_mc_code.equals("06")) {

						try (PreparedStatement update = oraConn.prepareStatement(
								"update uw_policy_risks set  pl_flex01 =?, pl_flex02 = ? where pl_org_code = ? and "
										+ "pl_pl_index = ? and PL_END_INDEX = ? and pl_flex01=?  ")) {

							update.setString(1, newCert);
							update.setString(2, statusCode == "00" ? "Y" : "N");
							update.setString(3, Settings.orgCode);
							update.setString(4, p_pl_index);
							update.setString(5, p_end_index);
							update.setString(6, certNo);
							update.execute();

						}

					}
					/* if it is motor we just update the flex01 */
					else {

						try (PreparedStatement update = oraConn
								.prepareStatement("update uw_policy_risks set pl_flex01 = ?  where pl_org_code = ? and"
										+ " pl_pl_index = ? and PL_END_INDEX = ?  and pl_flex01 = ?")) {

							update.setString(1, newCert);
							update.setString(2, Settings.orgCode);
							update.setString(3, p_pl_index);
							update.setString(4, p_end_index);
							update.setString(5, certNo);
							update.execute();

						}
					}


					// updating uw_cert_issued
					try (PreparedStatement update = oraConn.prepareStatement(
							"update uw_cert_issued set  cd_status = 'REVOKED' where cd_org_code  = ? and" + " cd_cert_no = ?   ")) {

						update.setString(1, Settings.orgCode);
						update.setString(2, certNo);
						update.execute();

					}

					// updating ai_vehicle_certificates
					try (PreparedStatement update = oraConn.prepareStatement(
							"update ai_vehicle_certificates set  ai_status = 'CANCELLED' where ai_org_code  = ? and"
									+ " ai_cert_no = ?   ")) {

						update.setString(1, Settings.orgCode);
						update.setString(2, certNo);
						update.execute();

					}

					// updating ai_marine_certificates
					try (PreparedStatement update = oraConn.prepareStatement(
							"update ai_marine_certificates set  ai_status = 'CANCELLED' where ai_org_code  = ? and"
									+ " ai_cert_no = ?   ")) {

						update.setString(1, Settings.orgCode);
						update.setString(2, certNo);
						update.execute();

					}



			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	
	
	
	
	
}
