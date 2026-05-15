package org.zfin.zirc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zfin.framework.HibernateUtil;
import org.zfin.profile.Person;
import org.zfin.profile.service.ProfileService;
import org.zfin.zirc.api.ZircFormSchema;
import org.zfin.zirc.api.ZircMutationFormSchema;
import org.zfin.zirc.dto.FieldUpdate;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.LineSubmissionPerson;
import org.zfin.zirc.entity.Mutation;
import org.zfin.zirc.repository.ZircSubmissionRepository;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@Log4j2
public class ZircSubmissionService {

    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

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

    /**
     * Apply a single field change against the form schema. The path is checked
     * against {@link ZircFormSchema#FIELDS}; unknown paths raise
     * {@link IllegalArgumentException} (mapped to 400 by the advice). The same
     * descriptor's read is used to capture the pre-update value for the audit
     * log so old/new round-trip through Jackson consistently.
     */
    public LineSubmission updateField(String zdbID, FieldUpdate update) {
        LineSubmission submission = getRequiredLineSubmission(zdbID);

        ZircFormSchema.FieldDescriptor descriptor = ZircFormSchema.FIELDS.get(update.path());
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown form field path: " + update.path());
        }

        JsonNode oldValue = descriptor.read().apply(submission);
        HibernateUtil.createTransaction();
        descriptor.write().accept(submission, update.value());
        HibernateUtil.flushAndCommitCurrentSession();

        Person currentUser = ProfileService.getCurrentSecurityUser();
        String userId = currentUser == null ? "anonymous" : currentUser.getZdbID();
        log.info("ZIRC_AUDIT user={} submission={} path={} old={} new={}",
                userId, zdbID, update.path(),
                safeJson(oldValue),
                safeJson(update.value()));

        return submission;
    }

    /**
     * Apply a single field change to a Mutation against
     * {@link ZircMutationFormSchema#FIELDS}. Mirrors {@link #updateField} but
     * for the per-mutation aggregate; audit log keys by mutation id.
     */
    public Mutation updateMutationField(Long mutationId, FieldUpdate update) {
        Mutation mutation = repository.getMutation(mutationId);
        if (mutation == null) {
            throw new ZircEntityNotFoundException("Mutation " + mutationId + " not found");
        }

        ZircMutationFormSchema.FieldDescriptor descriptor =
                ZircMutationFormSchema.FIELDS.get(update.path());
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown mutation field path: " + update.path());
        }

        JsonNode oldValue = descriptor.read().apply(mutation);
        HibernateUtil.createTransaction();
        descriptor.write().accept(mutation, update.value());
        HibernateUtil.flushAndCommitCurrentSession();

        Person currentUser = ProfileService.getCurrentSecurityUser();
        String userId = currentUser == null ? "anonymous" : currentUser.getZdbID();
        log.info("ZIRC_AUDIT user={} mutation={} path={} old={} new={}",
                userId, mutationId, update.path(),
                safeJson(oldValue),
                safeJson(update.value()));

        return mutation;
    }

    /**
     * Look up a mutation by database id alone (no parent-submission check).
     * Used by the mutation edit page where the URL only carries the mutation
     * id; ownership/visibility checks come from the parent submission via
     * {@link Mutation#getLineSubmission()} when needed.
     */
    public Mutation getRequiredMutationById(Long mutationId) {
        Mutation mutation = repository.getMutation(mutationId);
        if (mutation == null) {
            throw new ZircEntityNotFoundException("Mutation " + mutationId + " not found");
        }
        return mutation;
    }

    private static String safeJson(JsonNode node) {
        try {
            return node == null ? "null" : AUDIT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "?";
        }
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

}
