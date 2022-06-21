#!/opt/zfin/bin/perl 
#
# this script reads the SwissProt accession list from
# a curator and match out the detailed records from problemfile
# and writes it into ok2file which would be appended to okfile
# in loadsp.pl process. 
#
use strict;
use POSIX;
use lib $ENV{'ROOT_PATH'} . "/server_apps/";
use ZFINPerlModules qw(assert_environment);

sub main {
    assert_environment('ROOT_PATH', 'DB_NAME', 'SWISSPROT_EMAIL_REPORT');

    my $accfile = getAccessionFileContents();
    my %prob8records = getRecordsWithMultipleZfinIDs();
    writeProblemRecordsToOk2FileIfManuallyCurated($accfile, \%prob8records);

    sendEmails();
    printChecksums();
    archiveArtifacts();

}

sub getAccessionFileContents {
    if (@ARGV < 1) {
        die "Please enter the accession file name. \n";
    }

    open ACC, "<$ARGV[0]" or die "Cannot open $ARGV[0] file";
    undef $/;
    my $accfile = <ACC>;
    close ACC;

    return $accfile;
}

sub getRecordsWithMultipleZfinIDs {
    $/ = "\n";
    my %prob8records;
    open PROB8FILE, "<prob8" or die "Cannot open prob8";
    while (<PROB8FILE>) {
        if (/^AC (.*)/) {
            my @records = split(/;/, $1);
            foreach my $rec (@records) {
                $rec =~ s/\s*//g;
                $prob8records{$rec} = 1;
            }
        }
    }
    close(PROB8FILE);
    return %prob8records;
}

sub writeProblemRecordsToOk2FileIfManuallyCurated {
    my $manuallyCuratedIDs = shift();
    my $prob8recordsRef = shift();
    my %prob8records = %{ $prob8recordsRef };

    open PROBLEMFILE, "<problemfile" or die "Cannot open problemfile";
    open my $ok2fh, ">ok2file" or die "Cannot open ok2file";
    $/ = "//\n";
    while (<PROBLEMFILE>) {
        my $problemFileRecord = $_;

        if (my $acc = recordContainsManuallyCuratedID($problemFileRecord, $manuallyCuratedIDs)) {
            writeRecordToOk2File($ok2fh, $acc, $problemFileRecord, \%prob8records);
        }
    }
    close FILE;
    close $ok2fh;
}

sub recordContainsManuallyCuratedID{
    my $problemFileRecord = shift;
    my $manuallyCuratedIDs = shift;
    foreach my $line (split /\n/, $manuallyCuratedIDs) {
        my ($acc, $geneZdbID) = split(/,/, $line);
        if ($problemFileRecord =~ /$acc/) {
            return $acc;
        }
    }
    return 0;
}

sub writeRecordToOk2File{
    my $OKFileHandle = shift;
    my $acc = shift;
    my $problemFileRecord = shift;
    my $prob8recordsRef = shift;
    my %prob8records = %{ $prob8recordsRef };

    if ($prob8records{$acc}) {
        print "sp_match.pl: Skipping $acc due to being in prob8 file\n";
    } else {
        print $OKFileHandle $problemFileRecord;
    }
}

sub sendEmails {
    my $dbname = $ENV{'DB_NAME'};

    my $subject = "Auto from $dbname: SWISS-PROT check report";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'}, "$subject", "checkreport.txt");

    #----- Another mail send out problem files ----
    $subject = "Auto from $dbname: SWISS-PROT problem file";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'}, "$subject", "allproblems.txt");

    #----- Another mail send out problem files ----
    $subject = "Auto from $dbname: PubMed not in ZFIN";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'}, "$subject", "pubmed_not_in_zfin");

    #----- Another mail send out problem files ----
    $subject = "Auto from $dbname: report of processing pre_zfin.org";
    ZFINPerlModules->sendMailWithAttachedReport($ENV{'SWISSPROT_EMAIL_REPORT'}, "$subject", "redGeneReport.txt");
}

sub printChecksums {
    print("Checksums and file info of important files:\n");
    system("md5sum  *.ontology *2go prob* okfile pubmed_not_in_zfin *.unl *.txt *.dat *.dat.gz");
    system("ls -al  *.ontology *2go prob* okfile pubmed_not_in_zfin *.unl *.txt *.dat *.dat.gz");
}

sub archiveArtifacts {
    if ($ENV{'ARCHIVE_ARTIFACTS'}) {
        print("Archiving artifacts\n");
        my $directory = "archives/" . strftime("%Y-%m-%d-%H-%M-%S", localtime(time()));
        system("mkdir -p $directory");
        system("cp -rp ./ccnote $directory");
        system("cp -p *.ontology $directory");
        system("cp -p *2go $directory");
        system("cp -p prob* $directory");
        system("cp -p okfile $directory");
        system("cp -p ok2file $directory");
        system("cp -p pubmed_not_in_zfin $directory");
        system("cp -p *.unl $directory");
        system("cp -p *.txt $directory");
        system("cp -p *.dat $directory");
        print(" Not archiving *.dat.gz to $directory due to likely large size\n");
    }
}

main();
