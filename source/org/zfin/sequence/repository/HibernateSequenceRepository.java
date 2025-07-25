package org.zfin.sequence.repository;

import jakarta.persistence.Tuple;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.springframework.stereotype.Repository;
import org.zfin.Species;
import org.zfin.framework.HibernateUtil;
import org.zfin.infrastructure.repository.InfrastructureRepository;
import org.zfin.mapping.GenomeLocation;
import org.zfin.mapping.MarkerGenomeLocation;
import org.zfin.marker.Marker;
import org.zfin.marker.MarkerRelationship;
import org.zfin.marker.MarkerRelationshipType;
import org.zfin.marker.Transcript;
import org.zfin.marker.presentation.RelatedMarkerDBLinkDisplay;
import org.zfin.publication.Publication;
import org.zfin.repository.RepositoryFactory;
import org.zfin.sequence.*;
import org.zfin.sequence.blast.Origination;
import org.zfin.sequence.presentation.AccessionPresentation;

import java.util.*;
import java.util.stream.Collectors;

@Repository
public class HibernateSequenceRepository implements SequenceRepository {

    private final Logger logger = LogManager.getLogger(HibernateSequenceRepository.class);

    public ForeignDB getForeignDBByName(ForeignDB.AvailableName dbName) {
        Session session = HibernateUtil.currentSession();
        Query<ForeignDB> query = session.createQuery("from ForeignDB where dbName = :dbName", ForeignDB.class);
        query.setParameter("dbName", dbName);
        return query.uniqueResult();
    }

    public ReferenceDatabase getReferenceDatabaseByID(String referenceDatabaseID) {
        Session session = HibernateUtil.currentSession();
        Query<ReferenceDatabase> query = session.createQuery("from ReferenceDatabase where zdbID = :zdbID", ReferenceDatabase.class);
        query.setParameter("zdbID", referenceDatabaseID);
        return query.uniqueResult();
    }

    public ReferenceDatabase getReferenceDatabase(ForeignDB.AvailableName foreignDBName, ForeignDBDataType.DataType type, ForeignDBDataType.SuperType superType, Species.Type organism) {

        String hql = " from ReferenceDatabase referenceDatabase " +
                     " join fetch referenceDatabase.foreignDB " +
                     " where referenceDatabase.foreignDB.dbName = :dbName " +
                     " and referenceDatabase.foreignDBDataType.dataType = :type" +
                     " and referenceDatabase.foreignDBDataType.superType = :superType" +
                     " and referenceDatabase.organism  = :organism" +
                     " ";
        Query query = HibernateUtil.currentSession().createQuery(hql);
        query.setParameter("dbName", foreignDBName);
        query.setParameter("type", type);
        query.setParameter("superType", superType);
        query.setParameter("organism", organism.toString()); //ReferenceDatabase.organism is a String
        return (ReferenceDatabase) query.uniqueResult();
    }

    public ReferenceDatabase getZebrafishSequenceReferenceDatabase(ForeignDB.AvailableName foreignDBName,
                                                                   ForeignDBDataType.DataType type) {
        return getReferenceDatabase(foreignDBName, type, ForeignDBDataType.SuperType.SEQUENCE, Species.Type.ZEBRAFISH);
    }

    public List<ReferenceDatabase> getReferenceDatabasesByForeignDBName(ForeignDB.AvailableName dbName) {
        String hql = " from ReferenceDatabase referenceDatabase " +
                     " join fetch referenceDatabase.foreignDB " +
                     " where referenceDatabase.foreignDB.dbName = :dbName ";
        Query query = HibernateUtil.currentSession().createQuery(hql);
        query.setParameter("dbName", dbName);
        return query.list();
    }

    public List<ReferenceDatabase> getSequenceReferenceDatabases(ForeignDB.AvailableName name, ForeignDBDataType.DataType type) {

        String hql = " from ReferenceDatabase referenceDatabase " +
                     " where referenceDatabase.foreignDB.dbName = :dbName " +
                     " and referenceDatabase.foreignDBDataType.dataType = :type" +
                     " and referenceDatabase.foreignDBDataType.superType = :superType" +
                     " and referenceDatabase.organism  = :organism" +
                     " ";
        Query query = HibernateUtil.currentSession().createQuery(hql);
        query.setParameter("dbName", name);
        query.setParameter("type", type);
        query.setParameter("superType", ForeignDBDataType.SuperType.SEQUENCE);
        query.setParameter("organism", Species.Type.ZEBRAFISH.toString());

        return (List<ReferenceDatabase>) query.list();
    }

    ;

