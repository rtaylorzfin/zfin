#!/opt/zfin/bin/perl
use strict;
use warnings FATAL => 'all';

#
# NCBI_gene_load.pl
#
# This script loads the following db_link records based on mapped gene records between ZFIn and NCBI:
# 1) NCBI Gene Ids
# 2) UniGene Ids     ## as of January, 2020, no more UniGene Ids will be loaded or kept at ZFIN.
# 3) RefSeq accessions (including RefSeq RNA, RefPept, RefSeq DNA)
# 4) GenBank accessions (including GenBank RNA, GenPept, GenBank DNA)
#
# The script execute the prepareNCBIgeneLoad.sql to generate the delete list and a set of ZFIN genes with RNA.
# Then, the script maps ZFIN gene records to NCBI gene records based on
# 1) common GenBank RNA accessions
# 2) common Vega Gene Id
# Then, the script execute the loadNCBIgeneAccs.sql to delete all the db_link records previously loaded
# (accrding to the delete list), and load all the accessions for the gene records mapped.
#
# The values of dblink_length are also processed and loaded.
# And statistics and various reports are generated and emailed.

#
# See README or http://fogbugz.zfin.org/default.asp?W1625
# for a detailed documentation of the steps.

# execute the following to run the scriot in debug mode:
# perl -s NCBI_gene_load.pl -debug

use DBI;
use Cwd;
use POSIX;
use Try::Tiny;
use FindBin;

#relative path to library file(s) (ZFINPerlModules.pm)
use lib "$FindBin::Bin/../../";

#path to this directory (for NCBIState.pm)
use lib $FindBin::Bin;
use NCBIState;

# use lib $libraryPath;
use ZFINPerlModules qw(assertEnvironment trim getPropertyValue downloadOrUseLocalFile);

# ------------------- global variables, with variable names self-explanatory  ---------------------------------
our $debug = 1;



sub main {

    assertEnvironment('ROOT_PATH', 'PGHOST', 'DB_NAME', 'SWISSPROT_EMAIL_ERR', 'SWISSPROT_EMAIL_REPORT');

    system("/bin/date");

    # set environment variables
    chdir $ENV{'ROOT_PATH'} . "/server_apps/data_transfer/NCBIGENE/";

    &initializeDatabase();

    &removeOldFiles();

    &openLoggingFileHandles();

    #-------------------------------------------------------------------------------------------------
    # Step 1: Download and decompress NCBI data files
    #-------------------------------------------------------------------------------------------------
    &downloadNCBIFiles();

    &prepareNCBIgeneLoadDatabaseQuery();

    &getMetricsOfDbLinksToDelete();

    # Get Record Counts using global variables
    &getRecordCounts();

    &readZfinGeneInfoFile();

    #----------------------------------------------------------------------------------------------------------------------
    # Step 5: Map ZFIN gene records to NCBI gene records based on GenBank RNA sequences
    #----------------------------------------------------------------------------------------------------------------------

    #-----------------------------------------
    # Step 5-1: initial set of ZFIN records
    #-----------------------------------------
    &initializeSetsOfZfinRecords();

    #--------------------------------------------------------------------------------------------------------------
    # Step 5-2: Get dblink_length values
    #
    # This section continues to deal with dblink_length field
    # There are 3 sources for length:
    # 1) the existing dblink_length for GenBank including GenPept records
    # 2) the length value of RefSeq sequences on NCBI's RefSeq-release#.catalog file
    # 3) calculated length
    # 1) and 2) will be done by the following section and before parsing the gene2accession file.
    # And the length value will be stored in hash %sequenceLength
    # During parsing gene2accession file, accessions still missing length will be stored in a hash named %noLength
    # 3) will be done after parsing gene2accession file.
    #---------------------------------------------------------------------------------------------------------------
    &initializeSequenceLengthHash();

    #----------------------- 2) parse RefSeq-release#.catalog file to get the length for RefSeq sequences ----------------------

    &parseRefSeqCatalogFileForSequenceLength();

    &printSequenceLengthsCount();

    &parseGene2AccessionFile();

    &countNCBIGenesWithSupportingGenBankRNA();

    &logGenBankDNAncbiGeneIds();

    &logSupportingAccNCBI();

    &initializeHashOfNCBIAccessionsSupportingMultipleGenes();

    &initializeMapOfZfinToNCBIgeneIds();

    &logOneToZeroAssociations();

    &oneWayMappingNCBItoZfinGenes();

    &compare2WayMappingResults();

    #---------------- open a .unl file as the add list -----------------
    open(TOLOAD, ">toLoad.unl") || die "Cannot open toLoad.unl : $!\n";

    # -------- write the NCBI gene Ids mapped based on GenBank RNA accessions on toLoad.unl ------------
    &writeNCBIgeneIdsMappedBasedOnGenBankRNA();

    #------------------------ get 1:N list and N:N from ZFIN to NCBI -----------------------------
    &getOneToNNCBItoZFINgeneIds();

    #------------------------ get N:1 list and N:N from ZFIN to NCBI -----------------------------
    &getNtoOneAndNtoNfromZFINtoNCBI();

    #--------------------- report 1:N ---------------------------------------------
    &reportOneToN();

    #------------------- report N:1 -------------------------------------------------
    &reportNtoOne();

    ##-----------------------------------------------------------------------------------
    ## Step 6: map ZFIN gene records to NCBI gene Ids based on common Vega Gene Id
    ##-----------------------------------------------------------------------------------

    #---------------------------------------------------------------------------
    # prepare the list of ZFIN gene with Vega Ids to be mapped to NCBI records
    #---------------------------------------------------------------------------
    &buildVegaIDMappings();


    ## ---------------------------------------------------------------------------------------------------------------------
    ## doing the mapping based on common Vega Gene Id
    ## ---------------------------------------------------------------------------------------------------------------------
    &writeCommonVegaGeneIdMappings();

    #--------------------------------------------------------------------------------------------------------------
    # This section CONTINUES to deal with dblink_length field
    # There are 3 sources for length:
    # 1) the existing dblink_length for GenBank including GenPept records
    # 2) the length value of RefSeq sequences on NCBI's RefSeq-release#.catalog file
    # 3) calculated length
    # The first two have been done before parsing the gene2accession file.
    # During parsing gene2accession file, accessions still missing length are stored in a hash named %noLength
    #---------------------------------------------------------------------------------------------------------------

    #----------------------- 3) calculate the length for the those still with no length ---------------
    &calculateLengthForAccessionsWithoutLength();

    #---------------------------------------------------------------------------------------------
    # Step 7: prepare the final add-list for RefSeq and GenBank records
    #---------------------------------------------------------------------------------------------
    &getGenBankAndRefSeqsWithZfinGenes();

    #---------------------------------------------------------------------------
    #  write GenBank RNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    &writeGenBankRNAaccessionsWithMappedGenesToLoad();

    #---------------------------------------------------------------------------------------
    #  write GenPept accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------------------
    &initializeGenPeptAccessionsMap();

    &processGenBankAccessionsAssociatedToNonLoadPubs();

    # ----- get all the Genpept accessions associated with gene at ZFIN, and those with multiple ZFIN genes ----------------------------
    &printGenPeptsAssociatedWithGeneAtZFIN();

    #---------------------------------------------------------------------------
    #  write GenBank DNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    &writeGenBankDNAaccessionsWithMappedGenesToLoad();

    #---------------------------------------------------------------------------
    #  write RefSeq RNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    &writeRefSeqRNAaccessionsWithMappedGenesToLoad();

    #---------------------------------------------------------------------------
    #  write RefPept accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    &writeRefPeptAccessionsWithMappedGenesToLoad();

    #---------------------------------------------------------------------------
    #  write RefSeq DNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    &writeRefSeqDNAaccessionsWithMappedGenesToLoad();

    close TOLOAD;

    system("/bin/date");
    print LOG "Done everything before doing the deleting and inserting\n";
    print LOG "\n$NCBIState::ctToDelete total number of db_link records are dropped.\n$NCBIState::ctToLoad total number of new records are added.\n\n";
    print STATS "\n$NCBIState::ctToDelete total number of db_link records are dropped.\n$NCBIState::ctToLoad total number of new records are added.\n\n";

    #-----------------------------------------------------------------------------------------------------------------------
    # Step 8: execute the SQL file to do the deletion according to delete list, and do the loading according to te add list
    #-----------------------------------------------------------------------------------------------------------------------
    &executeDeleteAndLoadSQLFile();

    &sendLoadLogs;

    #-------------------------------------------------------------------------------------------------
    # Step 9: Report the GenPept accessions associated with multiple ZFIN genes after the load.
    # Report GenPept accessions associated with ZFIN genes still attributed to a non-load pub.
    # And do the record counts after the load, and report statistics.
    #-------------------------------------------------------------------------------------------------

    &reportAllLoadStatistics();

    &emailLoadReports();

    print LOG "\n\nAll done! \n\n\n";
    close LOG;

    system("/bin/date");

    exit;
}

#---------------------- subroutines  -------------------------------------------------------

# The return code from "system" isn't reliable when used in syntax of "system(...) or die ..."
# Use this subroutine to a better handling.
sub doSystemCommand {

  my $systemCommand = $_[0];

  print LOG "$0: Executing [$systemCommand] \n";

  my $returnCode = system( $systemCommand );

  if ( $returnCode != 0 ) {
     my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: failed at: $systemCommand . $! ";
     print LOG "\nFailed to execute system command, $systemCommand\nExit.\n\n";

     if ($systemCommand =~ m/loadNCBIgeneAccs\.sql/) {
       &sendLoadLogs;
     }
     &reportErrAndExit($subjectLine);
  }
}

sub reportErrAndExit {
  my $subjectError = $_[0];
  ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_ERR'},"$subjectError","logNCBIgeneLoad");
  close LOG;
  exit -1;
}

sub sendLoadLogs {
  my $subject = "Auto from $NCBIState::dbname: NCBI_gene_load.pl :: loadLog1 file";
  ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_ERR'},"$subject","loadLog1");
}

sub initializeDatabase {
    $NCBIState::dbname = $ENV{'DB_NAME'};
    $NCBIState::dbhost = $ENV{'PGHOST'};
    $NCBIState::username = "";
    $NCBIState::password = "";
    #open a handle on the db
    $NCBIState::handle = DBI->connect ("DBI:Pg:dbname=$NCBIState::dbname;host=$NCBIState::dbhost", $NCBIState::username, $NCBIState::password)
        or die "Cannot connect to database: $DBI::errstr\n";
}

sub removeOldFiles {
    #------------------------------------------------
    # remove old files
    #------------------------------------------------

    if (!$ENV{"SKIP_DOWNLOADS"}) {
        print "Removing old files in 5 seconds...\n";
        sleep(5);

        print "Removing prepareLog* loadLog* logNCBIgeneLoad debug* report* toDelete.unl toMap.unl toLoad.unl length.unl noLength.unl seq.fasta *.gz zf_gene_info.gz gene2vega.gz gene2accession.gz RefSeqCatalog.gz RELEASE_NUMBER\n";
        system("/bin/rm -f prepareLog*");
        system("/bin/rm -f loadLog*");
        system("/bin/rm -f logNCBIgeneLoad");
        system("/bin/rm -f debug*");
        system("/bin/rm -f report*");
        system("/bin/rm -f toDelete.unl");
        system("/bin/rm -f toMap.unl");
        system("/bin/rm -f toLoad.unl");
        system("/bin/rm -f length.unl");
        system("/bin/rm -f noLength.unl");
        system("/bin/rm -f seq.fasta");
        system("/bin/rm -f *.gz");

        system("/bin/rm -f zf_gene_info.gz");
        system("/bin/rm -f gene2vega.gz");
        system("/bin/rm -f gene2accession.gz");
        system("/bin/rm -f RefSeqCatalog.gz");
        system("/bin/rm -f RELEASE_NUMBER");
    }
}

sub openLoggingFileHandles {
    open LOG, '>', "logNCBIgeneLoad" or die "can not open logNCBIgeneLoad: $! \n";
    open STATS, '>', "reportStatistics" or die "can not open reportStatistics" ;
    print LOG "Start ... \n";
}

sub downloadNCBIFiles {
    ## only the following RefSeq catalog file may remain unchanged over a period of time
    ## the rest 3 are changing every day
    our $releaseNum = &getReleaseNumber();
    print LOG "RefSeq Catalog Release Number is $releaseNum.\n\n";

    &downloadNCBIFilesForRelease($releaseNum);
    print LOG "Done with downloading.\n\n";
}

sub getReleaseNumber {
    downloadOrUseLocalFile("ftp://ftp.ncbi.nlm.nih.gov/refseq/release/RELEASE_NUMBER", "RELEASE_NUMBER");

    open (REFSEQRELEASENUM, "RELEASE_NUMBER") ||  die "Cannot open RELEASE_NUMBER : $!\n";

    my $releaseNum = 0;
    while (<REFSEQRELEASENUM>) {
        if($_ =~ m/(\d+)/) {
            $releaseNum = $1;
        }
    }

    close REFSEQRELEASENUM;
    return $releaseNum;
}

sub downloadNCBIFilesForRelease {
    my $releaseNum = shift;
    my $catlogFolder = "ftp://ftp.ncbi.nlm.nih.gov/refseq/release/release-catalog/";
    my $catalogFile = "RefSeq-release" . $releaseNum . ".catalog.gz";
    my $ftpNCBIrefSeqCatalog = $catlogFolder . $catalogFile;

    try {
        downloadOrUseLocalFile($ftpNCBIrefSeqCatalog, "RefSeqCatalog.gz");
        #    &doSystemCommand("/local/bin/gunzip -c $catalogFile >RefSeqCatalog");

        downloadOrUseLocalFile("ftp://ftp.ncbi.nlm.nih.gov/gene/DATA/gene2accession.gz", "gene2accession.gz");
        #    &doSystemCommand("/local/bin/gunzip -f gene2accession.gz");

        downloadOrUseLocalFile("ftp://ftp.ncbi.nih.gov/gene/DATA/ARCHIVE/gene2vega.gz", "gene2vega.gz");
        #    &doSystemCommand("/local/bin/gunzip -f gene2vega.gz");

        downloadOrUseLocalFile("ftp://ftp.ncbi.nlm.nih.gov/gene/DATA/GENE_INFO/Non-mammalian_vertebrates/Danio_rerio.gene_info.gz", "zf_gene_info.gz");
        #    &doSystemCommand("/local/bin/gunzip -f zf_gene_info.gz");
    }
    catch {
        chomp $_;
        &reportErrAndExit("Auto from $NCBIState::dbname: NCBI_gene_load.pl :: $_");
    };

    #-------------------------------------------------------------------------------------------------
    # Check if all the downloaded and decompressed NCBI data files are in place.
    # If not, stop the process and send email to alert.
    #-------------------------------------------------------------------------------------------------

    if (!-e "zf_gene_info.gz" || !-e "gene2accession.gz" || !-e "RefSeqCatalog.gz") {
        my $subjectLine = "Auto from $NCBIState::dbname: NCBI_gene_load.pl :: ERROR with download";
        print LOG "\nMissing one or more downloaded NCBI file(s)\n\n";
        &reportErrAndExit($subjectLine);
    }
}

sub prepareNCBIgeneLoadDatabaseQuery {
    # Global: $dbname
    #--------------------------------------------------------------------------------------------------------------------
    # Step 2: execute prepareNCBIgeneLoad.sql to prepare
    #    1) a delete list, toDelete.unl
    #    2) a list of ZFIN genes to be mapped, toMap.unl
    #--------------------------------------------------------------------------------------------------------------------

    try {
        &doSystemCommand("psql -v ON_ERROR_STOP=1 -d $ENV{'DB_NAME'} -a -f prepareNCBIgeneLoad.sql >prepareLog1 2> prepareLog2");
    } catch {
        chomp $_;
        &reportErrAndExit("Auto from $NCBIState::dbname: NCBI_gene_load.pl :: faile at prepareNCBIgeneLoad.sql - $_");
    } ;

    print LOG "Done with preparing the delete list and the list for mapping.\n\n";

    my $subject = "Auto from $NCBIState::dbname: NCBI_gene_load.pl :: prepareLog1 file";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_ERR'},"$subject","prepareLog1");

    $subject = "Auto from $NCBIState::dbname: NCBI_gene_load.pl :: prepareLog2 file";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_ERR'},"$subject","prepareLog2");
}

sub getMetricsOfDbLinksToDelete {
    # This is a hash to store the zdb ids of db_link record to be deleted; used at later step
    # key: dblink zdb id
    # value: 1
    # Global: $ctToDelete
    # Global: %toDelete
    %NCBIState::toDelete = ();
    $NCBIState::ctToDelete = 0;

    open (TODELETE, "toDelete.unl") ||  die "Cannot open toDelete.unl : $!\n";

    while (<TODELETE>) {
        chomp;
        if ($_) {
            $NCBIState::ctToDelete++;
            my $dblinkIdToBeDeleted = $_;
            $NCBIState::toDelete{$dblinkIdToBeDeleted} = 1;
        }
    }

    close TODELETE;

    if ($NCBIState::ctToDelete == 0) {
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: the delete list, toDelete.unl, is empty";
        # print LOG "\nThe delete list, toDelete.unl is empty. Something is wrong.\n\n";
        &reportErrAndExit($subjectLine);
    }
}

