package org.zfin.gwt.marker;

import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.zfin.AbstractDatabaseTest;
import org.zfin.framework.HibernateUtil;
import org.zfin.gwt.root.dto.*;
import org.zfin.gwt.root.server.MarkerGoEvidenceRPCServiceImpl;
import org.zfin.gwt.root.ui.DuplicateEntryException;
import org.zfin.gwt.root.ui.MarkerGoEvidenceRPCService;
import org.zfin.mutant.MarkerGoTermEvidence;
import org.zfin.mutant.presentation.MarkerGoEvidencePresentation;
import org.zfin.mutant.repository.MutantRepositoryTest;
import org.zfin.publication.Publication;
import org.zfin.repository.RepositoryFactory;

import java.util.Set;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.*;
import static org.zfin.framework.HibernateUtil.currentSession;

/**
 * DB tests for MarkerGoEvidence code.
 */
@FixMethodOrder
public class GoEvidenceTest extends AbstractDatabaseTest {

    @Test
    public void getGoEvidenceDTO(){
        MarkerGoTermEvidence markerGoTermEvidence = MutantRepositoryTest.findSingleMarkerGoTermEvidenceWithOneInference();
        MarkerGoEvidenceRPCService markerRPCService = new MarkerGoEvidenceRPCServiceImpl();
        GoEvidenceDTO goEvidenceDTO = markerRPCService.getMarkerGoTermEvidenceDTO(markerGoTermEvidence.getZdbID()) ;
        assertNotNull(goEvidenceDTO);
        assertEquals(markerGoTermEvidence.getZdbID(),goEvidenceDTO.getZdbID());
        assertEquals(markerGoTermEvidence.getMarker().getZdbID(),goEvidenceDTO.getMarkerDTO().getZdbID());
        if(markerGoTermEvidence.getFlag()==null){
            assertNull(goEvidenceDTO.getFlag());
        }else{
            assertNotNull(goEvidenceDTO.getFlag());
            assertEquals(markerGoTermEvidence.getFlag(),goEvidenceDTO.getFlag());
        }
        assertEquals(GoEvidenceCodeEnum.getType(markerGoTermEvidence.getEvidenceCode().getCode()),goEvidenceDTO.getEvidenceCode());
        assertNotNull(goEvidenceDTO.getInferredFrom());
        assertEquals(1,goEvidenceDTO.getInferredFrom().size());

    }

    @Test
    public void validateReferenceDatabases(){
        assertNotNull(MarkerGoEvidencePresentation.getGenbankReferenceDatabase()) ;
        assertNotNull(MarkerGoEvidencePresentation.getEcReferenceDatabase()) ;
        assertNotNull(MarkerGoEvidencePresentation.getGenpeptReferenceDatabase()) ;
        assertNotNull(MarkerGoEvidencePresentation.getGoReferenceDatabase()) ;
        assertNotNull(MarkerGoEvidencePresentation.getInterproReferenceDatabase()) ;
        assertNotNull(MarkerGoEvidencePresentation.getRefseqReferenceDatabase()) ;
        assertNotNull(MarkerGoEvidencePresentation.getSpkwReferenceDatabase()) ;
    }

