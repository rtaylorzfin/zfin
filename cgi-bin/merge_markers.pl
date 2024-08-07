#!/opt/zfin/bin/perl

use strict;
use DBI;

use CGI;
use CGI::Carp 'fatalsToBrowser';
use HTTP::Request::Common qw/GET/;
use LWP::UserAgent;
use URI::Escape qw/uri_escape/;

## merge_markers.pl is called by merge-marker.jsp

my $data = new CGI();

my $recordToBeDeleted = $data->param("OID");
my $recordToBeMergedInto = $data->param("merge_oid");

my $dbname = "<!--|DB_NAME|-->";
my $dbhost = "<!--|PGHOST|-->";

my $username = "";
my $password = "";

print "Content-type: text/HTML\n\n<HTML>\n\n";


### open a handle on the db
my $dbh = DBI->connect ("DBI:Pg:dbname=$dbname;host=$dbhost", $username, $password)
    or die "\n\nCannot connect to PostgreSQL database: $DBI::errstr\n\n";

my $type1;
if ($recordToBeDeleted =~ m/^ZDB\-([A-Z]+)\-\d{6}\-\d+$/) {
    $type1 = $1;
} else {
    die "Not a valid ZDB ID for the record to be deleted\n";
}

my $type2;
if ($recordToBeMergedInto =~ m/^ZDB\-([A-Z]+)\-\d{6}\-\d+$/) {
    $type2 = $1;
} else {
    die "Not a valid ZDB ID for the record to be merged into\n";
}


my $markerAbbrevToBeDeleted;
my $markerNameToBeDeleted;
my $markerAbbrevMergedInto;
my $markerNameMergedInto;
my $daliasID;
my $nomenID;

## get marker abbreviations and names for the marker to be deleted; also, get the new dalias ID
my $sqlGetMarkerInfoForToBeDeleted = "select mrkr_abbrev, mrkr_name, get_id('DALIAS') as daliasid from marker where mrkr_zdb_id = '$recordToBeDeleted';";
my $curGetMarkerInfoForToBeDeleted = $dbh->prepare_cached($sqlGetMarkerInfoForToBeDeleted);
$curGetMarkerInfoForToBeDeleted->execute();            
$curGetMarkerInfoForToBeDeleted->bind_columns(\$markerAbbrevToBeDeleted, \$markerNameToBeDeleted, \$daliasID);
while ($curGetMarkerInfoForToBeDeleted->fetch()) {}
$curGetMarkerInfoForToBeDeleted->finish();

## get marker abbreviations and names for the marker to be merged into; also, get the new nomen ID
my $sqlGetMarkerInfoForMergedInto = "select mrkr_abbrev, mrkr_name, get_id('NOMEN') as nomenid from marker where mrkr_zdb_id = '$recordToBeMergedInto';";
my $curGetMarkerInfoForMergedInto = $dbh->prepare_cached($sqlGetMarkerInfoForMergedInto);
$curGetMarkerInfoForMergedInto->execute();            
$curGetMarkerInfoForMergedInto->bind_columns(\$markerAbbrevMergedInto, \$markerNameMergedInto, \$nomenID);
my $unspecifiedAllele1Gene2;
while ($curGetMarkerInfoForMergedInto->fetch()) {
  ## this will be used in later code block for FB case 10333
  $unspecifiedAllele1Gene2 = $markerAbbrevMergedInto . '_unspecified';
}
$curGetMarkerInfoForMergedInto->finish();

if ($type1 ne 'ATB') {

### ZFIN-6309, update genotype display names and fish names when merging genes

my $sqlGetGenotypeDisplayName = "select geno_zdb_id, geno_display_name from feature_marker_relationship, genotype_feature, genotype where fmrel_mrkr_zdb_id  = ? and fmrel_ftr_zdb_id= genofeat_feature_zdb_id and genofeat_geno_zdb_id = geno_zdb_id;";
my $curGetGenotypeDisplayName = $dbh->prepare($sqlGetGenotypeDisplayName);

my $sqlUpdtateGenotypeDislayName = "update genotype set geno_display_name = ? where geno_zdb_id = ?;";
my $curUpdtateGenotypeDislayName = $dbh->prepare($sqlUpdtateGenotypeDislayName);

$curGetGenotypeDisplayName->execute($recordToBeDeleted);

my $genotypeId;
my $genotypeDisplayName;
my $newDisplayName;
$curGetGenotypeDisplayName->bind_columns(\$genotypeId,\$genotypeDisplayName);

while ($curGetGenotypeDisplayName->fetch()) {
  if (index($genotypeDisplayName, $markerAbbrevToBeDeleted) != -1)   {
    $newDisplayName = $genotypeDisplayName =~ s/$markerAbbrevToBeDeleted/$markerAbbrevMergedInto/gr;
    $curUpdtateGenotypeDislayName->execute($newDisplayName,$genotypeId);
  }
}

$curGetGenotypeDisplayName->finish();
$curUpdtateGenotypeDislayName->finish();

my $sqlGetFishName = "select fish_zdb_id, fish_name from feature_marker_relationship, genotype_feature, fish where fmrel_mrkr_zdb_id  = ? and fmrel_ftr_zdb_id= genofeat_feature_zdb_id and genofeat_geno_zdb_id = fish_genotype_zdb_id;";
my $curGetFishName = $dbh->prepare($sqlGetFishName);

my $sqlUpdtateFishName = "update fish set fish_name = ? where fish_zdb_id = ?;";
my $curUpdateFishName = $dbh->prepare($sqlUpdtateFishName);

$curGetFishName->execute($recordToBeDeleted);

my $fishId;
my $fishName;
my $newFishName;
$curGetFishName->bind_columns(\$fishId,\$fishName);

while ($curGetFishName->fetch()) {
  if (index($fishName, $markerAbbrevToBeDeleted) != -1)   {
    $newFishName = $fishName =~ s/$markerAbbrevToBeDeleted/$markerAbbrevMergedInto/gr;
    $curUpdateFishName->execute($newFishName,$fishId);
  }
}

$curGetFishName->finish();
$curUpdateFishName->finish();

}

my $tableName;
my $primaryKeyColumn;

## global hash variable storing the SQLs
my %mergeSQLs = ();

$tableName = 'zdb_active_data';
$primaryKeyColumn = 'zactvd_zdb_id';

my %processed = ();
$processed{$tableName.$primaryKeyColumn} = 0;
$processed{'updatesrec_id'} = 0;
$processed{'zdb_replaced_datazrepld_old_zdb_id'} = 0;
$processed{'zdb_replaced_datazrepld_new_zdb_id'} = 0;
$processed{'record_attributionrecattrib_data_zdb_id'} = 0;
$processed{'record_attributionrecattrib_source_zdb_id'} = 0;

## deal with possible violation of unique constraint in record_attribution table 
## when updating some tables, inserting into record_attribution table will be triggered
cleanupRecordAttributionTable($recordToBeMergedInto, $recordToBeDeleted, 999);          

## call the recursive function to construct the merge-related SQLs
recursivelyGetSQLs($recordToBeDeleted, $recordToBeMergedInto, $tableName, $primaryKeyColumn, 0);
 
## sort by the values first (depth, reversed; i.e. the deepest level first),
## then by the keys (delete SQL before update SQL)
my @sorted = sort { $mergeSQLs{$b} <=> $mergeSQLs{$a} || $a cmp $b } keys %mergeSQLs;