sub getRecordCounts {
    #--------------------------------------------------------------------------------------
    # Step 3: record counts
    #--------------------------------------------------------------------------------------
    
    # globals: $dbhost          
    #           %genesWithRefSeqBeforeLoad
    #           $ctGenesWithRefSeqBefore          
    #           $numNCBIgeneIdBefore          
    #           $numRefSeqRNABefore          
    #           $numRefPeptBefore          
    #           $numRefSeqDNABefore          
    #           $numGenBankRNABefore          
    #           $numGenPeptBefore          
    #           $numGenBankDNABefore          
    #           $numGenesRefSeqRNABefore          
    #           $numGenesRefSeqPeptBefore          
    #           $numGenesGenBankBefore


    my $sql = "select mrkr_zdb_id, mrkr_abbrev from marker
         where (mrkr_zdb_id like 'ZDB-GENE%' or mrkr_zdb_id like '%RNAG%')
           and exists (select 1 from db_link
         where dblink_linked_recid = mrkr_zdb_id
           and dblink_fdbcont_zdb_id in ('ZDB-FDBCONT-040412-38','ZDB-FDBCONT-040412-39','ZDB-FDBCONT-040527-1'));";

    my $curGenesWithRefSeq = $NCBIState::handle->prepare($sql);

    $curGenesWithRefSeq->execute;

    my ($geneId, $geneSymbol);
    $curGenesWithRefSeq->bind_columns(\$geneId,\$geneSymbol);

    %NCBIState::genesWithRefSeqBeforeLoad = ();
    while ($curGenesWithRefSeq->fetch) {
        $NCBIState::genesWithRefSeqBeforeLoad{$geneId} = $geneSymbol;
    }

    $curGenesWithRefSeq->finish();

    $NCBIState::ctGenesWithRefSeqBefore = scalar(keys %NCBIState::genesWithRefSeqBeforeLoad);


    # NCBI Gene Id
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-1'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    $NCBIState::numNCBIgeneIdBefore = ZFINPerlModules->countData($sql);

    #RefSeq RNA
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-38'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    $NCBIState::numRefSeqRNABefore = ZFINPerlModules->countData($sql);

    # RefPept
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-39'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    $NCBIState::numRefPeptBefore = ZFINPerlModules->countData($sql);

    #RefSeq DNA
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040527-1'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

     $NCBIState::numRefSeqDNABefore = ZFINPerlModules->countData($sql);

    # GenBank RNA (only those loaded - excluding curated ones)
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-37'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
           and exists(select 1 from record_attribution
                       where recattrib_data_zdb_id = dblink_zdb_id
                         and recattrib_source_zdb_id in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2'));";

     $NCBIState::numGenBankRNABefore = ZFINPerlModules->countData($sql);

    # GenPept (only those loaded - excluding curated ones)
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
           and exists(select 1 from record_attribution
                       where recattrib_data_zdb_id = dblink_zdb_id
                         and recattrib_source_zdb_id in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2'));";

     $NCBIState::numGenPeptBefore = ZFINPerlModules->countData($sql);

    # GenBank DNA (only those loaded - excluding curated ones)
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-36'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
           and exists(select 1 from record_attribution
                       where recattrib_data_zdb_id = dblink_zdb_id
                         and recattrib_source_zdb_id in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2'));";

     $NCBIState::numGenBankDNABefore = ZFINPerlModules->countData($sql);

    # number of genes with RefSeq RNA
    $sql = "select distinct dblink_linked_recid
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-38'
           and dblink_acc_num like 'NM_%'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

     $NCBIState::numGenesRefSeqRNABefore = ZFINPerlModules->countData($sql);

    # number of genes with RefPept
    $sql = "select distinct dblink_linked_recid
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-39'
           and dblink_acc_num like 'NP_%'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

     $NCBIState::numGenesRefSeqPeptBefore = ZFINPerlModules->countData($sql);

    # number of genes with GenBank
    $sql = "select distinct dblink_linked_recid
          from db_link, foreign_db_contains, foreign_db
         where dblink_fdbcont_zdb_id = fdbcont_zdb_id
           and fdbcont_fdb_db_id = fdb_db_pk_id
           and fdb_db_name = 'GenBank'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

     $NCBIState::numGenesGenBankBefore = ZFINPerlModules->countData($sql);

}

sub readZfinGeneInfoFile {
    #--------------------------------------------------------------------------------------------
    # Step 4: Parse zf_gene_info file to get the NCBI records with gene Id, symbol, and Vega Id
    #         Since zf_gene_info file no loger has Vega IDs, have to parse gene2vega instead
    #--------------------------------------------------------------------------------------------
    # Global: $ctVegaIdsNCBI
    # Global: %NCBIgeneWithMultipleVega
    # Global: %NCBIidsGeneSymbols
    # Global: %geneSymbolsNCBIids
    # Global: %vegaIdsNCBIids
    # Global: %vegaIdwithMultipleNCBIids
    my $ctlines;

    # a hash to store Vega Gene Id and the ONLY related NCBI gene Id
    # key: Vega Gene Id
    # value: NCBI Gene Id

    %NCBIState::vegaIdsNCBIids = ();

    # a hash to store NCBI Gene Id and the corresponding NCBI gene symbol; will be used in reports
    # key: NCBI Gene Id
    # value: NCBI Gene Symbol

    %NCBIState::NCBIidsGeneSymbols = ();

    # a hash to store Vega Gene Ids and the multiple related NCBI gene Ids
    # key: Vega Gene Id
    # value: reference to an array of NCBI Gene Ids

    %NCBIState::vegaIdwithMultipleNCBIids = ();

    # a hash to store NCBI Gene Ids and the multiple related Vega gene Ids
    # key: NCBI Gene Id
    # value: the parsed field of dbXrefs containing multiple Vega Gene Ids

    %NCBIState::NCBIgeneWithMultipleVega = ();

    $NCBIState::ctVegaIdsNCBI = 0;
    $ctlines = 0;

    open(ZFGENEINFO, "cat zf_gene_info.gz | gunzip -c |") || die("Cannot open zf_gene_info : $!\n");

    #Format: tax_id GeneID Symbol LocusTag Synonyms dbXrefs chromosome map_location description type_of_gene Symbol_from_nomenclature_authority Full_name_from_nomenclature_authority Nomenclature_status Other_designations Modification_date

    # Sample record:
    # 7955    30037   tnc     CH211-166O17.1  tenc|wu:fk04d02 ZFIN:ZDB-GENE-980526-104|Ensembl:ENSDARG00000021948|Vega:OTTDARG00000032698     5       -       tenascin C      protein-coding  tnc     tenascin C      O       etID309720.5|tenascin   20130529

    my @fields;
    while (<ZFGENEINFO>) {
        chomp;

        if ($_) {
            $ctlines++;

            ## the first line is just description of the fields (format, as show above), not the data
            next if $ctlines < 2;

            undef @fields;
            @fields = split("\t");

            my $taxId = $fields[0];

            ## don't process if it is not zebrafish gene
            next if $taxId ne "7955";

            my $NCBIgeneId = $fields[1];
            my $symbol = $fields[2];

            $NCBIState::geneSymbolsNCBIids{$symbol} = $NCBIgeneId;
            $NCBIState::NCBIidsGeneSymbols{$NCBIgeneId} = $symbol;

            my $synonyms = $fields[4];
            my $dbXrefs = $fields[5];
            my $chr = $fields[6];
            my $typeOfGene = $fields[9];
            my $modDate = $fields[14];

            if ($_ =~ m/Vega(.+)Vega(.+)/) {
                $NCBIState::NCBIgeneWithMultipleVega{$NCBIgeneId} = $dbXrefs;
                print LOG "\nMultiple Vega: \n $NCBIgeneId \t $dbXrefs \n";
                next;
            }

            # Sample dbXrefs column: ZFIN:ZDB-GENE-980526-559|Ensembl:ENSDARG00000009351|Vega:OTTDARG00000027061
            # We ignore the ZFIN Gene ZDB Id and we need the Vega Id

            if ($dbXrefs =~ m/Vega:(OTTDARG[0-9]+)/) {  ### if VEGA Id is there
                my $VegaIdNCBI = $1;
                $NCBIState::ctVegaIdsNCBI++;

                ## if the Vega Gene Id is found in the hash of those with multiple NCBI gene ids
                ## or, if the Vega Gene Id is found in the hash of those with on;y 1 NCBI gene id,
                ## but the corresponding NCBI gene id is not the same as the NCBI gene id of this row,
                ## it means the Vega Id here must correspond to multiple NCBI gene Ids

                if (exists($NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI}) ||
                    (exists($NCBIState::vegaIdsNCBIids{$VegaIdNCBI}) && $NCBIState::vegaIdsNCBIids{$VegaIdNCBI} ne $NCBIgeneId)) {

                    ## if the Vega Gene Id is not found in the hash of those with multiple NCBI gene ids yet,
                    ## get the corresponding NCBI gene Id from the hash of %vegaIdsNCBIids, put it and
                    ## the NCBI gene id of the current row into an anonymous array;
                    ## set the reference to this anonymous array as the value of %NCBIState::vegaIdwithMultipleNCBIids
                    my $ref_arrayNCBIGenes;
                    if (!exists($NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI})) {
                        my $firstNCBIgeneIdFound = $NCBIState::vegaIdsNCBIids{$VegaIdNCBI};
                        $ref_arrayNCBIGenes = [$firstNCBIgeneIdFound,$NCBIgeneId];
                        $NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI} = $ref_arrayNCBIGenes;
                    } else {
                        ## otherwise, get the value of %vegaIdwithMultipleNCBIids, which is a reference to an anonymous array
                        ## and push the NCBI gene Id at current row to this array

                        $ref_arrayNCBIGenes = $NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI};
                        push(@$ref_arrayNCBIGenes, $NCBIgeneId);
                    }
                }

                $NCBIState::vegaIdsNCBIids{$VegaIdNCBI} = $NCBIgeneId;
            }
        }

    }
    close ZFGENEINFO;

    $ctlines--;    ## because the first line is just the description of the fileds


    print LOG "\nTotal number of records on NCBI's Danio_rerio.gene_info file: $ctlines\n\n";
    print LOG "\nctVegaIdsNCBI:  $NCBIState::ctVegaIdsNCBI\n\n" if $NCBIState::ctVegaIdsNCBI > 0;

    print STATS "\nTotal number of records on NCBI's Danio_rerio.gene_info file: $ctlines\n";
    print STATS "\nNumber of Vega Gene Id/NCBI Gene Id pairs on Danio_rerio.gene_info file: $NCBIState::ctVegaIdsNCBI\n\n" if $NCBIState::ctVegaIdsNCBI > 0;

    if ($NCBIState::ctVegaIdsNCBI == 0) {
        $ctlines = $NCBIState::ctVegaIdsNCBI = 0;

        open(VEGAINFO, "cat gene2vega.gz | gunzip -c |") || die("Cannot open gene2vega : $!\n");

        #Format: #tax_id GeneID  Vega_gene_identifier    RNA_nucleotide_accession.version        Vega_rna_identifier     protein_accession.version       Vega_protein_identifier

        # Sample record:
        # 7955    30037   OTTDARG00000032698      NM_130907.2     OTTDART00000045738      NP_570982.2     OTTDARP00000036100

        while (<VEGAINFO>) {
            chomp;

            if ($_) {
                $ctlines++;

                ## the first line is just description of the fields (format, as show above), not the data
                next if $ctlines < 2;

                undef @fields;
                @fields = split("\t");

                my $taxId = $fields[0];

                ## don't process if it is not zebrafish gene
                next if $taxId ne "7955";

                my $NCBIgeneId = $fields[1];
                my $VegaIdNCBI = $fields[2];

                $NCBIState::ctVegaIdsNCBI++;
                my $ref_arrayNCBIGenes;
                ## if the Vega Gene Id is found in the hash of those with multiple NCBI gene ids
                ## or, if the Vega Gene Id is found in the hash of those with on;y 1 NCBI gene id,
                ## but the corresponding NCBI gene id is not the same as the NCBI gene id of this row,
                ## it means the Vega Id here must correspond to multiple NCBI gene Ids

                if (exists($NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI}) ||
                    (exists($NCBIState::vegaIdsNCBIids{$VegaIdNCBI}) && $NCBIState::vegaIdsNCBIids{$VegaIdNCBI} ne $NCBIgeneId)) {

                    ## if the Vega Gene Id is not found in the hash of those with multiple NCBI gene ids yet,
                    ## get the corresponding NCBI gene Id from the hash of %vegaIdsNCBIids, put it and
                    ## the NCBI gene id of the current row into an anonymous array;
                    ## set the reference to this anonymous array as the value of %vegaIdwithMultipleNCBIids

                    if (!exists($NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI})) {
                        my $firstNCBIgeneIdFound = $NCBIState::vegaIdsNCBIids{$VegaIdNCBI};
                        $ref_arrayNCBIGenes = [$firstNCBIgeneIdFound,$NCBIgeneId];
                        $NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI} = $ref_arrayNCBIGenes;
                    } else {
                        ## otherwise, get the value of %vegaIdwithMultipleNCBIids, which is a reference to an anonymous array
                        ## and push the NCBI gene Id at current row to this array

                        $ref_arrayNCBIGenes = $NCBIState::vegaIdwithMultipleNCBIids{$VegaIdNCBI};
                        push(@$ref_arrayNCBIGenes, $NCBIgeneId);
                    }
                }

                $NCBIState::vegaIdsNCBIids{$VegaIdNCBI} = $NCBIgeneId;
            }
        }

        close(VEGAINFO);

        $ctlines--;    ## because the first line is just the description of the fileds

        print LOG "\nTotal number of records on NCBI's gene2vega file: $ctlines\n\n";
        print LOG "\nctVegaIdsNCBI:  $NCBIState::ctVegaIdsNCBI\n\n" if $NCBIState::ctVegaIdsNCBI > 0;

        print STATS "\nTotal number of records on NCBI's gene2vega file: $ctlines\n";
        print STATS "\nNumber of Vega Gene Id/NCBI Gene Id pairs on gene2vega file: $NCBIState::ctVegaIdsNCBI\n\n" if $NCBIState::ctVegaIdsNCBI > 0;
    }

    if($NCBIState::ctVegaIdsNCBI > 0) {
        print STATS "On NCBI's gene2vega file, the following Vega Ids correspond to more than 1 NCBI genes\n\n";
        print LOG "On NCBI's gene2vega file, the following Vega Ids correspond to more than 1 NCBI genes\n";

        my $ctVegaIdWithMultipleNCBIgene = 0;
        foreach my $vega (sort keys %NCBIState::vegaIdwithMultipleNCBIids) {
            $ctVegaIdWithMultipleNCBIgene++;
            my $ref_arrayNCBIGenes = $NCBIState::vegaIdwithMultipleNCBIids{$vega};
            print LOG "$vega @$ref_arrayNCBIGenes\n";
            print STATS "$vega @$ref_arrayNCBIGenes\n";
        }

        print LOG "\nctVegaIdWithMultipleNCBIgene = $ctVegaIdWithMultipleNCBIgene\n\n";
    }

    ##-------------------------------------------------------------------------------------------
    ## Get and store ZFIN gene zdb id and symbol
    ## The ZFIN gene symbols will be looked up and printed in various reports

    # key: gene zdb id
    # value: gene symbol at ZFIN

    %NCBIState::geneZDBidsSymbols = ();

    my $sqlGeneZDBidsSymbols = "select mrkr_zdb_id, mrkr_abbrev from marker where (mrkr_zdb_id like 'ZDB-GENE%' or mrkr_zdb_id like '%RNAG%') and mrkr_abbrev not like 'WITHDRAWN%';";

    my $curGeneZDBidsSymbols = $NCBIState::handle->prepare($sqlGeneZDBidsSymbols);
    my $zdbId;
    my $symbol;

    $curGeneZDBidsSymbols->execute();
    $curGeneZDBidsSymbols->bind_columns(\$zdbId,\$symbol);

    while ($curGeneZDBidsSymbols->fetch()) {
        $NCBIState::geneZDBidsSymbols{$zdbId} = $symbol;
    }

    $curGeneZDBidsSymbols->finish();
}

sub initializeSetsOfZfinRecords {
    #----------------------------------------------------------------------------------------------------------------------
    # Step 5: Map ZFIN gene records to NCBI gene records based on GenBank RNA sequences
    #----------------------------------------------------------------------------------------------------------------------

    #-----------------------------------------
    # Step 5-1: initial set of ZFIN records
    #-----------------------------------------
    #
    # Global: %supportedGeneZFIN
    # Global: %supportingAccZFIN
    # Global: $debug
    # Global: %accZFINsupportingMoreThan1
    # Global: %geneZFINwithAccSupportingMoreThan1
    # Global: %accZFINsupportingOnly1

    open (ZFINGENESUPPORTED, "toMap.unl") ||  die "Cannot open toMap.unl : $!\n";
    my $ctSupportedZFINgenes = 0;
    my %zfinGenes = ();
    while (<ZFINGENESUPPORTED>) {
        if ($_) {
            $ctSupportedZFINgenes++;
            chop;
            $zfinGenes{$_} = 1;
        }
    }

    close ZFINGENESUPPORTED;

    ## the following SQL is used to get the GenBank RNA accessions as evidence of (supporting) a gene record at ZFIN

    my $sqlGetSupportingGenBankRNAs = "select dblink_acc_num
                                  from db_link
                                 where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-37'
                                   and dblink_linked_recid = ?
                                union
                                select dblink_acc_num
                                  from db_link
                                 where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-37'
                                   and dblink_linked_recid not like 'ZDB-GENE%'
                                   and dblink_linked_recid not like '%RNAG%'
                                   and exists(select 1 from marker_relationship
                                               where mrel_mrkr_1_zdb_id = ?
                                                 and mrel_type = 'gene encodes small segment'
                                                 and dblink_linked_recid = mrel_mrkr_2_zdb_id);";

    my $curGetSupportingGenBankRNAs = $NCBIState::handle->prepare($sqlGetSupportingGenBankRNAs);

    ## %supportedGeneZFIN is a hash to store references to arrays of GenBank RNA accession(s) supporting ZDB gene Id
    ## key:    zdb gene id
    ## value:  reference to the array of accession(s) that support the gene
    ## example1:  $supportedGeneZFIN{$zdbGeneId1} = [$acc1, $acc2]
    ## example2:  $supportedGeneZFIN{$zdbGeneId2} = [$acc3, $acc4, $acc5]

    %NCBIState::supportedGeneZFIN = ();

    ## %supportingAccZFIN is a hash to store references to arrays of ZDB gene Ids supported by GenBank accession
    ## key:    GenBank RNA accession
    ## value:  reference to the array of zdb gene id(s) that is supported by the GenBank RNA accession
    ## example1:  $supportingAccZFIN{$acc1} = [$zdbGene1]            -- potential 1:1 if same on NCBI end
    ## example2:  $supportingAccZFIN{$acc2} = [$zdbGene2, $zdbGene3] -- 1 acc supporting 2 genes, which won't be used as evidence in mapping

    %NCBIState::supportingAccZFIN = ();
    my $ref_arrayGenes;
    my $ref_arrayAccs;
    my $GenBankAccAtZFIN;

    foreach my $geneZDBidSupported (keys %zfinGenes) {

        $curGetSupportingGenBankRNAs->execute($geneZDBidSupported, $geneZDBidSupported);
        $curGetSupportingGenBankRNAs->bind_columns(\$GenBankAccAtZFIN);

        while ($curGetSupportingGenBankRNAs->fetch()) {

            ## if the array of ZDB gene Ids supported by this GenBank RNA accession has not been created yet

            if (!exists($NCBIState::supportingAccZFIN{$GenBankAccAtZFIN})) {

                ## then create it with the first element (the supported ZDB gene id)

                $ref_arrayGenes = [$geneZDBidSupported];
                $NCBIState::supportingAccZFIN{$GenBankAccAtZFIN} = $ref_arrayGenes;

            } else {  ## otherwise, add this supported ZDB gene Id into the array

                $ref_arrayGenes = $NCBIState::supportingAccZFIN{$GenBankAccAtZFIN};
                push(@$ref_arrayGenes, $geneZDBidSupported);
            }


            ## if the array of the GenBank RNA accession(s) supporting the ZDB gene Id has not been created yet

            if (!exists($NCBIState::supportedGeneZFIN{$geneZDBidSupported})) {

                ## then create it with this supporting GenBank RNA accession

                $ref_arrayAccs = [$GenBankAccAtZFIN];

                $NCBIState::supportedGeneZFIN{$geneZDBidSupported} = $ref_arrayAccs;

            } else {  ## otherwise, add this supporting GenBank RNA accession into the array

                $ref_arrayAccs = $NCBIState::supportedGeneZFIN{$geneZDBidSupported};
                push(@$ref_arrayAccs, $GenBankAccAtZFIN);
            }

        }

    }

    print LOG "ctSupportedZFINgenes::: $ctSupportedZFINgenes\n\n";

    print LOG "Total number of ZFIN gene records supported by GenBank RNA: $ctSupportedZFINgenes\n\n";

    $curGetSupportingGenBankRNAs->finish();


    if ($debug) {
        open (DBG1, ">debug1") ||  die "Cannot open debug1 : $!\n";
        foreach my $geneAtZFIN (sort keys %NCBIState::supportedGeneZFIN) {
            $ref_arrayAccs = $NCBIState::supportedGeneZFIN{$geneAtZFIN};
            print DBG1 "$geneAtZFIN\t@$ref_arrayAccs \n";
        }
        close DBG1;

        open (DBG2, ">debug2") ||  die "Cannot open debug2 : $!\n";

        foreach my $accAtZFIN (sort keys %NCBIState::supportingAccZFIN) {
            $ref_arrayGenes = $NCBIState::supportingAccZFIN{$accAtZFIN};
            print DBG2 "$accAtZFIN\t@$ref_arrayGenes \n";
        }

        close DBG2;
    }

    #-----------------------------------------------------------------------------------------------------------------------------------
    #   Get the real initial mapping set at ZFIN end

    ## %accZFINsupportingMoreThan1 is a hash storing the references to array of genes for GenBank RNA accessions that supports more than 1 genes at ZFIN
    ## key:   GenBank RNA accession that supports more than 1 genes at ZFIN
    ## value: reference to array of more than 1 zdb gene ids supported by the GenBank RNA accession
    ## example: $accZFINsupportingMoreThan1{$acc1} = [$zdbGeneId1, $zdbGeneId2]
    ## %accZFINsupportingMoreThan1 is a subset of %supportingAccZFIN

    %NCBIState::accZFINsupportingMoreThan1 = ();

    ## %geneZFINwithAccSupportingMoreThan1 is a hash storing the references to array of GenBank RNA accessions for genes
    ## that are supported by accession(s) at ZFIN with at least 1 of them supporting more than 1 genes
    ## key:    gene zdb id of the gene with at least 1 supporting GenBank RNA accessions supporting more than 1 genes
    ## value:  reference to array of GenBank RNA accessions supporting the gene, at least 1 of which supports other gene(s)
    ## example:  $geneZFINwithAccSupportingMoreThan1{$zdbGeneId1} = [$acc1, $acc2, $acc3]  (at least 1 of the 3 accs supporting more than 1 genes)
    ## %geneZFINwithAccSupportingMoreThan1 is a subset of %supportedGeneZFIN

    %NCBIState::geneZFINwithAccSupportingMoreThan1 = ();

    ## %accZFINsupportingOnly1 is a hash storing the genes at ZFIN supported by the GenBank RNA accession that does NOT support another gene
    ## key:    GenBank RNA accession
    ## value:  gene zdb id of the gene
    ## example:  $accZFINsupportingOnly1{$acc1} = zdbGeneId1

    %NCBIState::accZFINsupportingOnly1 = ();

    ##-------------------------------------------------------------------------------------------------------------------------------------
    ## traverse the hash of %supportingAccZFIN to find and mark those GenBank RNA accessions stored at ZFIN supporting more than 1 genes
    ## also mark those accs that support only 1 gene at ZFIN
    ## also mark those genes supported by 2 or more GenBank RNA accessions
    ##-------------------------------------------------------------------------------------------------------------------------------------

    my $ctAllSupportingAccZFIN = 0;
    my $ctAccZFINSupportingMoreThan1 = 0;
    my $ctAccZFINSupportingOnly1 = 0;
    my $ref_arrayOfAccs;
    my $ctGenesZFINwithAccSupportingMoreThan1 = 0;

    foreach my $acc (keys %NCBIState::supportingAccZFIN) {
        $ctAllSupportingAccZFIN++;

        my $ref_arrayOfGenes = $NCBIState::supportingAccZFIN{$acc};


        if ($#$ref_arrayOfGenes > 0) {
            my @zdbGeneIDs = @$ref_arrayOfGenes;
            my $firstZDBID = $zdbGeneIDs[0];
            my $ctZdbGeneIds = 0;
            my $numDifferentGenes = 0;
            foreach my $zdbGeneID (@$ref_arrayOfGenes) {
                $ctZdbGeneIds++;
                if ($ctZdbGeneIds > 0) {
                    $numDifferentGenes++ if $zdbGeneID ne $firstZDBID;
                    $firstZDBID = $zdbGeneID;
                }
            }

            if ($numDifferentGenes > 0) { ## if the last index > 0, and not the same zdb gene ID, indicating more than 1 genes supported
                $ctAccZFINSupportingMoreThan1++;
                $NCBIState::accZFINsupportingMoreThan1{$acc} = $ref_arrayOfGenes;

                foreach my $genesInQuestion (@$ref_arrayOfGenes) {
                    $ref_arrayOfAccs = $NCBIState::supportedGeneZFIN{$genesInQuestion};
                    $NCBIState::geneZFINwithAccSupportingMoreThan1{$genesInQuestion} = $ref_arrayOfAccs;
                }
            } else { ## the acc only supports 1 gene
                $ctAccZFINSupportingOnly1++;

                foreach my $geneWithAccSupportingOnly1 (@$ref_arrayOfGenes) { ## only 1 element in the array
                    $NCBIState::accZFINsupportingOnly1{$acc} = $geneWithAccSupportingOnly1;
                }
            }
        } else {  ## the acc only supports 1 gene

            $ctAccZFINSupportingOnly1++;

            foreach my $geneWithAccSupportingOnly1 (@$ref_arrayOfGenes) { ## only 1 element in the array
                $NCBIState::accZFINsupportingOnly1{$acc} = $geneWithAccSupportingOnly1;
            }
        }

    }

    print STATS "\n\nThe following GenBank RNA accessions found at ZFIN are associated with multiple ZFIN genes.";
    print STATS "\nThe ZDB Gene Ids associated with these GenBank RNAs are excluded from mapping and hence the loading.\n\n";

    open (DBG3, ">debug3") ||  die "Cannot open debug3 : $!\n" if $debug;
    my $ctGenBankRNAsupportingMultipleZFINgenes = 0;
    foreach my $accSupportingMoreThan1 (sort keys %NCBIState::accZFINsupportingMoreThan1) {
        my $ref_accSupportingMoreThan1 = $NCBIState::accZFINsupportingMoreThan1{$accSupportingMoreThan1};
        print STATS "$accSupportingMoreThan1\t@$ref_accSupportingMoreThan1\n";
        print DBG3 "$accSupportingMoreThan1\t@$ref_accSupportingMoreThan1\n" if $debug;
        $ctGenBankRNAsupportingMultipleZFINgenes++;
    }
    close DBG3 if $debug;

    print STATS "\nTotal: $ctGenBankRNAsupportingMultipleZFINgenes\n\n";

    my $ref_accs;
    if ($debug) {
        open (DBG4, ">debug4") ||  die "Cannot open debug4 : $!\n";

        foreach my $geneWithAtLeast1accSupportingMoreThan1 (sort keys %NCBIState::geneZFINwithAccSupportingMoreThan1) {
            $ctGenesZFINwithAccSupportingMoreThan1++;
            $ref_accs = $NCBIState::geneZFINwithAccSupportingMoreThan1{$geneWithAtLeast1accSupportingMoreThan1};
            print DBG4 "$geneWithAtLeast1accSupportingMoreThan1\t@$ref_accs\n";
        }

        close DBG4;
    }

    print LOG "\nThe following should add up \nctAccZFINSupportingOnly1 + ctAccZFINSupportingMoreThan1 = ctAllSupportingAccZFIN \nOtherwise there is bug.\n";
    print LOG "$ctAccZFINSupportingOnly1 + $ctAccZFINSupportingMoreThan1 = $ctAllSupportingAccZFIN\n\n";

    ## if the numbers don't add up, stop the whole process
    if ($ctAccZFINSupportingOnly1 + $ctAccZFINSupportingMoreThan1 != $ctAllSupportingAccZFIN) {
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: some numbers don't add up";
        &reportErrAndExit($subjectLine);
    }

    print LOG "ctGenesZFINwithAccSupportingMoreThan1 = $ctGenesZFINwithAccSupportingMoreThan1\n\n";
}

