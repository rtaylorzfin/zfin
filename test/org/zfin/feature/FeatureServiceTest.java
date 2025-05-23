package org.zfin.feature;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Ignore;
import org.junit.Test;
import org.zfin.AbstractDatabaseTest;
import org.zfin.feature.repository.FeatureRepository;
import org.zfin.feature.repository.FeatureService;
import org.zfin.infrastructure.PublicationAttribution;
import org.zfin.marker.presentation.PhenotypeOnMarkerBean;
import org.zfin.repository.RepositoryFactory;
import org.zfin.sequence.FeatureDBLink;
import org.zfin.sequence.ReferenceDatabase;

import java.util.Calendar;
import java.util.Collection;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FeatureServiceTest extends AbstractDatabaseTest {

    static Logger logger = LogManager.getLogger(FeatureServiceTest.class);
    FeatureRepository featureRepository = RepositoryFactory.getFeatureRepository();

    @Test
    public void summaryPageLinksTest() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-130627-1");
        Set<FeatureDBLink> featureDbLinks = FeatureService.getSummaryDbLinks(feature);
        assertThat("Feature has summary page dblinks", featureDbLinks, is(notNullValue()));
    }

    @Test
    public void genbankLinksTest() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-100113-10");

        Set<FeatureDBLink> featureDbLinks = FeatureService.getSummaryDbLinks(feature);
        Set<FeatureDBLink> genbankFeatureDbLinks = FeatureService.getGenbankDbLinks(feature);
        assertThat("Feature has genbank dblinks", genbankFeatureDbLinks, is(notNullValue()));
    }
    @Test
    @Ignore
    public void zircLinksTest() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-020426-42");

        Set<FeatureDBLink> featureDbLinks = FeatureService.getSummaryDbLinks(feature);
        FeatureDBLink zircGenoLink = FeatureService.getZIRCGenoLink(feature);
        assertThat("Feature has zirc genotyping protocol", zircGenoLink, is(notNullValue()));
    }

    @Test
    public void getReferenceDatabaseDna() {
        // check that the version number is stripped off...
        ReferenceDatabase referenceDatabase = FeatureService.getForeignDbMutationDetailDna("NM_212779.1");
        assertThat(referenceDatabase, is(notNullValue()));
    }

    @Test
    public void sb60ShouldHaveDnaChangeAttribution() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-071127-4");
        Collection<PublicationAttribution> attributions = FeatureService.getDnaChangeAttributions(feature);
        assertThat(attributions, is(notNullValue()));
        assertThat(attributions, is(not(empty())));
    }

    @Test
    public void sb60ShouldHaveTranscriptConsequenceAttribution() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-071127-4");
        Collection<PublicationAttribution> attributions = FeatureService.getTranscriptConsequenceAttributions(feature);
        assertThat(attributions, is(notNullValue()));
        assertThat(attributions, is(not(empty())));
    }

    @Test
    public void sb60ShouldHaveProteinConsequenceAttribution() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-071127-4");
        Collection<PublicationAttribution> attributions = FeatureService.getProteinConsequenceAttributions(feature);
        assertThat(attributions, is(notNullValue()));
        assertThat(attributions, is(not(empty())));
    }

    @Test
    public void checkFeatureGenomeEvidenceMapping() {
        String evidenceCode = "TAS";
        String termId = FeatureService.getFeatureGenomeLocationEvidenceCodeTerm(evidenceCode);
    }

    @Test
    public void checkFeatureWithCleanPhenotypeOnPub() {
        Feature feature = featureRepository.getFeatureByID("ZDB-ALT-190821-6");
        PhenotypeOnMarkerBean bean = FeatureService.getPhenotypeOnFeature(feature);
        assertNotNull(bean);
    }

    @Test
    public void checkFeatureWithCleanPhenotypeOnPubPerformance() {
        long start = Calendar.getInstance().getTimeInMillis();
        String id = "ZDB-ALT-980203-444";
        RepositoryFactory.getExpressionRepository().getPhenotypeFromExpressionsByFeature(id);
        long timediff = Calendar.getInstance().getTimeInMillis() - start;
        assertTrue(timediff < 1000);
    }
}
