package com.arca.controllers;

import java.io.File;
import java.util.List;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;

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

	public static String findVehicleUsageS(String usage) {

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