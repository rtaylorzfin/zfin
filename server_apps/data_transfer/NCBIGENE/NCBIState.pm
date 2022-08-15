package NCBIState;
use strict;
use warnings FATAL => 'all';

#This file is a way of encapsulating the global state of the NCBI load.
#It replaces the old global variables.

our $pubMappedbasedOnRNA = "ZDB-PUB-020723-3";
our $pubMappedbasedOnVega = "ZDB-PUB-130725-2";

our $fdcontNCBIgeneId = "ZDB-FDBCONT-040412-1";
our $fdcontGenBankRNA = "ZDB-FDBCONT-040412-37";
our $fdcontGenPept = "ZDB-FDBCONT-040412-42";
our $fdcontGenBankDNA = "ZDB-FDBCONT-040412-36";
our $fdcontRefSeqRNA = "ZDB-FDBCONT-040412-38";
our $fdcontRefPept = "ZDB-FDBCONT-040412-39";
our $fdcontRefSeqDNA = "ZDB-FDBCONT-040527-1";

#used in eg. initializeDatabase 
our $dbname;
our $dbhost;
our $username;
our $password;
our $handle;

#used in eg. getMetricsOfDbLinksToDelete
our %toDelete;
our $ctToDelete;

#used in eg. getRecordCounts
our %genesWithRefSeqBeforeLoad;
our $ctGenesWithRefSeqBefore;
our $numNCBIgeneIdBefore;
our $numRefSeqRNABefore;
our $numRefPeptBefore;
our $numRefSeqDNABefore;
our $numGenBankRNABefore;
our $numGenPeptBefore;
our $numGenBankDNABefore;
our $numGenesRefSeqRNABefore;
our $numGenesRefSeqPeptBefore;
our $numGenesGenBankBefore;

#used in eg. readZfinGeneInfoFile
our $ctVegaIdsNCBI;
our %NCBIgeneWithMultipleVega;
our %NCBIidsGeneSymbols;
our %geneSymbolsNCBIids;
our %vegaIdsNCBIids;
our %vegaIdwithMultipleNCBIids;

#used in eg. initializeSetsOfZfinRecords
our %supportedGeneZFIN;
our %supportingAccZFIN;
our %accZFINsupportingMoreThan1;
our %geneZFINwithAccSupportingMoreThan1;
our %accZFINsupportingOnly1;

#used in eg. initializeSequenceLengthHash, lots of other places
our %sequenceLength;

#used in eg. parseGene2AccessionFile
our $ctNoLength;
our $ctNoLengthRefSeq;
our $ctZebrafishGene2accession;
our $ctlines;
our %GenBankDNAncbiGeneIds;
our %GenPeptNCBIgeneIds;
our %RefPeptNCBIgeneIds;
our %RefSeqDNAncbiGeneIds;
our %RefSeqRNAncbiGeneIds;
our %noLength;
our %supportedGeneNCBI;
our %supportingAccNCBI;



1;