sub initializeSequenceLengthHash {

    # use the following hash to store db_link sequence accession length
    # key: seqence accession
    # value: length
    # Global: %sequenceLength

    %NCBIState::sequenceLength = ();

    #---------------------- 1) store the dblink_length of GenBank accessions -----------------------

    my $sqlGenBankAccessionLength = "select dblink_acc_num, dblink_length
                                from db_link
                               where dblink_length is not null
                                 and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
                                 and dblink_fdbcont_zdb_id in ('ZDB-FDBCONT-040412-37','ZDB-FDBCONT-040412-42','ZDB-FDBCONT-040412-36');";


    my $curGenBankAccessionLength = $NCBIState::handle->prepare($sqlGenBankAccessionLength);

    $curGenBankAccessionLength->execute;

    my ($GenBankAcc,$seqLength);
    $curGenBankAccessionLength->bind_columns(\$GenBankAcc,\$seqLength);

    my $ctGenBankSeqLengthAtZFIN = 0;
    while ($curGenBankAccessionLength->fetch) {
        $ctGenBankSeqLengthAtZFIN++;
        $NCBIState::sequenceLength{$GenBankAcc} = $seqLength;
    }

    $curGenBankAccessionLength->finish();

    print LOG "\nctGenBankSeqLengthAtZFIN = $ctGenBankSeqLengthAtZFIN\n\n";
}

sub parseRefSeqCatalogFileForSequenceLength {
    # Global: %sequenceLength
    my $ctRefSeqLengthFromCatalog = 0;
    my @fields;
    my $refSeqLength;
    my $taxId;
    my $refSeqAcc;

    open(REFSEQCATALOG, "cat RefSeqCatalog.gz | gunzip -c | grep 7955 |") || die("Cannot open RefSeqCatalog.gz : $!\n");

    ## Sample record (last column is length of the sequence):
    ## 7955    Danio rerio     NP_001001398.2  89191828        complete|vertebrate_other       PROVISIONAL     205

    while (<REFSEQCATALOG>) {
        chomp;

        if ($_) {

            undef @fields;
            undef $refSeqLength;
            @fields = split("\t");

            $taxId = $fields[0];

            ## don't process if it is not zebrafish gene
            next if $taxId ne "7955";

            $refSeqAcc = $fields[2];
            $refSeqAcc =~ s/\.\d+//;     # truncate version number

            $refSeqLength = $fields[6];

            if ($refSeqLength) {
                $ctRefSeqLengthFromCatalog++;
                $NCBIState::sequenceLength{$refSeqAcc} = $refSeqLength
            }

        }

    }

    print LOG "\nctRefSeqLengthFromCatalog = $ctRefSeqLengthFromCatalog\n\n";

    close REFSEQCATALOG;
}

sub printSequenceLengthsCount {
    # Global: %sequenceLength
    my $ctAccWithLength = 0;
    foreach my $accWithLength (keys %NCBIState::sequenceLength) {
        $ctAccWithLength++;
    }

    print LOG "\nctAccWithLength = $ctAccWithLength";
}

sub parseGene2AccessionFile {
    # globals:
    # $ctNoLength
    # $ctNoLengthRefSeq
    # $ctZebrafishGene2accession
    # $ctlines
    # %GenBankDNAncbiGeneIds
    # %GenPeptNCBIgeneIds
    # %RefPeptNCBIgeneIds
    # %RefSeqDNAncbiGeneIds
    # %RefSeqRNAncbiGeneIds
    # %noLength
    # %supportedGeneNCBI
    # %supportingAccNCBI
    
    #------------------------------------------------------------------------------------------------------------------------------------
    # Step 5-3: initial set of NCBI records
    #
    # This section of code parses the NCBI's gene2accession file and
    # 1) store GenBank RNA accessions as supporting RNA evidence used for mapping
    # 2) store GenPept, GenBank DNA, RefSeq RNA, RefPept, and RefSeq DNA accessions as well
    # 3) look up length for all these accessions; if not found, put them in the hash %noLength
    #------------------------------------------------------------------------------------------------------------------------------------

    ## %supportedGeneNCBI is a hash to store references to arrays of GenBank RNA accessions supporting one NCBI zebrafish gene
    ## key:    NCBI zebrafish gene id
    ## value:  reference to the array of accession(s) that support the gene
    ## example1:  $supportedGeneNCBI{$ncbiGeneId1} = [$acc1, $acc2]
    ## example2:  $supportedGeneNCBI{$ncbiGeneId2} = [$acc3, $acc4, $acc5]

    %NCBIState::supportedGeneNCBI = ();

    ## %supportingAccNCBI is a hash to store references to arrays of NCBI zf gene Ids supported by one GenBank accession
    ## key:    GenBank RNA accession
    ## value:  reference to the array of NCBI zf gene id(s) that is supported by the GenBank RNA accession
    ## example1:  $supportingAccNCBI{$acc1} = [$ncbiGeneId1]               -- potential 1:1 if same on ZFIN end
    ## example2:  $supportingAccNCBI{$acc2} = [$ncbiGeneId2, $ncbiGeneId3] -- 1 acc supporting 2 genes, which won't be used as evidence in mapping

    %NCBIState::supportingAccNCBI = ();

    # Use the following hashes to store all kinds of RefSeq and GenBank accessions on gene2accession file,
    # except for GenBank RNA accessions, which are stored in the hash, %supportingAccNCBI, documented above.

    # key: sequence accession
    # value: NCBI gene Id

    %NCBIState::GenPeptNCBIgeneIds = ();
    %NCBIState::GenBankDNAncbiGeneIds = ();
    %NCBIState::RefSeqRNAncbiGeneIds = ();
    %NCBIState::RefPeptNCBIgeneIds = ();
    %NCBIState::RefSeqDNAncbiGeneIds = ();

    # Use the following hash to store those sequence accessions with which length value could not be found in the hash, %sequenceLength
    # key: sequence accession
    # value: NCBI gene Id

    %NCBIState::noLength = ();
    $NCBIState::ctNoLength = 0;
    $NCBIState::ctNoLengthRefSeq = 0;
    $NCBIState::ctZebrafishGene2accession = 0;
    my $ctlines = 0;
    my @fields;
    my $taxId;
    my $NCBIgeneId;
    my $status;
    my $GenBankRNAaccNCBI;
    my $ref_arrayAccs;
    my $ref_arrayGenes;
    my $GenPeptAcc;
    my $GenBankDNAacc;
    my $RefSeqRNAacc;
    my $RefPeptAcc;
    my $RefSeqDNAacc;

    print LOG "\nParsing NCBI gene2accession file ... \n\n";

    open(GENE2ACC, "cat gene2accession.gz | gunzip -c | grep 7955 |") || die("Cannot open gene2accession.gz : $!\n");

    ##Format: tax_id GeneID status RNA_nucleotide_accession.version RNA_nucleotide_gi protein_accession.version protein_gi genomic_nucleotide_accession.version genomic_nucleotide_gi start_position_on_the_genomic_accession end_position_on_the_genomic_accession orientation assembly mature_peptide_accession.version mature_peptide_gi Symbol

    while (<GENE2ACC>) {
        chomp;

        if ($_) {

            $ctlines++;

            undef @fields;
            @fields = split("\t");

            $taxId = $fields[0];

            ## don't process if it is not zebrafish gene
            next if $taxId ne "7955";

            $NCBIState::ctZebrafishGene2accession++;

            $NCBIgeneId = $fields[1];

            $status = $fields[2];

            if ($fields[7] =~ /^CR847926/) {
                print LOG "DEBUG found: CR847926\n";
                print "DEBUG found: CR847926\n";
            }

            if ($status eq "-") {
                if (ZFINPerlModules->stringStartsWithLetter($fields[3])) {
                    $GenBankRNAaccNCBI = $fields[3];
                    $GenBankRNAaccNCBI =~ s/\.\d+//;  ## truncate version number

                    if (!exists($NCBIState::sequenceLength{$GenBankRNAaccNCBI})) {
                        $NCBIState::noLength{$GenBankRNAaccNCBI} = $NCBIgeneId;
                        $NCBIState::ctNoLength++;
                    }

                    ## if the array of the GenBank RNA accession(s) supporting the NCBI gene Id has not been created yet

                    if (!exists($NCBIState::supportedGeneNCBI{$NCBIgeneId})) {

                        ## then create it with this supporting GenBank RNA accession

                        $ref_arrayAccs = [$GenBankRNAaccNCBI];

                        $NCBIState::supportedGeneNCBI{$NCBIgeneId} = $ref_arrayAccs;

                    } else {  ## otherwise, add this supporting GenBank RNA accession into the array

                        $ref_arrayAccs = $NCBIState::supportedGeneNCBI{$NCBIgeneId};

                        # add it only when it is not the same as the last item
                        push(@$ref_arrayAccs, $GenBankRNAaccNCBI) if $NCBIState::supportedGeneNCBI{$NCBIgeneId}[-1] ne $GenBankRNAaccNCBI;
                    }

                    ## if the array of NCBI gene Ids supported by this GenBank RNA accession has not been created yet

                    if (!exists($NCBIState::supportingAccNCBI{$GenBankRNAaccNCBI})) {

                        ## then create it with the first element (the supported NCBI gene id)

                        $ref_arrayGenes = [$NCBIgeneId];
                        $NCBIState::supportingAccNCBI{$GenBankRNAaccNCBI} = $ref_arrayGenes;

                    } else {  ## otherwise, add this supported NCBI gene Id into the array

                        $ref_arrayGenes = $NCBIState::supportingAccNCBI{$GenBankRNAaccNCBI};

                        # add it only when it is not the same as the last item
                        push(@$ref_arrayGenes, $NCBIgeneId) if $NCBIState::supportingAccNCBI{$GenBankRNAaccNCBI}[-1] ne $NCBIgeneId;
                    }

                }  ## ending if (stringStartsWithLetter$fields[3]))

                if (ZFINPerlModules->stringStartsWithLetter($fields[5])) {
                    $GenPeptAcc = $fields[5];
                    $GenPeptAcc =~ s/\.\d+//;
                    $NCBIState::GenPeptNCBIgeneIds{$GenPeptAcc} = $NCBIgeneId;

                    if (!exists($NCBIState::sequenceLength{$GenPeptAcc})) {
                        $NCBIState::noLength{$GenPeptAcc} = $NCBIgeneId;
                        $NCBIState::ctNoLength++;
                    }
                }

                if (ZFINPerlModules->stringStartsWithLetter($fields[7])) {
                    $GenBankDNAacc = $fields[7];
                    $GenBankDNAacc =~ s/\.\d+//;

                    #initialize the hash value to empty array if it is empty
                    if (!exists($NCBIState::GenBankDNAncbiGeneIds{$GenBankDNAacc})) {
                        $NCBIState::GenBankDNAncbiGeneIds{$GenBankDNAacc} = [];
                    }
                    #push the NCBI gene id into the array
                    push(@{$NCBIState::GenBankDNAncbiGeneIds{$GenBankDNAacc}}, $NCBIgeneId);

                    if (!exists($NCBIState::sequenceLength{$GenBankDNAacc})) {
                        $NCBIState::noLength{$GenBankDNAacc} = $NCBIgeneId;
                        $NCBIState::ctNoLength++;
                    }
                }
            } else {  # there is value of "status" field for all RefSeq accessions
                if (ZFINPerlModules->stringStartsWithLetter($fields[3])) {
                    $RefSeqRNAacc = $fields[3];
                    $RefSeqRNAacc =~ s/\.\d+//;
                    $NCBIState::RefSeqRNAncbiGeneIds{$RefSeqRNAacc} = $NCBIgeneId if $RefSeqRNAacc =~ m/^NM/ or $RefSeqRNAacc =~ m/^XM/ or $RefSeqRNAacc =~ m/^NR/ or $RefSeqRNAacc =~ m/^XR/;

                    if (!exists($NCBIState::sequenceLength{$RefSeqRNAacc})) {
                        $NCBIState::noLength{$RefSeqRNAacc} = $NCBIgeneId;
                        $NCBIState::ctNoLength++;
                        $NCBIState::ctNoLengthRefSeq++;
                    }
                }

                if (ZFINPerlModules->stringStartsWithLetter($fields[5])) {
                    $RefPeptAcc = $fields[5];
                    $RefPeptAcc =~ s/\.\d+//;
                    $NCBIState::RefPeptNCBIgeneIds{$RefPeptAcc} = $NCBIgeneId;

                    if (!exists($NCBIState::sequenceLength{$RefPeptAcc})) {
                        $NCBIState::noLength{$RefPeptAcc} = $NCBIgeneId;
                        $NCBIState::ctNoLength++;
                        $NCBIState::ctNoLengthRefSeq++;
                    }
                }

                if (ZFINPerlModules->stringStartsWithLetter($fields[7])) {
                    $RefSeqDNAacc = $fields[7];
                    $RefSeqDNAacc =~ s/\.\d+//;
                    $NCBIState::RefSeqDNAncbiGeneIds{$RefSeqDNAacc} = $NCBIgeneId;

                    if (!exists($NCBIState::sequenceLength{$RefSeqDNAacc})) {
                        $NCBIState::noLength{$RefSeqDNAacc} = $NCBIgeneId;
                        $NCBIState::ctNoLength++;
                        $NCBIState::ctNoLengthRefSeq++;
                    }
                }
            }

        }  # ending if ($status eq "-")

    }

    close GENE2ACC;

    print LOG "\n\nNumber of lines on gene2accession file:  $ctlines\n\n";
    print LOG "\nctZebrafishGene2accession:  $NCBIState::ctZebrafishGene2accession\n\n";


    print LOG "\nctNoLength = $NCBIState::ctNoLength\nctNoLengthRefSeq = $NCBIState::ctNoLengthRefSeq\n\n";

}