## execute the SQLs according to the above order
my($mergeSQL,$curMerge);
for (@sorted) {
   $mergeSQL = $_;
   $curMerge = $dbh->prepare($mergeSQL);
   $curMerge->execute();
   $curMerge->finish();
}

## deal with possible violation of unique constraint in record_attribution table, again
## this is because when updating some tables, inserting into record_attribution table are triggered
cleanupRecordAttributionTable($recordToBeMergedInto, $recordToBeDeleted, 0);

my $sqlDeleteFromRecordAttribution;
my $curDeleteFromRecordAttribution;

## execute the possible delete from record_attribution table SQL(s)
my $k;
my $v;
foreach $k (keys %mergeSQLs) {
  $v = $mergeSQLs{$k};
  if ($v eq '0') {
    $sqlDeleteFromRecordAttribution = $k;
    $curDeleteFromRecordAttribution = $dbh->prepare($sqlDeleteFromRecordAttribution);
    $curDeleteFromRecordAttribution->execute();
    $curDeleteFromRecordAttribution->finish();
  }
}

## update record_attribution table
my $sqlUpdateRecordAttribution = "update record_attribution set recattrib_data_zdb_id = '$recordToBeMergedInto' where recattrib_data_zdb_id = '$recordToBeDeleted';";
my $curUpdateRecordAttribution = $dbh->prepare($sqlUpdateRecordAttribution);
$curUpdateRecordAttribution->execute();
$curUpdateRecordAttribution->finish();

## Does the alias that we want to create already exist? If so, we can just use that daliasID
my $sqlGetAlias = "select dalias_zdb_id from data_alias where dalias_data_zdb_id = '$recordToBeMergedInto' and dalias_alias = '$markerAbbrevToBeDeleted' and dalias_group_id = 1;";
my $curGetAlias = $dbh->prepare_cached($sqlGetAlias);
$curGetAlias->execute();
my $existingAliasID;
$curGetAlias->bind_columns(\$existingAliasID);
my $existingAliasFound = 0;
while ($curGetAlias->fetch()) {
   $existingAliasFound = 1;
}

if ($existingAliasFound) {
    $daliasID = $existingAliasID;
} else {
    ## add new data_alias record
    my $sqlInsertZdbActiveData = "insert into zdb_active_data values('$daliasID');";
    my $curInsertZdbActiveData = $dbh->prepare_cached($sqlInsertZdbActiveData);
    $curInsertZdbActiveData->execute();
    $curInsertZdbActiveData->finish();

    my $sqlInsertDataAlias = "insert into data_alias (dalias_zdb_id, dalias_data_zdb_id, dalias_alias, dalias_group_id)
                              values ('$daliasID', '$recordToBeMergedInto', '$markerAbbrevToBeDeleted', '1');";
    my $curAddNewDataAlias = $dbh->prepare($sqlInsertDataAlias);
    $curAddNewDataAlias->execute();
    $curAddNewDataAlias->finish();
}

## add new marker_history record
my $sqlInsertZdbActiveData2 = "insert into zdb_active_data values('$nomenID');";
my $curInsertZdbActiveData2 = $dbh->prepare_cached($sqlInsertZdbActiveData2);
$curInsertZdbActiveData2->execute();
$curInsertZdbActiveData2->finish();

my $sqlInsertMrkrHistory = "insert into marker_history (mhist_zdb_id, mhist_mrkr_zdb_id, mhist_event, mhist_reason, mhist_date, 
                                                        mhist_mrkr_name_on_mhist_date, mhist_mrkr_abbrev_on_mhist_date, mhist_comments,mhist_dalias_zdb_id)
                            values ('$nomenID', '$recordToBeMergedInto', 'merged', 'same marker', now(), 
                                    '$markerNameMergedInto', '$markerAbbrevMergedInto', 'none', '$daliasID');";
my $curAddNewMrkrHistory = $dbh->prepare($sqlInsertMrkrHistory);
$curAddNewMrkrHistory->execute();
$curAddNewMrkrHistory->finish();

if ($type1 eq 'ATB') {

my $firstRecord = "none";
my $firstFound = 0;
my $secondRecord = "none";
my $secondFound = 0;

my $sqlGetIsoType = "select atb_type from antibody where atb_zdb_id = ?;";
my $curGetIsoType = $dbh->prepare_cached($sqlGetIsoType);
$curGetIsoType->execute($recordToBeDeleted);
$curGetIsoType->bind_columns(\$firstRecord);

while ($curGetIsoType->fetch()) {
   $firstFound = 1;
}

if($firstFound == 1 && $firstRecord ne 'none' && $firstRecord ne '') {
   $curGetIsoType->execute($recordToBeMergedInto);
   
   $curGetIsoType->bind_columns(\$secondRecord); 
   
   while ($curGetIsoType->fetch()) {
      $secondFound = 1;    
   }   
   
   if($secondRecord eq 'none' || $secondRecord eq '') {
      my $sqlUpdateIsoType = "update antibody set atb_type = ? where atb_zdb_id = ?;";
      my $curUpdateIsoType = $dbh->prepare_cached($sqlUpdateIsoType);                
      $curUpdateIsoType->execute($firstRecord, $recordToBeMergedInto);
      $curUpdateIsoType->finish();
   } 
}

$curGetIsoType->finish();

$firstRecord = "none";
$firstFound = 0;
$secondRecord = "none";
$secondFound = 0;

my $sqlGetHeavyChainIsotype = "select atb_hviso_name from antibody where atb_zdb_id = ?;";
my $curGetHeavyChainIsotype = $dbh->prepare_cached($sqlGetHeavyChainIsotype);
$curGetHeavyChainIsotype->execute($recordToBeDeleted);
$curGetHeavyChainIsotype->bind_columns(\$firstRecord);

while ($curGetHeavyChainIsotype->fetch()) {
   $firstFound = 1;
}

if($firstFound == 1 && $firstRecord ne 'none' && $firstRecord ne '') {
   $curGetHeavyChainIsotype->execute($recordToBeMergedInto);
   
   $curGetHeavyChainIsotype->bind_columns(\$secondRecord); 
   
   while ($curGetHeavyChainIsotype->fetch()) {
      $secondFound = 1;    
   }   
   
   if($secondRecord eq 'none' || $secondRecord eq '') {
      my $sqlUpdateHeavyChainIsotype = "update antibody set atb_hviso_name = ? where atb_zdb_id = ?;";
      my $curUpdateHeavyChainIsotype = $dbh->prepare_cached($sqlUpdateHeavyChainIsotype);                
      $curUpdateHeavyChainIsotype->execute($firstRecord, $recordToBeMergedInto);
      $curUpdateHeavyChainIsotype->finish();
   } 
}

$curGetHeavyChainIsotype->finish();

$firstRecord = "none";
$firstFound = 0;
$secondRecord = "none";
$secondFound = 0;

my $sqlGetLightChainIsotype = "select atb_ltiso_name from antibody where atb_zdb_id = ?;";
my $curGetLightChainIsotype = $dbh->prepare_cached($sqlGetLightChainIsotype);
$curGetLightChainIsotype->execute($recordToBeDeleted);
$curGetLightChainIsotype->bind_columns(\$firstRecord);

while ($curGetLightChainIsotype->fetch()) {
   $firstFound = 1;
}

if($firstFound == 1 && $firstRecord ne 'none' && $firstRecord ne '') {
   $curGetLightChainIsotype->execute($recordToBeMergedInto);
   
   $curGetLightChainIsotype->bind_columns(\$secondRecord); 
   
   while ($curGetLightChainIsotype->fetch()) {
      $secondFound = 1;    
   }   
   
   if($secondRecord eq 'none' || $secondRecord eq '') {
      my $sqlUpdateLightChainIsotype = "update antibody set atb_ltiso_name = ? where atb_zdb_id = ?;";
      my $curUpdateLightChainIsotype = $dbh->prepare_cached($sqlUpdateLightChainIsotype);                
      $curUpdateLightChainIsotype->execute($firstRecord, $recordToBeMergedInto);
      $curUpdateLightChainIsotype->finish();
   } 
}

$curGetLightChainIsotype->finish();

$firstRecord = "none";
$firstFound = 0;
$secondRecord = "none";
$secondFound = 0;

my $sqlGetHostSpecies = "select atb_host_organism from antibody where atb_zdb_id = ?;";
my $curGetHostSpecies = $dbh->prepare_cached($sqlGetHostSpecies);
$curGetHostSpecies->execute($recordToBeDeleted);
$curGetHostSpecies->bind_columns(\$firstRecord);

while ($curGetHostSpecies->fetch()) {
   $firstFound = 1;
}

if($firstFound == 1 && $firstRecord ne 'none' && $firstRecord ne '') {
   $curGetHostSpecies->execute($recordToBeMergedInto);
   
   $curGetHostSpecies->bind_columns(\$secondRecord); 
   
   while ($curGetHostSpecies->fetch()) {
      $secondFound = 1;    
   }   
   
   if($secondRecord eq 'none' || $secondRecord eq '') {
      my $sqlUpdateHostSpecies = "update antibody set atb_host_organism = ? where atb_zdb_id = ?;";
      my $curUpdateHostSpecies = $dbh->prepare_cached($sqlUpdateHostSpecies);                
      $curUpdateHostSpecies->execute($firstRecord, $recordToBeMergedInto);
      $curUpdateHostSpecies->finish();
   } 
}

$curGetHostSpecies->finish();


$firstRecord = "none";
$firstFound = 0;
$secondRecord = "none";
$secondFound = 0;

my $sqlGetImmunogenSpecies = "select atb_immun_organism from antibody where atb_zdb_id = ?;";
my $curGetImmunogenSpecies = $dbh->prepare_cached($sqlGetImmunogenSpecies);
$curGetImmunogenSpecies->execute($recordToBeDeleted);
$curGetImmunogenSpecies->bind_columns(\$firstRecord);

while ($curGetImmunogenSpecies->fetch()) {
   $firstFound = 1;
}

if($firstFound == 1 && $firstRecord ne 'none' && $firstRecord ne '') {
   $curGetImmunogenSpecies->execute($recordToBeMergedInto);
   
   $curGetImmunogenSpecies->bind_columns(\$secondRecord); 
   
   while ($curGetImmunogenSpecies->fetch()) {
      $secondFound = 1;    
   }   
   
   if($secondRecord eq 'none' || $secondRecord eq '') {
      my $sqlUpdateImmunogenSpecies = "update antibody set atb_immun_organism = ? where atb_zdb_id = ?;";
      my $curUpdateImmunogenSpecies = $dbh->prepare_cached($sqlUpdateImmunogenSpecies);                
      $curUpdateImmunogenSpecies->execute($firstRecord, $recordToBeMergedInto);
      $curUpdateImmunogenSpecies->finish();
   } 
}

$curGetImmunogenSpecies->finish();

}  ## end of if ($type1 eq 'ATB')

