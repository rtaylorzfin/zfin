package org.zfin.zirc.repository;

import org.springframework.stereotype.Repository;
import org.zfin.framework.HibernateUtil;
import org.zfin.profile.Person;
import org.zfin.zirc.entity.GenotypingAssay;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.Mutation;

import java.util.List;

@Repository
public class HibernateZircSubmissionRepository implements ZircSubmissionRepository {

    @Override
    public List<LineSubmission> getLineSubmissions() {
        return HibernateUtil.currentSession()
                .createQuery("from ZircLineSubmission order by createdAt desc", LineSubmission.class)
                .list();
    }

    @Override
    public LineSubmission getLineSubmission(String zdbID) {
        return HibernateUtil.currentSession().get(LineSubmission.class, zdbID);
    }

    @Override
    public Mutation getMutation(Long mutationId) {
        return HibernateUtil.currentSession().get(Mutation.class, mutationId);
    }

    @Override
    public GenotypingAssay getAssay(Long assayId) {
        return HibernateUtil.currentSession().get(GenotypingAssay.class, assayId);
    }

    @Override
    public Person getPerson(String personZdbID) {
        return HibernateUtil.currentSession().get(Person.class, personZdbID);
    }

    @Override
    public Person getPersonReference(String personZdbID) {
        return HibernateUtil.currentSession().getReference(Person.class, personZdbID);
    }

    @Override
    public void save(Object entity) {
        HibernateUtil.currentSession().persist(entity);
    }

    @Override
    public void delete(Object entity) {
        HibernateUtil.currentSession().remove(entity);
    }

}