sub countNCBIGenesWithSupportingGenBankRNA {
    open (DBG5, ">debug5") ||  die "Cannot open debug5 : $!\n" if $debug;

    my $ctGeneIdsNCBIonGene2accession = 0;
    foreach my $geneAtNCBI (sort keys %NCBIState::supportedGeneNCBI) {
        $ctGeneIdsNCBIonGene2accession++;
        my $ref_arrayAccs = $NCBIState::supportedGeneNCBI{$geneAtNCBI} if $debug;
        print DBG5 "$geneAtNCBI\t@$ref_arrayAccs\n" if $debug;
    }
    close DBG5 if $debug;
    print LOG "\nThe number of NCBI genes with supporting GenBank RNA: $ctGeneIdsNCBIonGene2accession\n\n";
    print STATS "\n\nThe number of NCBI genes with supporting GenBank RNA: $ctGeneIdsNCBIonGene2accession\n\n";

}

sub logGenBankDNAncbiGeneIds {
    # Global: %GenBankDNAncbiGeneIds
    # Global: $debug
    open(DBG5A, ">debug5a") || die "Cannot open debug5a : $!\n" if $debug;

    foreach my $gikey (sort keys %NCBIState::GenBankDNAncbiGeneIds) {
        my $giref_arrayAccs = $NCBIState::GenBankDNAncbiGeneIds{$gikey} if $debug;
        print DBG5A "$gikey\t@$giref_arrayAccs\n" if $debug;
    }

    close DBG5A if $debug;
}

sub logSupportingAccNCBI {
    # Global: $debug
    # Global: %supportingAccNCBI
    if ($debug) {
        open (DBG6, ">debug6") ||  die "Cannot open debug6 : $!\n";

        foreach my $accAtNCBI (sort keys %NCBIState::supportingAccNCBI) {
            my $ref_arrayGenes = $NCBIState::supportingAccNCBI{$accAtNCBI};
            print DBG6 "$accAtNCBI\t@$ref_arrayGenes\n";
        }

        close DBG6;
    }
}

sub initializeHashOfNCBIAccessionsSupportingMultipleGenes {
    # Globals: %accNCBIsupportingMoreThan1
    #             %accNCBIsupportingOnly1
    #             %geneNCBIwithAccSupportingMoreThan1

    #---------------------------------------------------------------------------------------------------------------------------------

    ## %accNCBIsupportingMoreThan1 is a hash storing the references to array of genes for GenBank RNA accessions that supports more than 1 genes at NCBI
    ## key:   GenBank RNA accession that supports more than 2 genes at NCBI
    ## value: reference to array of more than 1 NCBI gene ids supported by the GenBank RNA accession
    ## example: $accNCBIsupportingMoreThan1{$acc1} = [$ncbiGene1, $ncbiGene2]
    ## %accNCBIsupportingMoreThan1 is a subset of %supportingAccNCBI

    %NCBIState::accNCBIsupportingMoreThan1 = ();

    ## %geneNCBIwithAccSupportingMoreThan1 is a hash storing the references to array of GenBank RNA accessions for genes
    ## that are supported by accession(s) at NCBI with at least 1 of them supporting more than 1 genes
    ## key:    NCBI gene id of the gene with at least 1 supporting GenBank RNA accessions supporting more than 1 genes
    ## value:  reference to array of GenBank RNA accessions supporting the gene, at least 1 of which supports other gene(s)
    ## example:  $geneNCBIwithAccSupportingMoreThan1{$ncbiGeneId1} = [$acc1, $acc2, $acc3]  (at least 1 of the 3 accs supporting more than 1 genes)
    ## %geneNCBIwithAccSupportingMoreThan1 is a subset of %supportedGeneNCBI; can be called n's at NCBI;

    %NCBIState::geneNCBIwithAccSupportingMoreThan1 = ();

    ## %accNCBIsupportingOnly1 is a hash storing the genes at NCBI supported by the GenBank RNA accession that does NOT support another gene
    ## key:    GenBank RNA accession
    ## value:  NCBI gene id of the gene
    ## example:  $accNCBIsupportingOnly1{$acc1} = ncbiGeneId1

    %NCBIState::accNCBIsupportingOnly1 = ();

    ##------------------------------------------------------------------------------------------------------------------------------
    ## traverse the hash of %supportingAccNCBI to find and mark those GenBank RNA accessions at NCBI supporting more than 1 genes
    ## and also mark those accs that support only 1 gene at NCBI, which is vital for 1:1 mapping between NCBI and ZFIN
    ## also mark those genes supported by 2 or more GenBank RNA accessions
    ##------------------------------------------------------------------------------------------------------------------------------

    my $ctAllSupportingAccNCBI = 0;
    my $ctAccNCBISupportingMoreThan1 = 0;
    my $ctAccNCBISupportingOnly1 = 0;
    my $ref_arrayOfGenes;
    my $ref_arrayOfAccs;

    foreach my $acc (keys %NCBIState::supportingAccNCBI) {
        $ctAllSupportingAccNCBI++;

        $ref_arrayOfGenes = $NCBIState::supportingAccNCBI{$acc};

        if ($#$ref_arrayOfGenes > 0) {  ## if the last index > 0, indicating more than 1 genes supported
            $ctAccNCBISupportingMoreThan1++;
            $NCBIState::accNCBIsupportingMoreThan1{$acc} = $ref_arrayOfGenes;

            foreach my $genesInQuestion (@$ref_arrayOfGenes) {
                $ref_arrayOfAccs = $NCBIState::supportedGeneNCBI{$genesInQuestion};
                $NCBIState::geneNCBIwithAccSupportingMoreThan1{$genesInQuestion} = $ref_arrayOfAccs;
            }
        } else {  ## the acc only supports 1 gene

            $ctAccNCBISupportingOnly1++;

            foreach my $geneWithAccSupportingOnly1 (@$ref_arrayOfGenes) { ## only 1 element in the array
                $NCBIState::accNCBIsupportingOnly1{$acc} = $geneWithAccSupportingOnly1;
            }
        }

    }

    print STATS "\nThe following GenBank accession found on NCBI's gene2accession file support more than 1 NCBI genes\n";
    print LOG "\nThe following GenBank accession found on NCBI's gene2accession file support more than 1 NCBI genes\n";

    foreach my $accSupportingMoreThan1 (sort keys %NCBIState::accNCBIsupportingMoreThan1) {
        my $ref_accSupportingMoreThan1 = $NCBIState::accNCBIsupportingMoreThan1{$accSupportingMoreThan1};
        print STATS "$accSupportingMoreThan1\t@$ref_accSupportingMoreThan1\n";
        print LOG "$accSupportingMoreThan1\t@$ref_accSupportingMoreThan1\n";
    }

    print STATS "\nThe following NCBI's Gene Ids have at least 1 supporting GenBank accession that supports more than 1 NCBI genes\n";
    print LOG "\nThe following NCBI's Gene Ids have at least 1 supporting GenBank accession that supports more than 1 NCBI genes\n";

    foreach my $geneWithAtLeast1accSupportingMoreThan1 (sort keys %NCBIState::geneNCBIwithAccSupportingMoreThan1) {
        my $ref_accs = $NCBIState::geneNCBIwithAccSupportingMoreThan1{$geneWithAtLeast1accSupportingMoreThan1};
        print LOG "$geneWithAtLeast1accSupportingMoreThan1\t@$ref_accs\n";
        print STATS "$geneWithAtLeast1accSupportingMoreThan1\t@$ref_accs\n";
    }

    print LOG "\nThe following should add up \nctAccNCBISupportingOnly1 + ctAccNCBISupportingMoreThan1 = ctAllSupportingAccNCBI \nOtherwise there is bug.\n";
    print LOG "$ctAccNCBISupportingOnly1 + $ctAccNCBISupportingMoreThan1 = $ctAllSupportingAccNCBI\n\n";

    ## if the numbers don't add up, stop the whole process
    if ($ctAccNCBISupportingOnly1 + $ctAccNCBISupportingMoreThan1 != $ctAllSupportingAccNCBI) {
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: some numbers don't add up";
        &reportErrAndExit($subjectLine);
    }
}

sub initializeMapOfZfinToNCBIgeneIds {
    #--------------------------------------------------------------------------
    # Step 5-4: get 1:1, 1:N and 1:0 (from ZFIN to NCBI) lists
    #
    # pass 1 of the mapping: one-way mapping of ZFIN genes onto NCBI genes
    #--------------------------------------------------------------------------

    # Globals:
    #     %oneToNZFINtoNCBI
    #     %oneToOneZFINtoNCBI
    #     %genesZFINwithNoRNAFoundAtNCBI


    ## %oneToNZFINtoNCBI is the hash to store the 1:n one-way mapping result of ZDB gene zdb id onto NCBI gene zdb id
    ## %oneToNZFINtoNCBI include those 1:N (ZFIN to NCBI) and N:N (ZFIN to NCBI)
    ## key:    zdb gene Id
    ## value:  referec to the hash of 2 or more NCBI gene Ids that are mapped to zdb gene id
    ## example: $oneToNZFINtoNCBI{$zdbGeneId1} = \%mappedNCBIgeneIdsSet1

    %NCBIState::oneToNZFINtoNCBI = ();

    ## %oneToOneZFINtoNCBI is the hash to store the 1:1 one-way mapping result of NCBI gene zdb id onto ZFIN gene zdb id
    ## key:    zdb gene Id
    ## value:  NCBI gene Id that is mapped to the ZDB gene id and not mapped to another ZDB gene id
    ## example: $oneToOneZFINtoNCBI{$zdbGeneId1} = $NCBIgeneId1

    %NCBIState::oneToOneZFINtoNCBI = ();

    ## %genesZFINwithNoRNAFoundAtNCBI is the hash to store the ZFIN genes with supporting accessions all of which are not found at NCBI's gene2accession file
    ## key:    zdb gene Id
    ## value:  reference to the array of accession(s) that support the gene at ZFIN but not found at NCBI's gene2accession file
    ## example: $genesZFINwithNoRNAFoundAtNCBI{$zdbGeneId1} = {acc1, acc2}

    %NCBIState::genesZFINwithNoRNAFoundAtNCBI = ();

    ## doing the mapping of ZDB Gene Id to NCBI Gene Id based on the data in the following 3 hashes established before
    ## 1) %supportedGeneZFIN                     -- ZFIN genes that are supported by GenBank RNA accessions
    ## 2) %geneZFINwithAccSupportingMoreThan1    -- RNA-supported ZFIN genes having at least 1 RNA accession that supports other ZFIN gene
    ## those in 1) but not in 2) get processed
    ## 3) %accNCBIsupportingOnly1                -- GenBank accessions/NCBI gene Id pairs


    my $ct1to1ZFINtoNCBI = 0;
    my $ct1toNZFINtoNCBI = 0;
    my $ctProcessedZFINgenes = 0;
    my $ctZFINgenesSupported = 0;
    my $ctZFINgenesWithAllAccsNotFoundAtNCBI = 0;
    my %NCBIgeneIdsSaved;
    my $ref_mappedNCBIgeneIds;


    foreach my $zfinGene (keys %NCBIState::supportedGeneZFIN) {
        $ctZFINgenesSupported++;

        ## those genes with even just 1 supporting RNA sequence that supports another gene won't be processed
        if (!exists($NCBIState::geneZFINwithAccSupportingMoreThan1{$zfinGene})) {

            $ctProcessedZFINgenes++;

            ## refence to the array of supporting GenBank RNA accessions
            my $ref_arrayOfAccs = $NCBIState::supportedGeneZFIN{$zfinGene};

            my $ctAccsForGene = 0;
            my $mapped1to1 = 1;
            my $firstMappedNCBIgeneIdSaved = "None";

            my $ct = 0;
            foreach my $acc (@$ref_arrayOfAccs) {
                $ct++;

                ## only map ZFIN genes to NCBI genes that are with supporting RNA accessions supporting only 1 NCBI gene
                ## may have supporting acc at ZFIN that is not found at NCBI or not supporting any gene at NCBI; do nothing in such cases

                if (exists($NCBIState::accNCBIsupportingOnly1{$acc}) && !exists($NCBIState::accNCBIsupportingMoreThan1{$acc})) {

                    $ctAccsForGene++;
                    my $NCBIgeneId = $NCBIState::accNCBIsupportingOnly1{$acc};  ## this is the NCBI gene Id that is mapped to ZDB gene Id based on the common RNA acc
                    if ($ctAccsForGene == 1) {   # first acc in the supporting acc list for ZFIN gene which is also found at NCBI
                        $firstMappedNCBIgeneIdSaved = $NCBIgeneId;
                        $ref_mappedNCBIgeneIds = {$firstMappedNCBIgeneIdSaved => $acc};  ## anonymous hash to be put as value in an outer hash
                        %NCBIgeneIdsSaved = %$ref_mappedNCBIgeneIds;                ## to be looked up to avoid redundant NCBI gene id
                    } else {
                        if (!exists($NCBIgeneIdsSaved{$NCBIgeneId})) {  ## if the gene is not found in the save hash, it means mapped to another NCBI gene
                            ## do nothing if it is found in the save hash
                            $mapped1to1 = 0;
                            $NCBIgeneIdsSaved{$NCBIgeneId} = $acc;         ## add it to the save hash
                            $ref_mappedNCBIgeneIds->{$NCBIgeneId} = $acc;   ## add it to the hash for mapped NCBI gene ids
                        }
                    }

                }

            }  # of foreach $acc (@$ref_arrayOfAccs)

            if ($mapped1to1 == 1 && $firstMappedNCBIgeneIdSaved ne "None") {
                $NCBIState::oneToOneZFINtoNCBI{$zfinGene} = $firstMappedNCBIgeneIdSaved;
                $ct1to1ZFINtoNCBI++;
            }

            if ($mapped1to1 == 0) {
                $ct1toNZFINtoNCBI++;
                $NCBIState::oneToNZFINtoNCBI{$zfinGene} = $ref_mappedNCBIgeneIds;
            }

            if ($mapped1to1 == 1 && $firstMappedNCBIgeneIdSaved eq "None") {
                $ctZFINgenesWithAllAccsNotFoundAtNCBI++;
                $NCBIState::genesZFINwithNoRNAFoundAtNCBI{$zfinGene} = $ref_arrayOfAccs;
            }
        }

    }    # end of foreach $zfinGene (keys %supportedGeneZFIN)

    if ($debug) {
        open (DBG9, ">debug9") ||  die "Cannot open debug9 : $!\n";
        foreach my $geneZDBId (sort keys %NCBIState::oneToOneZFINtoNCBI) {
            my $ncbiGeneId = $NCBIState::oneToOneZFINtoNCBI{$geneZDBId};
            print DBG9 "$geneZDBId \t $ncbiGeneId\n";
        }

        close DBG9;

        open (DBG10, ">debug10") ||  die "Cannot open debug10 : $!\n";

        foreach my $zdbId (sort keys %NCBIState::oneToNZFINtoNCBI) {
            my $ref_hashNCBIgenes = $NCBIState::oneToNZFINtoNCBI{$zdbId};
            print DBG10 "$zdbId\t";
            foreach my $ncbiId (sort keys %$ref_hashNCBIgenes) {
                print DBG10 "$ncbiId ";
            }
            print DBG10 "\n";
        }

        close DBG10;
    }

    print LOG "\nctZFINgenesSupported = $ctZFINgenesSupported \nctProcessedZFINgenes = $ctProcessedZFINgenes\n\n";
    print LOG "\nct1to1ZFINtoNCBI = $ct1to1ZFINtoNCBI \n ct1toNZFINtoNCBI = $ct1toNZFINtoNCBI\n\n";

    print LOG "\nThe following should add up \nct1to1ZFINtoNCBI + ct1toNZFINtoNCBI + ctZFINgenesWithAllAccsNotFoundAtNCBI = ctProcessedZFINgenes \nOtherwise there is bug.\n";
    print LOG "$ct1to1ZFINtoNCBI + $ct1toNZFINtoNCBI + $ctZFINgenesWithAllAccsNotFoundAtNCBI = $ctProcessedZFINgenes\n\n";

    ## if the numbers don't add up, stop the whole process
    if ($ct1to1ZFINtoNCBI + $ct1toNZFINtoNCBI + $ctZFINgenesWithAllAccsNotFoundAtNCBI != $ctProcessedZFINgenes) {
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: some numbers don't add up";
        &reportErrAndExit($subjectLine);
    }
}

sub logOneToZeroAssociations {
    # Global: %genesZFINwithNoRNAFoundAtNCBI
    open (ONETOZERO, ">reportOneToZero") ||  die "Cannot open ONETOZERO : $!\n" if $debug;

    my $ctOneToZero = 0;
    foreach my $geneAtZFIN (sort keys %NCBIState::genesZFINwithNoRNAFoundAtNCBI) {
        my $ref_arrayAccsNotFoundAtNCBI = $NCBIState::genesZFINwithNoRNAFoundAtNCBI{$geneAtZFIN} if $debug;
        print ONETOZERO "$geneAtZFIN\t@$ref_arrayAccsNotFoundAtNCBI \n" if $debug;
        $ctOneToZero++;
    }

    close ONETOZERO if $debug;

    print LOG "\nctOneToZero = $ctOneToZero\n\n";

    print STATS "\nMapping result statistics: number of 1:0 (ZFIN to NCBI) - $ctOneToZero\n\n";
}