if ($type1 ne 'ATB') {

### FB case 11133

my $goawayFieldValue = "ZFIN:".$recordToBeDeleted;
my $intoFieldValue = "ZFIN:".$recordToBeMergedInto;
my $infgrmemMrkrgoevZdbId;
my $infgrmemInferredFrom;

my $sqlGetPrimaryKeysInferenceGroupMember = "select distinct infgrmem_mrkrgoev_zdb_id, infgrmem_inferred_from from inference_group_member where infgrmem_inferred_from = ? ;";
my $curGetPrimaryKeysInferenceGroupMembe = $dbh->prepare_cached($sqlGetPrimaryKeysInferenceGroupMember);
$curGetPrimaryKeysInferenceGroupMembe->execute($intoFieldValue);
$curGetPrimaryKeysInferenceGroupMembe->bind_columns(\$infgrmemMrkrgoevZdbId,\$infgrmemInferredFrom);

my %primaryKeysInferenceGroupMember = ();
my $primaryKey;
while ($curGetPrimaryKeysInferenceGroupMembe->fetch()) {
  $primaryKey = $infgrmemMrkrgoevZdbId . $infgrmemInferredFrom;
  $primaryKeysInferenceGroupMember{$primaryKey} = $infgrmemInferredFrom;
}

$curGetPrimaryKeysInferenceGroupMembe->finish();

my $sqlGetMrkrgoevZdbIds = "select distinct infgrmem_mrkrgoev_zdb_id from inference_group_member where infgrmem_inferred_from = ? ;";
my $curGetMrkrgoevZdbIds = $dbh->prepare_cached($sqlGetMrkrgoevZdbIds);
my $infgrmemMrkrgoevZdbIdWithGene1;
$curGetMrkrgoevZdbIds->execute($goawayFieldValue);
$curGetMrkrgoevZdbIds->bind_columns(\$infgrmemMrkrgoevZdbIdWithGene1);

my $sqlUpdateInferenceGroupMembe = "update inference_group_member set infgrmem_inferred_from = ? where infgrmem_mrkrgoev_zdb_id = ? and infgrmem_inferred_from = ?;";
my $curUpdateInferenceGroupMembe = $dbh->prepare_cached($sqlUpdateInferenceGroupMembe);

## update inference_group_member table
while ($curGetMrkrgoevZdbIds->fetch()) {
  $primaryKey = $infgrmemMrkrgoevZdbIdWithGene1 . $intoFieldValue;
  $curUpdateInferenceGroupMembe->execute($intoFieldValue, $infgrmemMrkrgoevZdbIdWithGene1, $goawayFieldValue) if !exists($primaryKeysInferenceGroupMember{$primaryKey});
  $primaryKeysInferenceGroupMember{$primaryKey} = $intoFieldValue;
}

$curGetMrkrgoevZdbIds->finish();
$curUpdateInferenceGroupMembe->finish();

### FB case 10333

my $sqlGetUnspecifiedAllele = "select fmrel_ftr_zdb_id from feature_marker_relationship,feature where fmrel_ftr_zdb_id = feature_zdb_id and fmrel_mrkr_zdb_id = ? and feature_unspecified = 't' ;";
my $curGetUnspecifiedAllele = $dbh->prepare_cached($sqlGetUnspecifiedAllele);
my $unspecifiedAlleleWithGeneToBeDeleted;
$curGetUnspecifiedAllele->execute($recordToBeDeleted);
$curGetUnspecifiedAllele->bind_columns(\$unspecifiedAlleleWithGeneToBeDeleted);

my $thereIsUnspecifiedAlleleWithGeneToBeDeleted = 0;
while ($curGetUnspecifiedAllele->fetch()) {
  $thereIsUnspecifiedAlleleWithGeneToBeDeleted++;
}

$curGetUnspecifiedAllele->execute($recordToBeMergedInto);
my $unspecifiedAlleleWithGeneRetained;
$curGetUnspecifiedAllele->bind_columns(\$unspecifiedAlleleWithGeneRetained);

my $thereIsUnspecifiedAlleleWithGeneRetained = 0;
while ($curGetUnspecifiedAllele->fetch()) {
  $thereIsUnspecifiedAlleleWithGeneRetained++;
}

$curGetUnspecifiedAllele->finish();

if ($thereIsUnspecifiedAlleleWithGeneRetained == 0 && $thereIsUnspecifiedAlleleWithGeneToBeDeleted > 0) {

  my $sqlRenameAlleleName = "update feature set (feature_name, feature_abbrev) = (?,?) where feature_zdb_id = ? ;";
  my $curRenameAlleleName = $dbh->prepare_cached($sqlRenameAlleleName);
   
  $curRenameAlleleName->execute($unspecifiedAllele1Gene2, $unspecifiedAllele1Gene2, $unspecifiedAlleleWithGeneToBeDeleted);
  $curRenameAlleleName->finish();

  my $sqlGetGenotypesWithUnspecifiedAllele = "select geno_zdb_id, geno_display_name, geno_handle from genotype, genotype_feature where genofeat_feature_zdb_id = ? and genofeat_geno_zdb_id = geno_zdb_id;";   
  my $curGetGenotypes = $dbh->prepare_cached($sqlGetGenotypesWithUnspecifiedAllele);

  my $sqlRenameRelatedGenotypes = "update genotype set (geno_display_name, geno_handle) = (?,?) where geno_zdb_id = ?;";   
  my $curRenameGenotypes = $dbh->prepare_cached($sqlRenameRelatedGenotypes);
      
  $curGetGenotypes->execute($unspecifiedAlleleWithGeneToBeDeleted);
  my $genoId;
  my $genoDisplayName;
  my $genoHandle;
  $curGetGenotypes->bind_columns(\$genoId,\$genoDisplayName,\$genoHandle);
   
  while ($curGetGenotypes->fetch()) {
    $genoDisplayName =~ s/$markerAbbrevToBeDeleted/$markerAbbrevMergedInto/;
    $genoHandle =~ s/$markerAbbrevToBeDeleted/$markerAbbrevMergedInto/;
    $curRenameGenotypes->execute($genoDisplayName,$genoHandle,$genoId);
  }
   
  $curGetGenotypes->finish();
  $curRenameGenotypes->finish();

}

} ## end of if (type1 ne 'ATB')


