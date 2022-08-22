package com.arca.controllers;

import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;

public class Base64DecodePdf {
	public boolean decodeString(String certNo, String base64) {
		String rootFolder = "./";
		try (Connection oraConn = CreateConnection.getOraConn();
				Statement stmt = oraConn.createStatement();
				ResultSet rs = stmt.executeQuery(
						"select sys_name from ad_system_codes where sys_type = 'API_DETAILS' and sys_code = 'ARCA_CERT_PATH'");) {
			while (rs.next()) {
				rootFolder = rs.getString("sys_name");
			}

		} catch (SQLException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		boolean converted = false;
		System.out.println("Test" + base64);
		File file = new File(rootFolder + certNo + ".pdf");
		try (FileOutputStream fos = new FileOutputStream(file);) {
			// convertion to preview the PDF file
			byte[] decoder = Base64.getDecoder().decode(base64);
			fos.write(decoder);
			converted = true;
			System.out.println("PDF File Saved");
		} catch (Exception e) {
			converted = false;

			e.printStackTrace();
		}

		return converted;

	}
}