sub oneWayMappingNCBItoZfinGenes {
    #--------------------------------------------------------------------------------
    # Step 5-5: get 1:1, 1:N and 1:0 (from NCBI to ZFIN) lists
    #
    # pass 2 of the mapping: one-way mapping of NCBI genes onto ZFIN genes
    #--------------------------------------------------------------------------------

    # Global: %oneToOneNCBItoZFIN
    #         %oneToNNCBItoZFIN
    #         %supportedGeneNCBI

    ## %oneToNNCBItoZFIN is the hash to store the 1:n one-way mapping result of NCBI gene zdb id onto ZFIN gene zdb id
    ## key:    NCBI gene Id
    ## value:  referec to the hash of 2 or more ZDB gene Ids that are mapped to NCBI gene id
    ## example: $oneToNNCBItoZFIN{$ncbiGeneId1} = \%mappedZDBgeneIdsSet1

    %NCBIState::oneToNNCBItoZFIN = ();

    ## %oneToOneNCBItoZFIN is the hash to store the 1:1 one-way mapping results of NCBI gene zdb id onto ZFIN gene zdb id
    ## %oneToOneNCBItoZFIN include those 1:N (NCBI to ZFIN) and N:N (NCBI to ZFIN)
    ## key:    NCBI gene Id
    ## value:  ZDB gene Id that is mapped to the NCBI gene id and not mapped to another NCBI gene id
    ## example: $oneToOneNCBItoZFIN{$zdbGeneId1} = $NCBIgeneId1

    %NCBIState::oneToOneNCBItoZFIN = ();

    ## %genesNCBIwithAllAccsNotFoundAtZFIN is the hash to store the NCBI genes with supporting accessions all of which are not found at ZFIN
    ## key:    zdb gene Id
    ## value:  reference to the array of accession(s) that support the gene at NCBI but not found at ZFIN
    ## example: $genesNCBIwithAllAccsNotFoundAtZFIN{$zdbGeneId1} = {acc1, acc2}

    my %genesNCBIwithAllAccsNotFoundAtZFIN = ();

    ## doing the mapping of NCBI Gene Id to ZFIN Gene Id based on the data in the following 3 hashes populated before
    ## 1) %supportedGeneNCBI                     -- NCBI genes that are supported by GenBank RNA accessions
    ## 2) %geneNCBIwithAccSupportingMoreThan1    -- RNA-supported NCBI genes having at least 1 RNA accession that supports other NCBI gene
    ## those in 1) but not in 2) get processed
    ## 3) %accZFINsupportingOnly1                -- GenBank accessions/ZDB gene Id pairs


    my $ct1to1NCBItoZFIN = 0;
    my $ct1toNNCBItoZFIN = 0;
    my $ctProcessedNCBIgenes = 0;
    my $ctNCBIgenesSupported = 0;
    my $ctNCBIgenesWithAllAccsNotFoundAtZFIN = 0;
    my $ref_arrayOfAccs;
    my $ref_mappedZDBgeneIds;
    my %ZDBgeneIdsSaved;

    foreach my $ncbiGene (keys %NCBIState::supportedGeneNCBI) {
        $ctNCBIgenesSupported++;

        ## those genes with even just 1 supporting RNA sequence that supports another gene won't be processed
        if (!exists($NCBIState::geneNCBIwithAccSupportingMoreThan1{$ncbiGene})) {

            $ctProcessedNCBIgenes++;

            ## refence to the array of supporting GenBank RNA accessions
            $ref_arrayOfAccs = $NCBIState::supportedGeneNCBI{$ncbiGene};

            my $ctAccsForGene = 0;
            my $mapped1to1 = 1;
            my $firstMappedZFINgeneIdSaved = "None";

            my $ct = 0;
            foreach my $acc (@$ref_arrayOfAccs) {
                $ct++;

                ## only map NCBI genes to ZFIN genes that are with supporting RNA accessions supporting only 1 ZFIN gene
                ## may have supporting acc at NCBI that is not found at ZFIN yet or not associated with any gene at ZFIN yet; do nothing in such cases
                if (exists($NCBIState::accZFINsupportingOnly1{$acc}) && !exists($NCBIState::accZFINsupportingMoreThan1{$acc})) {

                    $ctAccsForGene++;
                    my $ZDBgeneId = $NCBIState::accZFINsupportingOnly1{$acc};  ## this is the ZDB gene Id that is mapped to NCBI gene Id based on the common RNA acc

                    if ($ctAccsForGene == 1) {   # first acc in the supporting acc list for NCBI gene which is also found at ZFIN
                        $firstMappedZFINgeneIdSaved = $ZDBgeneId;
                        $ref_mappedZDBgeneIds = {$firstMappedZFINgeneIdSaved => $acc};  ## anonymous hash to be expanded and saved
                        %ZDBgeneIdsSaved = %$ref_mappedZDBgeneIds;                ## to be looked up to avoid redundant ZDB gene id
                    } else {
                        if (!exists($ZDBgeneIdsSaved{$ZDBgeneId})) {  ## if the gene is not found in the save hash, it means mapped to > 1 ZFIN genes
                            ## do nothing if it is found in the save hash
                            $mapped1to1 = 0;
                            $ZDBgeneIdsSaved{$ZDBgeneId} = $acc;         ## add it to the save hash
                            $ref_mappedZDBgeneIds->{$ZDBgeneId} = $acc;   ## add it to the hash for mapped ZFIN gene ids
                        }

                    }

                }

            }  # of foreach $acc (@$ref_arrayOfAccs)

            if ($mapped1to1 == 1 && $firstMappedZFINgeneIdSaved ne "None") {
                $NCBIState::oneToOneNCBItoZFIN{$ncbiGene} = $firstMappedZFINgeneIdSaved;
                $ct1to1NCBItoZFIN++;
            }

            if ($mapped1to1 == 0) {
                $ct1toNNCBItoZFIN++;
                $NCBIState::oneToNNCBItoZFIN{$ncbiGene} = $ref_mappedZDBgeneIds;
            }

            if ($mapped1to1 == 1 && $firstMappedZFINgeneIdSaved eq "None") {
                $ctNCBIgenesWithAllAccsNotFoundAtZFIN++;
                $genesNCBIwithAllAccsNotFoundAtZFIN{$ncbiGene} = $ref_arrayOfAccs;
            }
        }

    }    # end of foreach $ncbiGene (keys %supportedGeneNCBI)

    if ($debug) {
        open (DBG12, ">debug12") ||  die "Cannot open debug12 : $!\n";

        foreach my $ncbiGeneId (sort keys %NCBIState::oneToOneNCBItoZFIN) {
            my $geneZDBId = $NCBIState::oneToOneNCBItoZFIN{$ncbiGeneId};
            print DBG12 "$ncbiGeneId \t $geneZDBId\n";
        }

        close DBG12;

        open (DBG13, ">debug13") ||  die "Cannot open debug13 : $!\n";

        foreach my $ncbiId (sort keys %NCBIState::oneToNNCBItoZFIN) {
            my $ref_hashZDBgenes = $NCBIState::oneToNNCBItoZFIN{$ncbiId};
            print DBG13 "$ncbiId\t";

            foreach my $zdbId (sort keys %$ref_hashZDBgenes) {
                print DBG13 "$zdbId ";
            }

            print DBG13 "\n";
        }

        close DBG13;
    }

    open (DBG14, ">debug14") ||  die "Cannot open debug14 : $!\n" if $debug;

    my $ctzeroToOne = 0;
    foreach my $geneAtNCBI (sort keys %genesNCBIwithAllAccsNotFoundAtZFIN) {
        my $ref_arrayAccsNotFoundAtZFIN = $genesNCBIwithAllAccsNotFoundAtZFIN{$geneAtNCBI} if $debug;
        print DBG14 "$geneAtNCBI\t@$ref_arrayAccsNotFoundAtZFIN \n" if $debug;
        $ctzeroToOne++;
    }

    close DBG14 if $debug;

    print LOG "\nctzeroToOne = $ctzeroToOne\n\n";

    print LOG "\nctNCBIgenesSupported = $ctNCBIgenesSupported \nctProcessedNCBIgenes = $ctProcessedNCBIgenes\n\n";
    print LOG "\nct1to1NCBItoZFIN = $ct1to1NCBItoZFIN \n ct1toNNCBItoZFIN = $ct1toNNCBItoZFIN\n\n";

    print LOG "\nThe following should add up \nct1to1NCBItoZFIN + ct1toNNCBItoZFIN + ctNCBIgenesWithAllAccsNotFoundAtZFIN = ctProcessedNCBIgenes \nOtherwise there is bug.\n";
    print LOG "$ct1to1NCBItoZFIN + $ct1toNNCBItoZFIN + $ctNCBIgenesWithAllAccsNotFoundAtZFIN = $ctProcessedNCBIgenes\n\n";

    ## if the numbers don't add up, stop the whole process
    if ($ct1to1NCBItoZFIN + $ct1toNNCBItoZFIN + $ctNCBIgenesWithAllAccsNotFoundAtZFIN != $ctProcessedNCBIgenes) {
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: some numbers don't add up";
        &reportErrAndExit($subjectLine);
    }

    print STATS "\nMapping result statistics: number of 0:1 (ZFIN to NCBI) - $ctzeroToOne\n\n";
}

sub compare2WayMappingResults {
    #------------------------------------------------------------------------------------------------------------------------
    # Step 5-6: compare the 2-way mapping results and get the final 1:1, 1:N, N:1, and N:N lists
    #
    # pass 3 of the mapping: compare the results of both of the one-way mappings and make the final 1:1, 1:N, N:1, N:N lists
    #------------------------------------------------------------------------------------------------------------------------
    # Globals:
    #   %oneToOneZFINtoNCBI
    #   %mapped
    #   %mappedReversed
    #   $ctOneToOneNCBI

    %NCBIState::mapped = ();  ## the list of 1:1; key: ZDB gene Id; value: NCBI gene Id
    %NCBIState::mappedReversed = ();  ## the list of 1:1; key: NCBI gene Id; value: ZDB gene Id
    $NCBIState::ctOneToOneNCBI = 0;

    my $ctAllpotentialOneToOneZFIN = 0;
    my $ctOneToOneZFIN = 0;
    my $ncbiId;

    foreach my $zdbid (keys %NCBIState::oneToOneZFINtoNCBI) {
        $ctAllpotentialOneToOneZFIN++;
        $ncbiId = $NCBIState::oneToOneZFINtoNCBI{$zdbid};
        if (exists($NCBIState::oneToOneNCBItoZFIN{$ncbiId})) {
            $ctOneToOneZFIN++;
            $NCBIState::mapped{$zdbid} = $ncbiId;
        }
    }

    print LOG "\n ctAllpotentialOneToOneZFIN = $ctAllpotentialOneToOneZFIN \n ctOneToOneZFIN = $ctOneToOneZFIN\n\n";

    my $ctAllpotentialOneToOneNCBI = 0;
    foreach $ncbiId (keys %NCBIState::oneToOneNCBItoZFIN) {
        $ctAllpotentialOneToOneNCBI++;
        my $zdbId = $NCBIState::oneToOneNCBItoZFIN{$ncbiId};
        if (exists($NCBIState::oneToOneZFINtoNCBI{$zdbId})) {
            $NCBIState::ctOneToOneNCBI++;    ## this number should be the same as $ctOneToOneZFIN
            $NCBIState::mappedReversed{$ncbiId} = $zdbId;
        }
    }

    print LOG "\n ctAllpotentialOneToOneNCBI = $ctAllpotentialOneToOneNCBI \n ctOneToOneNCBI = $NCBIState::ctOneToOneNCBI\n\n";
    print STATS "\nMapping result statistics: number of 1:1 based on GenBank RNA - $NCBIState::ctOneToOneNCBI\n\n";
}

sub writeNCBIgeneIdsMappedBasedOnGenBankRNA {
    # -------- write the NCBI gene Ids mapped based on GenBank RNA accessions on toLoad.unl ------------
    # Globals:
    #  %mapped
    #  $ctToLoad
    $NCBIState::ctToLoad = 0;

    foreach my $zdbId (sort keys %NCBIState::mapped) {
        my $mappedNCBIgeneId = $NCBIState::mapped{$zdbId};
        print TOLOAD "$zdbId|$mappedNCBIgeneId|||$NCBIState::fdcontNCBIgeneId|$NCBIState::pubMappedbasedOnRNA\n";
        $NCBIState::ctToLoad++;
    }
}

sub getOneToNNCBItoZFINgeneIds {
    #------------------------ get 1:N list and N:N from ZFIN to NCBI -----------------------------
    # Globals:
    #  %nToOne
    #  %oneToN


    # %nToOne is a hash storing NCBI gene Ids in 1 to N mapping results mapped from NCBI to ZFIN
    # 1 to N from NCBI to ZFIN is equivalent to N to 1 from ZFIN to NCBI
    # key: NCBI gene Id
    # value: reference to hash of mapped ZDB gene Ids

    %NCBIState::nToOne = ();

    # %oneToN is a hash storing ZDB gene Ids in 1 to N mapping results mapped from ZFIN to NCBI
    # key: ZDB gene Id
    # value: reference to hash of mapped NCBI gene Ids

    %NCBIState::oneToN = ();

    my $ctOneToN = 0;
    my $ctNtoNfromZFIN = 0;
    my $mappedNCBIgene;
    my $refArrayAccs;
    my $refAssociatedNCBIgenes;
    my $refAssociatedZFINgenes;
    my $mappedZFINgene;

    # report N:N
    open (NTON, ">reportNtoN") ||  die "Cannot open reportNtoN : $!\n";

    foreach my $geneZFINtoMultiNCBI (sort keys %NCBIState::oneToNZFINtoNCBI) {

        # %zdbIdsOfNtoN is a hash storing ZDB gene Ids in N to N mapping results mapped from ZFIN to NCBI and back to ZFIN
        # key: ZDB gene Id
        # value: reference to hash of associated NCBI gene Id(s)

        my %zdbIdsOfNtoN = ();

        ## set on the flag of 1 to N (ZFIN to NCBI)
        my $oneToNflag = 1;

        # get the reference to the hash of mapped NCBI genes for this ZFIN gene
        my $ref_hashNCBIids = $NCBIState::oneToNZFINtoNCBI{$geneZFINtoMultiNCBI};

        ## for each 1 to N (ZFIN to NCBI), examine if there is 1 to N mapping the other way (NCBI to ZFIN)
        foreach my $ncbiId (sort keys %$ref_hashNCBIids) {

            ## if existing 1 to N the other way (NCBI to ZFIN), indicating N to N
            if (exists($NCBIState::oneToNNCBItoZFIN{$ncbiId})) {

                ## set off flag 1 to N (ZFIN to NCBI)
                $oneToNflag = 0;

                my $ref_hashZdbIds = $NCBIState::oneToNNCBItoZFIN{$ncbiId};
                foreach my $zdbId (keys %$ref_hashZdbIds) {
                    if (exists($NCBIState::oneToNZFINtoNCBI{$zdbId})) {
                        $zdbIdsOfNtoN{$zdbId} = $NCBIState::oneToNZFINtoNCBI{$zdbId};
                    } elsif (exists($NCBIState::oneToOneZFINtoNCBI{$zdbId})) {
                        $mappedNCBIgene = $NCBIState::oneToOneZFINtoNCBI{$zdbId};
                        $zdbIdsOfNtoN{$zdbId} = {$mappedNCBIgene => 1};
                    } else {                              ## impossible
                        print LOG "\n\nThere is a bug: $zdbId is one of the mapped ZDB Ids of $ncbiId but could not find a mapped NCBI Id?\n\n";
                    }
                }
            }
        }

        # print N to N if it is the case, otherwise, populate the 1 to N (ZFIN to NCBI) list
        if ($oneToNflag == 0) {  ## 1 to N flag off means N to N
            $ctNtoNfromZFIN++;

            print NTON "$ctNtoNfromZFIN) -------------------------------------------------------------------------------------------------\n";
            foreach my $zdbIdNtoN (sort keys %zdbIdsOfNtoN) {
                $refArrayAccs = $NCBIState::supportedGeneZFIN{$zdbIdNtoN};
                print NTON "$zdbIdNtoN ($NCBIState::geneZDBidsSymbols{$zdbIdNtoN}) [@$refArrayAccs]\n";

                $refAssociatedNCBIgenes = $zdbIdsOfNtoN{$zdbIdNtoN};
                # for each mapped NCBI gene
                foreach my $ncbiId (sort keys %$refAssociatedNCBIgenes) {
                    $refArrayAccs = $NCBIState::supportedGeneNCBI{$ncbiId};
                    print NTON "	$ncbiId ($NCBIState::NCBIidsGeneSymbols{$ncbiId}) [@$refArrayAccs]\n";
                }
            }

            print NTON "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n";
            foreach my $ncbiGene (sort keys %$ref_hashNCBIids) {
                $refArrayAccs = $NCBIState::supportedGeneNCBI{$ncbiGene};
                print NTON "$ncbiGene ($NCBIState::NCBIidsGeneSymbols{$ncbiGene}) [@$refArrayAccs]\n";

                # the following print the associated ZFIN records for each of the mapped NCBI gene

                if (exists($NCBIState::oneToNNCBItoZFIN{$ncbiGene})) {
                    $refAssociatedZFINgenes = $NCBIState::oneToNNCBItoZFIN{$ncbiGene};

                    # for each of the associated ZFIN gene
                    foreach my $zdbId (sort keys %$refAssociatedZFINgenes) {
                        $refArrayAccs = $NCBIState::supportedGeneZFIN{$zdbId};
                        print NTON "	$zdbId ($NCBIState::geneZDBidsSymbols{$zdbId}) [@$refArrayAccs]\n";
                    }
                } elsif (exists($NCBIState::oneToOneNCBItoZFIN{$ncbiGene})) {
                    $mappedZFINgene = $NCBIState::oneToOneNCBItoZFIN{$ncbiGene};
                    $refArrayAccs = $NCBIState::supportedGeneZFIN{$mappedZFINgene};
                    print NTON "	$mappedZFINgene ($NCBIState::geneZDBidsSymbols{$mappedZFINgene}) [@$refArrayAccs]\n";
                } else {                              ## impossible
                    print NTON "There is a bug: $ncbiGene is one of the mapped NCBI gene Ids of $geneZFINtoMultiNCBI but could not find a mapped ZDB Id?\n\n";
                }
            }

            print NTON "\n";

        } else {                 ## 1 to N (ZFIN to NCBI)
            $ctOneToN++;
            $NCBIState::oneToN{$geneZFINtoMultiNCBI} = $ref_hashNCBIids;
        }

    }

    print NTON "\n**** the above N to N are derived from mapping ZFIN records to NCBI records and then back to ZFIN records *****\n";
    print NTON "\n**** the following N to N are derived from mapping NCBI records to ZFIN record and then back to NCBI records ****\n";
    print NTON "\n******** redundancy between the two parts of reporting N to N is expected ***********\n\n";

    print LOG "\nctOneToN = $ctOneToN\nctNtoNfromZFIN = $ctNtoNfromZFIN\n\n";

    print STATS "\nMapping result statistics: number of 1:N (ZFIN to NCBI) - $ctOneToN\n\n";
    print STATS "\nMapping result statistics: number of N:N (ZFIN to NCBI) - $ctNtoNfromZFIN\n\n";
}

sub getNtoOneAndNtoNfromZFINtoNCBI {
    #------------------------ get N:1 list and N:N from ZFIN to NCBI -----------------------------
    # Globals:
    #  %oneToNNCBItoZFIN
    #  %zdbGeneIdsNtoOneAndNtoN


    my $ctNtoOne = 0;
    my $ctNtoNfromNCBI = 0;
    my $refArrayAccs;
    my $mappedNCBIgene;

    # the following hash stores those zdb gene ids that are involved in N:1 and N:N (ZFIN to NCBI)
    %NCBIState::zdbGeneIdsNtoOneAndNtoN = ();

    foreach my $geneNCBItoMultiZFIN (sort keys %NCBIState::oneToNNCBItoZFIN) {
        # %ncbiIdsOfNtoN is a hash storing NCBI gene Ids in N to N mapping results mapped from NCBI to ZFIN and back to NCBI
        # key: NCBI gene Id
        # value: reference to hash of associated ZDB gene Id(s)

        my %ncbiIdsOfNtoN = ();

        ## set on the flag of 1 to N (NCBI to ZFIN)
        my $oneToNflag = 1;

        # get the reference to the hash of mapped ZFIN genes for this NCBI gene
        my $ref_hashZFINids = $NCBIState::oneToNNCBItoZFIN{$geneNCBItoMultiZFIN};

        ## for each 1 to N (NCBI to ZFIN), examine if there is 1 to N mapping the other way (ZFIN to NCBI)
        foreach my $zfinId (sort keys %$ref_hashZFINids) {

            $NCBIState::zdbGeneIdsNtoOneAndNtoN{$zfinId} = $geneNCBItoMultiZFIN;

            ## if existing 1 to N the other way (ZFIN to NCBI), indicating N to N
            if (exists($NCBIState::oneToNZFINtoNCBI{$zfinId})) {

                ## set off flag 1 to N (NCBI to ZFIN)
                $oneToNflag = 0;

                my $ref_hashNcbiIds = $NCBIState::oneToNZFINtoNCBI{$zfinId};
                foreach my $ncbiId (keys %$ref_hashNcbiIds) {
                    if (exists($NCBIState::oneToNNCBItoZFIN{$ncbiId})) {
                        $ncbiIdsOfNtoN{$ncbiId} = $NCBIState::oneToNNCBItoZFIN{$ncbiId};
                    } elsif (exists($NCBIState::oneToOneNCBItoZFIN{$ncbiId})) {
                        my $mappedZFINgene = $NCBIState::oneToOneNCBItoZFIN{$ncbiId};
                        $ncbiIdsOfNtoN{$ncbiId} = {$mappedZFINgene => 1};
                    } else {                              ## impossible
                        print LOG "\n\nThere is a bug: $ncbiId is one of the mapped NCBI Ids of $zfinId but could not find a mapped ZDB Id?\n\n";
                    }
                }
            }
        }

        # print N to N if it is the case, otherwise, populate the 1 to N (ZFIN to NCBI) list, i.e. the N to 1 (ZFIN to NCBI) list
        if ($oneToNflag == 0) {  ## 1 to N flag off means N to N
            $ctNtoNfromNCBI++;

            print NTON "$ctNtoNfromNCBI -------------------------------------------------------------------------------------------------\n";
            foreach my $ncbiIdNtoN (sort keys %ncbiIdsOfNtoN) {
                $refArrayAccs = $NCBIState::supportedGeneNCBI{$ncbiIdNtoN};
                print NTON "$ncbiIdNtoN ($NCBIState::NCBIidsGeneSymbols{$ncbiIdNtoN}) [@$refArrayAccs]\n";

                my $refAssociatedZFINgenes = $ncbiIdsOfNtoN{$ncbiIdNtoN};
                # for each mapped ZFIN gene
                foreach my $zdbId (sort keys %$refAssociatedZFINgenes) {
                    $refArrayAccs = $NCBIState::supportedGeneZFIN{$zdbId};
                    print NTON "	$zdbId ($NCBIState::geneZDBidsSymbols{$zdbId}) [@$refArrayAccs]\n";
                }
            }

            print NTON "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n";
            foreach my $zdbId (sort keys %$ref_hashZFINids) {
                $refArrayAccs = $NCBIState::supportedGeneZFIN{$zdbId};
                print NTON "$zdbId ($NCBIState::geneZDBidsSymbols{$zdbId}) [@$refArrayAccs]\n";

                # the following print the associated NCBI records for each of the mapped ZDB gene

                if (exists($NCBIState::oneToNZFINtoNCBI{$zdbId})) {
                    my $refAssociatedNCBIgenes = $NCBIState::oneToNZFINtoNCBI{$zdbId};

                    # for each of the associated ZFIN gene
                    foreach my $ncbiGene (sort keys %$refAssociatedNCBIgenes) {
                        $refArrayAccs = $NCBIState::supportedGeneNCBI{$ncbiGene};
                        print NTON "	$ncbiGene ($NCBIState::NCBIidsGeneSymbols{$ncbiGene}) [@$refArrayAccs]\n";
                    }
                } elsif (exists($NCBIState::oneToOneZFINtoNCBI{$zdbId})) {
                    $mappedNCBIgene = $NCBIState::oneToOneZFINtoNCBI{$zdbId};
                    $refArrayAccs = $NCBIState::supportedGeneNCBI{$mappedNCBIgene};
                    print NTON "	$mappedNCBIgene ($NCBIState::NCBIidsGeneSymbols{$mappedNCBIgene}) [@$refArrayAccs]\n";
                } else {                              ## impossible
                    print NTON "There is a bug: $zdbId is one of the mapped ZFIN gene Ids of $geneNCBItoMultiZFIN but could not find a mapped NCBI Id?\n\n";
                }
            }

            print NTON "\n";

        } else {                 ## 1 to N (NCBI to ZFIN) i.e. N to 1 from ZFIN to NCBI
            $ctNtoOne++;
            $NCBIState::nToOne{$geneNCBItoMultiZFIN} = $ref_hashZFINids;
        }
    }

    close NTON;

    print LOG "\nctNtoOne = $ctNtoOne\nctNtoNfromNCBI = $ctNtoNfromNCBI\n\n";

    print STATS "\nMapping result statistics: number of N:1 (ZFIN to NCBI) - $ctNtoOne\n\n";
    print STATS "\nMapping result statistics: number of N:N (NCBI to ZFIN) - $ctNtoNfromNCBI\n\n";

    my $subject = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: List of N to N";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'},"$subject","reportNtoN");
}