### MRDL-121
### get the public notes and concatenate them 

my $sqlGetPublicNote = "select mrkr_comments from marker where mrkr_zdb_id = ?;";
my $curGetPublicNote = $dbh->prepare_cached($sqlGetPublicNote);
$curGetPublicNote->execute($recordToBeDeleted);
my $firstNote = "none";
$curGetPublicNote->bind_columns(\$firstNote);
my $firstNoteFound = 0;
while ($curGetPublicNote->fetch()) {
   $firstNoteFound = 1;
}

my $combinedPublicNote = "none";
if($firstNoteFound == 1 && $firstNote ne 'none') {
   $curGetPublicNote->execute($recordToBeMergedInto);
   my $secondNote = "none";
   $curGetPublicNote->bind_columns(\$secondNote); 
   my $secondNoteFound = 0;
   while ($curGetPublicNote->fetch()) {
      $secondNoteFound = 1;    
   }   
   if($secondNoteFound == 1 && $secondNote ne 'none') {
      $combinedPublicNote = $secondNote . "\n\n" . "$firstNote";
   } else {
      $combinedPublicNote = $firstNote;
   }
}

$curGetPublicNote->finish();

if($combinedPublicNote ne 'none') {
   my $sqlUpdatePublicNote = "update marker set mrkr_comments = ? where mrkr_zdb_id = ?;";
   my $curUpdatePublicNote = $dbh->prepare_cached($sqlUpdatePublicNote);                
   $curUpdatePublicNote->execute($combinedPublicNote, $recordToBeMergedInto);
   $curUpdatePublicNote->finish();
}

my $delete = "delete from zdb_active_data where zactvd_zdb_id = ?;";
my $curDelete = $dbh->prepare_cached($delete);

## for STRs to be merged, if used in fish, delete the fish records with str1 so as to
## avoid unique constraint (informix.fish_name_alternate_key) violation
## same for marker_relationship_alternate_key 
if ($recordToBeDeleted =~ m/MRPHLNO/ || $recordToBeDeleted =~ m/CRISP/ || $recordToBeDeleted =~ m/TALEN/) {
  my $getFishIdsToDelete = "select distinct fstr1.fishstr_fish_zdb_id
                              from fish_str fstr1
                             where fstr1.fishstr_str_zdb_id = ?
                               and exists(select 'x' from fish_str fstr2
                                           where fstr2.fishstr_str_zdb_id = ?
                                             and fstr2.fishstr_fish_zdb_id = fstr1.fishstr_fish_zdb_id);";

  my $curFishIdsToDelete = $dbh->prepare($getFishIdsToDelete);
  
  $curFishIdsToDelete->execute($recordToBeDeleted, $recordToBeMergedInto);
  
  my $fishIDtoDelete;
  $curFishIdsToDelete->bind_columns(\$fishIDtoDelete);
  my %fishIDs = ();
  while ($curFishIdsToDelete->fetch()) {
    $fishIDs{$fishIDtoDelete} = 1;
  }
  $curFishIdsToDelete->finish();
  
  my $getMrkrRelIds = "select distinct mrkrrel1.mrel_zdb_id
                         from marker_relationship mrkrrel1
                        where mrkrrel1.mrel_mrkr_1_zdb_id = ?
                          and exists(select 'x' from marker_relationship mrkrrel2
                                      where mrkrrel2.mrel_mrkr_1_zdb_id = ?
                                        and mrkrrel2.mrel_mrkr_2_zdb_id = mrkrrel1.mrel_mrkr_2_zdb_id
                                        and mrkrrel2.mrel_type = mrkrrel1.mrel_type);";

  my $curGetMrkrRelIds = $dbh->prepare($getMrkrRelIds);
  
  $curGetMrkrRelIds->execute($recordToBeDeleted, $recordToBeMergedInto);
  
  my $mrkrRelID;
  $curGetMrkrRelIds->bind_columns(\$mrkrRelID);
  my %mrkrRelIDs = ();
  while ($curGetMrkrRelIds->fetch()) {
     $mrkrRelIDs{$mrkrRelID} = 1;  
  }
  $curGetMrkrRelIds->finish();

  my $fishZdbId;    
  foreach $fishZdbId (keys %fishIDs) {
     $curDelete->execute($fishZdbId);              
  }

  my $mrkrRelationId;
  foreach $mrkrRelationId (keys %mrkrRelIDs) {
     $curDelete->execute($mrkrRelationId);
  }
}

$curDelete->finish();


## deal with root GO term from either of the party whenever there is non-root GO term for the other party, see FB case 11048
my $deleteMrkrGoEvd = "delete from zdb_active_data where zactvd_zdb_id = ?;"; 
my $curDeleteMrkrGoEvd = $dbh->prepare_cached($deleteMrkrGoEvd);

my $getNonRootBioProcess = "select * from marker_go_term_evidence, term 
                             where mrkrgoev_mrkr_zdb_id = ? 
                               and mrkrgoev_term_zdb_id != 'ZDB-TERM-091209-6070'
                               and mrkrgoev_term_zdb_id = term_zdb_id 
                               and term_ontology = 'biological_process';";
my $curNonRootBioProcess = $dbh->prepare_cached($getNonRootBioProcess);

