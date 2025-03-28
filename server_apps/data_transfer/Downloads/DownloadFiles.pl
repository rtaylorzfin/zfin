#!/opt/zfin/bin/perl -w
#-----------------------------------------------------------------------
# Runs script to create data files for public download.
#
# We extract several different kinds of information:
#
# All genetic markers (includes genes, ests, sslps, etc.)
#	zfin id, name, symbol, type
#
# Synonyms  (for any item in all genetic markers file) There may be multiple lines
#   per zfin id
#	zfin id, synonym
#
# Orthology - separate files for:
#   zebrafish - human
#	zfin id , zebrafish symbol, human symbol, OMIM id, Entrez Gene id
#   zebrafish - mouse
#	zfin id , zebrafish symbol, mouse symbol, MGI id, Entrez Gene id
#   zebrafish - fly
#	zfin id,  zebrafish symbol, fly symbol,  Flybase id
#   zebrafish - yeast
#	zfin id,  zebrafish symbol, yeast symbol,  SGD id
#
# Gene Ontology-
#	A copy of the file we send to GO.
#
# Gene Expression
#	gene zfin id , gene symbol, probe zfin id, probe name, expression type,
#       expression pattern zfin id, pub zfin id, genotype zfin id,
#       experiment zfin id#
# Mapping data
#	zfin id, symbol, panel symbol, LG, loc, metric
#
# Sequence data - separate files for GenBank, RefSeq, EntrezGene
# SWISS-PROT, Interpro
#	zfin id, symbol, accession number
#
# Genotypes
#	zfin id, allele/construct, type, gene symbol, corresponding zfin gene id
#
# Morpholino
#       zfin id of gene, gene symbol, zfin id of MO, MO symbol, public note

use DBI;
use Try::Tiny;
use FindBin;
use lib "$FindBin::Bin/../../perl_lib/";
use ZFINPerlModules qw(assertEnvironment);
assertEnvironment('ROOT_PATH', 'PGHOST', 'DB_NAME', 'SOURCEROOT');

my $sourceRoot = $ENV{'SOURCEROOT'};
my $rootPath = $ENV{'ROOT_PATH'};
my $dbhost = $ENV{'PGHOST'};
my $dbname = $ENV{'DB_NAME'};
my $dbusername = "";
my $dbpassword = "";

# define GLOBALS

# set environment variables

$ENV{"DBDATE"}="Y4MD-";

chdir "$rootPath/server_apps/data_transfer/Downloads";

$downloadStagingDir = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging";
if (-e $downloadStagingDir) {
    system("rm -rf $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/*");
}
else {
    mkdir $downloadStagingDir;
}

try {
  ZFINPerlModules->doSystemCommand("psql -v ON_ERROR_STOP=1 -d $dbname -a -f DownloadFiles.sql");
} catch {
  warn "Failed at DownloadFiles.sql - $_";
  exit -1;
};

try {
  system("sed -i 's/^[ \\t]*//;s/[ \\t]*\$//' $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/crispr_fasta.fa");
  system("sed -i 's/^[ \\t]*//;s/[ \\t]*\$//' $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/talen_fasta.fa");
} catch {
  warn "Failed to remove trailing white spaces - $_";
  exit -1;
};

try {
  system("./patoNumbers.pl");
} catch {
  warn "Failed at patoNumbers.pl - $_";
  exit -1;
};

try {
  system("./generateStagedAnatomy.pl");
} catch {
  warn "Failed at generateStagedAnatomy.pl - $_";
  exit -1;
};


### open a handle on the db
$dbh = DBI->connect ("DBI:Pg:dbname=$dbname;host=$dbhost", $dbusername, $dbpassword)
    or die "Cannot connect to database: $DBI::errstr\n";

## create downloadsStaging/identifiersForIntermine.txt
$sql = "select feature_zdb_id as id1, feature_zdb_id as id2
          from feature
        union
        select feature_zdb_id as id1, feature_abbrev as id2
          from feature       
        union
        select mrkr_zdb_id as id1,mrkr_zdb_id as id2
          from marker
        union
        select mrkr_zdb_id as id1, mrkr_abbrev as id2
          from marker         
        union
        select dblink_linked_recid as id1, dblink_acc_num as id2
          from db_link
        union
        select zrepld_new_zdb_id as id1, zrepld_old_zdb_id as id2         
          from zdb_replaced_data
         where exists (Select 'x' from marker where mrkr_zdb_id = zrepld_new_zdb_id)
        order by 2;";       