    /**
     * Will only be changing the qualifier, evidence code and pub.  The qualifier can be null and not-null.
     */
    @Test
    @Ignore("broken")
    public void editGoEvidenceHeader() throws TermNotFoundException, DuplicateEntryException {
        MarkerGoTermEvidence markerGoTermEvidence = MutantRepositoryTest.findSingleMarkerGoTermEvidenceWithOneInference();
        MarkerGoEvidenceRPCService markerRPCService = new MarkerGoEvidenceRPCServiceImpl();
        GoEvidenceDTO goEvidenceDTO = markerRPCService.getMarkerGoTermEvidenceDTO(markerGoTermEvidence.getZdbID()) ;
        assertNotNull(goEvidenceDTO);
        assertEquals(markerGoTermEvidence.getZdbID(),goEvidenceDTO.getZdbID());
        assertEquals(1,goEvidenceDTO.getInferredFrom().size());

        assertEquals(markerGoTermEvidence.getMarker().getZdbID(),goEvidenceDTO.getMarkerDTO().getZdbID());
        if(markerGoTermEvidence.getFlag()==null){
            assertNull(goEvidenceDTO.getFlag());
        }else{
            assertNotNull(goEvidenceDTO.getFlag());
            assertEquals(markerGoTermEvidence.getFlag(),goEvidenceDTO.getFlag());
        }
        assertEquals(GoEvidenceCodeEnum.getType(markerGoTermEvidence.getEvidenceCode().getCode()),goEvidenceDTO.getEvidenceCode());
        assertNotNull(goEvidenceDTO.getPublicationZdbID());
        assertEquals(markerGoTermEvidence.getSource().getZdbID(),goEvidenceDTO.getPublicationZdbID());



        // set it to some random pub and add inference group
        Publication publication = (Publication) currentSession().createQuery("from Publication").setMaxResults(1).uniqueResult();
        assertNotSame(goEvidenceDTO.getPublicationZdbID(),publication.getZdbID());
        goEvidenceDTO.setEvidenceCode(GoEvidenceCodeEnum.IC);
        goEvidenceDTO.setFlag(GoEvidenceQualifier.CONTRIBUTES_TO);
        goEvidenceDTO.setPublicationZdbID(publication.getZdbID());

        Set<String> inferredFromSet = goEvidenceDTO.getInferredFrom();
        String inferenceTestString = InferenceCategory.REFSEQ.prefix()+" test-inference" ;
        inferredFromSet.add(inferenceTestString) ;

        GoEvidenceDTO goEvidenceDTO2 = markerRPCService.editMarkerGoTermEvidenceDTO(goEvidenceDTO);

        // validate
        assertEquals(GoEvidenceCodeEnum.IC,goEvidenceDTO2.getEvidenceCode());
        assertEquals(2,goEvidenceDTO2.getInferredFrom().size());
        assertTrue(goEvidenceDTO2.getInferredFrom().contains(inferenceTestString));
        assertEquals(GoEvidenceQualifier.CONTRIBUTES_TO,goEvidenceDTO2.getFlag());
        assertEquals(publication.getZdbID(),goEvidenceDTO2.getPublicationZdbID());

        // lets set the evidence flag to null
        goEvidenceDTO2.setFlag(null);
        goEvidenceDTO2.setEvidenceCode(GoEvidenceCodeEnum.IGI);
        inferredFromSet = goEvidenceDTO2.getInferredFrom();
        assertTrue(inferredFromSet.remove(inferenceTestString));
        goEvidenceDTO2.setPublicationZdbID(goEvidenceDTO.getPublicationZdbID());
        GoEvidenceDTO goEvidenceDTO3 = markerRPCService.editMarkerGoTermEvidenceDTO(goEvidenceDTO2);

        // validate same as before
        assertNull(goEvidenceDTO3.getFlag());
        assertEquals(1,goEvidenceDTO3.getInferredFrom().size());
        assertFalse(goEvidenceDTO3.getInferredFrom().contains(inferenceTestString));
        assertNotNull(goEvidenceDTO3.getEvidenceCode());
        assertEquals(GoEvidenceCodeEnum.IGI,goEvidenceDTO3.getEvidenceCode());
        assertEquals(goEvidenceDTO.getPublicationZdbID(),goEvidenceDTO3.getPublicationZdbID());
    }



    /**
     * Will only be changing the qualifier, evidence code and pub.  The qualifier can be null and not-null.
     */
    @Test
    @Ignore("broken")
    public void createGoEvidenceHeader() throws TermNotFoundException, DuplicateEntryException {
        MarkerGoTermEvidence markerGoTermEvidence = MutantRepositoryTest.findSingleMarkerGoTermEvidenceWithOneInference();
        MarkerGoEvidenceRPCService markerRPCService = new MarkerGoEvidenceRPCServiceImpl();
        GoEvidenceDTO goEvidenceDTO = markerRPCService.getMarkerGoTermEvidenceDTO(markerGoTermEvidence.getZdbID()) ;
        assertNotNull(goEvidenceDTO);
        assertEquals(markerGoTermEvidence.getZdbID(),goEvidenceDTO.getZdbID());

        // now lets set it to null and create some stuff
        goEvidenceDTO.setZdbID(null);
        GoEvidenceDTO goEvidenceDTOCreated = markerRPCService.createMarkerGoTermEvidence(goEvidenceDTO);
        assertEquals(1,goEvidenceDTOCreated.getInferredFrom().size());

        // make non-duplicate
        goEvidenceDTO.setEvidenceCode(GoEvidenceCodeEnum.IC);

        assertEquals(markerGoTermEvidence.getMarker().getZdbID(),goEvidenceDTOCreated.getMarkerDTO().getZdbID());
        assertNull(goEvidenceDTOCreated.getFlag());
        assertNotNull(goEvidenceDTOCreated.getEvidenceCode());
        assertEquals(GoEvidenceCodeEnum.IC,goEvidenceDTOCreated.getEvidenceCode());
        String pub1 = goEvidenceDTOCreated.getPublicationZdbID();
        assertNotNull(pub1);

        HibernateUtil.createTransaction();
        //System.out.println("deleting: " + goEvidenceDTOCreated.getZdbID()) ;
        RepositoryFactory.getInfrastructureRepository().deleteActiveDataByZdbID(goEvidenceDTOCreated.getZdbID());
        HibernateUtil.flushAndCommitCurrentSession();
    }

    @Test(expected = DuplicateEntryException.class)
    @Ignore("broken")
    public void testDuplicateEntry() throws TermNotFoundException, DuplicateEntryException {
        MarkerGoTermEvidence markerGoTermEvidence = MutantRepositoryTest.findSingleMarkerGoTermEvidenceWithOneInference();
        MarkerGoEvidenceRPCService markerRPCService = new MarkerGoEvidenceRPCServiceImpl();
        GoEvidenceDTO goEvidenceDTO = markerRPCService.getMarkerGoTermEvidenceDTO(markerGoTermEvidence.getZdbID()) ;
        assertNotNull(goEvidenceDTO);
        assertEquals(markerGoTermEvidence.getZdbID(), goEvidenceDTO.getZdbID());

        // now lets set it to null and create some stuff
        goEvidenceDTO.setZdbID(null);
        try {
            markerRPCService.createMarkerGoTermEvidence(goEvidenceDTO);
        } finally {
            HibernateUtil.rollbackTransaction();
        }

    }
}
