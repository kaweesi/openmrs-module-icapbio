package org.openmrs.module.icapbio.web.controller;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PatientIdentifierType.LocationBehavior;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonName;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.WebConstants;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PatientBiometricsController {
	private static final Log log = LogFactory.getLog(PatientBiometricsController.class);

	@RequestMapping(value = "/admin/patients/patient", method = RequestMethod.GET)
	public void redirectPatientOpenMRSToMyPage(HttpServletResponse response,
			@RequestParam("patientId") Patient patient) {
		try {
			response.sendRedirect("../../module/icapbio/patientBiometrics.form?patientId=" + patient.getPatientId());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@RequestMapping(value = "/module/icapbio/patientBiometrics", method = RequestMethod.GET)
	public void renderPatientForm(@RequestParam("patientId") Patient patient, HttpServletRequest request,
			ModelMap map) {

		if (Context.isAuthenticated()) {
			PatientService ps = Context.getPatientService();
			String patientId = request.getParameter("patientId");
			Integer id;
			if (patientId != null) {
				try {
					id = Integer.valueOf(patientId);
					patient = ps.getPatientOrPromotePerson(id);
					if (patient == null) {
						HttpSession session = request.getSession();
						session.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "patientDashboard.noPatientWithId");
						session.setAttribute(WebConstants.OPENMRS_ERROR_ARGS, patientId);
						// return new Patient();
					}
				} catch (NumberFormatException numberError) {
					log.warn("Invalid patientId supplied: '" + patientId + "'", numberError);
				}
			}
		}

		if (patient == null) {
			patient = new Patient();

			String name = request.getParameter("addName");
			if (name != null) {
				String gender = request.getParameter("addGender");
				String date = request.getParameter("addBirthdate");
				String age = request.getParameter("addAge");

				getMiniPerson(patient, name, gender, date, age);
			}
		}

		if (patient.getIdentifiers().size() < 1) {
			patient.addIdentifier(new PatientIdentifier());
		} else {
			// we need to check if current patient has preferred id
			// if no we look for suitable one to set it as preferred
			if (patient.getPatientIdentifier() != null && !patient.getPatientIdentifier().isPreferred()) {

				List<PatientIdentifier> pi = patient.getActiveIdentifiers();
				for (PatientIdentifier patientIdentifier : pi) {
					if (!patientIdentifier.isVoided() && !patientIdentifier.getIdentifierType().isRetired()) {
						patientIdentifier.setPreferred(true);
						break;
					}
				}
			}
		}
		try {
			map = referenceData(request, patient);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void postPatientForm() {

	}

	protected Person setupFormBackingObject(Person person) {

		// set a default name and address for the person. This allows us to use
		// person.names[0] binding in the jsp
		if (person.getNames().size() < 1) {
			person.addName(new PersonName());
		}

		if (person.getAddresses().size() < 1) {
			person.addAddress(new PersonAddress());
		}

		// initialize the user/person sets
		// hibernate seems to have an issue with empty lists/sets if they aren't
		// initialized

		person.getAttributes().size();

		return person;
	}

	public static <P extends Person> void getMiniPerson(P person, String name, String gender, String date, String age) {

		person.addName(Context.getPersonService().parsePersonName(name));

		person.setGender(gender);
		Date birthdate = null;
		boolean birthdateEstimated = false;
		if (StringUtils.isNotEmpty(date)) {
			try {
				// only a year was passed as parameter
				if (date.length() < 5) {
					Calendar c = Calendar.getInstance();
					c.set(Calendar.YEAR, Integer.valueOf(date));
					c.set(Calendar.MONTH, 0);
					c.set(Calendar.DATE, 1);
					birthdate = c.getTime();
					birthdateEstimated = true;
				}
				// a full birthdate was passed as a parameter
				else {
					birthdate = Context.getDateFormat().parse(date);
					birthdateEstimated = false;
				}
			} catch (ParseException e) {
				log.debug("Error getting date from birthdate", e);
			}
		} else if (age != null && !"".equals(age)) {
			Calendar c = Calendar.getInstance();
			c.setTime(new Date());
			Integer d = c.get(Calendar.YEAR);
			d = d - Integer.parseInt(age);
			try {
				birthdate = DateFormat.getDateInstance(DateFormat.SHORT).parse("01/01/" + d);
				birthdateEstimated = true;
			} catch (ParseException e) {
				log.debug("Error getting date from age", e);
			}
		}
		if (birthdate != null) {
			person.setBirthdate(birthdate);
		}
		person.setBirthdateEstimated(birthdateEstimated);

	}

	private ModelMap referenceData(HttpServletRequest request, Patient patient) throws Exception {

		List<Form> forms = new Vector<Form>();
		ModelMap map = new ModelMap();
		List<Encounter> encounters = new Vector<Encounter>();

		if (Context.isAuthenticated() && patient.getPatientId() != null) {
			boolean onlyPublishedForms = true;
			if (Context.hasPrivilege(PrivilegeConstants.VIEW_UNPUBLISHED_FORMS)) {
				onlyPublishedForms = false;
			}
			forms.addAll(Context.getFormService().getForms(null, onlyPublishedForms, null, false, null, null, null));

			List<Encounter> encs = Context.getEncounterService().getEncountersByPatient(patient);
			if (encs != null && encs.size() > 0) {
				encounters.addAll(encs);
			}
		}

		String patientVariation = "";
		if (patient.isDead()) {
			patientVariation = "Dead";
		}

		Concept reasonForExitConcept = Context.getConceptService()
				.getConcept(Context.getAdministrationService().getGlobalProperty("concept.reasonExitedCare"));

		if (reasonForExitConcept != null && patient.getPatientId() != null) {
			List<Obs> patientExitObs = Context.getObsService().getObservationsByPersonAndConcept(patient,
					reasonForExitConcept);
			if (patientExitObs != null && patientExitObs.size() > 0) {
				log.debug("Exit obs is size " + patientExitObs.size());
				if (patientExitObs.size() == 1) {
					Obs exitObs = patientExitObs.iterator().next();
					Concept exitReason = exitObs.getValueCoded();
					Date exitDate = exitObs.getObsDatetime();
					if (exitReason != null && exitDate != null) {
						patientVariation = "Exited";
					}
				} else {
					log.error("Too many reasons for exit - not putting data into model");
				}
			}
		}
		List<PatientIdentifierType> pits = Context.getPatientService().getAllPatientIdentifierTypes();
		boolean identifierLocationUsed = false;
		for (PatientIdentifierType pit : pits) {
			if (pit.getLocationBehavior() == null || pit.getLocationBehavior() == LocationBehavior.REQUIRED) {
				identifierLocationUsed = true;
			}
		}
		map.put("identifierTypes", pits);
		map.put("identifierLocationUsed", identifierLocationUsed);
		map.put("identifiers", patient.getIdentifiers());
		map.put("patientVariation", patientVariation);

		map.put("forms", forms);

		// empty objects used to create blank template in the view
		map.put("emptyIdentifier", new PatientIdentifier());
		map.put("emptyName", new PersonName());
		map.put("emptyAddress", new PersonAddress());
		map.put("encounters", encounters);

		String causeOfDeathOther = "";

		if (Context.isAuthenticated()) {

			String propCause = Context.getAdministrationService().getGlobalProperty("concept.causeOfDeath");
			Concept conceptCause = Context.getConceptService().getConcept(propCause);

			if (conceptCause != null) {
				// TODO add back in for persons
				List<Obs> obssDeath = Context.getObsService().getObservationsByPersonAndConcept(patient, conceptCause);
				if (obssDeath.size() == 1) {
					Obs obsDeath = obssDeath.iterator().next();
					causeOfDeathOther = obsDeath.getValueText();
					if (causeOfDeathOther == null) {
						log.debug("cod is null, so setting to empty string");
						causeOfDeathOther = "";
					} else {
						log.debug("cod is valid: " + causeOfDeathOther);
					}
				} else {
					log.debug("obssDeath is wrong size: " + obssDeath.size());
				}
			} else {
				log.warn("No concept death cause found");
			}

		}
		return map;
	}
}