$cur = $dbh->prepare($sql);
$cur->execute();
$cur->bind_columns(\$id1, \$id2);

%identifiers = ();        
while ($cur->fetch()) {   
  if (!exists($identifiers{$id1})) {
     $identifiers{$id1} = $id2;
  } else {
     $identifiers{$id1} = $identifiers{$id1} . "," . $id2;
  }
}

$cur->finish();

open (IDS, ">downloadsStaging/identifiersForIntermine.txt") || die "Cannot open identifiersForIntermine.txt : $!\n";
foreach $id (keys %identifiers) {
  $v = $identifiers{$id};
  print IDS "$id\t$v\n";
}
close IDS;

### Parent Query for All Knockdown Reagent Data Downloads:
$sql = "
    create temp view knockdown_parent_query as
        select gn.mrkr_zdb_id, a.szm_term_ont_id, gn.mrkr_abbrev, knockdown.mrkr_zdb_id as id2, b.szm_term_ont_id as id3, knockdown.mrkr_abbrev as abbr2,
               seq_sequence, seq_sequence_2, knockdown.mrkr_comments
        from marker gn, marker knockdown, marker_sequence, marker_relationship, so_zfin_mapping a, so_zfin_mapping b
        where gn.mrkr_zdb_id = mrel_mrkr_2_zdb_id
          and knockdown.mrkr_zdb_id = mrel_mrkr_1_zdb_id
          and a.szm_object_type = gn.mrkr_type
          and b.szm_object_type = knockdown.mrkr_type
          and mrel_type in ('knockdown reagent targets gene', 'crispr targets region')
          and knockdown.mrkr_zdb_id = seq_mrkr_zdb_id
        order by gn.mrkr_abbrev
";
$cur = $dbh->prepare($sql);
$cur->execute();

### FB case 8651, Include Publication in Morpholino Data Download

$sql = "select * from knockdown_parent_query where get_obj_type(id2) = 'MRPHLNO' order by mrkr_abbrev";
$cur = $dbh->prepare($sql);
$cur->execute();
$cur->bind_columns(\$geneId, \$a_szm_term_ont_id, \$gene, \$MoId, \$b_szm_term_ont_id, \$Mo, \$MoSeq, \$MoSeq2Unused, \$note);

$MOfileWithPubsAndNoHTMLtags = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/Morpholinos.txt";

open (MOWITHPUBS, ">$MOfileWithPubsAndNoHTMLtags") || die "Cannot open $MOfileWithPubsAndNoHTMLtags : $!\n";

while ($cur->fetch()) {
    # remove HTML tags and back slash from the public note column of the download file of Morpholino data
    if ($note) {
      $note =~ s/<[^<>]+>//g;
      $note =~ s/\\//g;
    } else {
        $note = "";
    }
            
    print MOWITHPUBS "$geneId\t$a_szm_term_ont_id\t$gene\t$MoId\t$b_szm_term_ont_id\t$Mo\t$MoSeq\t";

    @pubIds = ();
    my ($pub);
    $innerSql = "select ra.recattrib_source_zdb_id
                 from record_attribution ra
                 where ra.recattrib_data_zdb_id = ?
                 union
                 select ra.recattrib_source_zdb_id
                 from record_attribution ra , marker_relationship mr
                 where mr.mrel_mrkr_2_zdb_id = ?
                 and ra.recattrib_data_zdb_id = mr.mrel_zdb_id
                 union
                 select ra.recattrib_source_zdb_id
                 from record_attribution ra , marker_relationship mr
                 where mr.mrel_mrkr_1_zdb_id = ?
                 and ra.recattrib_data_zdb_id = mr.mrel_zdb_id";
        
    $curInner = $dbh->prepare($innerSql);
    $curInner->execute($MoId, $MoId, $MoId);
    $curInner->bind_columns(\$pub);
    while ($curInner->fetch()) {
         push(@pubIds, $pub);
    }
    $pubs = join(",", sort(@pubIds));
    print MOWITHPUBS "$pubs\t$note\n";
}

