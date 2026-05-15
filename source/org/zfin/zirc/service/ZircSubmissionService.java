package org.zfin.zirc.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zfin.framework.HibernateUtil;
import org.zfin.profile.Person;
import org.zfin.profile.service.ProfileService;
import org.zfin.zirc.dto.LineSubmissionAcceptanceReasonsUpdate;
import org.zfin.zirc.dto.LineSubmissionAdditionalInfoUpdate;
import org.zfin.zirc.dto.LineSubmissionBackgroundUpdate;
import org.zfin.zirc.dto.LineSubmissionOverviewUpdate;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.LineSubmissionPerson;
import org.zfin.zirc.entity.Mutation;
import org.zfin.zirc.repository.ZircSubmissionRepository;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class ZircSubmissionService {

    @Autowired
    private ZircSubmissionRepository repository;

    public List<LineSubmission> getActiveLineSubmissions() {
        return repository.getLineSubmissions().stream()
                .filter(submission -> submission.getDeletedAt() == null)
                .filter(submission -> !Boolean.FALSE.equals(submission.getIsDraft()) || submission.getSubmittedAt() == null)
                .toList();
    }

    public List<LineSubmission> getClosedLineSubmissions() {
        return Collections.emptyList();
    }

    public LineSubmission getRequiredLineSubmission(String zdbID) {
        LineSubmission submission = repository.getLineSubmission(zdbID);
        if (submission == null || submission.getDeletedAt() != null) {
            throw new ZircEntityNotFoundException("Line submission " + zdbID + " not found");
        }
        return submission;
    }

    public Mutation getRequiredMutation(String submissionId, Long mutationId) {
        Mutation mutation = repository.getMutation(mutationId);
        if (mutation == null || mutation.getLineSubmission() == null ||
                !submissionId.equals(mutation.getLineSubmission().getZdbID())) {
            throw new ZircEntityNotFoundException("Mutation " + mutationId + " not found on line submission " + submissionId);
        }
        return mutation;
    }

    public LineSubmission createDraftForCurrentUser() {
        HibernateUtil.createTransaction();
        LineSubmission submission = new LineSubmission();
        repository.save(submission);
        addCurrentUserAsSubmitter(submission);
        HibernateUtil.flushAndCommitCurrentSession();
        return submission;
    }

    public LineSubmission updateOverview(String zdbID, LineSubmissionOverviewUpdate update) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        HibernateUtil.createTransaction();
        submission.setName(clean(update.name()));
        submission.setAbbreviation(clean(update.abbreviation()));
        submission.setPreviousNames(clean(update.previousNames()));
        HibernateUtil.flushAndCommitCurrentSession();
        return submission;
    }

    public LineSubmission updateAcceptanceReasons(String zdbID, LineSubmissionAcceptanceReasonsUpdate update) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        HibernateUtil.createTransaction();
        submission.setReasons(update.reasons() == null ? new String[0] : update.reasons());
        submission.setReasonsOther(clean(update.reasonsOther()));
        HibernateUtil.flushAndCommitCurrentSession();
        return submission;
    }

    public LineSubmission updateBackground(String zdbID, LineSubmissionBackgroundUpdate update) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        HibernateUtil.createTransaction();
        submission.setSingleAllelic(update.singleAllelic());
        submission.setMaternalBackground(clean(update.maternalBackground()));
        submission.setPaternalBackground(clean(update.paternalBackground()));
        submission.setBackgroundChangeable(update.backgroundChangeable());
        submission.setBackgroundChangeConcerns(clean(update.backgroundChangeConcerns()));
        HibernateUtil.flushAndCommitCurrentSession();
        return submission;
    }

    public LineSubmission updateAdditionalInfo(String zdbID, LineSubmissionAdditionalInfoUpdate update) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        HibernateUtil.createTransaction();
        submission.setUnreportedFeaturesDetails(clean(update.unreportedFeaturesDetails()));
        submission.setHusbandryInfo(clean(update.husbandryInfo()));
        submission.setAdditionalInfo(clean(update.additionalInfo()));
        HibernateUtil.flushAndCommitCurrentSession();
        return submission;
    }

    public Mutation addMutation(String submissionId) {
        LineSubmission submission = getRequiredLineSubmission(submissionId);
        HibernateUtil.createTransaction();
        Mutation mutation = new Mutation();
        mutation.setLineSubmission(submission);
        mutation.setSortOrder(nextMutationSortOrder(submission));
        repository.save(mutation);
        HibernateUtil.flushAndCommitCurrentSession();
        return mutation;
    }

    public void deleteMutation(String submissionId, Long mutationId) {
        Mutation mutation = getRequiredMutation(submissionId, mutationId);
        HibernateUtil.createTransaction();
        repository.delete(mutation);
        HibernateUtil.flushAndCommitCurrentSession();
    }

    public void addSubmitter(String zdbID, String personZdbID) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);
        Person person = repository.getPerson(personZdbID);
        if (person == null) {
            throw new ZircEntityNotFoundException("Person " + personZdbID + " not found");
        }
        boolean alreadyLinked = submission.getPersons().stream()
                .anyMatch(lsp -> personZdbID.equals(lsp.getPerson().getZdbID()) && "submitter".equals(lsp.getRole()));
        if (alreadyLinked) {
            return;
        }
        HibernateUtil.createTransaction();
        LineSubmissionPerson lsp = new LineSubmissionPerson();
        lsp.setLineSubmission(submission);
        lsp.setPerson(person);
        lsp.setRole("submitter");
        lsp.setSortOrder(submission.getPersons().size() + 1);
        repository.save(lsp);
        HibernateUtil.flushAndCommitCurrentSession();
    }

    private void addCurrentUserAsSubmitter(LineSubmission submission) {
        Person currentUser = ProfileService.getCurrentSecurityUser();
        if (currentUser == null || currentUser.getZdbID() == null) {
            return;
        }
        Person attachedUser = repository.getPersonReference(currentUser.getZdbID());
        LineSubmissionPerson lsp = new LineSubmissionPerson();
        lsp.setLineSubmission(submission);
        lsp.setPerson(attachedUser);
        lsp.setRole("submitter");
        lsp.setSortOrder(1);
        repository.save(lsp);
    }

    private static int nextMutationSortOrder(LineSubmission submission) {
        return submission.getMutations().stream()
                .map(Mutation::getSortOrder)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
