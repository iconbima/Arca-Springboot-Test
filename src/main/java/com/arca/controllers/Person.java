package com.arca.controllers;

public class Person {

	Address address;// address entity
	String telephone;// phone number, optional ;
	String profession; // occupation of a natural person, optional ;
	String denominationSociale;// corporate name of a corporation ;
	String prenom;// first name of a natural person
	String nom;// name of an individual ;
	String postnom;// d�une personne physique
	String sexe; // Sex of a natural person in the form of <sexe code="F" /> or <sexe code="M" />
	String dateNaissance; // date of birth of a natural person;
	String lieuNaissance;// place of birth of a natural person;
	String civilite;// civility of a natural person in the form <civility code="X" /> where X is
					// among Mrs, Miss, Mr., Dr, Pr or Me;
	String noImmatriculation; // registration number of a natural or legal person subject to registration with
								// the RCCM or another register
	String dateImmatriculation; // date of registration of a natural or legal person subject to registration
								// with the RCCM or another register
	String registreImmatriculation;// registration register of a natural or legal person subject to registration,
									// this may be the RCCM or another register.

	// Attributes
	String reference; // unique reference of the person in the insurer's information system;
	String personneMorale;// true or false ;
	String paysEtablissement;// country of establishment, two-letter ISO code (the list can be extended to
								// include obtained with a documentation message _pays) ;
	String immatriculation;// RCCM, non-taxable or other ;
	String operation;// addition, modification or withdrawal. (ajout, modification ou retrait.)

	/**
	 * @param address
	 * @param telephone
	 * @param profession
	 * @param denominationSociale
	 * @param prenom
	 * @param nom
	 * @param postnom
	 * @param sexe
	 * @param dateNaissance
	 * @param lieuNaissance
	 * @param civilite
	 * @param noImmatriculation
	 * @param dateImmatriculation
	 * @param registreImmatriculation
	 * @param reference
	 * @param personneMorale
	 * @param paysEtablissement
	 * @param immatriculation
	 * @param operation
	 */
	public Person(Address address, String telephone, String profession, String denominationSociale, String prenom,
			String nom, String postnom, String sexe, String dateNaissance, String lieuNaissance, String civilite,
			String noImmatriculation, String dateImmatriculation, String registreImmatriculation, String reference,
			String personneMorale, String paysEtablissement, String immatriculation, String operation) {
		this.address = address;
		this.telephone = telephone;
		this.profession = profession;
		this.denominationSociale = denominationSociale;
		this.prenom = prenom;
		this.nom = nom;
		this.postnom = postnom;
		this.sexe = sexe;
		this.dateNaissance = dateNaissance;
		this.lieuNaissance = lieuNaissance;
		this.civilite = civilite;
		this.noImmatriculation = noImmatriculation;
		this.dateImmatriculation = dateImmatriculation;
		this.registreImmatriculation = registreImmatriculation;
		this.reference = reference;
		this.personneMorale = personneMorale;
		this.paysEtablissement = paysEtablissement;
		this.immatriculation = immatriculation;
		this.operation = operation;
	}

	public Address getAddress() {
		return address;
	}

	public void setAddress(Address address) {
		this.address = address;
	}

	public String getTelephone() {
		return telephone;
	}

	public void setTelephone(String telephone) {
		this.telephone = telephone;
	}

	public String getProfession() {
		return profession;
	}

	public void setProfession(String profession) {
		this.profession = profession;
	}

	public String getDenominationSociale() {
		return denominationSociale;
	}

	public void setDenominationSociale(String denominationSociale) {
		this.denominationSociale = denominationSociale;
	}

	public String getPrenom() {
		return prenom;
	}

	public void setPrenom(String prenom) {
		this.prenom = prenom;
	}

	public String getNom() {
		return nom;
	}

	public void setNom(String nom) {
		this.nom = nom;
	}

	public String getPostnom() {
		return postnom;
	}

	public void setPostnom(String postnom) {
		this.postnom = postnom;
	}

	public String getSexe() {
		return sexe;
	}

	public void setSexe(String sexe) {
		this.sexe = sexe;
	}

	public String getDateNaissance() {
		return dateNaissance;
	}

	public void setDateNaissance(String dateNaissance) {
		this.dateNaissance = dateNaissance;
	}

	public String getLieuNaissance() {
		return lieuNaissance;
	}

	public void setLieuNaissance(String lieuNaissance) {
		this.lieuNaissance = lieuNaissance;
	}

	public String getCivilite() {
		return civilite;
	}

	public void setCivilite(String civilite) {
		this.civilite = civilite;
	}

	public String getNoImmatriculation() {
		return noImmatriculation;
	}

	public void setNoImmatriculation(String noImmatriculation) {
		this.noImmatriculation = noImmatriculation;
	}

	public String getDateImmatriculation() {
		return dateImmatriculation;
	}

	public void setDateImmatriculation(String dateImmatriculation) {
		this.dateImmatriculation = dateImmatriculation;
	}

	public String getRegistreImmatriculation() {
		return registreImmatriculation;
	}

	public void setRegistreImmatriculation(String registreImmatriculation) {
		this.registreImmatriculation = registreImmatriculation;
	}

	public String getReference() {
		return reference;
	}

	public void setReference(String reference) {
		this.reference = reference;
	}

	public String getPersonneMorale() {
		return personneMorale;
	}

	public void setPersonneMorale(String personneMorale) {
		this.personneMorale = personneMorale;
	}

	public String getPaysEtablissement() {
		return paysEtablissement;
	}

	public void setPaysEtablissement(String paysEtablissement) {
		this.paysEtablissement = paysEtablissement;
	}

	public String getImmatriculation() {
		return immatriculation;
	}

	public void setImmatriculation(String immatriculation) {
		this.immatriculation = immatriculation;
	}

	public String getOperation() {
		return operation;
	}

	public void setOperation(String operation) {
		this.operation = operation;
	}

}
