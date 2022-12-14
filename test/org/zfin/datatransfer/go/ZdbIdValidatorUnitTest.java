package org.zfin.datatransfer.go;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.zfin.AbstractDatabaseTest;
import org.zfin.AppConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class})
@WebAppConfiguration
public class ZdbIdValidatorUnitTest extends AbstractDatabaseTest {

    @Test
    public void validateSimpleID() {

        //test that a known gene validates as existing
        boolean exists = ZdbIdValidator.validateExists("ZDB-GENE-110913-113");
        assertTrue(exists);

        //test that an invalid data type fails to validate
        exists = ZdbIdValidator.validateExists("ZDB-BOGUSTYPE-111111-11");
        assertFalse(exists);

        //test that a valid formatted id that does not exist returns false
        exists = ZdbIdValidator.validateExists("ZDB-GENE-650000-1");
        assertFalse(exists);

    }

    @Test
    public void validateListOfIDs() {

        List<String> ids = List.of(
                "ZDB-XPAT-040724-1381",
                "ZDB-TERMREL-190214-522",
                "ZDB-CUR-190826-593",
                "ZDB-FMREL-120806-15882",
                "ZDB-DALIAS-170508-7",
                "ZDB-FIG-100830-19",
                "ZDB-EXTNOTE-210715-2",
                "ZDB-DALIAS-160831-60188",
                "ZDB-DBLINK-220830-1784",
                "ZDB-DALIAS-091209-19932"
        );

        //test that a list of known entities validate as existing
        boolean exists = ZdbIdValidator.validateAllIdsExist(ids);
        assertTrue(exists);

        //test that a list with some valid and some invalid will fail:
        ids = List.of(
                "ZDB-GENE-650000-1",
                "ZDB-GENE-650000-2",
                "ZDB-GENE-650000-3",
                "ZDB-FMREL-120806-15882",
                "ZDB-DALIAS-170508-7",
                "ZDB-FIG-100830-19",
                "ZDB-EXTNOTE-210715-2",
                "ZDB-DALIAS-160831-60188",
                "ZDB-DBLINK-220830-1784",
                "ZDB-DALIAS-091209-19932"
        );
        exists = ZdbIdValidator.validateAllIdsExist(ids);
        assertFalse(exists);

    }

    @Test
    public void getInvalidIDsFromList() {

        //test that a list with some valid and some invalid will fail:
        List<String> ids = List.of(
                "ZDB-GENE-650000-1",
                "ZDB-GENE-650000-2",
                "ZDB-GENE-650000-3",
                "ZDB-FMREL-120806-15882",
                "ZDB-DALIAS-170508-7",
                "ZDB-FIG-100830-19",
                "ZDB-EXTNOTE-210715-2",
                "ZDB-DALIAS-160831-60188",
                "ZDB-DBLINK-220830-1784",
                "ZDB-DALIAS-091209-19932"
        );
        List<String> invalidIDs = ZdbIdValidator.getInvalidIDsFromSet(new HashSet<>(ids));
        assertEquals(3, invalidIDs.size());
        assertEquals("ZDB-GENE-650000-1", invalidIDs.get(0));
        assertEquals("ZDB-GENE-650000-2", invalidIDs.get(1));
        assertEquals("ZDB-GENE-650000-3", invalidIDs.get(2));

    }

}
