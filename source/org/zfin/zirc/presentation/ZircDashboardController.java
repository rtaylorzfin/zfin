package org.zfin.zirc.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.zfin.framework.HibernateUtil;
import org.zfin.framework.presentation.LookupStrings;
import org.zfin.profile.Person;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.service.ZircEntityNotFoundException;
import org.zfin.zirc.service.ZircSubmissionService;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/zirc")
public class ZircDashboardController {

    @Autowired
    private ZircSubmissionService zircSubmissionService;

    @RequestMapping(value = "/dashboard", method = RequestMethod.GET)
    public String viewDashboard(Model model) {
        model.addAttribute("activeSubmissions", zircSubmissionService.getActiveLineSubmissions());
        model.addAttribute("closedSubmissions", zircSubmissionService.getClosedLineSubmissions());
        model.addAttribute(LookupStrings.DYNAMIC_TITLE, "Line Submission Dashboard");
        return "zirc/dashboard";
    }

    @RequestMapping(value = "/line-submission/{zdbID}", method = RequestMethod.GET)
    public String viewLineSubmission(@PathVariable String zdbID, Model model) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        model.addAttribute("submission", submission);
        model.addAttribute(LookupStrings.DYNAMIC_TITLE, "Line Submission: " + submission.getName());
        return "zirc/line-submission-detail";
    }

    /**
     * Legacy page entry point. The React editor should create the draft through
     * /action/api/zirc/line-submissions on first save, but this route is kept as
     * a durable URL for the dashboard button while the page is being migrated.
     */
    @RequestMapping(value = "/line-submission/new", method = RequestMethod.GET)
    public String newLineSubmission(Model model) {
        model.addAttribute("submission", new LineSubmission());
        model.addAttribute(LookupStrings.DYNAMIC_TITLE, "New Line Submission");
        return "zirc/line-submission-edit";
    }

    @RequestMapping(value = "/line-submission/{zdbID}/edit", method = RequestMethod.GET)
    public String editLineSubmission(@PathVariable String zdbID, Model model) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        model.addAttribute("submission", submission);
        String label = (submission.getName() != null && !submission.getName().isBlank())
                ? submission.getName() : zdbID;
        model.addAttribute(LookupStrings.DYNAMIC_TITLE, "Edit Line Submission: " + label);
        return "zirc/line-submission-edit";
    }

    /**
     * JSON autocomplete for the "add submitter" modal on the line-submission detail page.
     * Returns a list of {label, value, fullName} entries suitable for jQuery UI autocomplete:
     * "label" is shown in the dropdown ("Pich, Christian (ZDB-PERS-060413-1)"), "value" is
     * the person's full name (placed back into the input), and "fullName"/"zdbID" are picked
     * up by the select-handler to POST the add request.
     */
    @RequestMapping(value = "/persons/search", method = RequestMethod.GET)
    @ResponseBody
    public List<Map<String, String>> searchPersons(@RequestParam(value = "term", required = false) String term) {
        if (term == null || term.isBlank()) {
            return Collections.emptyList();
        }
        List<Person> people = HibernateUtil.currentSession()
                .createQuery(
                    "from Person where lower(fullName) like :q order by fullName",
                    Person.class)
                .setParameter("q", "%" + term.toLowerCase() + "%")
                .setMaxResults(20)
                .list();
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Person p : people) {
            Map<String, String> entry = new HashMap<>();
            entry.put("label", p.getFullName() + " (" + p.getZdbID() + ")");
            entry.put("value", p.getFullName());
            entry.put("zdbID", p.getZdbID());
            entry.put("fullName", p.getFullName());
            out.add(entry);
        }
        return out;
    }

    /**
     * Add a person to a line submission as a submitter (POST). Idempotent — returns silently
     * if the (submission, person, role) tuple already exists.
     */
    @RequestMapping(value = "/line-submission/{zdbID}/add-submitter", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, String> addSubmitter(@PathVariable String zdbID,
                                            @RequestParam("personZdbID") String personZdbID) {
        Person person = HibernateUtil.currentSession().get(Person.class, personZdbID);
        if (person == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Person " + personZdbID + " not found");
        }
        zircSubmissionService.addSubmitter(zdbID, personZdbID);
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("personZdbID", personZdbID);
        result.put("personName", person.getFullName());
        return result;
    }

    private LineSubmission getRequiredLineSubmission(String zdbID) {
        try {
            return zircSubmissionService.getRequiredLineSubmission(zdbID);
        } catch (ZircEntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

}