close MOWITHPUBS;

## generate a feature data file for CZRC
try {
  system("./CZRCfeature.pl")
} catch {
  warn "Failed at CZRCfeature.pl - $_";
  exit -1;
};

## generate a file with antibodies and associated expression experiment
## ZFIN-5654
$sql = "
 select xpatex_atb_zdb_id, atb.mrkr_abbrev, xpatex_gene_zdb_id as gene_zdb,
	'' as geneAbbrev, xpatex_zdb_id as xpat_zdb, xpatex_assay_name,xpatassay_mmo_id,
	xpatex_source_zdb_id, fish_zdb_id, genox_exp_zdb_id
 from expression_experiment2, fish_experiment, fish, marker atb,expression_pattern_assay
 where xpatex_genox_Zdb_id = genox_zdb_id
 and xpatex_assay_name=xpatassay_name
 and genox_fish_zdb_id = fish_Zdb_id
 and atb.mrkr_zdb_id = xpatex_atb_zdb_id
   and xpatex_gene_zdb_id is null
 AND not exists (Select 'x' from clone
      where clone_mrkr_zdb_id = xpatex_probe_feature_zdb_id
      and clone_problem_type = 'Chimeric')
UNION
 select xpatex_atb_zdb_id, atb.mrkr_abbrev, xpatex_gene_zdb_id as gene_zdb,
	gene.mrkr_abbrev as geneAbbrev, xpatex_zdb_id as xpat_zdb,xpatex_assay_name,xpatassay_mmo_id,
	xpatex_source_zdb_id, fish_zdb_id, genox_exp_zdb_id
 from expression_experiment2, fish_experiment, fish, marker atb, marker gene,expression_pattern_assay
 where xpatex_genox_Zdb_id = genox_zdb_id
 and xpatex_assay_name=xpatassay_name
 and genox_fish_zdb_id = fish_Zdb_id
 and atb.mrkr_zdb_id = xpatex_atb_zdb_id
 and gene.mrkr_zdb_id = xpatex_gene_zdb_id
   and xpatex_gene_zdb_id is not null
 and gene.mrkr_abbrev not like 'WITHDRAWN:'
 AND not exists (Select 'x' from clone
      where clone_mrkr_zdb_id = xpatex_probe_feature_zdb_id
      and clone_problem_type = 'Chimeric');
";
$cur = $dbh->prepare($sql);
$cur->execute();
$cur->bind_columns(\$abId, \$abSym, \$geneId, \$geneSym,  \$xpId, \$xpType, \$xpTypeId, \$pubId, \$fishId, \$envId);


$abXpatFishFile = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/abxpat_fish.txt";

open (ABXPFISH, ">$abXpatFishFile") || die "Cannot open $abXpatFishFile : $!\n";

while ($cur->fetch()) {
    # remove back slash from the gene ID column of the download file
    if ($geneId) {
        $geneId =~ s/\\//g;
    } else {
        $geneId = "";
    }
            
    print ABXPFISH "$abId\t$abSym\t$geneId\t$geneSym\t$xpId\t$xpType\t$xpTypeId\t$pubId\t$fishId\t$envId\n";
}

close ABXPFISH;

$TALENfileWithPubsAndNoHTMLtags = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/TALEN.txt";

open (TALENWITHPUBS, ">$TALENfileWithPubsAndNoHTMLtags") || die "Cannot open $TALENfileWithPubsAndNoHTMLtags : $!\n";

$sql = "select * from knockdown_parent_query where get_obj_type(id2) = 'TALEN' order by mrkr_abbrev";

$cur = $dbh->prepare($sql);
$cur->execute();
$cur->bind_columns(\$geneId, \$a_szm_term_ont_id, \$gene, \$talen_id, \$b_szm_term_ont_id, \$talen, \$talen_seq1, \$talen_seq2, \$note); 

