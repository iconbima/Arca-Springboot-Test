package com.arca.controllers;

import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;

import com.arca.ArcaController;

public class Base64DecodePdf {
	public boolean decodeString(String certNo, String base64) {
	
		boolean converted = false;
		File file = new File(ArcaController.ROOTFOLDER + certNo + ".pdf");
		try (FileOutputStream fos = new FileOutputStream(file);) {
			// convertion to preview the PDF file
			byte[] decoder = Base64.getDecoder().decode(base64);
			fos.write(decoder);
			converted = true;
		} catch (Exception e) {
			converted = false;

			e.printStackTrace();
		}

		return converted;

	}
}
