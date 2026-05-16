package org.zfin.zirc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.zfin.framework.HibernateUtil;
import org.zfin.profile.Person;
import org.zfin.profile.service.ProfileService;
import org.zfin.properties.ZfinPropertiesEnum;
import org.zfin.zirc.api.ZircAssayFormSchema;
import org.zfin.zirc.api.ZircFormSchema;
import org.zfin.zirc.api.ZircMutationFormSchema;
import org.zfin.zirc.dto.FieldUpdate;
import org.zfin.zirc.entity.AuditEntry;
import org.zfin.zirc.entity.GenotypingAssay;
import org.zfin.zirc.entity.GenotypingAssayFile;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.LineSubmissionPerson;
import org.zfin.zirc.entity.Mutation;
import org.zfin.zirc.repository.ZircSubmissionRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
        writeAudit("submission", zdbID, "update", update.path(), oldValue, update.value());
        HibernateUtil.flushAndCommitCurrentSession();

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
        writeAudit("mutation", String.valueOf(mutationId), "update",
                update.path(), oldValue, update.value());
        HibernateUtil.flushAndCommitCurrentSession();

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

    /**
     * Look up an assay by database id alone. Parent-mutation ownership
     * checks are deferred to the controller when needed.
     */
    public GenotypingAssay getRequiredAssayById(Long assayId) {
        GenotypingAssay assay = repository.getAssay(assayId);
        if (assay == null) {
            throw new ZircEntityNotFoundException("Assay " + assayId + " not found");
        }
        return assay;
    }

    /**
     * Apply a single field change to a GenotypingAssay against
     * {@link ZircAssayFormSchema#FIELDS}. Mirrors {@link #updateMutationField}
     * but for the per-assay aggregate; audit log keys by assay id.
     */
    public GenotypingAssay updateAssayField(Long assayId, FieldUpdate update) {
        GenotypingAssay assay = getRequiredAssayById(assayId);

        ZircAssayFormSchema.FieldDescriptor descriptor =
                ZircAssayFormSchema.FIELDS.get(update.path());
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown assay field path: " + update.path());
        }

        JsonNode oldValue = descriptor.read().apply(assay);
        HibernateUtil.createTransaction();
        descriptor.write().accept(assay, update.value());
        writeAudit("assay", String.valueOf(assayId), "update",
                update.path(), oldValue, update.value());
        HibernateUtil.flushAndCommitCurrentSession();

        return assay;
    }

    private static String safeJson(JsonNode node) {
        try {
            return node == null ? "null" : AUDIT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Persist a row in {@code zirc.audit} and emit the legacy {@code ZIRC_AUDIT}
     * log line so existing log-based pipelines keep working. The insert is
     * invoked inside whatever transaction the caller has already opened so
     * the audit reflects committed state precisely; if the main commit fails
     * the audit row rolls back with it.
     */
    private void writeAudit(
            String entityKind,
            String entityId,
            String action,
            String path,
            JsonNode oldValue,
            JsonNode newValue) {
        Person currentUser = ProfileService.getCurrentSecurityUser();
        String actor = currentUser == null ? "anonymous" : currentUser.getZdbID();

        AuditEntry entry = new AuditEntry();
        entry.setActor(actor);
        entry.setEntityKind(entityKind);
        entry.setEntityId(entityId);
        entry.setAction(action);
        entry.setPath(path);
        entry.setOldValue(safeJson(oldValue));
        entry.setNewValue(safeJson(newValue));
        repository.save(entry);

        log.info("ZIRC_AUDIT user={} {}={} action={} path={} old={} new={}",
                actor, entityKind, entityId, action,
                path == null ? "-" : path,
                safeJson(oldValue),
                safeJson(newValue));
    }

    public Mutation addMutation(String submissionId) {
        LineSubmission submission = getRequiredLineSubmission(submissionId);
        HibernateUtil.createTransaction();
        Mutation mutation = new Mutation();
        mutation.setLineSubmission(submission);
        mutation.setSortOrder(nextMutationSortOrder(submission));
        repository.save(mutation);
        HibernateUtil.currentSession().flush();
        writeAudit("submission", submissionId, "create-mutation", null,
                null, AUDIT_MAPPER.valueToTree(
                        java.util.Map.of("mutationId", mutation.getId())));
        HibernateUtil.flushAndCommitCurrentSession();
        return mutation;
    }

    public void deleteMutation(String submissionId, Long mutationId) {
        Mutation mutation = getRequiredMutation(submissionId, mutationId);
        HibernateUtil.createTransaction();
        repository.delete(mutation);
        writeAudit("submission", submissionId, "delete-mutation", null,
                AUDIT_MAPPER.valueToTree(java.util.Map.of("mutationId", mutationId)),
                null);
        HibernateUtil.flushAndCommitCurrentSession();
    }

    /**
     * Create a new {@link GenotypingAssay} under the given mutation. Mirrors
     * {@link #addMutation} — assigns the next sort order so cards stay
     * stably ordered. Returns the parent mutation so callers can refresh the
     * MutationResponse in one round trip.
     */
    public Mutation addAssay(Long mutationId) {
        Mutation mutation = getRequiredMutationById(mutationId);
        HibernateUtil.createTransaction();
        GenotypingAssay assay = new GenotypingAssay();
        assay.setMutation(mutation);
        assay.setSortOrder(nextAssaySortOrder(mutation));
        repository.save(assay);
        mutation.getGenotypingAssays().add(assay);
        HibernateUtil.currentSession().flush();
        writeAudit("mutation", String.valueOf(mutationId), "create-assay", null,
                null, AUDIT_MAPPER.valueToTree(
                        java.util.Map.of("assayId", assay.getId())));
        HibernateUtil.flushAndCommitCurrentSession();
        return mutation;
    }

    public void deleteAssay(Long assayId) {
        GenotypingAssay assay = getRequiredAssayById(assayId);
        Long mutationId = assay.getMutation() == null ? null : assay.getMutation().getId();
        HibernateUtil.createTransaction();
        repository.delete(assay);
        writeAudit("mutation", mutationId == null ? "?" : String.valueOf(mutationId),
                "delete-assay", null,
                AUDIT_MAPPER.valueToTree(java.util.Map.of("assayId", assayId)),
                null);
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

    private static int nextAssaySortOrder(Mutation mutation) {
        return mutation.getGenotypingAssays().stream()
                .map(GenotypingAssay::getSortOrder)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    // ─── Attachments (M4.3) ─────────────────────────────────────────────────

    /** Hard upper bound on a single uploaded attachment. */
    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    /**
     * Content-types we'll accept. Starter list — internal-curator workflow.
     * For richer formats (e.g. CSV chromatograms) extend this list.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/svg+xml", "image/tiff",
            "application/pdf",
            "text/plain", "text/csv");

    public GenotypingAssayFile getRequiredAssayFile(Long fileId) {
        GenotypingAssayFile file = repository.getAssayFile(fileId);
        if (file == null) {
            throw new ZircEntityNotFoundException("Assay file " + fileId + " not found");
        }
        return file;
    }

    /**
     * Persist an uploaded {@link MultipartFile} as an attachment on the
     * given assay. Layout per the schema comment:
     * {@code $TARGETROOT/server_apps/data_transfer/ZIRC/<submission zdb id>/
     *  assay-<assay id>-<file id>-<sanitized filename>}.
     *
     * <p>Returns the parent assay so callers can return a refreshed
     * AssayResponse in one round trip.
     */
    public GenotypingAssay storeAttachment(Long assayId, MultipartFile upload) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }
        if (upload.getSize() > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException(
                    "Attachment exceeds size limit (" + MAX_ATTACHMENT_BYTES + " bytes)");
        }
        String contentType = upload.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Content type not allowed: " + contentType);
        }

        GenotypingAssay assay = getRequiredAssayById(assayId);
        String submissionId = assay.getMutation().getLineSubmission().getZdbID();
        String safeName = sanitizeFilename(upload.getOriginalFilename());

        HibernateUtil.createTransaction();

        // Insert first to obtain the generated file id, then we can build
        // a deterministic on-disk name. storedPath stays placeholder until
        // the file is actually written; we update it below.
        GenotypingAssayFile file = new GenotypingAssayFile();
        file.setAssay(assay);
        file.setOriginalFilename(upload.getOriginalFilename());
        file.setContentType(contentType);
        file.setFileSize(upload.getSize());
        file.setStoredPath("__pending__");
        repository.save(file);
        HibernateUtil.currentSession().flush();

        Path dir = Paths.get(
                ZfinPropertiesEnum.TARGETROOT.value(),
                "server_apps", "data_transfer", "ZIRC", submissionId);
        Files.createDirectories(dir);

        String storedName = "assay-" + assayId + "-" + file.getId() + "-" + safeName;
        Path stored = dir.resolve(storedName);
        upload.transferTo(stored.toFile());
        file.setStoredPath(stored.toString());

        // Audit the upload — old=null, new={file id + original name + size}
        JsonNode meta = AUDIT_MAPPER.valueToTree(java.util.Map.of(
                "fileId", file.getId(),
                "originalFilename", upload.getOriginalFilename(),
                "bytes", upload.getSize()));
        writeAudit("assay", String.valueOf(assayId), "upload", null, null, meta);

        HibernateUtil.flushAndCommitCurrentSession();

        return assay;
    }

    public void deleteAttachment(Long fileId) {
        GenotypingAssayFile file = getRequiredAssayFile(fileId);
        Long assayId = file.getAssay() == null ? null : file.getAssay().getId();
        String storedPath = file.getStoredPath();
        String originalFilename = file.getOriginalFilename();

        HibernateUtil.createTransaction();
        repository.delete(file);
        // Audit captures the about-to-be-removed file's metadata as "old".
        JsonNode meta = AUDIT_MAPPER.valueToTree(java.util.Map.of(
                "fileId", fileId,
                "originalFilename", originalFilename == null ? "" : originalFilename,
                "storedPath", storedPath == null ? "" : storedPath));
        writeAudit("assay", assayId == null ? "?" : String.valueOf(assayId),
                "delete-file", null, meta, null);
        HibernateUtil.flushAndCommitCurrentSession();

        // Best-effort unlink of the on-disk file. We've already committed
        // the DB delete, so a missing file is not an error condition.
        if (storedPath != null) {
            try {
                Files.deleteIfExists(Paths.get(storedPath));
            } catch (IOException e) {
                log.warn("ZIRC attachment delete: failed to remove file on disk: {}", storedPath, e);
            }
        }
    }

    /**
     * Strip path separators, control characters, and leading dots from a
     * caller-supplied filename so it's safe to use as the suffix of a
     * server-constructed path. Returns {@code "file"} as a fallback when
     * the result would otherwise be empty.
     */
    static String sanitizeFilename(String name) {
        if (name == null) {return "file";}
        String trimmed = name.replaceAll("[\\\\/\\u0000-\\u001F]", "_");
        // Collapse path traversal segments to underscores too.
        trimmed = trimmed.replace("..", "_");
        // Strip leading dots so we don't create hidden files.
        while (trimmed.startsWith(".")) {trimmed = trimmed.substring(1);}
        if (trimmed.isBlank()) {return "file";}
        // Cap length so very long names don't blow up file systems.
        if (trimmed.length() > 200) {trimmed = trimmed.substring(0, 200);}
        return trimmed;
    }

    public File resolveAttachmentPath(GenotypingAssayFile file) {
        return new File(file.getStoredPath());
    }

}