while ($cur->fetch()) {
    # remove HTML tags and back slash from the public note column of the download file of TALEN data
    if ($note) {
      $note =~ s/<[^<>]+>//g;
      $note =~ s/\\//g;
    } else {
        $note = "";
    }
            
    print TALENWITHPUBS "$geneId\t$a_szm_term_ont_id\t$gene\t$talen_id\t$b_szm_term_ont_id\t$talen\t$talen_seq1\t$talen_seq2\t";

    %pubIds = ();
    $numOfPubs = 0;
    my ($pub);
    $curInner = $dbh->prepare("select distinct recattrib_source_zdb_id from record_attribution where recattrib_data_zdb_id = ?");
    $curInner->execute($talen_id);
    $curInner->bind_columns(\$pub);
    while ($curInner->fetch()) {
         $pubIds{$pub} = 1;
         $numOfPubs++;
    }

    if ($numOfPubs > 0) {
        $numOfPubsCt = $numOfPubs;
        foreach $key (sort keys %pubIds) {
           $numOfPubsCt--;
           if ($numOfPubsCt == 0) {
               print TALENWITHPUBS "$key\t$note\n";
           } else {
               print TALENWITHPUBS "$key,";
           }
        }
    } else {
        print TALENWITHPUBS "$note\n";
    }    
}

close TALENWITHPUBS;

$CRISPRfileWithPubsAndNoHTMLtags = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/CRISPR.txt";

open (CRISPRWITHPUBS, ">$CRISPRfileWithPubsAndNoHTMLtags") || die "Cannot open $CRISPRfileWithPubsAndNoHTMLtags : $!\n";

$sql = "select * from knockdown_parent_query where get_obj_type(id2) = 'CRISPR' order by mrkr_abbrev";

$cur = $dbh->prepare($sql);
$cur->execute();
$cur->bind_columns(\$geneId, \$a_szm_term_ont_id, \$gene, \$crispr_id, \$b_szm_term_ont_id, \$crispr, \$crispr_seq, \$crispr_seq2_unused, \$note);

while ($cur->fetch()) {
    # remove HTML tags and back slash from the public note column of the download file of CRISPR data
    if ($note) {
      $note =~ s/<[^<>]+>//g;
      $note =~ s/\\//g;
    } else {
        $note = "";
    }
            
    print CRISPRWITHPUBS "$geneId\t$a_szm_term_ont_id\t$gene\t$crispr_id\t$b_szm_term_ont_id\t$crispr\t$crispr_seq\t";

    %pubIds = ();
    $numOfPubs = 0;
    my ($pub);
    $curInner = $dbh->prepare("select distinct recattrib_source_zdb_id from record_attribution where recattrib_data_zdb_id = ?");
    $curInner->execute($crispr_id);
    $curInner->bind_columns(\$pub);
    while ($curInner->fetch()) {
         $pubIds{$pub} = 1;
         $numOfPubs++;
    }

    if ($numOfPubs > 0) {
        $numOfPubsCt = $numOfPubs;
        foreach $key (sort keys %pubIds) {
           $numOfPubsCt--;
           if ($numOfPubsCt == 0) {
               print CRISPRWITHPUBS "$key\t$note\n";
           } else {
               print CRISPRWITHPUBS "$key,";
           }
        }
    } else {
        print CRISPRWITHPUBS "$note\n";
    }    
}

close CRISPRWITHPUBS;

$curInner->finish();


# FB case 7670, add Source field to antibodies.txt download file

$antibodyFile = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/antibodies2.txt";

open (AB, "$antibodyFile") || die "Cannot open antibodies2.txt : $!\n";
@lines=<AB>;
close(AB);


$antibodyFileWithSupplier = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/antibodies.txt";

open (ABSOURCE, ">$antibodyFileWithSupplier") || die "Cannot open $antibodyFileWithSupplier : $!\n";


