package com.arca;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.arca.config.Settings;
import com.arca.controllers.CreateConnection;
import com.arca.controllers.GetConfiguration;
import com.arca.controllers.SubmitGITPolicy;
import com.arca.controllers.SubmitMotorPolicy;
import com.arca.controllers.SubmitMotorVehicle;

@RestController
@RequestMapping("arca")
public class ArcaController {

	private static Connection oraConn = null;
	public static Statement stmt = null;
	public static String USERNAME = "-1";
	public static String PASSWORD = "-1";
	public static String ROOTFOLDER = "./";
	public static String SSL_PASSWORD = "./";
	public static String HOST = "./";
	public static String ADDRESS = "./";

	public ArcaController() {
		try {

			System.out.println("Connecting To Database");
			oraConn = CreateConnection.getOraConn();
			System.out.println("Database Connected!");
			System.out.println("---------------------setting default values------------------------");

			try (Statement stmt = CreateConnection.getOraConn().createStatement();
					ResultSet rs = stmt.executeQuery(
							"select sys_name,sys_code from ad_system_codes where sys_type = 'ARCA_API_DETAILS'");) {
				while (rs.next()) {
					if (rs.getString("sys_code").equals("ARCA_CERT_PATH")) {
						ROOTFOLDER = rs.getString("sys_name");
						//ROOTFOLDER = "D:\\Api\\Arca\\Certs\\";
					} else if (rs.getString("sys_code").equals("ARCA_USERNAME")) {
						USERNAME = (rs.getString("sys_name"));
					} else if (rs.getString("sys_code").equals("ARCA_PASSWORD")) {
						PASSWORD = (rs.getString("sys_name"));
					} else if (rs.getString("sys_code").equals("ARCA_SSL_PASSWORD")) {
						SSL_PASSWORD = (rs.getString("sys_name"));
					} else if (rs.getString("sys_code").equals("ARCA_HOST")) {
						HOST = (rs.getString("sys_name"));
					} else if (rs.getString("sys_code").equals("ARCA_ADDRESS")) {
						ADDRESS = (rs.getString("sys_name"));
					}

				}

				System.out.println(" USERNAME = " + USERNAME);
				System.out.println(" PASSWORD = " + PASSWORD);
				System.out.println(" ROOTFOLDER = " + ROOTFOLDER);
				System.out.println(" SSL_PASSWORD = " + SSL_PASSWORD);
				System.out.println(" HOST = " + HOST);
				System.out.println(" ADDRESS = " + ADDRESS);
				System.out.println("---------------------FINISHED------------------------");
				// rootFolder = "D:\\Api\\Arca\\Certs\\";

			} catch (SQLException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

		} catch (SQLException e) {
			System.out.println("Errors Connecting to Database\n" + e.getMessage());
			System.exit(1);
		} catch (Exception e) {
			System.out.println("Errors Connecting to Database\n" + e.getMessage());
			System.exit(1);
		}

	}

	/* This will submit a policy to arca */
	@GetMapping(path = "sendArcaRequest/{pl_index}/{end_index}/{created_by}")
	public String sendMessage(@PathVariable("pl_index") int pl_index, @PathVariable("end_index") int end_index,
			@PathVariable("created_by") String created_by) throws Exception {
		String response = "";
		try (Statement stmt = oraConn.createStatement();

				ResultSet rs = stmt
						.executeQuery(" select pc_mc_code, pc_pr_code from uw_policy_class where pc_pl_index = "
								+ pl_index + " and pc_org_code = '" + Settings.orgCode + "' ")) {
			while (rs.next()) {
				if (rs.getString("pc_pr_code").equals("062") || rs.getString("pc_pr_code").equals("061")) {
//					response = "We are testing motor policies for now!";
					SubmitGITPolicy sp = new SubmitGITPolicy();
					response = (sp.sendArcaMessage(pl_index, end_index, created_by));
				} else if (rs.getString("pc_mc_code").contains("07") || rs.getString("pc_mc_code").contains("08")) {

//					response = "We are also eager to go live with motor, it's coming soon!";
					SubmitMotorPolicy sp = new SubmitMotorPolicy();
					response = (sp.sendArcaMessage(pl_index, end_index, created_by));

				} else {
					response = "Sorry this product has not been configured with ARCA yet!";
				}
			}
		}

		return response;

	}

	/* This will submit a policy to arca */
	@GetMapping(path = "sendArcaRisk/{pl_index}/{end_index}/{risk_index}/{created_by}")
	public String sendSingleRisk(@PathVariable("pl_index") int pl_index, @PathVariable("end_index") int end_index,
			@PathVariable("risk_index") int risk_index,
			@PathVariable("created_by") String created_by) throws Exception {
		String response = "";
		try (Statement stmt = oraConn.createStatement();

				ResultSet rs = stmt
						.executeQuery(" select pc_mc_code, pc_pr_code from uw_policy_class where pc_pl_index = "
								+ pl_index + " and pc_org_code = '" + Settings.orgCode + "' ")) {
			while (rs.next()) {
				if (rs.getString("pc_pr_code").equals("062") || rs.getString("pc_pr_code").equals("061")) {
					response = "This option is available for motor policies only!";
//					SubmitGITPolicy sp = new SubmitGITPolicy();
//					response = (sp.sendArcaMessage(pl_index, end_index, created_by));
				} else if (rs.getString("pc_mc_code").contains("07") || rs.getString("pc_mc_code").contains("08")) {

//					response = "We are also eager to go live with motor, it's coming soon!";
					SubmitMotorVehicle sp = new SubmitMotorVehicle();
					response = (sp.sendArcaMessage(pl_index, end_index,risk_index, created_by));

				} else {
					response = "Sorry this product has not been configured with ARCA yet!";
				}
			}
		}

		return response;

	}

	/* This will submit cancel an existing certificate on arca */
	@RequestMapping(value = "/cancelArcaCert", method = RequestMethod.POST)
	public String cancelCert(@RequestBody CancelCert cancelCert) throws Exception {
		String response = "";

		SubmitGITPolicy sp = new SubmitGITPolicy();
		response = sp.cancelCertificate(cancelCert.getCertNo(), cancelCert.getCancelReason(), cancelCert.getUserCode());

		return response;

	}

	/*
	 * This will return the codes used in ARCA i.e 1. Country 2. Currency 3.
	 * Administrative subdivisions of the DRC 4. Error lists
	 */
	@GetMapping(path = "getCoding")
	public String getCoding() throws Exception {
		GetConfiguration gc = new GetConfiguration();
		return gc.sendConfigRequest("_codification");

	}

	/*
	 * This will return the country codes as in the arca system
	 */
	@GetMapping(path = "getCountriesCodes")
	public String getCountries() throws Exception {
		GetConfiguration gc = new GetConfiguration();
		return gc.sendConfigRequest("_pays");

	}

	/*
	 * This will return currency codes
	 */
	@GetMapping(path = "getCurrency")
	public String getCurrency() throws Exception {
		GetConfiguration gc = new GetConfiguration();
		return gc.sendConfigRequest("_devise");

	}

	/*
	 * This will return the insurance company configured products
	 */
	@GetMapping(path = "getProducts")
	public String getProducts() throws Exception {
		GetConfiguration gc = new GetConfiguration();
		return gc.sendConfigRequest("_listeProduits");

	}

	/*
	 * This will return the ARCA error codes
	 */
	@GetMapping(path = "getErrors")
	public String getErrors() throws Exception {
		GetConfiguration gc = new GetConfiguration();
		return gc.sendConfigRequest("_erreur");

	}

}