my $getRootBioProcess = "select mrkrgoev_zdb_id from marker_go_term_evidence where mrkrgoev_mrkr_zdb_id = ? and mrkrgoev_term_zdb_id = 'ZDB-TERM-091209-6070';";
my $curRootBioProcess = $dbh->prepare_cached($getRootBioProcess);

$curNonRootBioProcess->execute($recordToBeDeleted);

my $mrkrGoEvdId;

while ($curNonRootBioProcess->fetch()) {
   $curRootBioProcess->execute($recordToBeMergedInto);
   $curRootBioProcess->bind_columns(\$mrkrGoEvdId);
   while ($curRootBioProcess->fetch()) {
      $curDeleteMrkrGoEvd->execute($mrkrGoEvdId);
   }
}

$curNonRootBioProcess->execute($recordToBeMergedInto);
while ($curNonRootBioProcess->fetch()) {        
   $curRootBioProcess->execute($recordToBeDeleted); 
   while ($curRootBioProcess->fetch()) {    
      $curDeleteMrkrGoEvd->execute($mrkrGoEvdId);
   }
}

$curRootBioProcess->finish();
$curNonRootBioProcess->finish();

my $getNonRootMolFunc = "select * from marker_go_term_evidence, term
                          where mrkrgoev_mrkr_zdb_id = ?
                            and mrkrgoev_term_zdb_id != 'ZDB-TERM-091209-2432'
                            and mrkrgoev_term_zdb_id = term_zdb_id
                            and term_ontology = 'molecular_function';";
my $curNonRootMolFunc = $dbh->prepare_cached($getNonRootMolFunc);

my $getRootMolFunc = "select mrkrgoev_zdb_id from marker_go_term_evidence where mrkrgoev_mrkr_zdb_id = ? and mrkrgoev_term_zdb_id = 'ZDB-TERM-091209-2432';";
my $curRootMolFunc = $dbh->prepare_cached($getRootMolFunc);

$curNonRootMolFunc->execute($recordToBeDeleted);

while ($curNonRootMolFunc->fetch()) {
   $curRootMolFunc->execute($recordToBeMergedInto);
   $curRootMolFunc->bind_columns(\$mrkrGoEvdId);
   while ($curRootMolFunc->fetch()) {
      $curDeleteMrkrGoEvd->execute($mrkrGoEvdId);
   }
}

$curNonRootMolFunc->execute($recordToBeMergedInto);
while ($curNonRootMolFunc->fetch()) {
   $curRootMolFunc->execute($recordToBeDeleted);
   while ($curRootMolFunc->fetch()) {
      $curDeleteMrkrGoEvd->execute($mrkrGoEvdId);
   }
}

$curRootMolFunc->finish();
$curNonRootMolFunc->finish();

my $getNonRootCelComp = "select * from marker_go_term_evidence, term
                          where mrkrgoev_mrkr_zdb_id = ?
                            and mrkrgoev_term_zdb_id != 'ZDB-TERM-091209-4029'
                            and mrkrgoev_term_zdb_id = term_zdb_id
                            and term_ontology = 'cellular_component';";
my $curNonRootCelComp = $dbh->prepare_cached($getNonRootCelComp);

my $getRootCelComp = "select mrkrgoev_zdb_id from marker_go_term_evidence where mrkrgoev_mrkr_zdb_id = ? and mrkrgoev_term_zdb_id = 'ZDB-TERM-091209-4029';";
my $curRootCelComp = $dbh->prepare_cached($getRootCelComp);

$curNonRootCelComp->execute($recordToBeDeleted);

while ($curNonRootCelComp->fetch()) {
   $curRootCelComp->execute($recordToBeMergedInto);
   $curRootCelComp->bind_columns(\$mrkrGoEvdId);
   while ($curRootCelComp->fetch()) {
      $curDeleteMrkrGoEvd->execute($mrkrGoEvdId);
   }
}

$curNonRootCelComp->execute($recordToBeMergedInto);
while ($curNonRootCelComp->fetch()) {
   $curRootCelComp->execute($recordToBeDeleted);
   while ($curRootCelComp->fetch()) {
      $curDeleteMrkrGoEvd->execute($mrkrGoEvdId);
   }
}

$curRootCelComp->finish();
$curNonRootCelComp->finish();

$curDeleteMrkrGoEvd->finish();