sub reportOneToN {
    #--------------------- report 1:N ---------------------------------------------
    # Globals:
    #  %oneToN
    #  %supportedGeneZFIN
    #  %supportedGeneNCBI
    #  %geneZDBidsSymbols
    #  %NCBIidsGeneSymbols
    open (ONETON, ">reportOneToN") ||  die "Cannot open reportOneToN : $!\n";

    my $ct = 0;
    foreach my $zdbId (sort keys %NCBIState::oneToN) {
        $ct++;
        print ONETON "$ct) ---------------------------------------------\n";
        my $refArrayAccs = $NCBIState::supportedGeneZFIN{$zdbId};
        print ONETON "$zdbId ($NCBIState::geneZDBidsSymbols{$zdbId}) [@$refArrayAccs]\n\n";

        my $refHashMultiNCBIgenes = $NCBIState::oneToN{$zdbId};
        foreach my $ncbiId (sort keys %$refHashMultiNCBIgenes) {
            $refArrayAccs = $NCBIState::supportedGeneNCBI{$ncbiId};
            print ONETON "   $ncbiId ($NCBIState::NCBIidsGeneSymbols{$ncbiId}) [@$refArrayAccs]\n\n";
        }
    }

    close ONETON;

    my $subject = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: List of 1 to N";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'},"$subject","reportOneToN");
}

sub reportNtoOne {
    #------------------- report N:1 -------------------------------------------------
    # Globals:
    #  %nToOne
    #  %supportedGeneNCBI
    #  %NCBIidsGeneSymbols

    open (NTOONE, ">reportNtoOne") ||  die "Cannot open reportNtoOne : $!\n";

    my $ct = 0;
    foreach my $ncbiId (sort keys %NCBIState::nToOne) {
        $ct++;
        print NTOONE "$ct) ---------------------------------------------\n";
        my $refArrayAccs = $NCBIState::supportedGeneNCBI{$ncbiId};
        print NTOONE "$ncbiId ($NCBIState::NCBIidsGeneSymbols{$ncbiId}) [@$refArrayAccs]\n\n";

        my $refHashMultiZFINgenes = $NCBIState::nToOne{$ncbiId};
        foreach my $zdbId (sort keys %$refHashMultiZFINgenes) {
            $refArrayAccs = $NCBIState::supportedGeneZFIN{$zdbId};
            print NTOONE "   $zdbId ($NCBIState::geneZDBidsSymbols{$zdbId}) [@$refArrayAccs]\n\n";
        }
    }

    close NTOONE;

    my $subject = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: List of N to 1";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'},"$subject","reportNtoOne");
}

sub buildVegaIDMappings {
    ##-----------------------------------------------------------------------------------
    ## Step 6: map ZFIN gene records to NCBI gene Ids based on common Vega Gene Id
    ##-----------------------------------------------------------------------------------
    #---------------------------------------------------------------------------
    # prepare the list of ZFIN gene with Vega Ids to be mapped to NCBI records
    #---------------------------------------------------------------------------
    # Globals:
    #     %ZDBgeneAndVegaGeneIds
    #     %VegaGeneAndZDBgeneIds
    #     %ZDBgeneWithMultipleVegaGeneIds
    #     %vegaGeneIdWithMultipleZFINgenes

    my $sqlGetVEGAidAndGeneZDBId = "select mrel_mrkr_1_zdb_id, dblink_acc_num
                               from marker_relationship, db_link
                              where mrel_mrkr_2_zdb_id = dblink_linked_recid
                                and dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-14'
                                and (mrel_mrkr_1_zdb_id like 'ZDB-GENE%' or mrel_mrkr_1_zdb_id like '%RNAG%')
                                and dblink_acc_num like 'OTTDARG%'
                                and mrel_type = 'gene produces transcript';";

    my $curGetZDBgeneIdVegaGeneId = $NCBIState::handle->prepare($sqlGetVEGAidAndGeneZDBId);
    $curGetZDBgeneIdVegaGeneId->execute();

    my ($geneZdbId, $VegaGeneId);
    $curGetZDBgeneIdVegaGeneId->bind_columns(\$geneZdbId,\$VegaGeneId);

    ## store the ZDB Gene ID/VEGA Gene Id and VEGA Gene Id/ZDB Gene ID pairs in hashes
    ## also store those ZDB gene Ids with multiple corresponding VEGA Gene Ids

    %NCBIState::ZDBgeneAndVegaGeneIds = ();
    %NCBIState::VegaGeneAndZDBgeneIds = ();
    %NCBIState::ZDBgeneWithMultipleVegaGeneIds = ();
    %NCBIState::vegaGeneIdWithMultipleZFINgenes = ();

    my $ctTotalZDBgeneIdVegaGeneIds = 0;
    my $ref_arrayZDBgenes;

    while ($curGetZDBgeneIdVegaGeneId->fetch()) {
        $ctTotalZDBgeneIdVegaGeneIds++;
        my $ref_arrayVegaGeneIds;
        if (exists($NCBIState::ZDBgeneWithMultipleVegaGeneIds{$geneZdbId}) || (exists($NCBIState::ZDBgeneAndVegaGeneIds{$geneZdbId}) && $NCBIState::ZDBgeneAndVegaGeneIds{$geneZdbId} ne $VegaGeneId)) {
            if (!exists($NCBIState::ZDBgeneWithMultipleVegaGeneIds{$geneZdbId})) {
                my $firstVegaGeneIdFound = $NCBIState::ZDBgeneAndVegaGeneIds{$geneZdbId};
                $ref_arrayVegaGeneIds = [$firstVegaGeneIdFound,$VegaGeneId];
                $NCBIState::ZDBgeneWithMultipleVegaGeneIds{$geneZdbId} = $ref_arrayVegaGeneIds;
            } else {
                $ref_arrayVegaGeneIds = $NCBIState::ZDBgeneWithMultipleVegaGeneIds{$geneZdbId};
                push(@$ref_arrayVegaGeneIds, $VegaGeneId);
            }
        }

        if (exists($NCBIState::vegaGeneIdWithMultipleZFINgenes{$VegaGeneId})
            || (exists($NCBIState::VegaGeneAndZDBgeneIds{$VegaGeneId}) && $NCBIState::VegaGeneAndZDBgeneIds{$VegaGeneId} ne $geneZdbId)) {
            if (!exists($NCBIState::vegaGeneIdWithMultipleZFINgenes{$VegaGeneId})) {
                my $firstGeneZDBidFound = $NCBIState::VegaGeneAndZDBgeneIds{$VegaGeneId};
                $ref_arrayZDBgenes = [$firstGeneZDBidFound,$geneZdbId];
                $NCBIState::vegaGeneIdWithMultipleZFINgenes{$VegaGeneId} = $ref_arrayZDBgenes;
            } else {
                $ref_arrayZDBgenes = $NCBIState::vegaGeneIdWithMultipleZFINgenes{$VegaGeneId};
                push(@$ref_arrayZDBgenes, $geneZdbId);
            }
        }

        $NCBIState::ZDBgeneAndVegaGeneIds{$geneZdbId} = $VegaGeneId;
        $NCBIState::VegaGeneAndZDBgeneIds{$VegaGeneId} = $geneZdbId;
    }

    print LOG "\nctTotalZDBgeneIdVegaGeneIds = $ctTotalZDBgeneIdVegaGeneIds\n\n";

    print STATS "\nThe total number of ZFIN genes with Vega Gene Id: $ctTotalZDBgeneIdVegaGeneIds\n\n";

    $curGetZDBgeneIdVegaGeneId->finish();

    my $ctVegaIdWithMultipleZDBgene = 0;
    print LOG "\nThe following Vega Gene Ids at ZFIN correspond to multiple ZDB Gene Ids\n";
    foreach my $vega (sort keys %NCBIState::vegaGeneIdWithMultipleZFINgenes) {
        $ctVegaIdWithMultipleZDBgene++;
        $ref_arrayZDBgenes = $NCBIState::vegaGeneIdWithMultipleZFINgenes{$vega};
        print LOG "$vega @$ref_arrayZDBgenes\n";
    }
    print LOG "\nctVegaIdWithMultipleZDBgene = $ctVegaIdWithMultipleZDBgene\n\n";

    open (ZDBGENENVEGA, ">reportZDBgeneIdWithMultipleVegaIds") ||  die "Cannot open reportZDBgeneIdWithMultipleVegaIds : $!\n";
    my $ctZDBgeneIdWithMultipleVegaId = 0;
    foreach my $zdbGene (sort keys %NCBIState::ZDBgeneWithMultipleVegaGeneIds) {
        $ctZDBgeneIdWithMultipleVegaId++;
        my $ref_arrayVegaIds = $NCBIState::ZDBgeneWithMultipleVegaGeneIds{$zdbGene};
        print ZDBGENENVEGA "$zdbGene @$ref_arrayVegaIds\n";
    }
    print LOG "\nctZDBgeneIdWithMultipleVegaId = $ctZDBgeneIdWithMultipleVegaId\n\n";
    close ZDBGENENVEGA;
}

sub writeCommonVegaGeneIdMappings {
    ## ---------------------------------------------------------------------------------------------------------------------
    ## doing the mapping based on common Vega Gene Id
    ## ---------------------------------------------------------------------------------------------------------------------
    # Globals:
    #   %geneZDBidsSymbols
    #   %mapped
    #   %oneToNZFINtoNCBI
    #   %zdbGeneIdsNtoOneAndNtoN
    #   %geneZFINwithAccSupportingMoreThan1
    #   %oneToOneViaVega

    %NCBIState::oneToOneViaVega = ();

    my $ctMappedViaVega = 0;

    foreach my $zdbId (sort keys %NCBIState::geneZDBidsSymbols) {
        ## exclude those in the final 1:1, 1:N, N:1, N:N lists and those in the list of those
        ## with at least one GenBank RNA accession supporting more than 1 genes

        if (!exists($NCBIState::mapped{$zdbId}) && !exists($NCBIState::oneToNZFINtoNCBI{$zdbId}) && !exists($NCBIState::zdbGeneIdsNtoOneAndNtoN{$zdbId})
            && !exists($NCBIState::geneZFINwithAccSupportingMoreThan1{$zdbId})) {

            if (exists($NCBIState::ZDBgeneAndVegaGeneIds{$zdbId}) && !exists($NCBIState::ZDBgeneWithMultipleVegaGeneIds{$zdbId})) {
                my $vegaGeneId = $NCBIState::ZDBgeneAndVegaGeneIds{$zdbId};

                ## exclude those NCBI gene Ids that are in the final 1:1, 1:N, N:1, N:N lists and
                ## those in the list of those with at least 1 GenBank RNA accession supporting more than 1 genes

                if (exists($NCBIState::vegaIdsNCBIids{$vegaGeneId})
                    && !exists($NCBIState::vegaIdwithMultipleNCBIids{$vegaGeneId})
                    && !exists($NCBIState::vegaGeneIdWithMultipleZFINgenes{$vegaGeneId})
                ) {

                    my $NCBIgeneIdMappedViaVega = $NCBIState::vegaIdsNCBIids{$vegaGeneId};

                    ## exclude those NCBI gene Ids with multiple Vega Gene Ids, and those with multiple GenBank RNAs,
                    ## and those already mapped

                    if (!exists($NCBIState::NCBIgeneWithMultipleVega{$NCBIgeneIdMappedViaVega})
                        && !exists($NCBIState::geneNCBIwithAccSupportingMoreThan1{$NCBIgeneIdMappedViaVega})
                        && !exists($NCBIState::mappedReversed{$NCBIgeneIdMappedViaVega})
                    ) {

                        ## ----- write the NCBI gene Ids mapped via Vega gene Id on toLoad.unl ---------------
                        print TOLOAD "$zdbId|$NCBIgeneIdMappedViaVega|||$NCBIState::fdcontNCBIgeneId|$NCBIState::pubMappedbasedOnVega\n";
                        $NCBIState::ctToLoad++;

                        $NCBIState::oneToOneViaVega{$NCBIgeneIdMappedViaVega} = $zdbId;
                        $ctMappedViaVega++;
                    }
                }
            }
        }
    }

    my $ctTotalMapped = $ctMappedViaVega + $NCBIState::ctOneToOneNCBI;
    print LOG "\nctMappedViaVega = $ctMappedViaVega\n\nTotal number of the gene records mapped: $ctMappedViaVega + $NCBIState::ctOneToOneNCBI = $ctTotalMapped\n\n";
    print STATS "\nMapping result via Vega Gene Id: $ctMappedViaVega additional gene records are mapped\n\n";
    print STATS "Total number of the gene records mapped: $ctMappedViaVega + $NCBIState::ctOneToOneNCBI = $ctTotalMapped\n\n";
}

sub calculateLengthForAccessionsWithoutLength {
    #--------------------------------------------------------------------------------------------------------------
    # This section CONTINUES to deal with dblink_length field
    # There are 3 sources for length:
    # 1) the existing dblink_length for GenBank including GenPept records
    # 2) the length value of RefSeq sequences on NCBI's RefSeq-release#.catalog file
    # 3) calculated length
    # The first two have been done before parsing the gene2accession file.
    # During parsing gene2accession file, accessions still missing length are stored in a hash named %noLength
    #---------------------------------------------------------------------------------------------------------------
    # Globals:
    #   %noLength

    #----------------------- 3) calculate the length for the those still with no length ---------------
    open (NOLENGTH, ">noLength.unl") ||  die "Cannot open noLength.unl : $!\n";

    foreach my $accWithNoLength (keys %NCBIState::noLength) {
        my $NCBIgeneId = $NCBIState::noLength{$accWithNoLength};

        # print to the noLength.unl only for those with mapped gene Id
        print NOLENGTH "$accWithNoLength\n" if exists($NCBIState::mappedReversed{$NCBIgeneId}) || exists($NCBIState::oneToOneViaVega{$NCBIgeneId});
    }

    close NOLENGTH;

    system("/bin/date");

    if (!-e "noLength.unl") {
        print LOG "\nCannot find noLength.unl as input file for efetch.\n\n";
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: no input file for efetch.r";
        &reportErrAndExit($subjectLine);
    }

    print LOG "\nStart efetching ... \n\n";
    print "\nStart efetching ... \n\n";

    # Using the above noLength.unl as input, call efetch to get the fasta sequences
    # and output to seq.fasta file. This step is time-consuming.

    if ($ENV{"SKIP_DOWNLOADS"}) {
        print LOG "\nSKIP_DOWNLOADS is set, so skipping the efetch step.\n\n";
        print "\nSKIP_DOWNLOADS is set, so skipping the efetch step.\n\n";
    } else {
        my $currentDir = cwd;

        # Set the JAVA_HOME path to override the jenkins one
        $ENV{'JAVA_HOME'} = getPropertyValue("JAVA_HOME");


        my $cmdEfetch = "cd " . $ENV{'SOURCEROOT'} . " ; " .
            "gradle '-DncbiLoadInput=$currentDir/noLength.unl' " .
            "       '-DncbiLoadOutput=$currentDir/seq.fasta' " .
            "         NCBILoadTask ; " .
            "cd $currentDir";
        print "Executing $cmdEfetch\n";
        print LOG "Executing $cmdEfetch\n";

        &doSystemCommand($cmdEfetch);
    }

    print LOG "\nAfter efetching\n\n";
    print "\nAfter efetching\n\n";

    system("/bin/date");

    if (!-e "seq.fasta") {
        print LOG "\nCannot execute efetch for input noLength.unl and output seq.fasta: $! \n\n";
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: ERROR with efetch";
        &reportErrAndExit($subjectLine);
    }

    print LOG "\nDone with efetching.\n\n";

    # fasta_len.awk is the script that does the calculation based on fasta sequence

    my $cmdCalLength = "/opt/zfin/bin/fasta_len.awk seq.fasta >length.unl";
    &doSystemCommand($cmdCalLength);

    if (!-e "length.unl") {
        print LOG "\nError happened when execute fasta_len.awk seq.fasta >length.unl: $! \n\n";
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: ERROR with fasta_len.awk";
        &reportErrAndExit($subjectLine);
    }

    my $ctSeqLengthCalculated = 0;
    open (LENGTH, "length.unl") ||  die "Cannot open length.unl : $!\n";
    while (<LENGTH>) {
        chomp;

        if ($_ =~ m/^(\w+)\|(\d+)\|$/) {
            my $acc = $1;
            my $length = $2;

            $NCBIState::sequenceLength{$acc} = $length;

            $ctSeqLengthCalculated++;
        }

    }

    close LENGTH;

    print LOG "\nctSeqLengthCalculated = $ctSeqLengthCalculated\n\n";
}