    public Accession getAccessionByAlternateKey(String number, ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();
        String hql = "from Accession where number = :number";

        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            hql += " AND referenceDatabase in (:referenceDatabase)";
        }
        Query<Accession> criteria = session.createQuery(hql, Accession.class);
        criteria.setParameter("number", number);
        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            criteria.setParameterList("referenceDatabase", referenceDatabases);
        }
        return criteria.uniqueResult();
    }

    public List<Accession> getAccessionsByNumber(String number) {
        Session session = HibernateUtil.currentSession();
        Query<Accession> query = session.createQuery("from Accession where number = :number", Accession.class);
        query.setParameter("number", number);
        return query.list();
    }


    /**
     * Explicitly do not get transcripts.
     *
     * @param referenceDatabases Reference databases to view.
     * @return
     */
    public Map<String, MarkerDBLink> getUniqueMarkerDBLinks(ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();

        Query<MarkerDBLink> query = session.createQuery("from MarkerDBLink where referenceDatabase in (:referenceDatabase) order by accessionNumber", MarkerDBLink.class);
        query.setParameterList("referenceDatabase", referenceDatabases);
        List<MarkerDBLink> dbLinks = query.list();

        HashMap<String, MarkerDBLink> returnMap = new HashMap<>();

        // todo: this should be logged somewhere else possible and not be tied directly to microarray
        for (MarkerDBLink markerDBLink : dbLinks) {
            if (false == returnMap.containsKey(markerDBLink.getAccessionNumber())) {
                returnMap.put(markerDBLink.getAccessionNumber(), markerDBLink);
            } else {
                if (markerDBLink.getMarker().isInTypeGroup(Marker.TypeGroup.CDNA_AND_EST)) {
                    logger.warn("CDNA/EST accession references more than 1 link:" + markerDBLink.getAccessionNumber());
                } else { // if is in genedom or otherwise, we don't really care
                    logger.debug("Accession references >1 links: " + markerDBLink.getAccessionNumber());
                }
            }

        }

        return returnMap;
    }

    /**
     * Get unique acccessions for a given set of databases.
     *
     * @param referenceDatabases
     * @return
     */
    @SuppressWarnings("unchecked")
    public Set<String> getAccessions(ReferenceDatabase... referenceDatabases) {
        Set<String> results = new HashSet<String>();

        String hql = "" +
                     " select dbl.accessionNumber from DBLink dbl where dbl.referenceDatabase in (:referenceDatabases) ";
        results.addAll(HibernateUtil.currentSession().createQuery(hql)
            .setParameterList("referenceDatabases", referenceDatabases)
            .list());

        return results;
    }

    public Map<String, Collection<MarkerDBLink>> getMarkerDBLinks(ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();

        Query<MarkerDBLink> query = session.createQuery("select mdl from MarkerDBLink mdl join fetch mdl.marker join fetch mdl.publications where mdl.referenceDatabase in (:referenceDatabase) order by mdl.accessionNumber", MarkerDBLink.class);
        query.setParameterList("referenceDatabase", referenceDatabases);
        List<MarkerDBLink> dbLinks = query.list();

        MultiValuedMap returnMap = new ArrayListValuedHashMap<String, MarkerDBLink>();
        for (MarkerDBLink markerDBLink : dbLinks) {
            returnMap.put(markerDBLink.getAccessionNumber(), markerDBLink);
        }

        return returnMap.asMap();
    }

    public DBLink getDBLinkByID(String zdbID) {
        Session session = HibernateUtil.currentSession();
        return (DBLink) session.get(DBLink.class, zdbID);
    }

    public List<DBLink> getDBLinksForAccession(String accessionString) {
        return getDBLinksForAccession(accessionString, true);
    }

    public List<DBLink> getDBLinksForAccession(String accessionString, boolean include, ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();

        String hql = "select dbl from DBLink dbl "
                     + "where dbl.accessionNumber = :accessionNumber ";

        if (referenceDatabases.length > 0) {
            if (include) {
                hql += "and dbl.referenceDatabase in (:referenceDatabases) ";
            } else {
                hql += "and dbl.referenceDatabase not in (:referenceDatabases) ";
            }
        }
        hql += "order by dbl.referenceDatabase asc, dbl.accessionNumber asc ";

        Query query = session.createQuery(hql);

        query.setParameter("accessionNumber", accessionString);
        if (referenceDatabases.length > 0) {
            query.setParameterList("referenceDatabases", referenceDatabases);
        }
        return query.list();
    }

    public List<MarkerDBLink> getMarkerDBLinksForAccession(String accessionString, ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();
        String hql = "from MarkerDBLink where accessionNumber = :accessionNumber";

        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            hql += " AND referenceDatabase in (:referenceDatabase)";
        }
        hql += " order by referenceDatabase, accessionNumber";
        Query<MarkerDBLink> query = session.createQuery(hql, MarkerDBLink.class);
        query.setParameter("accessionNumber", accessionString);
        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            query.setParameterList("referenceDatabase", referenceDatabases);
        }
        return query.list();
    }


    @SuppressWarnings("unchecked")
    public List<String> getGenbankCdnaDBLinks() {
        return (List<String>) HibernateUtil.currentSession().createNativeQuery("" +
                                                                               "select  dbl.dblink_acc_num from db_link dbl , marker m, marker_type_group_member gm " +
                                                                               "where dbl.dblink_fdbcont_zdb_id in  " +
                                                                               "(  " +
                                                                               "   select  " +
                                                                               "   fdbc.fdbcont_zdb_id  " +
                                                                               "   from foreign_db_contains fdbc, foreign_db db, foreign_db_data_type dt  " +
                                                                               "   where " +
                                                                               "   db.fdb_db_name in( 'GenBank', 'RefSeq')  " +
                                                                               "   and " +
                                                                               "   dt.fdbdt_data_type = 'RNA'  " +
                                                                               "   and " +
                                                                               "   fdbc.fdbcont_fdb_db_id = db.fdb_db_pk_id  " +
                                                                               "   and " +
                                                                               "   fdbc.fdbcont_fdbdt_id = dt.fdbdt_pk_id  " +
                                                                               ")  " +
                                                                               "and m.mrkr_zdb_id=dbl.dblink_linked_recid " +
                                                                               "and gm.mtgrpmem_mrkr_type=m.mrkr_type " +
                                                                               "and gm.mtgrpmem_mrkr_type_group in ('GENEDOM','CDNA_AND_EST') " +
                                                                               " ").list();
    }

    /**
     * from getZfinGbAcc.pl, sql_xpat
     * <p/>
     * Select cDNA that is encoded by genes with expression (that are not microRNA).
     * <p/>
     * 1 - select genes with expression that are not microRNA (~10K)
     * 2 - select small segments encoded by those genes (??)
     * 3 - return RNA for a small set of databases (GenBank, Vega_Trans, PREVEGA, RefSeq) (~ 41K) (of 131K)
     *
     * @return List of DBLink accessions.
     */
    @SuppressWarnings("unchecked")
    public Set<String> getGenbankXpatCdnaDBLinks() {
        // this currently takes 30 seconds, returns about 41K records
        Set<String> results = new HashSet<>();
        results.addAll((List<String>) HibernateUtil.currentSession().createNativeQuery(
            """
                select dbl.dblink_acc_num  from db_link dbl, foreign_db_contains, foreign_db, foreign_db_data_type
                where dblink_fdbcont_zdb_id = fdbcont_zdb_id
                and fdb_db_name in ('GenBank','Vega_Trans','PREVEGA','RefSeq')
                and fdbdt_data_type = 'RNA'
                and fdbcont_fdbdt_id = fdbdt_pk_id
                and fdbcont_fdb_db_id = fdb_db_pk_id
                and
                exists (
                select mr.mrel_mrkr_1_zdb_id
                from marker_relationship mr, marker g,  expression_experiment2 ee
                where dbl.dblink_linked_recid = mr.mrel_mrkr_2_zdb_id
                and g.mrkr_zdb_id=mr.mrel_mrkr_1_zdb_id
                and mr.mrel_type='gene encodes small segment'
                and substring(g.mrkr_name from 1 for 7)  <> 'microRNA'
                and ee.xpatex_gene_zdb_id =  g.mrkr_zdb_id
                and exists (select er.xpatres_pk_id from expression_result2 er, expression_figure_stage ef where ef.efs_xpatex_zdb_id = ee.xpatex_zdb_id
                and ef.efs_pk_id = er.xpatres_efs_id)
                union
                select g.mrkr_zdb_id
                from expression_experiment2 ee, marker g
                where dbl.dblink_linked_recid = ee.xpatex_gene_zdb_id
                and substring(g.mrkr_name from 1 for 7) <> 'microRNA'
                and ee.xpatex_gene_zdb_id =  g.mrkr_zdb_id
                and exists (select er.xpatres_pk_id from expression_result2 er, expression_figure_stage ef where ef.efs_xpatex_zdb_id = ee.xpatex_zdb_id
                and ef.efs_pk_id = er.xpatres_efs_id))
                """).list());
        return results;
    }

    @SuppressWarnings("unchecked")
    public List<String> getGenbankSequenceDBLinks() {
        return (List<String>) HibernateUtil.currentSession().createNativeQuery("" +
                                                                               " select dblink_acc_num " +
                                                                               "from db_link " +
                                                                               "where dblink_fdbcont_zdb_id in " +
                                                                               "(" +
                                                                               "   select " +
                                                                               "   fdbcont_zdb_id " +
                                                                               "   from foreign_db_contains, foreign_db, foreign_db_data_type " +
                                                                               "   where fdb_db_name = 'GenBank' " +
                                                                               "   and fdbdt_super_type = 'sequence' " +
                                                                               "   and fdbcont_fdbdt_id = fdbdt_pk_id " +
                                                                               "   and fdbcont_fdb_db_id = fdb_db_pk_id " +
                                                                               ")   " +
                                                                               "").list();
    }

    public List<DBLink> getDBLinks(String accessionString, ReferenceDatabase... referenceDatabases) {
        // check for a version-truncated accession  number as well...
        String truncatedAccession = null;
        if (accessionString.contains("."))
            truncatedAccession = accessionString.substring(0, accessionString.indexOf("."));
        Session session = HibernateUtil.currentSession();
        String hql = "from DBLink where " +
                     " (accessionNumber = :accessionNumber1 or accessionNumber = :accessionNumber2)";
        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            hql += " and referenceDatabase in (:referenceDatabase) ";
        }
        hql += "order by referenceDatabase, accessionNumber";

        Query<DBLink> query = session.createQuery(hql, DBLink.class);
        query.setParameter("accessionNumber1", accessionString);
        query.setParameter("accessionNumber2", truncatedAccession);
        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            query.setParameterList("referenceDatabase", referenceDatabases);
        }
        return query.list();
    }

    public List<TranscriptDBLink> getTranscriptDBLinksForAccession(String accessionString, ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();
        String hql = "from TranscriptDBLink where accessionNumber = :accessionNumber";

        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            hql += " AND referenceDatabase in (:referenceDatabase)";
        }
        hql += " order by referenceDatabase, accessionNumber";
        Query<TranscriptDBLink> query = session.createQuery(hql, TranscriptDBLink.class);
        query.setParameter("accessionNumber", accessionString);
        if (referenceDatabases != null && referenceDatabases.length > 0 && referenceDatabases[0] != null) {
            query.setParameterList("referenceDatabase", referenceDatabases);
        }
        return query.list();
    }

    public List<TranscriptDBLink> getTranscriptDBLinksForAccession(String accessionString, Transcript transcript) {
        Session session = HibernateUtil.currentSession();
        Query<TranscriptDBLink> query = session.createQuery("from TranscriptDBLink where " +
                                                            "accessionNumber = :AccessionNumber and transcript = :transcript order by accessionNumber", TranscriptDBLink.class);
        query.setParameter("accessionNumber", accessionString);
        query.setParameter("transcript", transcript);
        return query.list();
    }

    public List<TranscriptDBLink> getTranscriptDBLinksForTranscript(Transcript transcript,
                                                                    ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();
        Query<TranscriptDBLink> query = session.createQuery("from TranscriptDBLink where " +
                                                            "referenceDatabase in (:referenceDatabase) and transcript = :transcript order by accessionNumber", TranscriptDBLink.class);
        query.setParameter("transcript", transcript);
        query.setParameterList("referenceDatabase", referenceDatabases);
        return query.list();
    }

    public DBLink getDBLinkByAlternateKey(String accessionString, String dataZdbID,
                                          ReferenceDatabase referenceDatabases) {
        Session session = HibernateUtil.currentSession();
        Query<DBLink> query = session.createQuery("from DBLink where " +
                                                  "referenceDatabase = :referenceDatabase and " +
                                                  "dataZdbID = :dataZdbID and accessionNumber = :accessionNumber order by accessionNumber", DBLink.class);
        query.setParameter("accessionNumber", accessionString);
        query.setParameter("referenceDatabase", referenceDatabases);
        query.setParameter("dataZdbID", dataZdbID);
        return query.uniqueResult();
    }

    public List<MarkerDBLink> getDBLinksForMarker(Marker marker, ReferenceDatabase... referenceDatabases) {
        Session session = HibernateUtil.currentSession();
        String hql = "from MarkerDBLink where marker = :marker";

        if (referenceDatabases.length > 0) {
            hql += " AND referenceDatabase in (:referenceDatabase)";
        }
        hql += " order by referenceDatabase, marker";
        Query<MarkerDBLink> query = session.createQuery(hql, MarkerDBLink.class);
        query.setParameter("marker", marker);
        if (referenceDatabases.length > 0) {
            query.setParameterList("referenceDatabase", referenceDatabases);
        }
        return query.list();
    }

    /**
     * Saves a collection of MarkerDBLinks.
     * The try catch block is within the loop because I want to report on failures directly.
     * Notes from here for massive batch inserts: http://docs.jboss.org/hibernate/core/3.3/reference/en/html/batch.html
     *
     * @param dbLinksToAdd   List of DBLinks to add
     * @param attributionPub Publication to attribute
     */
    public void addDBLinks(Collection<MarkerDBLink> dbLinksToAdd, Publication attributionPub, int commitChunk) {
        if (CollectionUtils.isNotEmpty(dbLinksToAdd)) {
            Session session = HibernateUtil.currentSession();
            try {
                InfrastructureRepository ir = RepositoryFactory.getInfrastructureRepository();
                int size = dbLinksToAdd.size();
                int counter = 0;
                for (MarkerDBLink dbLink : dbLinksToAdd) {
                    session.save(dbLink);
                    logger.debug("adding dblink[" + dbLink.getZdbID() + "]:\n" + dbLink.getAccessionNumber() + " db: " + dbLink.getReferenceDatabase().getForeignDB().getDbName() + " markerID[" + dbLink.getMarker().getZdbID() + "]  [" + counter + "/" + (size - 1) + "]");
                    logger.debug("ADDED dblink:\n" + dbLink.getAccessionNumber() + " db: " +
                                 dbLink.getReferenceDatabase().getForeignDB().getDbName() + "[" + counter + "/" + (size - 1) + "]");

                    if (counter % commitChunk == 0 && counter != 0) {
                        logger.debug("flushing links[" + commitChunk + "]" + " [" + counter + "/" + (size - 1) + "]");
                        session.flush();
                    }
                    ++counter;
                }


                if (attributionPub != null) {
                    for (MarkerDBLink dbLink : dbLinksToAdd) {
                        ir.insertRecordAttribution(dbLink.getZdbID(), attributionPub.getZdbID());
                    }
                }
                logger.debug("flushing links[" + commitChunk + "]" + " [" + counter + "/" + (size) + "]");
                session.flush();
            } catch (Exception e) {
                logger.error("failed to save MarkerDBLinks", e);
            }
        }
        dbLinksToAdd.clear();

    }

    /**
     * Removes dbLinks and associated RecordAttribution and ZdbID data.
     *
     * @param dbLinksToRemove DBLinks to remove
     */
    public int removeDBLinks(Collection<DBLink> dbLinksToRemove) {

        logger.debug("dbLinksToRemove.size: " + dbLinksToRemove.size());

        if (dbLinksToRemove.size() == 0) {
            return 0;
        }

        Session session = HibernateUtil.currentSession();
        session.flush();  // without this, it fails
        logger.debug("flushed");


        List<String> dbLinkZdbIdsToDelete = new ArrayList<String>();
        for (DBLink dbLink : dbLinksToRemove) {
            dbLinkZdbIdsToDelete.add(dbLink.getZdbID());
        }

        InfrastructureRepository ir = RepositoryFactory.getInfrastructureRepository();
        if (dbLinkZdbIdsToDelete.size() > 0) {
            ir.deleteActiveDataByZdbID(dbLinkZdbIdsToDelete);
        }
        session.flush();  // test

        String hql = "" +
                     "delete from MarkerDBLink dbl where dbl.id in (:dbLinks)";
        Query query = session.createQuery(hql);
        query.setParameterList("dbLinks", dbLinkZdbIdsToDelete);
        return query.executeUpdate();

    }

    public int removeAccessionByNumber(String accessionNumber) {
        String hql = "" +
                     "delete from Accession a where a.number = :accessionNumber ";
        Query query = HibernateUtil.currentSession().createQuery(hql);
        query.setParameter("accessionNumber", accessionNumber);
        return query.executeUpdate();
    }

    /**
     * Pulled from markerview.apg, line 2524:...
     * This is a union of 3 statements:
     * 1 - get marker DBLinks with a direct sequence reference database
     * 2 - get marker DBLinks with a relation to linked gene  todo: specify type
     * 3 - get marker DBLinks with a relation to linked clone todo: specify type
     *
     * @param marker                Marker to get sequences from
     * @param referenceDatabaseType Type of reference database to pull markers from
     * @return A list of all associated DBLinks.
     */
    public MarkerDBLinkList getAllSequencesForMarkerAndType(Marker marker, ForeignDBDataType.DataType referenceDatabaseType) {
        Session session = HibernateUtil.currentSession();

        MarkerDBLinkList dbLinks = new MarkerDBLinkList();
        String hql = "" +
                     "from MarkerDBLink dbl " +
                     " where dbl.marker.zdbID = :markerZdbID and dbl.referenceDatabase.foreignDBDataType.superType = :superType " +
                     " and dbl.referenceDatabase.foreignDBDataType.dataType = :type " +
                     " order by dbl.referenceDatabase.foreignDB.dbName , dbl.accessionNumber ";

        Query query = session.createQuery(hql);
        query.setParameter("markerZdbID", marker.getZdbID());
        query.setParameter("superType", "sequence");
        query.setParameter("type", referenceDatabaseType);
        dbLinks.addAll(query.list());


        String hql1 = "select dbl " +
                      "from MarkerDBLink dbl, MarkerRelationship mr" +
                      " where mr.secondMarker.zdbID = :markerZdbID and dbl.referenceDatabase.foreignDBDataType.superType = :superType " +
                      " and dbl.referenceDatabase.foreignDBDataType.dataType  = :type and mr.firstMarker.zdbID = dbl.marker.zdbID " +
                      " and mr.firstMarker.markerType = :markerType ";
        // todo: and marker_relation type = .....
//        " and mr.type = :markerRelationshipType " ;
        query = session.createQuery(hql1);
        query.setParameter("markerZdbID", marker.getZdbID());
        query.setParameter("superType", "sequence");
        query.setParameter("type", referenceDatabaseType.name());
        query.setParameter("markerType", Marker.Type.GENE.name()); //if using setParameter, change hql to use "...markerType.name = :markerType..."
        // todo: and marker_relation type = .....
//        query.setParameter("markerRelationshipType", MarkerRelationship.Type.GENE_ENCODES_SMALL_SEGMENT.name()) ;
        dbLinks.addAll(query.list());


        String hql2 = "select dbl " +
                      "from MarkerDBLink dbl, MarkerRelationship mr" +
                      " where mr.secondMarker.zdbID = :markerZdbID and dbl.referenceDatabase.foreignDBDataType.superType = :superType " +
                      " and dbl.referenceDatabase.foreignDBDataType.dataType = :type and mr.firstMarker.zdbID = dbl.marker.zdbID ";
        // todo: and first marker type to clone somehow
//        " and :markerTypeGrroup in (mr.firstMarker.markerType.typeGroupStrings)    " ;
//        " and mr.type = :markerRelationshipType " ;
        // todo: and marker_relation type = .....
        query = session.createQuery(hql2);
        query.setParameter("markerZdbID", marker.getZdbID());
        query.setParameter("superType", ForeignDBDataType.SuperType.SEQUENCE);
        query.setParameter("type", referenceDatabaseType);
//        query.setParameter("markerTypeGroup",Marker.TypeGroup.CLONE.name()) ;
        dbLinks.addAll(query.list());

        return dbLinks;
    }