# ZFIN-6073 duplicate Alliance link
my $deleteDuplicateAlianceLink = "delete from zdb_active_data 
                                   where exists(select 1 from db_link
                                                        where dblink_zdb_id = zactvd_zdb_id 
                                                          and dblink_linked_recid = ? 
                                                          and dblink_acc_num = ? 
                                                          and dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-171018-1');";

my $curDeleteDuplicateAlianceLink = $dbh->prepare_cached($deleteDuplicateAlianceLink);

$curDeleteDuplicateAlianceLink->execute($recordToBeMergedInto, $recordToBeDeleted);

$curDeleteDuplicateAlianceLink->finish();

# regne_genox
my $regenGenox = "select regen_genox_marker(?);";
my $curRegenGenox = $dbh->prepare($regenGenox);
$curRegenGenox->execute($recordToBeMergedInto);
$curRegenGenox->finish();

## delete from zdb_replaced_data table
my $sqlDeleteReplacedData = "delete from zdb_replaced_data where zrepld_old_zdb_id = '$recordToBeDeleted';";
my $curDeleteReplacedData = $dbh->prepare($sqlDeleteReplacedData);
$curDeleteReplacedData->execute();
$curDeleteReplacedData->finish();

## update zdb_replaced_data table
my $sqlUpdateReplacedData = "update zdb_replaced_data set zrepld_new_zdb_id = '$recordToBeMergedInto' where zrepld_new_zdb_id = '$recordToBeDeleted';";
my $curUpdateReplacedData = $dbh->prepare($sqlUpdateReplacedData);
$curUpdateReplacedData->execute();
$curUpdateReplacedData->finish();

## delete from zdb_active_data table
my $sqlDeleteMarker = "delete from zdb_active_data where zactvd_zdb_id = '$recordToBeDeleted';";
my $curDeleteMarker = $dbh->prepare($sqlDeleteMarker);
$curDeleteMarker->execute();
$curDeleteMarker->finish();

## add new zdb_replaced_data record
my $sqlInsertReplacedData = "insert into zdb_replaced_data (zrepld_old_zdb_id, zrepld_new_zdb_id) values ('$recordToBeDeleted', '$recordToBeMergedInto');";
my $curInsertReplacedData = $dbh->prepare($sqlInsertReplacedData);
$curInsertReplacedData->execute();
$curInsertReplacedData->finish();

$dbh->disconnect();

print <<EOA;

<HEAD>
    <script language="JavaScript">    
    window.location.href='/$recordToBeMergedInto';
</script>  
</HEAD>
<BODY>
    Database scanned. Now merging.
</BODY>
</HTML>
EOA
    
exit;

## recursive function which takes the following 5 parameters:
## 1) value of the primary key column of the record to be deleted 
## 2) value of the primary key column of the record to be merged into
## 3) table name
## 4) primary key column name
## 5) depth
## all merge action related SQLs are generated and stored in a hash data structure to be 
## sorted and executed later on
 
sub recursivelyGetSQLs {
  my $toBeDeleted = shift;
  my $toBeMergedInto = shift;
  my $parentTable = shift;
  my $foreignKey = shift;
  my $depth = shift;
  $depth++;

  my $sql = "select distinct c.table_name, k1.column_name, k2.table_name, k2.column_name, k1.table_schema, k2.table_schema
                     from information_schema.constraint_table_usage c,
                          information_schema.key_column_usage k1, information_schema.table_constraints tc1,
                          information_schema.key_column_usage k2, information_schema.table_constraints tc2
                    where k1.column_name = '$foreignKey'
                      and k1.table_name = '$parentTable'
                      and tc1.table_name = k1.table_name
                      and tc1.constraint_name = k1.constraint_name
                      and c.table_name = k1.table_name
                      and c.constraint_name = k2.constraint_name
                      and c.table_name != k2.table_name
                      and tc2.table_name = k2.table_name
                      and tc2.constraint_name = k2.constraint_name
                      and tc2.constraint_type = 'FOREIGN KEY'
                 order by 1, 3, 4;";

  my $cur = $dbh->prepare_cached($sql);
  $cur->execute();
  my ($parentTableName, $colName, $childTableName, $foreignKeyColumn, $parentTableSchema, $childTableSchema);
  $cur->bind_columns(\$parentTableName, \$colName, \$childTableName, \$foreignKeyColumn, \$parentTableSchema, \$childTableSchema);
  
  while ($cur->fetch()) {  # the while loop to go thru all the child table one level deeper
    ## only process those that have not been processed before
    if (!exists($processed{$childTableName.$foreignKeyColumn})) {               
      my $sqlMakeSense = "select * from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeDeleted';";
      my $curMakeSense = $dbh->prepare_cached($sqlMakeSense);
      $curMakeSense->execute();
      my $numberOfResults = 0;
      while ($curMakeSense->fetch()) {
        $numberOfResults++;
      }
      $curMakeSense->finish();

      ## do nothing if there is no records in the child table or if it is the record_attribution table (having another script to do something with this table)
      if ($numberOfResults > 0 && $childTableName ne 'record_attribution') {  
        ## find out the columns consisting of unique constrait
        my $sqlGetUniqueKeyColumn = "select kc.column_name from information_schema.key_column_usage kc,
                                                                information_schema.table_constraints tc
                                      where kc.table_name = '$childTableName'
                                        and tc.table_name = kc.table_name
                                        and tc.constraint_name = kc.constraint_name
                                        and tc.constraint_type = 'UNIQUE'";
        my $curGetUniqueKeyColumn = $dbh->prepare_cached($sqlGetUniqueKeyColumn);
        $curGetUniqueKeyColumn->execute();
            
        my $uniqueKeyCol;
        $curGetUniqueKeyColumn->bind_columns(\$uniqueKeyCol);
                
        my %uniqueKeyColumns = ();
        my $foreignKeyColumnInUniqueConstraint = 0;
        my $numberOfUniqueKeyCols = 0;
        while ($curGetUniqueKeyColumn->fetch()) {           
          $uniqueKeyColumns{$uniqueKeyCol} = $depth if ($uniqueKeyCol ne $foreignKeyColumn);
          $foreignKeyColumnInUniqueConstraint = 1 if ($uniqueKeyCol eq $foreignKeyColumn);
          $numberOfUniqueKeyCols++;                 
        }
        
        $curGetUniqueKeyColumn->finish();
  
        my $sqlGetPrimaryKeyColumn = "select kc.column_name from information_schema.key_column_usage kc,
                                                                 information_schema.table_constraints tc
                                       where kc.table_name = '$childTableName'
                                         and tc.table_name = kc.table_name
                                         and tc.constraint_name = kc.constraint_name
                                         and tc.constraint_type = 'PRIMARY KEY'";
        my $curGetPrimaryKeyColumn = $dbh->prepare_cached($sqlGetPrimaryKeyColumn);
        $curGetPrimaryKeyColumn->execute();
            
        my $primaryKeyCol;
        $curGetPrimaryKeyColumn->bind_columns(\$primaryKeyCol);
                
        my %primaryKeyColumns = ();
        my $primaryKeyOfChildTable;
        my $soloPK;
        my $foreignKeyColumnInPrimaryKey = 0;
        my $numberOfPrimaryKeys = 0;
        while ($curGetPrimaryKeyColumn->fetch()) {  
          if ($primaryKeyCol ne $foreignKeyColumn) {
              $primaryKeyColumns{$primaryKeyCol} = $depth;
              $primaryKeyOfChildTable = $primaryKeyCol;
          } else {
              $foreignKeyColumnInPrimaryKey = 1;
              $soloPK = $primaryKeyCol;
          }
          $numberOfPrimaryKeys++;                 
        }
        $curGetPrimaryKeyColumn->finish();  
  
        my $deleteSQL = "";

        ## One senario: when the related foreign key column is part of the unique constraint which consists of more than one columns.
        ## In this senario, the primary key is not a composite key
        ## And, in this senario, the recursive function is called to go one level deeper    
        if ($foreignKeyColumnInUniqueConstraint > 0 && $numberOfUniqueKeyCols > 1 && $numberOfPrimaryKeys == 1) {
                    
          my $selectList = join (", ", keys %uniqueKeyColumns);
              
          my $sqlCheckValuesForMergedInto = "select $primaryKeyOfChildTable, " . $selectList . " from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeMergedInto';";
                                   
          my $curCheckValuesForMergedInto = $dbh->prepare_cached($sqlCheckValuesForMergedInto);
          $curCheckValuesForMergedInto->execute();
          my %valuePairsForMergedInto = ();
          my $primaryKeyColValueForMergedInto;
          my $ctValuePairsMergedInto = 0;
          if ($numberOfUniqueKeyCols == 2) {
              my $uniqueValueForMergedInto;
              $curCheckValuesForMergedInto->bind_columns(\$primaryKeyColValueForMergedInto, \$uniqueValueForMergedInto);                
                                
              while ($curCheckValuesForMergedInto->fetch()) {  
                $ctValuePairsMergedInto++;
                $valuePairsForMergedInto{$uniqueValueForMergedInto} = $primaryKeyColValueForMergedInto;
              }              
                                                
          } elsif ($numberOfUniqueKeyCols == 3) {
              my ($uniqueValueForMergedInto1, $uniqueValueForMergedInto2);
              $curCheckValuesForMergedInto->bind_columns(\$primaryKeyColValueForMergedInto, \$uniqueValueForMergedInto1, \$uniqueValueForMergedInto2);                
                                
              while ($curCheckValuesForMergedInto->fetch()) {  
                $ctValuePairsMergedInto++;
                $valuePairsForMergedInto{$uniqueValueForMergedInto1.$uniqueValueForMergedInto2} = $primaryKeyColValueForMergedInto;
              }

          } elsif ($numberOfUniqueKeyCols == 4) {
              my ($uniqueValueForMergedInto1, $uniqueValueForMergedInto2, $uniqueValueForMergedInto3);
              $curCheckValuesForMergedInto->bind_columns(\$primaryKeyColValueForMergedInto, \$uniqueValueForMergedInto1, \$uniqueValueForMergedInto2, \$uniqueValueForMergedInto3);                
                                
              while ($curCheckValuesForMergedInto->fetch()) {  
                $ctValuePairsMergedInto++;
                $valuePairsForMergedInto{$uniqueValueForMergedInto1.$uniqueValueForMergedInto2.$uniqueValueForMergedInto3} = $primaryKeyColValueForMergedInto;
              }
          }
          
          $curCheckValuesForMergedInto->finish();
              
          ## No need for delete SQL or go to deeper level if there is nothing to check against for unique constraint violation 
          if ($ctValuePairsMergedInto > 0) {
                                                               
            my $sqlCheckValuesForDeleted = "select $primaryKeyOfChildTable, " . $selectList . " from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeDeleted';";
                                    
            my $curCheckValuesForDeleted = $dbh->prepare_cached($sqlCheckValuesForDeleted);
            $curCheckValuesForDeleted->execute();
            my %valuePairsForDeleted = ();
            my $primaryKeyColValueForDeleted;
            my $primaryKeyColValueForMergedIntoAsPara;
                
            if ($numberOfUniqueKeyCols == 2) {
              my $uniqueValueForDeleted;
              $curCheckValuesForDeleted->bind_columns(\$primaryKeyColValueForDeleted, \$uniqueValueForDeleted);                
                
              while ($curCheckValuesForDeleted->fetch()) {  
                # violation found! Need to construct and store the delete SQL:
                if (exists($valuePairsForMergedInto{$uniqueValueForDeleted})) {
                  $deleteSQL = "delete from $childTableSchema.$childTableName where $primaryKeyOfChildTable = '$primaryKeyColValueForDeleted';";
                  $mergeSQLs{$deleteSQL} = $depth;
                        
                  $primaryKeyColValueForMergedIntoAsPara = $valuePairsForMergedInto{$uniqueValueForDeleted};
                  ## call the recursive function to go one level deeper
                  recursivelyGetSQLs($primaryKeyColValueForDeleted, $primaryKeyColValueForMergedIntoAsPara, $childTableName, $primaryKeyOfChildTable, $depth);                          
                        
                }
              }
            } elsif ($numberOfUniqueKeyCols == 3) {
                my ($uniqueValueForDeleted1, $uniqueValueForDeleted2);
                $curCheckValuesForDeleted->bind_columns(\$primaryKeyColValueForDeleted, \$uniqueValueForDeleted1, \$uniqueValueForDeleted2);                
                
                while ($curCheckValuesForDeleted->fetch()) {  
                  # violation found! Need to construct and store the delete SQL:
                  if (exists($valuePairsForMergedInto{$uniqueValueForDeleted1.$uniqueValueForDeleted2})) {
                    $deleteSQL = "delete from $childTableSchema.$childTableName where $primaryKeyOfChildTable = '$primaryKeyColValueForDeleted';";
                    $mergeSQLs{$deleteSQL} = $depth;
                        
                    $primaryKeyColValueForMergedIntoAsPara = $valuePairsForMergedInto{$uniqueValueForDeleted1.$uniqueValueForDeleted2};
                    ## call the recursive function to go one level deeper
                    recursivelyGetSQLs($primaryKeyColValueForDeleted, $primaryKeyColValueForMergedIntoAsPara, $childTableName, $primaryKeyOfChildTable, $depth);                                          
                  }
                }
            } elsif ($numberOfUniqueKeyCols == 4) {
                my ($uniqueValueForDeleted1, $uniqueValueForDeleted2, $uniqueValueForDeleted3);
                $curCheckValuesForDeleted->bind_columns(\$primaryKeyColValueForDeleted, \$uniqueValueForDeleted1, \$uniqueValueForDeleted2, \$uniqueValueForDeleted3);                
                
                while ($curCheckValuesForDeleted->fetch()) {  
                  # violation found! Need to construct and store the delete SQL:
                  if (exists($valuePairsForMergedInto{$uniqueValueForDeleted1.$uniqueValueForDeleted2.$uniqueValueForDeleted3})) {
                    $deleteSQL = "delete from $childTableSchema.$childTableName where $primaryKeyOfChildTable = '$primaryKeyColValueForDeleted';";
                    $mergeSQLs{$deleteSQL} = $depth;
                        
                    $primaryKeyColValueForMergedIntoAsPara = $valuePairsForMergedInto{$uniqueValueForDeleted1.$uniqueValueForDeleted2.$uniqueValueForDeleted3};
                    ## call the recursive function to go one level deeper
                    recursivelyGetSQLs($primaryKeyColValueForDeleted, $primaryKeyColValueForMergedIntoAsPara, $childTableName, $primaryKeyOfChildTable, $depth);                           
                  }
                }
            }
            
            $curCheckValuesForDeleted->finish();

                
          } # end of if ($ctValuePairsMergedInto > 0)
            

        } # end of if ($foreignKeyColumnInUniqueConstraint > 0 && $numberOfUniqueKeyCols > 1 && $foreignKeyColumnInPrimaryKey > 0 && $numberOfPrimaryKeys == 1)
        
        ## Another senario: when the related foreign key column is part of the primary key which consists of more than one columns 
        ## In this senario, the delete SQL should be constructed and stored
        if ($foreignKeyColumnInPrimaryKey == 1 && $numberOfPrimaryKeys > 1) {
                      
          my $selectListP = join (", ", keys %primaryKeyColumns);
              
          my $sqlCheckValuesForMergedIntoP = "select " . $selectListP . " from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeMergedInto';";
                        
          my $curCheckValuesForMergedIntoP = $dbh->prepare_cached($sqlCheckValuesForMergedIntoP);
          $curCheckValuesForMergedIntoP->execute();
          my %valuePairsForMergedIntoP = ();
          my $ctValuePairsMergedIntoP = 0;
          if ($numberOfPrimaryKeys == 2) {
            my $uniqueValueForMergedIntoP;
            $curCheckValuesForMergedIntoP->bind_columns(\$uniqueValueForMergedIntoP);                
                                
            while ($curCheckValuesForMergedIntoP->fetch()) {  
              $ctValuePairsMergedIntoP++;
              $valuePairsForMergedIntoP{$uniqueValueForMergedIntoP} = $depth;
            }
                
          } elsif ($numberOfPrimaryKeys == 3) {
              my ($uniqueValueForMergedIntoP1, $uniqueValueForMergedIntoP2);
              $curCheckValuesForMergedIntoP->bind_columns(\$uniqueValueForMergedIntoP1, \$uniqueValueForMergedIntoP2);                
                                
              while ($curCheckValuesForMergedIntoP->fetch()) {  
                $ctValuePairsMergedIntoP++;
                $valuePairsForMergedIntoP{$uniqueValueForMergedIntoP1.$uniqueValueForMergedIntoP2} = $depth;
              }
          } elsif ($numberOfPrimaryKeys == 4) {
              my ($uniqueValueForMergedIntoP1, $uniqueValueForMergedIntoP2, $uniqueValueForMergedIntoP3);
              $curCheckValuesForMergedIntoP->bind_columns(\$uniqueValueForMergedIntoP1, \$uniqueValueForMergedIntoP2, \$uniqueValueForMergedIntoP3);                
                                
              while ($curCheckValuesForMergedIntoP->fetch()) {  
                $ctValuePairsMergedIntoP++;
                $valuePairsForMergedIntoP{$uniqueValueForMergedIntoP1.$uniqueValueForMergedIntoP2.$uniqueValueForMergedIntoP3} = $depth;
              }
          }
          
          $curCheckValuesForMergedIntoP->finish();
              
          ## No need for delete SQL if there is nothing to check againt for primary key unique constraint violation 
          if ($ctValuePairsMergedIntoP > 0) {
                                                               
            my $sqlCheckValuesForDeletedP = "select " . $selectListP . " from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeDeleted';";
                                    
            my $curCheckValuesForDeletedP = $dbh->prepare_cached($sqlCheckValuesForDeletedP);
            $curCheckValuesForDeletedP->execute();
            my %valuePairsForDeletedP = ();
            my $primaryKeyColValueForDeletedP;
            if ($numberOfPrimaryKeys == 2) {
              my $uniqueValueForDeletedP;
              $curCheckValuesForDeletedP->bind_columns(\$uniqueValueForDeletedP);                
                
              while ($curCheckValuesForDeletedP->fetch()) {  
                # violation found! Need to construct and store the delete SQL:
                if (exists($valuePairsForMergedIntoP{$uniqueValueForDeletedP})) {
                  $deleteSQL = "delete from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeDeleted' and ";
                  foreach my $primaryKeyComponent (keys %primaryKeyColumns) {
                    $deleteSQL = $deleteSQL . $primaryKeyComponent . " = '$uniqueValueForDeletedP';";
                  }
                        
                  $mergeSQLs{$deleteSQL} = $depth;                       
                }
              }
            } elsif ($numberOfPrimaryKeys == 3) {
                my ($uniqueValueForDeletedP1, $uniqueValueForDeletedP2);
                $curCheckValuesForDeletedP->bind_columns(\$uniqueValueForDeletedP1, \$uniqueValueForDeletedP2);                
                
                while ($curCheckValuesForDeletedP->fetch()) {  
                  # violation found! Need to construct and store the delete SQL:
                  if (exists($valuePairsForMergedIntoP{$uniqueValueForDeletedP1.$uniqueValueForDeletedP2})) {
                    $deleteSQL = "delete from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeDeleted' and ";
                    my $ct = 0;
                    foreach my $primaryKeyComponent (keys %primaryKeyColumns) {
                      $ct++;                         
                      if ($ct == 1) {
                        $deleteSQL = $deleteSQL . $primaryKeyComponent . " = '$uniqueValueForDeletedP1' and ";
                      } else {
                          $deleteSQL = $deleteSQL . $primaryKeyComponent . " = '$uniqueValueForDeletedP2';" 
                      }
                    }
                        
                    $mergeSQLs{$deleteSQL} = $depth;                                              
                  }
                }
            } elsif ($numberOfPrimaryKeys == 4) {
                my ($uniqueValueForDeletedP1, $uniqueValueForDeletedP2, $uniqueValueForDeletedP3);
                $curCheckValuesForDeletedP->bind_columns(\$uniqueValueForDeletedP1, \$uniqueValueForDeletedP2, \$uniqueValueForDeletedP3);                
                
                while ($curCheckValuesForDeletedP->fetch()) {  
                  # violation found! Need to construct and store the delete SQL:
                  if (exists($valuePairsForMergedIntoP{$uniqueValueForDeletedP1.$uniqueValueForDeletedP2.$uniqueValueForDeletedP3})) {
                    $deleteSQL = "delete from $childTableSchema.$childTableName where $foreignKeyColumn = '$toBeDeleted' and ";
                    my $ct = 0;
                    foreach my $primaryKeyComponent (keys %primaryKeyColumns) {
                      $ct++;
                      if ($ct == 1) {
                          $deleteSQL = $deleteSQL . $primaryKeyComponent . " = '$uniqueValueForDeletedP1' and ";
                      } elsif ($ct == 2) {
                          $deleteSQL = $deleteSQL . $primaryKeyComponent . " = '$uniqueValueForDeletedP2' and ";
                      } else {
                          $deleteSQL = $deleteSQL . $primaryKeyComponent . " = '$uniqueValueForDeletedP3';" 
                      }
                    }
                        
                    $mergeSQLs{$deleteSQL} = $depth;                                              
                  }
                }
            }
            
            $curCheckValuesForDeletedP->finish();
                
          } # end of if ($ctValuePairsMergedIntoP > 0)     


        } # end of if ($foreignKeyColumnInPrimaryKey == 1 && $numberOfPrimaryKeys > 1)
         
        ## In the senario of FK is the solo PK, call the recursive function to go one level deeper without constructing the update SQL
        if ($foreignKeyColumnInPrimaryKey == 1 && $numberOfPrimaryKeys == 1) {                        
            recursivelyGetSQLs($toBeDeleted, $toBeMergedInto, $childTableName, $soloPK, $depth);             
            
        } else {
            ## construct and store the update SQLs first, if flagged 
            my $updateSQL = "update $childTableSchema.$childTableName
                                set $foreignKeyColumn = '$toBeMergedInto'
                              where $foreignKeyColumn = '$toBeDeleted';";
            $mergeSQLs{$updateSQL} = $depth;           
        } 
            
      } ## end of if ($numberOfResults > 0 && $childTableName ne 'record_attribution') 

      ## mark the child table as processed
      $processed{$childTableName.$foreignKeyColumn} = $depth;
          
    } ## end of if (!exists($processed{$childTableName.$foreignKeyColumn}))


        
  } ## end of the while loop to go thru all the child table one level deeper
      
  $cur->finish();       


} # end of recursive function

## in this function, delete from record_attribution SQL(s) are generated and stored in the hash, whenever violation of some unique constrait is found 
sub cleanupRecordAttributionTable {
  my $mergedInto = shift;
  my $toDelete = shift;
  my $order = shift;
  my $sqlCheckRecordAttributionForMergedInto = "select recattrib_pk_id, recattrib_source_zdb_id, recattrib_source_type from record_attribution where recattrib_data_zdb_id = '$mergedInto';";
  my ($pkRecordAttribution, $source, $type);
  my $curCheckRecordAttributionForMergedInto = $dbh->prepare_cached($sqlCheckRecordAttributionForMergedInto);
  $curCheckRecordAttributionForMergedInto->execute();
  my %recordAttributionForMergedInto = ();
  my $ctRecordAttributionForMergedInto = 0;

  $curCheckRecordAttributionForMergedInto->bind_columns(\$pkRecordAttribution, \$source, \$type);                
                                
  while ($curCheckRecordAttributionForMergedInto->fetch()) {  
    $ctRecordAttributionForMergedInto++;
    $recordAttributionForMergedInto{$source.$type} = $pkRecordAttribution;
  }

  $curCheckRecordAttributionForMergedInto->finish();
              
  ## No need for 'delete from record_attribution' SQL if there is no record there for the record to be merged into 
  if ($ctRecordAttributionForMergedInto > 0) {                                                               
    my $sqlCheckRecordAttributionForDeleted = "select recattrib_pk_id, recattrib_source_zdb_id, recattrib_source_type from record_attribution where recattrib_data_zdb_id = '$toDelete';";             
                        
    my $curCheckRecordAttributionForDeleted = $dbh->prepare_cached($sqlCheckRecordAttributionForDeleted);
    $curCheckRecordAttributionForDeleted->execute();
    my %recordAttributionForDEleted = ();                

    $curCheckRecordAttributionForDeleted->bind_columns(\$pkRecordAttribution, \$source, \$type);                 
                
    while ($curCheckRecordAttributionForDeleted->fetch()) {  
      # violation found! Need to construct and store the delete SQL:
      if (exists($recordAttributionForMergedInto{$source.$type})) {
        my $deleteFromRecordAttributionSQL = "delete from record_attribution where recattrib_pk_id = '$pkRecordAttribution';";
        $mergeSQLs{$deleteFromRecordAttributionSQL} = $order;                         
      }
    }
            
    $curCheckRecordAttributionForDeleted->finish();
                
  } # end of if ($ctRecordAttributionForMergedInto > 0)
} # end of cleanupRecordAttributionTable function





