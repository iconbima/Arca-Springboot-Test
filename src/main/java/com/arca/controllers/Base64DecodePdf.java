package com.arca.controllers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import com.arca.ArcaController;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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

	public boolean saveCertFromUrl(String certNo, String fileURL) {
		System.err.println(certNo);
		System.err.println(fileURL);
		boolean saved = false;

		// Encode username and password for Basic Authentication
		String auth = ArcaController.USERNAME + ":" + ArcaController.PASSWORD;
		String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

		OkHttpClient client = new OkHttpClient();
		System.err.println(encodedAuth);
		
		Request request = new Request.Builder().url(fileURL).header("Authorization", "Basic " + encodedAuth).get()
				.build();

		String saveFilePath = ArcaController.ROOTFOLDER + certNo + ".pdf";

		try {
			Response response = client.newCall(request).execute();
			if (!response.isSuccessful()) {
				System.out.println("Failed to download file: " + response);
				saved = false;
			} else {
				InputStream inputStream = response.body().byteStream();
				FileOutputStream fileOutputStream = new FileOutputStream(saveFilePath);
				byte[] buffer = new byte[1024];
				int bytesRead;
				System.out.println("Downloading the PDF file...");
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					fileOutputStream.write(buffer, 0, bytesRead);
				}
				fileOutputStream.close();
				inputStream.close();
				System.out.println("PDF file downloaded successfully: " + saveFilePath);
				saved = true;
			}
		} catch (IOException e) {
			System.out.println("Error while downloading the file: " + e.getMessage());
			e.printStackTrace();
			saved = false;
		}

		return saved;

	}
}