foreach $line (@lines) {

    chop($line);
    undef (@fields);
    @fields = split(/\s+/, $line);

    $antibodyId = $fields[0];

    $cur = $dbh->prepare('select distinct l.name from int_data_supplier, lab l where idsup_data_zdb_id = ? and idsup_supplier_zdb_id = l.zdb_id union select distinct c.name from int_data_supplier, company c where idsup_data_zdb_id = ? and idsup_supplier_zdb_id = c.zdb_id;');
    $cur->execute($antibodyId,$antibodyId);
    my ($supplier);
    %suppliers = ();
    $numOfSuppliers = 0;
    $cur->bind_columns(\$supplier);
    while ($cur->fetch()) {
         $suppliers{$numOfSuppliers} = $supplier;
         $numOfSuppliers++;
    }

    print ABSOURCE "$line\t";

    if ($numOfSuppliers > 0) {
        $numOfSuppliersCt = $numOfSuppliers;
        foreach $key (sort { $suppliers{$a} cmp $suppliers{$b} } keys %suppliers) {
           $numOfSuppliersCt--;
           $value = $suppliers{$key};
           if ($numOfSuppliersCt == 0) {
               print ABSOURCE "$value\n";
           } else {
               print ABSOURCE "$value, ";
           }
        }
    } else {
        print ABSOURCE "\t \n";
    }

}

close ABSOURCE;
close AB;

$cur->finish();

$dbh->disconnect
    or warn "Disconnection failed: $DBI::errstr \n";


# FB case 8886, remove HTML tags from the download file of Sanger Alleles

$sangerAllelesWithHTMLtags = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/saAlleles2.txt";

open (SAHTML, $sangerAllelesWithHTMLtags) || die "Can't open $sangerAllelesWithHTMLtags : $!\n";

@lines=<SAHTML>;
$sangerAlleles = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/saAlleles.txt";

open (SA,  ">$sangerAlleles") || die "Can't open: $sangerAlleles $!\n";
foreach $line (@lines) {
  $line =~ s/<[^<>]+>//g;
  print SA "$line";
}

close SAHTML;
close SA;

# remove temporary files

system("rm $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/saAlleles2.txt");

$huAllelesHtml = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/huAlleles2.txt";

open (HUALLELEHTML, $huAllelesHtml) || die "Can't open $huAllelesHtml : $!\n";

@lines=<HUALLELEHTML>;
$huAlleles = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging/huAlleles.txt";

open (HUALLELE,  ">$huAlleles") || die "Can't open: $huAlleles $!\n";
foreach $line (@lines) {
  $line =~ s/<[^<>]+>//g;
  print HUALLELE "$line";
}

close HUALLELEHTML;
close HUALLELE;

system("rm $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/huAlleles2.txt");


# This part checks for any failed download files (those with 0 bytes), and ends the script if it finds some.


$emptyFilesList = "/tmp/${dbname}emptyFiles.txt";
system("rm -f $emptyFilesList");

open (EMPTY, ">$emptyFilesList") || die "Can't open $emptyFilesList !\n";

$dir = "$rootPath/server_apps/data_transfer/Downloads/downloadsStaging";

opendir(DH, $dir) or die $!;

while (my $file = readdir(DH)) {

    my $filesize = -s $dir."/".$file || 0;

    next if ($filesize > 0);

    print "empty file! : $file"."\n"; 
    print EMPTY $file." FILE IS EMPTY! \n";
}
close EMPTY;

if (!(-z $emptyFilesList)) {
    die "there are files with 0 data!";
}

system("cd $sourceRoot/ && gradle createMeshChebiMappingFile && cp mesh-chebi-mapping.tsv $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/.");


# move files to production location -- assume all are good, as the file check above did not end the script


system("rm -rf $rootPath/home/data_transfer/Downloads/*.txt");
system("rm -rf $rootPath/home/data_transfer/Downloads/*.unl");
system("rm -rf $rootPath/home/data_transfer/Downloads/intermineData/*");

system("cp $rootPath/server_apps/data_transfer/Downloads/downloadsStaging/*  $rootPath/home/data_transfer/Downloads/") and die "can not cp files to production location";


system("$rootPath/server_apps/data_transfer/Downloads/intermineData/dumper.sh") and die "error running dumper.sh";

system("ant -f $rootPath/server_apps/data_transfer/Downloads/build.xml archive-download-files");

