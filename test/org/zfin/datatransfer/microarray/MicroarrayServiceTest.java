package org.zfin.datatransfer.microarray;

import org.junit.Ignore;
import org.junit.Test;
import org.zfin.AbstractDatabaseTest;
import org.zfin.expression.service.ExpressionService;
import org.zfin.framework.HibernateUtil;
import org.zfin.marker.Marker;
import org.zfin.repository.RepositoryFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.*;

/**
 */
public class MicroarrayServiceTest extends AbstractDatabaseTest {

    private ExpressionService expressionService = new ExpressionService();

    @Test
    public void updateGeoLink() {
        Marker m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-GENE-030131-9286");
        try {
            assertEquals(0, expressionService.updateGeoLinkForMarker(m));
            RepositoryFactory.getInfrastructureRepository().removeRecordAttributionForData(m.getZdbID(), MicroarrayWebserviceJob.MICROARRAY_PUB);
            assertEquals(1, expressionService.updateGeoLinkForMarker(m));
        } catch (Exception e) {
            fail(e.toString());
        }
    }


    @Test
    @Ignore
    public void findGeoLinkForNCBI() {
        Marker m;
        m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-EST-010427-5"); // af086761
        assertNotNull(m);
        assertThat(expressionService.updateGeoLinkForMarker(m), greaterThan(-1));
        HibernateUtil.currentSession().flush();
        assertNotNull(expressionService.getGeoLinkForMarkerIfExists(m));

        m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-GENE-030131-4918"); // myl12.1
        assertNotNull(m);
        assertThat(expressionService.updateGeoLinkForMarker(m), lessThan(1));
        HibernateUtil.currentSession().flush();
        assertNotNull(expressionService.getGeoLinkForMarkerIfExists(m));

        m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-GENE-110207-1"); // agbl1
        assertNotNull(m);
        assertThat(expressionService.updateGeoLinkForMarker(m), greaterThan(-1));
    }

    @Test
    @Ignore //Ignoring on 11/30 -- we are getting sporadic failures in our NCBI api access (maybe rate limiting?). Additionally, the feature we are testing might need to be removed (ZFIN-7738).
    public void getGeoLinkForMarker() {
        Marker m;

        m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-GENE-041008-244");
        assertNotNull(m);
        assertThat(expressionService.updateGeoLinkForMarker(m), lessThan(1));
        assertThat(expressionService.updateGeoLinkForMarker(m), lessThan(1));


        m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-GENE-001103-2");
        assertThat(expressionService.updateGeoLinkForMarker(m), greaterThan(-1));
        HibernateUtil.currentSession().flush();
        assertThat(expressionService.updateGeoLinkForMarker(m), greaterThan(-1));
//        linkString = expressionService.getGeoLinkForMarker(m); // will still grab this until rerun

        m = RepositoryFactory.getMarkerRepository().getMarkerByID("ZDB-SSLP-000426-106");
        assertThat(expressionService.updateGeoLinkForMarker(m), greaterThan(-1));
        HibernateUtil.currentSession().flush();
        assertThat(expressionService.updateGeoLinkForMarker(m), greaterThan(-1));
        assertNotNull(m);
        assertNull(expressionService.getGeoLinkForMarkerIfExists(m));

    }
}
