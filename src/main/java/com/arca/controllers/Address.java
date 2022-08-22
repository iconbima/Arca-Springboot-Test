package com.arca.controllers;

public class Address {
	String adresseEtranger; // Mandatory for non DRC clients
	String voie; // Road detials
	String commune; // municipality (or sector or chiefdom) of the address (the list can be obtained
					// with a message documentation _adresseRDC)
	String quartier;// neighborhood, optional
	String complement;// address complement, optional.

	/**
	 * @param adresseEtranger
	 * @param voie
	 * @param commune
	 * @param quartier
	 * @param complement
	 */
	public Address(String adresseEtranger, String voie, String commune, String quartier, String complement) {

		this.adresseEtranger = adresseEtranger;
		this.voie = voie;
		this.commune = commune;
		this.quartier = quartier;
		this.complement = complement;
	}

	public String getAdresseEtranger() {
		return adresseEtranger;
	}

	public void setAdresseEtranger(String adresseEtranger) {
		this.adresseEtranger = adresseEtranger;
	}

	public String getVoie() {
		return voie;
	}

	public void setVoie(String voie) {
		this.voie = voie;
	}

	public String getCommune() {
		return commune;
	}

	public void setCommune(String commune) {
		this.commune = commune;
	}

	public String getQuartier() {
		return quartier;
	}

	public void setQuartier(String quartier) {
		this.quartier = quartier;
	}

	public String getComplement() {
		return complement;
	}

	public void setComplement(String complement) {
		this.complement = complement;
	}

	@Override
	public String toString() {
		return "Address [adresseEtranger=" + adresseEtranger + ", voie=" + voie + ", commune=" + commune + ", quartier="
				+ quartier + ", complement=" + complement + "]";
	}

}
