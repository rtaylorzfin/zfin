package org.zfin.zirc.repository;

import org.zfin.profile.Person;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.Mutation;

import java.util.List;

public interface ZircSubmissionRepository {

    List<LineSubmission> getLineSubmissions();

    LineSubmission getLineSubmission(String zdbID);

    Mutation getMutation(Long mutationId);

    Person getPerson(String personZdbID);

    Person getPersonReference(String personZdbID);

    void save(Object entity);

    void delete(Object entity);

}