sub getGenBankAndRefSeqsWithZfinGenes {
    #---------------------------------------------------------------------------------------------
    # Step 7: prepare the final add-list for RefSeq and GenBank records
    #---------------------------------------------------------------------------------------------
    # Globals:
    #   %geneAccFdbcont

    ## the following SQL is used to get all the existing GenBank and RefSeq records with gene and pseudogene at ZFIN
    ## these records should not and could not be loaded

    my $sqlGetGenBankAndRefSeqAccs = "select dblink_linked_recid, dblink_acc_num, dblink_fdbcont_zdb_id, dblink_zdb_id
                                 from db_link
                                where (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
                                  and dblink_fdbcont_zdb_id in ('ZDB-FDBCONT-040412-37','ZDB-FDBCONT-040412-42',
                                                                'ZDB-FDBCONT-040412-36','ZDB-FDBCONT-040412-38',
                                                                'ZDB-FDBCONT-040412-39','ZDB-FDBCONT-040527-1');";

    my $curGetGenBankAndRefSeqAccs = $NCBIState::handle->prepare($sqlGetGenBankAndRefSeqAccs);

    $curGetGenBankAndRefSeqAccs->execute();
    my ($gene,$acc,$fdbcont,$dblinkId);
    $curGetGenBankAndRefSeqAccs->bind_columns(\$gene,\$acc,\$fdbcont,\$dblinkId);

    # in order for the inserting not to violate the constraint unique (dblink_linked_recid,dblink_acc_num,dblink_fdbcont_zdb_id)
    # key: concatenated string of gene zdb id,accession number and zdb if of fdbcont
    # value: zdb id of the db_link record

    %NCBIState::geneAccFdbcont = ();

    my $ctGeneAccFdbcont = 0;
    while ($curGetGenBankAndRefSeqAccs->fetch()) {
        if (!exists($NCBIState::toDelete{$dblinkId})) {              # exclude those to be deleted first
            $NCBIState::geneAccFdbcont{$gene . $acc . $fdbcont} = $dblinkId;
            $ctGeneAccFdbcont++;
        }
    }

    print LOG "\nctGeneAccFdbcont = $ctGeneAccFdbcont\n\n";

    $curGetGenBankAndRefSeqAccs->finish();
}

sub writeGenBankRNAaccessionsWithMappedGenesToLoad {
    #---------------------------------------------------------------------------
    #  write GenBank RNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    # Globals:
    #   %accNCBIsupportingOnly1
    #   %mappedReversed
    #   %geneAccFdbcont
    #   %oneToOneViaVega

    foreach my $GenBankRNA (sort keys %NCBIState::accNCBIsupportingOnly1) {
        my $NCBIgeneId = $NCBIState::accNCBIsupportingOnly1{$GenBankRNA};
        my $zdbGeneId;
        if (exists($NCBIState::mappedReversed{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::mappedReversed{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankRNA . $NCBIState::fdcontGenBankRNA})) {
                my $length = exists($NCBIState::sequenceLength{$GenBankRNA}) ? $NCBIState::sequenceLength{$GenBankRNA} : '';
                print TOLOAD "$zdbGeneId|$GenBankRNA||$length|$NCBIState::fdcontGenBankRNA|$NCBIState::pubMappedbasedOnRNA\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankRNA . $NCBIState::fdcontGenBankRNA} = 1;
                $NCBIState::ctToLoad++;
            }
        } elsif (exists($NCBIState::oneToOneViaVega{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::oneToOneViaVega{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankRNA . $NCBIState::fdcontGenBankRNA})) {
                my $length = exists($NCBIState::sequenceLength{$GenBankRNA}) ? $NCBIState::sequenceLength{$GenBankRNA} : '';
                print TOLOAD "$zdbGeneId|$GenBankRNA||$length|$NCBIState::fdcontGenBankRNA|$NCBIState::pubMappedbasedOnVega\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankRNA . $NCBIState::fdcontGenBankRNA} = 1;
                $NCBIState::ctToLoad++;
            }
        }
    }
}

sub initializeGenPeptAccessionsMap {
    #---------------------------------------------------------------------------------------
    #  write GenPept accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------------------
    # Globals:
    #     %GenPeptAttributedToNonLoadPub
    #     %GenPeptDbLinkIdAttributedToNonLoadPub

    # get the Genpept accessions and the attribututed pulications that are not the load publications

    my $sqlGenPeptAttributedToNonLoadPub = "select dblink_acc_num, dblink_zdb_id, recattrib_source_zdb_id
                                       from record_attribution, db_link
                                      where recattrib_data_zdb_id = dblink_zdb_id
                                        and dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'
                                        and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
                                        and recattrib_source_zdb_id not in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2');";

    my $curGenPeptAttributedToNonLoadPub = $NCBIState::handle->prepare($sqlGenPeptAttributedToNonLoadPub);

    $curGenPeptAttributedToNonLoadPub->execute;

    my ($GenPept,$dbLinkId,$nonLoadPub);
    $curGenPeptAttributedToNonLoadPub->bind_columns(\$GenPept,\$dbLinkId,\$nonLoadPub);

    # use the following hash to store GenPept accessions and the attributed pulications that are not one of the load publications
    # key: GenPept accession
    # value: publication zdb id

    %NCBIState::GenPeptAttributedToNonLoadPub = ();

    # use the following hash to store GenPept acc and db_link zdb Id that are attributed pulications that are not one of the load publications
    # key: GenPept accession
    # value: db_link zdb id

    %NCBIState::GenPeptDbLinkIdAttributedToNonLoadPub = ();

    my $ctGenPeptNonLoadPub = 0;
    while ($curGenPeptAttributedToNonLoadPub->fetch) {
        $NCBIState::GenPeptAttributedToNonLoadPub{$GenPept} = $nonLoadPub;
        $NCBIState::GenPeptDbLinkIdAttributedToNonLoadPub{$GenPept} = $dbLinkId;
        $ctGenPeptNonLoadPub++;
    }

    print LOG "\nNumber of GenPept accessions attributed to non-load pub: $ctGenPeptNonLoadPub\n\n";

    $curGenPeptAttributedToNonLoadPub->finish();

    my $ctGenPeptAttributedToNonLoadPub = scalar(keys %NCBIState::GenPeptAttributedToNonLoadPub);

    print LOG "\nctGenPeptAttributedToNonLoadPub = $ctGenPeptAttributedToNonLoadPub\n\n";
}

sub processGenBankAccessionsAssociatedToNonLoadPubs {
    #-------- deal with those GenBank accessions attributed to non-load publication -------
    # Globals:
    #  %GenPeptsToLoad
    #  %GenPeptNCBIgeneIds
    #  %mappedReversed
    #  %geneAccFdbcont
    #  %GenPeptAttributedToNonLoadPub
    #  %GenPeptDbLinkIdAttributedToNonLoadPub

    # use the following hash to store GenPept accessions to be loaded
    # key: GenPept accession number
    # value: zdb gene id
    %NCBIState::GenPeptsToLoad = ();

    open (MORETODELETE, ">>toDelete.unl") ||  die "Cannot open toDelete.unl : $!\n";

    my $ctToAttribute = 0;

    print LOG "\nThe GenPept accessions used to attribute to non-load publication now attribute to load pub:\n\n";
    print LOG "GenPept\tZFIN gene Id\tnon-load pub\tload pub\n";
    print LOG "-------\t------------\t------------\t--------\n";

    my $zdbGeneId;
    my $moreToDelete;
    foreach my $GenPept (sort keys %NCBIState::GenPeptNCBIgeneIds) {
        my $NCBIgeneId = $NCBIState::GenPeptNCBIgeneIds{$GenPept};
        if (exists($NCBIState::mappedReversed{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::mappedReversed{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $GenPept . $NCBIState::fdcontGenPept})) {
                my $length = exists($NCBIState::sequenceLength{$GenPept}) ? $NCBIState::sequenceLength{$GenPept} : '';
                print TOLOAD "$zdbGeneId|$GenPept||$length|$NCBIState::fdcontGenPept|$NCBIState::pubMappedbasedOnRNA\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $GenPept . $NCBIState::fdcontGenPept} = 1;
                $NCBIState::ctToLoad++;
                $NCBIState::GenPeptsToLoad{$GenPept} = $zdbGeneId;
            } else {
                if (exists($NCBIState::GenPeptAttributedToNonLoadPub{$GenPept}) && exists($NCBIState::GenPeptDbLinkIdAttributedToNonLoadPub{$GenPept})) {
                    $moreToDelete = $NCBIState::GenPeptDbLinkIdAttributedToNonLoadPub{$GenPept};
                    print MORETODELETE "$moreToDelete\n";
                    $NCBIState::toDelete{$moreToDelete} = 1;
                    my $length = exists($NCBIState::sequenceLength{$GenPept}) ? $NCBIState::sequenceLength{$GenPept} : '';
                    print TOLOAD "$zdbGeneId|$GenPept||$length|$NCBIState::fdcontGenPept|$NCBIState::pubMappedbasedOnRNA\n";
                    $NCBIState::geneAccFdbcont{$zdbGeneId . $GenPept . $NCBIState::fdcontGenPept} = 1;
                    $NCBIState::ctToLoad++;
                    print LOG "$GenPept\t$zdbGeneId\t$NCBIState::GenPeptAttributedToNonLoadPub{$GenPept}\t$NCBIState::pubMappedbasedOnRNA\n";
                    $ctToAttribute++;
                }
            }
        } elsif (exists($NCBIState::oneToOneViaVega{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::oneToOneViaVega{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $GenPept . $NCBIState::fdcontGenPept})) {
                my $length = exists($NCBIState::sequenceLength{$GenPept}) ? $NCBIState::sequenceLength{$GenPept} : '';
                print TOLOAD "$zdbGeneId|$GenPept||$length|$NCBIState::fdcontGenPept|$NCBIState::pubMappedbasedOnVega\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $GenPept . $NCBIState::fdcontGenPept} = 1;
                $NCBIState::ctToLoad++;
                $NCBIState::GenPeptsToLoad{$GenPept} = $zdbGeneId;
            } else {
                if (exists($NCBIState::GenPeptAttributedToNonLoadPub{$GenPept}) && exists($NCBIState::GenPeptDbLinkIdAttributedToNonLoadPub{$GenPept})) {
                    $moreToDelete = $NCBIState::GenPeptDbLinkIdAttributedToNonLoadPub{$GenPept};
                    print MORETODELETE "$moreToDelete\n";
                    $NCBIState::toDelete{$moreToDelete} = 1;
                    my $length = exists($NCBIState::sequenceLength{$GenPept}) ? $NCBIState::sequenceLength{$GenPept} : '';
                    print TOLOAD "$zdbGeneId|$GenPept||$length|$NCBIState::fdcontGenPept|$NCBIState::pubMappedbasedOnVega\n";
                    $NCBIState::geneAccFdbcont{$zdbGeneId . $GenPept . $NCBIState::fdcontGenPept} = 1;
                    $NCBIState::ctToLoad++;
                    print LOG "$GenPept\t$zdbGeneId\t$NCBIState::GenPeptAttributedToNonLoadPub{$GenPept}\t$NCBIState::pubMappedbasedOnVega\n";
                    $ctToAttribute++;
                }
            }
        }
    }

    close MORETODELETE;

    print LOG "---------------------------------------------------------------\nTotal: $ctToAttribute\n\n";

    print STATS "\nNon-load attribution for the $ctToAttribute manually curated GenPept db_link records get replaced by;\n 1 of the 2 load pubs (depending on mapping type).\n\n";
}

sub printGenPeptsAssociatedWithGeneAtZFIN {
    # ----- get all the Genpept accessions associated with gene at ZFIN, and those with multiple ZFIN genes ----------------------------
    # Globals:
    #   %GenPeptsToLoad


    my $sqlAllGenPeptWithGeneZFIN = "select dblink_acc_num, dblink_linked_recid
                                from db_link
                               where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'
                                 and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $curAllGenPeptWithGeneZFIN = $NCBIState::handle->prepare($sqlAllGenPeptWithGeneZFIN);

    $curAllGenPeptWithGeneZFIN->execute;

    my ($GenPept,$geneZdbId);
    $curAllGenPeptWithGeneZFIN->bind_columns(\$GenPept,\$geneZdbId);

    # use the following hash to store all the GenPept accession stored at ZFIN that are assoctied with gene
    # key: GenPept accession
    # value: gene zdb id

    my %AllGenPeptWithGeneZFIN = ();

    # a hash to store GenPept accessions and the multiple related ZFIN gene Ids
    # key: GenPept accession
    # value: reference to an array of gene zdb id

    my %GenPeptWithMultipleZDBgene = ();
    my $ref_arrayZDBgeneIds;
    while ($curAllGenPeptWithGeneZFIN->fetch) {

        if (exists($GenPeptWithMultipleZDBgene{$GenPept}) ||
            (exists($AllGenPeptWithGeneZFIN{$GenPept}) && $AllGenPeptWithGeneZFIN{$GenPept} ne $geneZdbId)) {

            if (!exists($GenPeptWithMultipleZDBgene{$GenPept})) {
                my $firstGenPept = $AllGenPeptWithGeneZFIN{$GenPept};
                $ref_arrayZDBgeneIds = [$firstGenPept,$geneZdbId];
                $GenPeptWithMultipleZDBgene{$GenPept} = $ref_arrayZDBgeneIds;
            } else {
                $ref_arrayZDBgeneIds = $GenPeptWithMultipleZDBgene{$GenPept};
                push(@$ref_arrayZDBgeneIds, $geneZdbId);
            }
        }

        $AllGenPeptWithGeneZFIN{$GenPept} = $geneZdbId;
    }

    $curAllGenPeptWithGeneZFIN->finish();

    my $ctAllGenPeptWithGeneZFIN = scalar(keys %AllGenPeptWithGeneZFIN);

    my $ctGenPeptWithMultipleZDBgene = scalar(keys %GenPeptWithMultipleZDBgene);

    print LOG "\nctAllGenPeptWithGeneZFIN = $ctAllGenPeptWithGeneZFIN\n\n";

    print LOG "\nctGenPeptWithMultipleZDBgene = $ctGenPeptWithMultipleZDBgene\n\n";

    print LOG "-----The GenBank accessions to be loaded but also associated with multiple ZFIN genes----\n\n";
    print LOG "GenPept \t mapped gene \tall associated genes\n";
    print LOG "--------\t-------------\t-------------\n";

    my $ctGenPeptWithMultipleZDBgeneToLoad = 0;
    foreach $GenPept (sort keys %GenPeptWithMultipleZDBgene) {
        if (exists($NCBIState::GenPeptsToLoad{$GenPept})) {
            $ref_arrayZDBgeneIds = $GenPeptWithMultipleZDBgene{$GenPept};
            print LOG "$GenPept\t$NCBIState::GenPeptsToLoad{$GenPept}\t@$ref_arrayZDBgeneIds\n";
            $ctGenPeptWithMultipleZDBgeneToLoad++;
        }
    }
    print LOG "-----------------------------------------\nTotal: $ctGenPeptWithMultipleZDBgeneToLoad\n\n\n";

    print STATS "\nBefore the load, the total number of GenPept accessions associated with multiple ZFIN genes: $ctGenPeptWithMultipleZDBgeneToLoad\n\n";

}

sub writeGenBankDNAaccessionsWithMappedGenesToLoad {
    #---------------------------------------------------------------------------
    #  write GenBank DNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    # Globals:
    #   %GenBankDNAncbiGeneIds
    #   %mappedReversed
    #   %geneAccFdbcont
    #   %oneToOneViaVega
    my $zdbGeneId;
    foreach my $GenBankDNA (sort keys %NCBIState::GenBankDNAncbiGeneIds) {
        my @multipleNCBIgeneIds = @{$NCBIState::GenBankDNAncbiGeneIds{$GenBankDNA}};
        foreach my $NCBIgeneId (@multipleNCBIgeneIds) {
            if (exists($NCBIState::mappedReversed{$NCBIgeneId})) {
                $zdbGeneId = $NCBIState::mappedReversed{$NCBIgeneId};
                if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankDNA . $NCBIState::fdcontGenBankDNA})) {
                    my $length = exists($NCBIState::sequenceLength{$GenBankDNA}) ? $NCBIState::sequenceLength{$GenBankDNA} : '';
                    print TOLOAD "$zdbGeneId|$GenBankDNA||$length|$NCBIState::fdcontGenBankDNA|$NCBIState::pubMappedbasedOnRNA\n";
                    $NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankDNA . $NCBIState::fdcontGenBankDNA} = 1;
                    $NCBIState::ctToLoad++;
                }
            }
            elsif (exists($NCBIState::oneToOneViaVega{$NCBIgeneId})) {
                $zdbGeneId = $NCBIState::oneToOneViaVega{$NCBIgeneId};
                if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankDNA . $NCBIState::fdcontGenBankDNA})) {
                    my $length = exists($NCBIState::sequenceLength{$GenBankDNA}) ? $NCBIState::sequenceLength{$GenBankDNA} : '';
                    print TOLOAD "$zdbGeneId|$GenBankDNA||$length|$NCBIState::fdcontGenBankDNA|$NCBIState::pubMappedbasedOnVega\n";
                    $NCBIState::geneAccFdbcont{$zdbGeneId . $GenBankDNA . $NCBIState::fdcontGenBankDNA} = 1;
                    $NCBIState::ctToLoad++;
                }
            }
        }
    }
}

sub writeRefSeqRNAaccessionsWithMappedGenesToLoad {
    #---------------------------------------------------------------------------
    #  write RefSeq RNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    # Globals:
    #  %RefSeqRNAncbiGeneIds
    #  %mappedReversed
    #  %geneAccFdbcont
    #  $NCBIState::pubMappedbasedOnRNA
    #  $NCBIState::pubMappedbasedOnVega
    #  $ctToLoad
    #  $NCBIState::fdcontRefSeqRNA
    my $NCBIgeneId;
    my $zdbGeneId;
    foreach my $RefSeqRNA (sort keys %NCBIState::RefSeqRNAncbiGeneIds) {
        $NCBIgeneId = $NCBIState::RefSeqRNAncbiGeneIds{$RefSeqRNA};
        if (exists($NCBIState::mappedReversed{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::mappedReversed{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqRNA . $NCBIState::fdcontRefSeqRNA})) {
                my $length = exists($NCBIState::sequenceLength{$RefSeqRNA}) ? $NCBIState::sequenceLength{$RefSeqRNA} : '';
                print TOLOAD "$zdbGeneId|$RefSeqRNA||$length|$NCBIState::fdcontRefSeqRNA|$NCBIState::pubMappedbasedOnRNA\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqRNA . $NCBIState::fdcontRefSeqRNA} = 1;
                $NCBIState::ctToLoad++;
            }
        } elsif (exists($NCBIState::oneToOneViaVega{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::oneToOneViaVega{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqRNA . $NCBIState::fdcontRefSeqRNA})) {
                my $length = exists($NCBIState::sequenceLength{$RefSeqRNA}) ? $NCBIState::sequenceLength{$RefSeqRNA} : '';
                print TOLOAD "$zdbGeneId|$RefSeqRNA||$length|$NCBIState::fdcontRefSeqRNA|$NCBIState::pubMappedbasedOnVega\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqRNA . $NCBIState::fdcontRefSeqRNA} = 1;
                $NCBIState::ctToLoad++;
            }
        }
    }
}

sub writeRefPeptAccessionsWithMappedGenesToLoad {
    #---------------------------------------------------------------------------
    #  write RefPept accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    # Globals:
    #  %RefPeptNCBIgeneIds
    #  %mappedReversed
    #  %geneAccFdbcont
    #  $NCBIState::pubMappedbasedOnRNA
    #  $NCBIState::pubMappedbasedOnVega
    #  $ctToLoad
    #  $NCBIState::fdcontRefPept
    #  %oneToOneViaVega

    foreach my $RefPept (sort keys %NCBIState::RefPeptNCBIgeneIds) {
        my $NCBIgeneId = $NCBIState::RefPeptNCBIgeneIds{$RefPept};
        my $zdbGeneId;
        if (exists($NCBIState::mappedReversed{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::mappedReversed{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $RefPept . $NCBIState::fdcontRefPept})) {
                my $length = exists($NCBIState::sequenceLength{$RefPept}) ? $NCBIState::sequenceLength{$RefPept} : '';
                print TOLOAD "$zdbGeneId|$RefPept||$length|$NCBIState::fdcontRefPept|$NCBIState::pubMappedbasedOnRNA\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $RefPept . $NCBIState::fdcontRefPept} = 1;
                $NCBIState::ctToLoad++;
            }
        } elsif (exists($NCBIState::oneToOneViaVega{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::oneToOneViaVega{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $RefPept . $NCBIState::fdcontRefPept})) {
                my $length = exists($NCBIState::sequenceLength{$RefPept}) ? $NCBIState::sequenceLength{$RefPept} : '';
                print TOLOAD "$zdbGeneId|$RefPept||$length|$NCBIState::fdcontRefPept|$NCBIState::pubMappedbasedOnVega\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $RefPept . $NCBIState::fdcontRefPept} = 1;
                $NCBIState::ctToLoad++;
            }
        }
    }
}