//    public TreeSet<MarkerDBLink> getSequenceDBLinksForMarker(Marker marker) {
//        Session session = HibernateUtil.currentSession() ;
//
//        TreeSet<MarkerDBLink> dbLinks = new TreeSet<MarkerDBLink>() ;
//
//        String hql = "" +
//                "from MarkerDBLink dbl " +
//                " where dbl.marker.zdbID = :markerZdbID and dbl.referenceDatabase.superType = :superType ";
//
//        Query query = session.createQuery(hql) ;
//        query.setParameter("markerZdbID",marker.getZdbID()) ;
//        query.setParameter("superType","sequence") ;
//        for (Object o : query.list() ) {
//            MarkerDBLink dblink = (MarkerDBLink)o;
//            //todo: evenentually there should be a display group for marker linked sequences..?
//            if (!dblink.isInDisplayGroup(DisplayGroup.GroupName.DISPLAYED_NUCLEOTIDE_SEQUENCE)
//                    && !dblink.isInDisplayGroup(DisplayGroup.GroupName.STEM_LOOP))
//                dbLinks.add(dblink);
//        }
//        return dbLinks ;
//    }
//
//
//    public TreeSet<TranscriptDBLink> getSequenceDBLinksForTranscript(Transcript transcript) {
//        Session session = HibernateUtil.currentSession() ;
//
//        TreeSet<TranscriptDBLink> dbLinks = new TreeSet<TranscriptDBLink>() ;
//
//        String hql = "" +
//                "from TranscriptDBLink dbl " +
//                " where dbl.transcript.zdbID = :transcriptZdbID and dbl.referenceDatabase.superType = :superType ";
//
//        Query query = session.createQuery(hql) ;
//        query.setParameter("transcriptZdbID",transcript.getZdbID()) ;
//        query.setParameter("superType","sequence") ;
//        for (Object o : query.list() ) {
//            TranscriptDBLink dblink = (TranscriptDBLink)o;
//            //todo: evenentually there should be a display group for marker linked sequences..?
//            if (!dblink.isInDisplayGroup(DisplayGroup.GroupName.DISPLAYED_NUCLEOTIDE_SEQUENCE)
//                    && !dblink.isInDisplayGroup(DisplayGroup.GroupName.STEM_LOOP))
//                dbLinks.add(dblink);
//        }
//        return dbLinks ;
//    }


    public MarkerDBLinkList getNonSequenceMarkerDBLinksForMarker(Marker marker) {
        Session session = HibernateUtil.currentSession();
        String hql = "select dbl " +
                     "from MarkerDBLink dbl " +
                     " where dbl.marker.zdbID = :markerZdbID and dbl.referenceDatabase.foreignDBDataType.superType <> :superType";
        Query query = session.createQuery(hql);
        query.setParameter("markerZdbID", marker.getZdbID());
        query.setParameter("superType", ForeignDBDataType.SuperType.SEQUENCE);


        MarkerDBLinkList dbLinks = new MarkerDBLinkList();
        dbLinks.addAll(query.list());

        return dbLinks;
    }

    @Override
    public DBLink getDBLink(String markerZdbID, String accession, String referenceDBName) {
        Session session = HibernateUtil.currentSession();
        String hql = "from DBLink mdbl where mdbl.accessionNumber = :accession " +
                     " and mdbl.dataZdbID = :markerZdbID " +
                     " and mdbl.referenceDatabase.foreignDB.dbName = :referenceDBName";
        Query query = session.createQuery(hql);
        query.setParameter("accession", accession);
        query.setParameter("markerZdbID", markerZdbID);
        query.setParameter("referenceDBName", ForeignDB.AvailableName.getType(referenceDBName));
        return (DBLink) query.uniqueResult();
    }

    @Override
    public DBLink getDBLinkByReferenceDatabaseID(String markerZdbID, String accession, String referenceDatabaseID) {
        Session session = HibernateUtil.currentSession();
        String hql = """
            from DBLink mdbl where mdbl.accessionNumber = :accession
             and mdbl.dataZdbID = :markerZdbID
             and mdbl.referenceDatabase.zdbID = :referenceDatabaseID
            """;
        Query query = session.createQuery(hql);
        query.setParameter("accession", accession);
        query.setParameter("markerZdbID", markerZdbID);
        query.setParameter("referenceDatabaseID", referenceDatabaseID);
        return (DBLink) query.uniqueResult();
    }

    @Override
    public List<DBLink> getAtlasDBLink(String markerZdbID, ForeignDB.AvailableName referenceDBName) {
        String hql = "select mdbl from DBLink mdbl where mdbl.dataZdbID = :markerZdbID " +
                     "and mdbl.referenceDatabase.foreignDB.dbName = :referenceDBName";
        Query query = HibernateUtil.currentSession().createQuery(hql);
        query.setParameter("referenceDBName", referenceDBName);
        query.setParameter("markerZdbID", markerZdbID);
        return query.list();
    }

    @Override
    public DBLink getDBLink(String featureZDbID, String accession) {
        Session session = HibernateUtil.currentSession();
        String hql = "from DBLink mdbl where mdbl.accessionNumber = :accession " +
                     " and mdbl.dataZdbID = :markerZdbID ";

        Query query = session.createQuery(hql);
        query.setParameter("accession", accession);
        query.setParameter("markerZdbID", featureZDbID);

        return (DBLink) query.uniqueResult();
    }

    public List<ReferenceDatabase> getReferenceDatabasesWithInternalBlast() {
        Session session = HibernateUtil.currentSession();
        String hql = "select rd from ReferenceDatabase rd join rd.primaryBlastDatabase blast " +
                     " where blast.origination.type in (:externalTypes) " +
                     " order by rd.foreignDB.dbName asc ";
        Query query = session.createQuery(hql);
        List<Origination.Type> types = new ArrayList<Origination.Type>();
        types.add(Origination.Type.CURATED);
        types.add(Origination.Type.LOADED);
        query.setParameterList("externalTypes", types);
        return (List<ReferenceDatabase>) query.list();
    }

    public Map<String, List<DBLink>> getDBLinksForAccessions(Collection<String> accessionNumbers) {
        Map<String, List<DBLink>> returnMap = new HashMap<>();
        if (CollectionUtils.isEmpty(accessionNumbers)) {
            return returnMap;
        }

        Session session = HibernateUtil.currentSession();
        Query<DBLink> query = session.createQuery("from DBLink where accessionNumber in (:accessionNumber)", DBLink.class);
        query.setParameterList("accessionNumber", accessionNumbers);
        List<DBLink> dbLinks = query.list();

        // cheaper to remap then to do further queries
        for (DBLink dbLink : dbLinks) {
            List<DBLink> dbLinksForAccession = returnMap.get(dbLink.getAccessionNumber());
            if (dbLinksForAccession == null) {
                List<DBLink> dbLinkList = new ArrayList<DBLink>();
                dbLinkList.add(dbLink);
                returnMap.put(dbLink.getAccessionNumber(), dbLinkList);
            } else {
                dbLinksForAccession.add(dbLink);
            }
        }

        return returnMap;
    }


    /**
     * Retrieves all marker ids with sequence information (accession numbers)
     *
     * @param firstNIds number of sequences to be returned
     * @return list of markers
     */
    public List<String> getAllNSequences(int firstNIds) {
        Session session = HibernateUtil.currentSession();
        String hql = "select distinct dataZdbID from DBLink " +
                     "where referenceDatabase.foreignDBDataType.superType = :superType " +
                     " and dataZdbID not like :transcript " +
                     " group by dataZdbID  " +
                     " having count(accessionNumber) > 1   ";

        Query query = session.createQuery(hql);
        query.setParameter("superType", ForeignDBDataType.SuperType.SEQUENCE);
        query.setParameter("transcript", "ZDB-TSCRIPT%");
        if (firstNIds > 0)
            query.setMaxResults(firstNIds);

        return (List<String>) query.list();
    }


    /**
     * TODO:
     * Find dblink where referenceDatabase belongs to "marker linked sequence"
     * <p/>
     * and
     * <p/>
     * dblink is on the second related marker of type 'gene contains small segment', ' clone contains small segment',
     * , or 'gene encodes small segment' and the clone is not chimeric
     * <p/>
     * <p/>
     * dblink is on the first related marker of type 'clone contains gene' and the clone is not chimeric
     *
     * @param zdbID
     * @param superType
     * @return
     */
    //TODO: change ENSDARP to a real HQL query, or re-write as sql: Christian and Sierra struggled with this for a couple of
    //hours with no easy fix using DisplayGroup and DisplayGroupMember.  The mapping on those classes makes for a
    //very odd query output where hibernate tries to find fdbcont_zdb_ids in a collection of fdbcdgm_pk_ids.
    @Override
    public List<DBLink> getDBLinksForMarker(String zdbID, ForeignDBDataType.SuperType superType) {
        Session session = HibernateUtil.currentSession();
        String hql = """
                select distinct dbl
                from DBLink dbl, DisplayGroup dg, ReferenceDatabase ref
                where dbl.referenceDatabase = ref
                and ref.foreignDBDataType.superType = :superType
                and dbl.dataZdbID = :markerZdbId
                and not exists (from DisplayGroupMember dgm where dgm.referenceDatabase = ref and dgm.displayGroup = dg)
                and dg.groupName = :groupName
            """;


        Query<DBLink> query = session.createQuery(hql, DBLink.class);
        query.setParameter("superType", superType);
        query.setParameter("groupName", DisplayGroup.GroupName.HIDDEN_DBLINKS);
        query.setParameter("markerZdbId", zdbID);
        return query.list();
    }


    @Override
    public int getNumberDBLinks(Marker marker) {
        String sql = " select count(*) from ( " +
                     " select dblink_acc_num, fdb_db_name " +
                     "  from db_link, foreign_db_contains, foreign_db, " +
                     "               foreign_db_contains_display_group_member, foreign_db_contains_display_group " +
                     "  where dblink_linked_recid = :markerZdbId " +
                     "    and fdbcont_fdb_db_id = fdb_db_pk_id " +
                     "    and dblink_fdbcont_zdb_id = fdbcont_zdb_id " +
                     "    and fdbcdg_name = 'marker linked sequence' " +
                     "    and fdbcdg_pk_id = fdbcdgm_group_id " +
                     "    and fdbcdgm_fdbcont_zdb_id = fdbcont_zdb_id " +
//                "    -- and fdb_db_name != 'ZFIN_PROT' " +
                     "  UNION " +
                     "  select dblink_acc_num, fdb_db_name " +
                     "  from db_link, foreign_db_contains, marker_relationship, foreign_db, " +
                     "             foreign_db_contains_display_group_member, foreign_db_contains_display_group " +
                     "  where mrel_mrkr_1_zdb_id = :markerZdbId " +
                     "    and fdbcont_fdb_db_id = fdb_db_pk_id " +
                     "    and dblink_linked_recid = mrel_mrkr_2_zdb_id " +
                     "    and dblink_fdbcont_zdb_id = fdbcont_zdb_id " +
                     "    and fdbcdg_name = 'marker linked sequence' " +
                     "    and fdbcdg_pk_id = fdbcdgm_group_id " +
                     "    and fdbcdgm_fdbcont_zdb_id = fdbcont_zdb_id " +
//                "    -- and fdb_db_name != 'ZFIN_PROT' " +
                     "    and mrel_type in ('gene contains small segment', " +
                     "		      'clone contains small segment', " +
                     "		      'gene encodes small segment') " +
                     "    and mrel_mrkr_2_zdb_id not in ('$chimeric_clone_list') " +
                     "  UNION " +
                     "  select dblink_acc_num, fdb_db_name " +
                     "  from db_link, foreign_db_contains,foreign_db, marker_relationship, " +
                     "             foreign_db_contains_display_group_member, foreign_db_contains_display_group " +
                     "  where mrel_mrkr_2_zdb_id = :markerZdbId " +
                     "    and dblink_linked_recid = mrel_mrkr_1_zdb_id " +
                     "    and dblink_fdbcont_zdb_id = fdbcont_zdb_id " +
                     "    and fdbcdg_name = 'marker linked sequence' " +
                     "    and fdbcdg_pk_id = fdbcdgm_group_id " +
                     "    and fdbcdgm_fdbcont_zdb_id = fdbcont_zdb_id " +
                     "    and fdbcont_fdb_db_id = fdb_db_pk_id " +
                     "    and mrel_type in ('clone contains gene') " +
                     "    and mrel_mrkr_1_zdb_id not in ('$chimeric_clone_list') " +
                     "    ) as query ";
        return Integer.parseInt(HibernateUtil.currentSession().createNativeQuery(sql)
            .setParameter("markerZdbId", marker.getZdbID())
            .uniqueResult().toString());
    }

    @Override
    public List<DBLink> getDBLinksForMarkerAndDisplayGroup(Marker marker, DisplayGroup.GroupName groupName) {
        String hql = "select distinct dbl from DBLink dbl  " +
                     "join dbl.referenceDatabase.displayGroupMembers dgm " +
                     "where dgm.displayGroup.groupName = :displayGroup " +
                     "and " +
                     "dbl.dataZdbID = :markerZdbId ";
        Query query = HibernateUtil.currentSession().createQuery(hql)
            .setParameter("markerZdbId", marker.getZdbID())
            .setParameter("displayGroup", groupName);
        return query.list();
    }

    @Override
    public List<TranscriptDBLink> getTranscriptDBLinksForMarkerAndDisplayGroup(Transcript transcript, DisplayGroup.GroupName groupName) {
        String hql = """
                select distinct dbl
                from TranscriptDBLink dbl
                join dbl.referenceDatabase dgmRef
                join DisplayGroupMember dgm on dgmRef = dgm.referenceDatabase
                join dgm.displayGroup dg
                where dg.groupName = :displayGroup
                and dbl.dataZdbID = :markerZdbId
            """;

        Query query = HibernateUtil.currentSession().createQuery(hql)
            .setParameter("markerZdbId", transcript.getZdbID())
            .setParameter("displayGroup", groupName);
        return query.list();
    }

    @Override
    public List<RelatedMarkerDBLinkDisplay> getDBLinksForFirstRelatedMarker(Marker marker, DisplayGroup.GroupName groupName, MarkerRelationship.Type... markerRelationshipTypes) {
        return getDBLinksForNthRelatedMarker(true, marker, groupName, markerRelationshipTypes);
    }

    @Override
    public List<RelatedMarkerDBLinkDisplay> getDBLinksForSecondRelatedMarker(Marker marker, DisplayGroup.GroupName groupName, MarkerRelationship.Type... markerRelationshipTypes) {
        return getDBLinksForNthRelatedMarker(false, marker, groupName, markerRelationshipTypes);
    }

    private List<RelatedMarkerDBLinkDisplay> getDBLinksForNthRelatedMarker(boolean isFirstMarker, Marker marker, DisplayGroup.GroupName groupName, MarkerRelationship.Type... markerRelationshipTypes) {
        String hql = """
                select distinct dbl, mr
                from DBLink dbl, DisplayGroup dg, DisplayGroupMember dgm, ReferenceDatabase ref,
                MarkerRelationship  mr
                where dg.groupName = :displayGroup
                and dbl.referenceDatabase=ref
                and dgm.referenceDatabase = ref
                and dgm.displayGroup = dg
                and mr.markerRelationshipType.name in (:types)
            """;

        if (isFirstMarker) {
            hql += """
                and mr.secondMarker.zdbID = dbl.dataZdbID
                and mr.firstMarker.zdbID = :markerZdbId
                """;
        } else {
            hql += """
                and mr.firstMarker.zdbID = dbl.dataZdbID
                and mr.secondMarker.zdbID = :markerZdbId
                """;
        }

        Set<String> types = new HashSet<>();
        if (markerRelationshipTypes.length != 0) {
            for (MarkerRelationship.Type type : markerRelationshipTypes) {
                types.add(type.toString());
            }
        } else {
            for (MarkerRelationship.Type type : MarkerRelationship.Type.values()) {
                types.add(type.toString());
            }
        }
        Query<Tuple> query = HibernateUtil.currentSession().createQuery(hql, Tuple.class)
            .setParameter("markerZdbId", marker.getZdbID())
            .setParameter("displayGroup", groupName)
            .setParameterList("types", types);
        List<Tuple> results = query.list();
        return results.stream().map(tuple -> {
            MarkerDBLink dblink = (MarkerDBLink) tuple.get(0);
            MarkerRelationship mr = (MarkerRelationship) tuple.get(1);

            RelatedMarkerDBLinkDisplay display = new RelatedMarkerDBLinkDisplay();
            MarkerRelationshipType relationshipType = mr.getMarkerRelationshipType();
            String relationshipLabel;

            if (isFirstMarker) {
                relationshipLabel = relationshipType.getFirstToSecondLabel();
            } else {
                relationshipLabel = relationshipType.getSecondToFirstLabel();
            }
            display.setRelationshipType(relationshipLabel);
            display.setLink(dblink);
            return display;
        }).collect(Collectors.toList());
    }

    @Override
    public Collection<String> getDBLinkAccessionsForMarker(Marker marker, ForeignDBDataType.DataType dataType) {
        String hql = "  select dbl.accessionNumber from DBLink dbl " +
                     "  where dbl.dataZdbID = :markerZdbID   " +
                     "  and dbl.referenceDatabase.foreignDBDataType.dataType = :dataType ";
        return HibernateUtil.currentSession().createQuery(hql)
            .setParameter("markerZdbID", marker.getZdbID())
            .setParameter("dataType", dataType)
            .list();
    }

    @Override
    public Collection<String> getDBLinkAccessionsForEncodedMarkers(Marker marker, ForeignDBDataType.DataType dataType) {
        String hql = "  select dbl.accessionNumber from MarkerRelationship mr join mr.firstMarker m , MarkerDBLink dbl " +
                     "  where mr.firstMarker.zdbID = :markerZdbID   " +
                     "  and mr.secondMarker.zdbID = dbl.dataZdbID  " +
                     "  and mr.type = :markerType  " +
                     "  and dbl.referenceDatabase.foreignDBDataType.dataType = :dataType " +
                     " ";
        return HibernateUtil.currentSession().createQuery(hql)
            .setParameter("markerZdbID", marker.getZdbID())
            .setParameter("dataType", dataType)
            .setParameter("markerType", MarkerRelationship.Type.GENE_ENCODES_SMALL_SEGMENT)
            .list();
    }

    /**
     * select dbl.dblink_acc_num,m.mrkr_zdb_id
     * from db_link dbl
     * join foreign_db_contains fdbc on dbl.dblink_fdbcont_zdb_id=fdbc.fdbcont_zdb_id
     * join foreign_db fdb on fdb.fdb_db_pk_id=fdbc.fdbcont_fdb_db_id
     * join foreign_db_data_type dt on fdbc.fdbcont_fdbdt_id=dt.fdbdt_pk_id
     * join marker m on dbl.dblink_linked_recid=m.mrkr_zdb_id
     * where
     * dt.fdbdt_data_type='RNA'
     * and
     * dt.fdbdt_super_type='sequence'
     * --and  fdb.fdb_db_name='GenBank'
     * and
     * m.mrkr_type in ('GENE','GENEP','EST','CDNA')
     *
     * @return Map&lt;accession,ZdbID&gt;
     */
    @Override
    public Map<String, String> getGeoAccessionCandidates() {
        String hql = " " +
                     "  select dbl.accessionNumber,dbl.dataZdbID from MarkerDBLink dbl " +
                     "  where dbl.referenceDatabase.foreignDBDataType.dataType = :dataType " +
                     "  and dbl.referenceDatabase.foreignDBDataType.superType = :superType " +
                     "  and dbl.marker.markerType.name in (:types) " +
                     "";
        List<String> types = new ArrayList<String>();
        types.add(Marker.Type.CDNA.name());
        types.add(Marker.Type.EST.name());
        types.add(Marker.Type.GENE.name());
        types.add(Marker.Type.GENEP.name());
        List<DBLink> dblinks = HibernateUtil.currentSession().createQuery(hql)
            .setParameterList("types", types)
            .setParameter("dataType", ForeignDBDataType.DataType.RNA)
            .setParameter("superType", ForeignDBDataType.SuperType.SEQUENCE)
            .setResultTransformer(

                (Object[] tuple, String[] aliases) -> {
                    MarkerDBLink dbLink = new MarkerDBLink();
                    dbLink.setAccessionNumber(tuple[0].toString());
                    dbLink.setDataZdbID(tuple[1].toString());
                    return dbLink;
                })
            .list();
        Map<String, String> accessionCandidates = new HashMap<String, String>();
        for (DBLink dbLink : dblinks) {
            accessionCandidates.put(dbLink.getAccessionNumber(), dbLink.getDataZdbID());
        }

        return accessionCandidates;
    }

    @Override
    public List<MarkerDBLink> getWeakReferenceDBLinks(Marker gene, MarkerRelationship.Type type1, MarkerRelationship.Type type2) {
        String hql = """
                select distinct dbl 
                from DBLink dbl, DisplayGroup dg, DisplayGroupMember dgm, ReferenceDatabase ref, MarkerRelationship ctmr, MarkerRelationship gtmr
                where ctmr.firstMarker.zdbID = dbl.dataZdbID
                and dg.groupName = :displayGroup
                and gtmr.firstMarker.zdbID = :markerZdbId
                and dbl.referenceDatabase = ref
                and dgm.referenceDatabase = ref
                and dgm.displayGroup = dg
                and gtmr.secondMarker.zdbID = ctmr.secondMarker.zdbID
                and gtmr.type = :type1
                and ctmr.type = :type2
            """;

        //     " and gtmr.type = 'gene produces transcript' " +
//                " and ctmr.type = 'clone contains transcript' " +

        Query query = HibernateUtil.currentSession().createQuery(hql)
            .setParameter("markerZdbId", gene.getZdbID())
            .setParameter("type1", type1)
            .setParameter("type2", type2)
            .setParameter("displayGroup", DisplayGroup.GroupName.MARKER_LINKED_SEQUENCE);
        return query.list();
    }

    /**
     * Retrieve a list of all accessions for a given database.
     *
     * @param name foreign database
     * @return list of DBLink records.
     */
    @Override
    public List<DBLink> getDBLinks(ForeignDB.AvailableName name) {
        return getDBLinks(name, -1);
    }

    /**
     * Retrieve the first numberOfRecords of all accessions for a given database.
     *
     * @param name            foreign database
     * @param numberOfRecords numberOfRecords
     * @return list of DBLink records.
     */
    @Override
    public List<DBLink> getDBLinks(ForeignDB.AvailableName name, int numberOfRecords) {
        Session session = HibernateUtil.currentSession();
        String hql = "from DBLink where " +
                     " referenceDatabase.foreignDB.dbName = :dbName";
        Query query = session.createQuery(hql);
        query.setParameter("dbName", name);
        if (numberOfRecords > 0)
            query.setMaxResults(numberOfRecords);
        return (List<DBLink>) query.list();
    }

    @Override
    public List<AccessionPresentation> getAccessionPresentation(ForeignDB.AvailableName name, Marker marker) {
        if (marker == null)
            return null;

        Session session = HibernateUtil.currentSession();

        String hql = "select dblink.accessionNumber, dblink.referenceDatabase.foreignDB.dbUrlPrefix, dblink.referenceDatabase.foreignDB.dbUrlSuffix from DBLink dblink " +
                     "      where dblink.referenceDatabase.foreignDB.dbName = :dbName  " +
                     "        and dblink.dataZdbID = :dataZdbID " +
                     "   order by dblink.accessionNumber ";

        return HibernateUtil.currentSession().createQuery(hql)
            .setParameter("dbName", name)
            .setParameter("dataZdbID", marker.getZdbID())
            .setResultTransformer(

                (Object[] tuple, String[] aliases) -> {
                    AccessionPresentation accessionPresentation = new AccessionPresentation();
                    accessionPresentation.setAccessionNumber(tuple[0].toString());
                    if (tuple[2] == null) {
                        accessionPresentation.setUrl(tuple[1].toString() + tuple[0].toString());
                    } else {
                        accessionPresentation.setUrl(tuple[1].toString() + tuple[0].toString() + tuple[2].toString());
                    }
                    return accessionPresentation;

                })
            .list();
    }

    @Override
    public List<DBLink> getDBLinksForAccession(Accession accession) {
        return HibernateUtil.currentSession().createQuery("from DBLink where accessionNumber = :accessionNumber and referenceDatabase = :referenceDatabase", DBLink.class)
            .setParameter("accessionNumber", accession.getNumber())
            .setParameter("referenceDatabase", accession.getReferenceDatabase())
            .list();
    }


    /*
     from db_link link
        join accession_bank acc on link.dblink_acc_num=acc.accbk_acc_num
        join foreign_db_contains fdbc on fdbc.fdbcont_zdb_id = link.dblink_fdbcont_zdb_id
        join foreign_db_data_type fdbdt on fdbc.fdbcont_fdbdt_id = fdbdt.fdbdt_pk_id
        left outer join accession_version av on acc.accbk_acc_num=av.accver_acc_num
        where acc.accbk_pk_id=?
        and fdbdt.fdbdt_super_type = 'sequence'
        and fdbdt.fdbdt_data_type in ( 'RNA','Polypeptide' )


    */

    @Override
    public List<MarkerDBLink> getBlastableDBlinksForAccession(Accession accession) {

        Session session = HibernateUtil.currentSession();

        String hql = """
            from DBLink where accessionNumber = :accessionNumber
            and referenceDatabase = :referenceDatabase
            and referenceDatabase.foreignDBDataType.superType = :superType
            and (referenceDatabase.foreignDBDataType.dataType = :dataType1 OR referenceDatabase.foreignDBDataType.dataType = :dataType2 )
            """;
        List<MarkerDBLink> markerDBLinks = new ArrayList<>(session.createQuery(hql)
            .setParameter("accessionNumber", accession.getNumber())
            .setParameter("referenceDatabase", accession.getReferenceDatabase())
            .setParameter("superType", ForeignDBDataType.SuperType.SEQUENCE)
            .setParameter("dataType1", ForeignDBDataType.DataType.RNA)
            .setParameter("dataType2", ForeignDBDataType.DataType.POLYPEPTIDE)
            .list());

        ReferenceDatabase ensembl = session.get(ReferenceDatabase.class, "ZDB-FDBCONT-061018-1");

        markerDBLinks.addAll(
            session.createQuery("from DBLink where accessionNumber = :accessionNumber and referenceDatabase = :referenceDatabase")
                .setParameter("accessionNumber", accession.getNumber())
                .setParameter("referenceDatabase", ensembl)
                .list()
        );

        return markerDBLinks;
    }

    @Override
    public List<ReferenceDatabase> getReferenceDatabases(List<ForeignDB.AvailableName> availableNames,
                                                         List<ForeignDBDataType.DataType> dataTypes,
                                                         ForeignDBDataType.SuperType superType,
                                                         Species.Type species) {
        String hql = """
             from ReferenceDatabase referenceDatabase 
             where referenceDatabase.foreignDB.dbName in (:dbNames) 
             and referenceDatabase.foreignDBDataType.dataType in  (:types)
             and referenceDatabase.foreignDBDataType.superType = :superType
             and referenceDatabase.organism  = :organism
            """;
        Query<ReferenceDatabase> query = HibernateUtil.currentSession().createQuery(hql, ReferenceDatabase.class);
        query.setParameterList("dbNames", availableNames);
        query.setParameterList("types", dataTypes);
        query.setParameter("superType", superType);
        query.setParameter("organism", species.toString());
        return query.list();
    }

    private List<MarkerDBLink> cachedMarkerDbLinks;

    @Override
    public List<MarkerDBLink> getAllEnsemblGenes(ForeignDB.AvailableName foreignDB) {
        String hql = """ 
                  from MarkerDBLink
                  where referenceDatabase.foreignDB.dbName = (:dbName)
                  AND accessionNumber like 'ENSDARG%'
            """;
        Query<MarkerDBLink> query = HibernateUtil.currentSession().createQuery(hql, MarkerDBLink.class);
        query.setParameter("dbName", foreignDB);
        cachedMarkerDbLinks = query.list();
        return cachedMarkerDbLinks;
    }

    public List<MarkerDBLink> getAllForeignDbGenes(ForeignDB.AvailableName foreignDB) {
        String hql = """ 
                  from MarkerDBLink
                  where referenceDatabase.foreignDB.dbName = (:dbName)
            """;
        Query<MarkerDBLink> query = HibernateUtil.currentSession().createQuery(hql, MarkerDBLink.class);
        query.setParameter("dbName", foreignDB);
        List<MarkerDBLink> markerDbLinks = query.list();
        return markerDbLinks;
    }

    public List<MarkerDBLink>
    getAllGenbankGenes() {
        String hql = """
            select mdb from MarkerDBLink as mdb, DisplayGroupMember as dgm
            where mdb.referenceDatabase.foreignDB.dbName = (:dbName)
            AND mdb.referenceDatabase = dgm.referenceDatabase
            AND dgm.displayGroup.id = 18
            """;
        Query<MarkerDBLink> query = HibernateUtil.currentSession().createQuery(hql, MarkerDBLink.class);
        query.setParameter("dbName", ForeignDB.AvailableName.GENE);
        return query.list();
    }

    public List<MarkerDBLink> getAllVegaGenes() {
        String hql = " from MarkerDBLink  " +
                     " where referenceDatabase.foreignDB.dbName = (:dbName) ";
        Query<MarkerDBLink> query = HibernateUtil.currentSession().createQuery(hql, MarkerDBLink.class);
        query.setParameter("dbName", ForeignDB.AvailableName.VEGA);
        return query.list();
    }

    @Override
    public List<DBLink> getAllEnsemblTranscripts() {
        String hql = " from DBLink  " +
                     " where referenceDatabase.foreignDB.dbName = (:dbName) ";
        Query<DBLink> query = HibernateUtil.currentSession().createQuery(hql, DBLink.class);
        query.setParameter("dbName", ForeignDB.AvailableName.ENSEMBL_TRANS);
        return query.list();
    }

    @Override
    public Map<Marker, List<TranscriptDBLink>> getAllRelevantEnsemblTranscripts() {
        String hql = """
             select link, rel.firstMarker from TranscriptDBLink as link,
             MarkerRelationship as rel
             where link.referenceDatabase.foreignDB.dbName = :dbName
             AND rel.secondMarker = link.transcript
             AND rel.type = :type
            """;
        Query query = HibernateUtil.currentSession().createQuery(hql);
        query.setParameter("dbName", ForeignDB.AvailableName.ENSEMBL_TRANS);
//        query.setParameter("vega", ForeignDB.AvailableName.VEGA_TRANS);
        query.setParameter("type", MarkerRelationship.Type.GENE_PRODUCES_TRANSCRIPT);
        List<Object[]> list = query.list();
        System.out.println("Total Number of Ensembl Transcript records: " + list.size());
        Map<Marker, List<TranscriptDBLink>> map = new HashMap<>();
        list.forEach(tuple -> {
            List<TranscriptDBLink> transcriptList = map.computeIfAbsent(((Marker) tuple[1]), k -> new ArrayList<>());
            transcriptList.add((TranscriptDBLink) tuple[0]);
        });
        return map;
    }


    @Override
    public Integer deleteUnitProtProteome() {
        return HibernateUtil.currentSession().createQuery("delete ReferenceProtein").executeUpdate();
    }

    @Override
    public Integer deleteReferenceProteinByDBLinkID(String dbLinkID) {
        Session session = HibernateUtil.currentSession();
        String hql = "delete from ReferenceProtein rp where rp.uniprotAccession.zdbID = :dbLinkID";
        Query query = session.createQuery(hql);
        query.setParameter("dbLinkID", dbLinkID);
        return query.executeUpdate();
    }

    @Override
    public DisplayGroup getDisplayGroup(DisplayGroup.GroupName displayGroupName) {
        return HibernateUtil.currentSession().createQuery("from DisplayGroup where groupName = :groupName", DisplayGroup.class)
            .setParameter("groupName", displayGroupName).uniqueResult();
    }

    @Override
    public List<MarkerGenomeLocation> getAllGenomeLocations(GenomeLocation.Source source) {
        Session session = HibernateUtil.currentSession();
        String hql = """
            from MarkerGenomeLocation
            where source = :source
            """;
        Query<MarkerGenomeLocation> query = session.createQuery(hql, MarkerGenomeLocation.class);
        query.setParameter("source", source);

        return query.list();
    }

    @Override
    public void saveOrUpdateGenomeLocation(GenomeLocation genomeLocation) {
        Session session = HibernateUtil.currentSession();
        session.saveOrUpdate(genomeLocation);
        session.flush();
    }
}


