package org.zfin;

import junit.framework.JUnit4TestAdapter;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.junit.runner.RunWith;
import org.junit.runners.AllTests;
import org.zfin.anatomy.AnatomySmokeTest;
import org.zfin.antibody.smoketest.AntibodySmokeTest;
import org.zfin.datatransfer.DownloadSmokeTest;
import org.zfin.expression.presentation.FigureSummarySmokeTest;
import org.zfin.feature.presentation.FeatureDetailSmokeTest;
import org.zfin.feature.presentation.GenotypeDetailSmokeTest;
import org.zfin.figure.presentation.FigureViewWebSpec;
import org.zfin.fish.smoketest.FishSmokeTest;
import org.zfin.fish.smoketest.PhenotypeSummarySmokeTest;
import org.zfin.gwt.lookup.LookupSmokeTest;
import org.zfin.httpunittest.MarkerViewSmokeTest;
import org.zfin.mapping.MappingDetailSmokeTest;
import org.zfin.marker.MarkerStrSmokeTest;
import org.zfin.marker.MarkerselectWebSpec;
import org.zfin.mutant.smoketest.ConstructSmokeTest;
import org.zfin.ontology.presentation.OntologyWebSpec;
import org.zfin.search.presentation.SearchWebSpec;
import org.zfin.sequence.blast.smoketest.BlastSmokeTest;
import org.zfin.webservice.MarkerRestSmokeTest;
import org.zfin.webservice.MarkerSoapClientSmokeTest;
import org.zfin.webservice.MarkerSoapSmokeTest;

import java.util.List;
import java.util.stream.Stream;

/**
 * Smoke tests: Integration tests.
 */
@RunWith(AllTests.class)
public class SmokeTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite();
        for (Test test : findAllTestCases()) {
            suite.addTest(test);
        }
        return suite;
    }

    private static List<JUnit4TestAdapter> findAllTestCases() {
        return findAllTestCaseClasses()
                .stream()
                .map(JUnit4TestAdapter::new)
                .toList();
    }

    private static List<Class> findAllTestCaseClasses() {
        List<Class> testsThatShouldPass = List.of(new Class[]{
                AnatomySmokeTest.class,
                AntibodySmokeTest.class,
                BlastSmokeTest.class,
                DownloadSmokeTest.class,
                FeatureDetailSmokeTest.class,
                FigureSummarySmokeTest.class,
                FishSmokeTest.class,
                PhenotypeSummarySmokeTest.class,
                ConstructSmokeTest.class,
                GenotypeDetailSmokeTest.class,
                LookupSmokeTest.class,
                MappingDetailSmokeTest.class,
                MarkerSoapSmokeTest.class,
                MarkerSoapClientSmokeTest.class,
                MarkerStrSmokeTest.class,
                MarkerViewSmokeTest.class,
                MarkerRestSmokeTest.class
        });

        List<Class> legacyTests = List.of(new Class[] {
                FigureViewWebSpec.class,
                MarkerselectWebSpec.class,
                OntologyWebSpec.class,
                SearchWebSpec.class
        });

        List<Class> allTests = Stream.concat(testsThatShouldPass.stream(), legacyTests.stream()).toList();

        String singleTest = System.getProperty("singleTest");
        if (singleTest == null) {
            return testsThatShouldPass;
        } else{
            System.out.println("Picked up flag singleTest: " + singleTest);
            System.out.flush();
            List<Class> matchingTests = allTests.stream().filter(c -> c.getSimpleName().equals(singleTest)).toList();
            if (matchingTests.size() == 0) {
                System.out.println("No test found with name " + singleTest);
                System.out.flush();
            }
            return matchingTests;
        }
    }
}