sub writeRefSeqDNAaccessionsWithMappedGenesToLoad {
    #---------------------------------------------------------------------------
    #  write RefSeq DNA accessions with mapped genes onto toLoad.unl
    #---------------------------------------------------------------------------
    # Globals:
    #  %RefSeqDNAncbiGeneIds
    #  %mappedReversed
    #  %geneAccFdbcont
    #  $NCBIState::pubMappedbasedOnRNA
    #  $NCBIState::pubMappedbasedOnVega
    #  $ctToLoad
    #  $NCBIState::fdcontRefSeqDNA
    #  %oneToOneViaVega

    foreach my $RefSeqDNA (sort keys %NCBIState::RefSeqDNAncbiGeneIds) {
        my $NCBIgeneId = $NCBIState::RefSeqDNAncbiGeneIds{$RefSeqDNA};
        my $zdbGeneId;
        if (exists($NCBIState::mappedReversed{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::mappedReversed{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqDNA . $NCBIState::fdcontRefSeqDNA})) {
                my $length = exists($NCBIState::sequenceLength{$RefSeqDNA}) ? $NCBIState::sequenceLength{$RefSeqDNA} : '';
                print TOLOAD "$zdbGeneId|$RefSeqDNA||$length|$NCBIState::fdcontRefSeqDNA|$NCBIState::pubMappedbasedOnRNA\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqDNA . $NCBIState::fdcontRefSeqDNA} = 1;
                $NCBIState::ctToLoad++;
            }
        } elsif (exists($NCBIState::oneToOneViaVega{$NCBIgeneId})) {
            $zdbGeneId = $NCBIState::oneToOneViaVega{$NCBIgeneId};
            if (!exists($NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqDNA . $NCBIState::fdcontRefSeqDNA})) {
                my $length = exists($NCBIState::sequenceLength{$RefSeqDNA}) ? $NCBIState::sequenceLength{$RefSeqDNA} : '';
                print TOLOAD "$zdbGeneId|$RefSeqDNA||$length|$NCBIState::fdcontRefSeqDNA|$NCBIState::pubMappedbasedOnVega\n";
                $NCBIState::geneAccFdbcont{$zdbGeneId . $RefSeqDNA . $NCBIState::fdcontRefSeqDNA} = 1;
                $NCBIState::ctToLoad++;
            }
        }
    }
}

sub executeDeleteAndLoadSQLFile {
    #-----------------------------------------------------------------------------------------------------------------------
    # Step 8: execute the SQL file to do the deletion according to delete list, and do the loading according to te add list
    #-----------------------------------------------------------------------------------------------------------------------

    if (!-e "toLoad.unl" || $NCBIState::ctToLoad == 0) {
        print LOG "\nMissing the add list, toLoad.unl, or it is empty. Something is wrong!\n\n";
        close STATS;
        my $subjectLine = "Auto from $NCBIState::dbname: " . "NCBI_gene_load.pl :: missing or empty add list, toLoad.unl";
        &reportErrAndExit($subjectLine);
    }

    try {
        &doSystemCommand("psql -v ON_ERROR_STOP=1 -d $ENV{'DB_NAME'} -a -f loadNCBIgeneAccs.sql >loadLog1 2> loadLog2");
    } catch {
        chomp $_;
        &reportErrAndExit("Auto from $NCBIState::dbname: NCBI_gene_load.pl :: failed at loadNCBIgeneAccs.sql");
    } ;

    print LOG "\nDone with the deltion and loading!\n\n";
}

sub reportAllLoadStatistics {
    #-------------------------------------------------------------------------------------------------
    # Step 9: Report the GenPept accessions associated with multiple ZFIN genes after the load.
    # Report GenPept accessions associated with ZFIN genes still attributed to a non-load pub.
    # And do the record counts after the load, and report statistics.
    #-------------------------------------------------------------------------------------------------

    # ----- AFTER THE LOAD, get all the Genpept accessions associated with gene at ZFIN, and those with multiple ZFIN genes ---------

    my $sqlAllGenPeptWithGeneAfterLoad = "select dblink_acc_num, dblink_linked_recid
                                     from db_link
                                    where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'
                                      and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $curAllGenPeptWithGeneAfterLoad = $NCBIState::handle->prepare($sqlAllGenPeptWithGeneAfterLoad);

    $curAllGenPeptWithGeneAfterLoad->execute;

    my ($GenPept, $geneZdbId);
    $curAllGenPeptWithGeneAfterLoad->bind_columns(\$GenPept,\$geneZdbId);

    # use the following hash to store all the GenPept accession stored at ZFIN that are assoctied with gene after the load
    # key: GenPept accession
    # value: gene zdb id

    my %allGenPeptWithGeneAfterLoad = ();

    # a hash to store GenPept accessions and the multiple related ZFIN gene Ids after the load
    # key: GenPept accession
    # value: reference to an array of gene zdb id

    my %GenPeptWithMultipleZDBgeneAfterLoad = ();
    my $ref_arrayZDBgeneIds;

    while ($curAllGenPeptWithGeneAfterLoad->fetch) {

        if (exists($GenPeptWithMultipleZDBgeneAfterLoad{$GenPept}) ||
            (exists($allGenPeptWithGeneAfterLoad{$GenPept}) && $allGenPeptWithGeneAfterLoad{$GenPept} ne $geneZdbId)) {

            if (!exists($GenPeptWithMultipleZDBgeneAfterLoad{$GenPept})) {
                my $firstGenPept = $allGenPeptWithGeneAfterLoad{$GenPept};
                $ref_arrayZDBgeneIds = [$firstGenPept,$geneZdbId];
                $GenPeptWithMultipleZDBgeneAfterLoad{$GenPept} = $ref_arrayZDBgeneIds;
            } else {
                $ref_arrayZDBgeneIds = $GenPeptWithMultipleZDBgeneAfterLoad{$GenPept};
                push(@$ref_arrayZDBgeneIds, $geneZdbId);
            }
        }

        $allGenPeptWithGeneAfterLoad{$GenPept} = $geneZdbId;
    }

    $curAllGenPeptWithGeneAfterLoad->finish();

    my $ctAllGenPeptWithGeneZFINafterLoad = scalar(keys %allGenPeptWithGeneAfterLoad);

    my $ctGenPeptWithMultipleZDBgeneAfterLoad = scalar(keys %GenPeptWithMultipleZDBgeneAfterLoad);

    print LOG "\nctAllGenPeptWithGeneZFINafterLoad = $ctAllGenPeptWithGeneZFINafterLoad\n\n";

    print LOG "\nctGenPeptWithMultipleZDBgeneAfterLoad = $ctGenPeptWithMultipleZDBgeneAfterLoad\n\n";

    print STATS "----- After the load, the GenBank accessions associated with multiple ZFIN genes----\n\n";
    print STATS "GenPept \t mapped gene \tall associated genes\n";
    print STATS "--------\t-------------\t-------------\n";

    $ctGenPeptWithMultipleZDBgeneAfterLoad = 0;
    foreach $GenPept (sort keys %GenPeptWithMultipleZDBgeneAfterLoad) {
        $ref_arrayZDBgeneIds = $GenPeptWithMultipleZDBgeneAfterLoad{$GenPept};
        print STATS "$GenPept\t$NCBIState::GenPeptsToLoad{$GenPept}\t@$ref_arrayZDBgeneIds\n";
        $ctGenPeptWithMultipleZDBgeneAfterLoad++;
    }
    print STATS "-----------------------------------------\nTotal: $ctGenPeptWithMultipleZDBgeneAfterLoad\n\n\n";

    print LOG "\nctGenPeptWithMultipleZDBgeneAfterLoad = $ctGenPeptWithMultipleZDBgeneAfterLoad\n\n";

    #-------------------------------------------------------------------------------------------------
    # Report GenPept accessions associated with ZFIN genes still attributed to a non-load pub.
    #-------------------------------------------------------------------------------------------------
    print STATS "\n------GenPept accessions with ZFIN genes still attributed to non-load publication ----------\n\n";

    open (NONLOADPUBGENPPEPT, "reportNonLoadPubGenPept") ||  die "Cannot open reportNonLoadPubGenPept : $!\n";

    my @lines = <NONLOADPUBGENPPEPT>;
    my $ctGenPeptNonLoadPub = 0;
    foreach my $line (@lines) {
        $ctGenPeptNonLoadPub++;
        chop($line);
        my @fields = split(/\|/, $line);
        print STATS "$fields[0]\t$fields[1]\t$fields[2]\n";
    }

    close NONLOADPUBGENPPEPT;

    print STATS "--------------------------\nTotal: $ctGenPeptNonLoadPub\n\n\n";

    #-------------------------------------------------------------------------------------------------
    # Do the record counts after the load, and report statistics.
    #-------------------------------------------------------------------------------------------------

    my $sql = "select mrkr_zdb_id, mrkr_abbrev from marker
         where (mrkr_zdb_id like 'ZDB-GENE%' or mrkr_zdb_id like '%RNAG%')
           and exists (select 1 from db_link
         where dblink_linked_recid = mrkr_zdb_id
           and dblink_fdbcont_zdb_id in ('ZDB-FDBCONT-040412-38','ZDB-FDBCONT-040412-39','ZDB-FDBCONT-040527-1'));";

    my $curGenesWithRefSeqAfter = $NCBIState::handle->prepare($sql);

    $curGenesWithRefSeqAfter->execute;

    my ($geneId, $geneSymbol);
    $curGenesWithRefSeqAfter->bind_columns(\$geneId,\$geneSymbol);

    my %genesWithRefSeqAfterLoad = ();

    while ($curGenesWithRefSeqAfter->fetch) {
        $genesWithRefSeqAfterLoad{$geneId} = $geneSymbol;
    }

    $curGenesWithRefSeqAfter->finish();

    my $ctGenesWithRefSeqAfter = scalar(keys %genesWithRefSeqAfterLoad);

    $NCBIState::handle->disconnect();

    # NCBI Gene Id
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-1'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numNCBIgeneIdAfter = ZFINPerlModules->countData($sql);

    #RefSeq RNA
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-38'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numRefSeqRNAAfter = ZFINPerlModules->countData($sql);

    # RefPept
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-39'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numRefPeptAfter = ZFINPerlModules->countData($sql);

    #RefSeq DNA
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040527-1'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numRefSeqDNAAfter = ZFINPerlModules->countData($sql);

    # GenBank RNA (only those loaded - excluding curated ones)
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-37'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
           and exists(select 1 from record_attribution
                       where recattrib_data_zdb_id = dblink_zdb_id
                         and recattrib_source_zdb_id in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2'));";

    my $numGenBankRNAAfter = ZFINPerlModules->countData($sql);

    # GenPept (only those loaded - excluding curated ones)
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
           and exists(select 1 from record_attribution
                       where recattrib_data_zdb_id = dblink_zdb_id
                         and recattrib_source_zdb_id in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2'));";

    my $numGenPeptAfter = ZFINPerlModules->countData($sql);

    # GenBank DNA (only those loaded - excluding curated ones)
    $sql = "select distinct dblink_acc_num
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-36'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%')
           and exists(select 1 from record_attribution
                       where recattrib_data_zdb_id = dblink_zdb_id
                         and recattrib_source_zdb_id in ('ZDB-PUB-020723-3','ZDB-PUB-130725-2'));";

    my $numGenBankDNAAfter = ZFINPerlModules->countData($sql);

    # number of genes with RefSeq RNA
    $sql = "select distinct dblink_linked_recid
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-38'
           and dblink_acc_num like 'NM_%'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numGenesRefSeqRNAAfter = ZFINPerlModules->countData($sql);

    # number of genes with RefPept
    $sql = "select distinct dblink_linked_recid
          from db_link
         where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-39'
           and dblink_acc_num like 'NP_%'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numGenesRefSeqPeptAfter = ZFINPerlModules->countData($sql);

    # number of genes with GenBank
    $sql = "select distinct dblink_linked_recid
          from db_link, foreign_db_contains, foreign_db
         where dblink_fdbcont_zdb_id = fdbcont_zdb_id
           and fdbcont_fdb_db_id = fdb_db_pk_id
           and fdb_db_name = 'GenBank'
           and (dblink_linked_recid like 'ZDB-GENE%' or dblink_linked_recid like '%RNAG%');";

    my $numGenesGenBankAfter = ZFINPerlModules->countData($sql);

    print STATS "\n********* Percentage change of various categories of records *************\n\n";

    print STATS "number of db_link records with gene     \t";
    print STATS "before load\t";
    print STATS "after load\t";
    print STATS "percentage change\n";
    print STATS "----------------------------------------\t-----------\t-----------\t-------------------------\n";

    print STATS "NCBI gene Id                                  \t";
    print STATS "$NCBIState::numNCBIgeneIdBefore   \t";
    print STATS "$numNCBIgeneIdAfter   \t";
    printf STATS "%.2f\n", ($numNCBIgeneIdAfter - $NCBIState::numNCBIgeneIdBefore) / $NCBIState::numNCBIgeneIdBefore * 100 if ($NCBIState::numNCBIgeneIdBefore > 0);

    print STATS "RefSeq RNA                                 \t";
    print STATS "$NCBIState::numRefSeqRNABefore        \t";
    print STATS "$numRefSeqRNAAfter       \t";
    printf STATS "%.2f\n", ($numRefSeqRNAAfter - $NCBIState::numRefSeqRNABefore) / $NCBIState::numRefSeqRNABefore * 100 if ($NCBIState::numRefSeqRNABefore > 0);

    print STATS "RefPept                                 \t";
    print STATS "$NCBIState::numRefPeptBefore   \t";
    print STATS "$numRefPeptAfter   \t";
    printf STATS "%.2f\n", ($numRefPeptAfter - $NCBIState::numRefPeptBefore) / $NCBIState::numRefPeptBefore * 100 if ($NCBIState::numRefPeptBefore > 0);

    print STATS "RefSeq DNA                                 \t";
    print STATS "$NCBIState::numRefSeqDNABefore      \t";
    print STATS "$numRefSeqDNAAfter        \t";
    if ($NCBIState::numRefSeqDNABefore > 0) {
        printf STATS "%.2f\n", ($numRefSeqDNAAfter - $NCBIState::numRefSeqDNABefore) / $NCBIState::numRefSeqDNABefore * 100;
    } else {
        printf STATS "\n";
    }

    print STATS "GenBank RNA                                 \t";
    print STATS "$NCBIState::numGenBankRNABefore        \t";
    print STATS "$numGenBankRNAAfter       \t";
    printf STATS "%.2f\n", ($numGenBankRNAAfter - $NCBIState::numGenBankRNABefore) / $NCBIState::numGenBankRNABefore * 100 if ($NCBIState::numGenBankRNABefore > 0);

    print STATS "GenPept                                 \t";
    print STATS "$NCBIState::numGenPeptBefore   \t";
    print STATS "$numGenPeptAfter   \t";
    printf STATS "%.2f\n", ($numGenPeptAfter - $NCBIState::numGenPeptBefore) / $NCBIState::numGenPeptBefore * 100 if ($NCBIState::numGenPeptBefore > 0);

    print STATS "GenBank DNA                                 \t";
    print STATS "$NCBIState::numGenBankDNABefore       \t";
    print STATS "$numGenBankDNAAfter        \t";
    printf STATS "%.2f\n", ($numGenBankDNAAfter - $NCBIState::numGenBankDNABefore) / $NCBIState::numGenBankDNABefore * 100 if ($NCBIState::numGenBankDNABefore > 0);

    print STATS "\n\n";

    print STATS "number of genes                              \t";
    print STATS "before load\t";
    print STATS "after load\t";
    print STATS "percentage change\n";
    print STATS "----------------------------------------\t-----------\t-----------\t-------------------------\n";

    print STATS "with RefSeq                             \t";
    print STATS "$NCBIState::ctGenesWithRefSeqBefore   \t";
    print STATS "$ctGenesWithRefSeqAfter   \t";
    printf STATS "%.2f\n", ($ctGenesWithRefSeqAfter - $NCBIState::ctGenesWithRefSeqBefore) / $NCBIState::ctGenesWithRefSeqBefore * 100 if ($NCBIState::ctGenesWithRefSeqBefore > 0);

    print STATS "with RefSeq NM                          \t";
    print STATS "$NCBIState::numGenesRefSeqRNABefore   \t";
    print STATS "$numGenesRefSeqRNAAfter   \t";
    printf STATS "%.2f\n", ($numGenesRefSeqRNAAfter - $NCBIState::numGenesRefSeqRNABefore) / $NCBIState::numGenesRefSeqRNABefore * 100 if ($NCBIState::numGenesRefSeqRNABefore > 0);

    print STATS "with RefSeq NP                          \t";
    print STATS "$NCBIState::numGenesRefSeqPeptBefore   \t";
    print STATS "$numGenesRefSeqPeptAfter   \t";
    printf STATS "%.2f\n", ($numGenesRefSeqPeptAfter - $NCBIState::numGenesRefSeqPeptBefore) / $NCBIState::numGenesRefSeqPeptBefore * 100 if ($NCBIState::numGenesRefSeqPeptBefore > 0);

    print STATS "with GenBank                            \t";
    print STATS "$NCBIState::numGenesGenBankBefore        \t";
    print STATS "$numGenesGenBankAfter       \t";
    printf STATS "%.2f\n", ($numGenesGenBankAfter - $NCBIState::numGenesGenBankBefore) / $NCBIState::numGenesGenBankBefore * 100 if ($NCBIState::numGenesGenBankBefore > 0);

    my @keysSortedByValues = sort { lc($NCBIState::geneZDBidsSymbols{$a}) cmp lc($NCBIState::geneZDBidsSymbols{$b}) } keys %NCBIState::geneZDBidsSymbols;

    print STATS "\n\nList of genes used to have RefSeq acc but no longer having any:\n";
    print STATS "-------------------------------------------------------------------\n";

    my $symbol;
    my $ctGenesLostRefSeq = 0;
    foreach my $zdbGeneId (@keysSortedByValues) {
        $symbol = $NCBIState::geneZDBidsSymbols{$zdbGeneId};
        if (exists($NCBIState::genesWithRefSeqBeforeLoad{$zdbGeneId})
            && !exists($genesWithRefSeqAfterLoad{$zdbGeneId})) {
            $ctGenesLostRefSeq++;
            print STATS "$symbol\t$zdbGeneId\n";

        }
    }

    print STATS "\ntotal: $ctGenesLostRefSeq\n\n";

    print STATS "\n\nList of genes now having RefSeq acc but used to have none ReSeq:\n";
    print STATS "-------------------------------------------------------------------\n";

    my $ctGenesGainRefSeq = 0;
    foreach my $zdbGeneId (@keysSortedByValues) {
        $symbol = $NCBIState::geneZDBidsSymbols{$zdbGeneId};
        if (exists($genesWithRefSeqAfterLoad{$zdbGeneId})
            && !exists($NCBIState::genesWithRefSeqBeforeLoad{$zdbGeneId})) {
            $ctGenesGainRefSeq++;
            print STATS "$symbol\t$zdbGeneId\n";

        }
    }

    print STATS "\ntotal: $ctGenesGainRefSeq\n\n\n";

    close STATS;
}

sub emailLoadReports {
    my $subject = "Auto from $NCBIState::dbname: NCBI_gene_load.pl :: Statistics";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'},"$subject","reportStatistics");

    $subject = "Auto from $NCBIState::dbname: NCBI_gene_load.pl :: log file";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_ERR'},"$subject","logNCBIgeneLoad");
}

main();