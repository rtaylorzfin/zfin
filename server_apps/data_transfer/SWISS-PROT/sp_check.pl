#!/opt/zfin/bin/perl 

# sp_check.pl
#
# This script reads Swiss-Prot file(which consists of protein records),
# checks each record with corresponding gene record in ZFIN database 
# and accordingly divides the records into several catagories. It checks 
# the Database cross-references(DR) about whether ZFIN accession number is
# provided, whether EMBL GenPept accession numbers are provided and associated 
# with the same marker in ZFIN, it then checks EMBL GenBank accessions for those
# that couldn't be decided by GenPept matching. The scripts also
# checks the Reference cross-reference(s)(RX) to see whether any PubMed 
# number presents and whether they all in ZFIN database. Records with problems 
# are collected and divided into different problem files for biologists to 
# look into.

use strict;
use warnings;
use DBI;
use POSIX;


use lib $ENV{'ROOT_PATH'} . "/server_apps/";
use ZFINPerlModules qw(assertEnvironment md5File);
assertEnvironment('PGHOST','ROOT_PATH', 'DB_NAME');


my $num_ok = 0;   # number of good records that are going to be loaded
my $num_prob = 0; # number of problem records

my $dbname = $ENV{'DB_NAME'};
my $dbhost = $ENV{'PGHOST'};
my $username = "";
my $password = "";

### open a handle on the db
my $dbh = DBI->connect("DBI:Pg:dbname=$dbname;host=$dbhost", $username, $password)
    or die "Cannot connect to PostgreSQL database: $DBI::errstr\n";

### Globals
### TODO: move these to local scope if possible.
my $after_embl;
my $count;
my $dr_zfin;
my $embl_ac;
my $embl_exist;
my $fileno;
my $good;
my $match;
my $is_empty_embl_list;
my $num_pub;
my $one_match;
my $probfile;
my $qual_check;
my $zfin_ac;
my @EMBL;
my @embl_nt;
my @embl_nt_matched;
my @pubmed;
my @rx;
my @zfin;
my $last_percent = 0;
my $progress_count = 0;
my $record_count = 0;
my $use_refseq_match = 0;


sub main {
    # Take a SP file as input (content format restricted).

    # Create the output files and give them titles.
    init_files();

    # if PubMed number not in zfin, output to a single file
    open PUB, ">pubmed_not_in_zfin" or die "Cannot open the pubmed_not_in_zfin:$!";

    my $zfin_dat_hash = md5File('zfin.dat');
    print "Processing zfin.dat (md5:$zfin_dat_hash) at " . strftime("%Y-%m-%d %H:%M:%S", localtime(time())) . " \n";

    $/ = "//\n";
    open(UNPT, "zfin.dat") || die "Cannot open zfin.dat : $!\n";
    $record_count = 0;
    while (<UNPT>) {
        $record_count++;
    }
    close(UNPT);
    print "zfin.dat has $record_count records\n";

    open(UNPT, "zfin.dat") || die "Cannot open zfin.dat : $!\n";

    while (<UNPT>) {
        my $record = $_;
        print_progress();

        init_var(); # Initialize the variables and arrays

        # records in probfile contains AC, RX, DR EMBL lines
        # they go to one of the prob# files for curator review.
        my $problem_buffer = "";

        next if handleMissingEmblAndRefSeqRecord($record);

        next if handleDoubleZfinRecord($record);

        # record in tempfile contains AC, GN, CC, ID, DE, DR, KW,
        # it goes to "okfile" and "problemfile". Some record from problemfile
        # would be matched out and appended to the okfile for parsing.

        my $temp_buffer = "";

        foreach (split /\n/) {
            $_ = $_ . "\n";
            my $line = $_;

            next if isIgnoreLine($line);

            # if the line is a relevant line, append it to the temp buffer for future output to okfile
            capturePassThroughLine($line, $temp_buffer);

            next if handleAcLine($line, $problem_buffer);

            next if handleRxLine($line, $problem_buffer);

            next if handleMatchingAgainstEmblLines($line, $problem_buffer, $temp_buffer);

            # after the EMBL lines and ZFIN line, check the GenPept matching,
            # if GenPept matching is not sufficient, use GenBank to further sort
            # the records.
            # This assumes all the EMBL lines are together so the above clause gets executed all times before this clause.
            handlePostEmblLinesMatching($line, $problem_buffer, $temp_buffer);

            next if handleRefSeqMatching($line, $problem_buffer, $temp_buffer);

            next if handleZfinLine($line, $problem_buffer, $temp_buffer);

            next if handleDrKwLine($line, $temp_buffer);

            handleCloseRecord($line, $problem_buffer, $temp_buffer);

        } # foreach loop for one record

    } # for loop for zfin.dat file
    close UNPT;

    close PUB;
    print "Finished processing zfin.dat at " . strftime("%Y-%m-%d %H:%M:%S", localtime(time())) . " \n";

    open CHECKREP, ">checkreport.txt" or die "Cannot open checkreport.txt:$!";
    print CHECKREP "\nFinal report: \n";
    print CHECKREP "\t problem records(#) : $num_prob \n";
    print CHECKREP "\t ok records(#)  : $num_ok \n";

    my $ok_percent = sprintf("%.1f", 100 - ($num_prob/($num_prob+$num_ok)*100));

    print CHECKREP "\t ok percentage   : $ok_percent%\n";
    close CHECKREP;
}

sub init_var() {

    @rx = ();
    @pubmed = ();
    @EMBL = ();
    @zfin = ();
    @embl_nt = ();
    @embl_nt_matched = ();
    $embl_ac = '';
    $zfin_ac = '';
    $one_match = '';
    $dr_zfin = '';
    $num_pub = 0;
    $embl_exist = 0;
    $count = 0;
    $is_empty_embl_list = 0;
    $good = 0;
    $fileno = 0;
    $after_embl = 0;
    $qual_check = 0;
    $use_refseq_match = 0;
}

# Check whether at least one EMBL numbers are in ZFIN database, 
# and whether the matched ZFIN records are the same one. 
# Return two values that denotes the checking result.   

sub Embl_Match($$) {

    my ($embl_ac, $dbname, $sth, $geneZdbId, $sql);
    $embl_ac = $_[0];
    $dbname = $_[1];
    my @embl_match = ();
    $sql = "";
    if ($dbname eq "GenPept") {
        $sql = "  select distinct dblink_linked_recid
                  from db_link 
                  where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'
                    and dblink_linked_recid like 'ZDB-GENE-%'
                    and dblink_acc_num = ?
                  union
                  select mrel_mrkr_1_zdb_id 
                    from marker_relationship,db_link
                   where dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-42'  
                     and mrel_type = 'gene encodes small segment'
                     and mrel_mrkr_2_zdb_id=dblink_linked_recid 
                     and dblink_acc_num= ?";

    }
    if ($dbname eq "GenBank") {
        $sql = "  select distinct dblink_linked_recid
                  from db_link 
                  where dblink_fdbcont_zdb_id in ('ZDB-FDBCONT-040412-36',
                                                  'ZDB-FDBCONT-040412-37')
                    and dblink_linked_recid like 'ZDB-GENE-%'
                    and dblink_acc_num = ?
                  union
                  select mrel_mrkr_1_zdb_id 
                    from marker_relationship,db_link
                   where dblink_fdbcont_zdb_id in ('ZDB-FDBCONT-040412-37',
                                                  'ZDB-FDBCONT-040412-36')
                     and mrel_type = 'gene encodes small segment'
                     and mrel_mrkr_2_zdb_id=dblink_linked_recid 
                     and dblink_acc_num= ?";
    }
    $sth = $dbh->prepare($sql);
    $sth->execute($embl_ac, $embl_ac);

    while ($geneZdbId = $sth->fetchrow_array) {
        if ($geneZdbId =~ /ZDB-GENE/) {
            push @embl_match, $geneZdbId;
        }
    }
    return (@embl_match); #matches in ZFIN db for each EMBL number
}

sub Embl_Genomic_Check() {

    my ($gb_acc, $all_genomic, $isGenomic);
    $all_genomic = 1;
    foreach $gb_acc (@embl_nt_matched) {
        ($isGenomic) = $dbh->selectrow_array("select 1
                                         from accession_bank
                                        where accbk_acc_num = '$gb_acc'
                                          and accbk_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-36'");
        if (!$isGenomic) {
            $all_genomic = 0;
            last;
        }
    }
    return ($all_genomic);
}

sub RefSeq_Match {
    my $np_acc = shift;
    my $nm_acc = shift;

    # remove version number
    $np_acc =~ s/\.\d+$//;

    my $sth = $dbh->prepare("select distinct dblink_linked_recid
                             from db_link
                             where dblink_fdbcont_zdb_id in (
                                'ZDB-FDBCONT-040412-38',
                                'ZDB-FDBCONT-040412-39',
                                'ZDB-FDBCONT-040527-1',
                                'ZDB-FDBCONT-041217-2'
                             )
                                and dblink_linked_recid like 'ZDB-GENE-%'
                                and dblink_acc_num = ?");
    $sth->execute($np_acc);
    my @embl_match = ();
    while (my $geneZdbId = $sth->fetchrow_array) {
        if ($geneZdbId =~ /ZDB-GENE/) {
            push @embl_match, $geneZdbId;
        }
    }
    return (@embl_match); #matches in ZFIN db for each EMBL number
}

sub handleMissingEmblAndRefSeqRecord {
    my $record = shift;

    # TODO: simplify by removing the legacy logic and retaining the elsif clause as an if clause
    if ($ENV{"USE_LEGACY_LOGIC"} && $record !~ /DR\s*EMBL;/) {
        # if no EMBL line
        file_append("prob7", $record);
        file_append("problemfile", $record);

        $num_prob++;
        return 1;
    } elsif (!$ENV{"USE_LEGACY_LOGIC"} && $record !~ /DR\s*EMBL;/ && $record !~ /DR\s*RefSeq;/) {
        # if no EMBL line
        file_append("prob7", $record);
        file_append("problemfile", $record);

        $num_prob++;
        return 1;
    } elsif (!$ENV{"USE_LEGACY_LOGIC"} && $record !~ /DR\s*EMBL;/ && $record =~ /DR\s*RefSeq;/) {
        print "No EMBL line, but has RefSeq line\n";
        $use_refseq_match = 1;
    }
    return 0;
}

sub handleDoubleZfinRecord {
    my $record = shift;
    if (/DR\s*ZFIN;.*\nDR\s*ZFIN;/) {
        # if >1 ZFIN lines
        file_append("prob8", $record);
        file_append("problemfile", $record);

        $num_prob++;
        return 1;
    }
    return 0;
}

sub isIgnoreLine {
    my $line = shift;
    my $isRelevantLine = ($line =~ /^AC/ ||
        $line =~ /^GN/ ||
        $line =~ /^CC/ ||
        $line =~ /^ID/ ||
        $line =~ /^DE/ ||
        $line =~ /^DR/ ||
        $line =~ /^KW/ ||
        $line =~ /\/\// ||
        $line =~ /^RX\s+MEDLINE=\d+.*PubMed=(\d+)/);
    return !$isRelevantLine;
}

#check whether at least one EMBL# is in ZFIN,
#and for those have match(es), if every one has multiple matches,
#that is a problem. Only if there is at least one has one match and
#that match also appears in other EMBL#'s matches, the record is ok. 

sub Embl_Check() {

    my $is_empty_embl_list = 0;
    my $same = 0; #whether all EMBL#s in ZFIN associated with the same marker
    my $matches_buffer = "";
    if (!@EMBL) {
        $is_empty_embl_list = 1;
    } else {
        if ($one_match) {

            while ($match = pop @EMBL) {
                $matches_buffer .= "$match,"; #for debugging
                if ($match eq $one_match) { #whether the one-match appears in all the
                    $count--;               #other matches.
                }
            }
            if (!$count) {
                $same = 1;
            } else {
                file_append("spcheck.dbg.log", "DEBUG Embl_Check: one_match ($one_match) is truthy, but not all matches are the same ($matches_buffer).\n");
            }
        } else {
            file_append("spcheck.dbg.log", "DEBUG Embl_Check: one_match is not truthy. \@EMBL is @EMBL.\n")
        }
    }
    return ($is_empty_embl_list, $same);
}


# Check whether at least one PubMed number is in ZFIN db.
# Return 0/1 that denote this result.
sub PubMed_Check() {
    my $prob_buffer = $_[0];

    my $match = 0;
    my ($sth, $pubmed, $qpubmed, $pub_match);

    foreach my $rx (@rx) {
        chop($rx); # the added '\n' could only be chopped not chompped.
        $prob_buffer .= "$rx";

        $pubmed = $1 if ($rx =~ /^RX\s+MEDLINE=\d+.*PubMed=(\d+)/);
        $qpubmed = $dbh->quote($pubmed);
        $pub_match = $dbh->selectrow_array("
                 select zdb_id
                 from publication
                 where accession_no = $qpubmed
               ");

        if ($pub_match) {

            $prob_buffer .= "\t$pub_match";
            $match = 1;
        } else {

            print PUB "$pubmed\n";
        }
        $prob_buffer .= "\n";
    }

    $_[0] = $prob_buffer; #is this necessary in perl?
    return $match;
}

sub capturePassThroughLine {
    my $line = shift;
    my $temp_buffer = $_[0];

    if ($line =~ /^AC/ || $line =~ /^GN/ || $line =~ /^CC/ || $line =~ /^ID/ || $line =~ /^DE/) {
        $_[0] .= $line;
    }
}

sub handleAcLine {
    my $line = shift;
    my $problem_buffer = $_[0];
    if ($line =~ /^AC/) {
        $problem_buffer .= $line;
        $_[0] = $problem_buffer;
        return 1;
    }
    return 0;
}

sub handleRxLine {
    my $line = shift;
    if ($line =~ /^RX\s+MEDLINE=\d+.*PubMed=(\d+)/) {
        # now only PubMed in zfin
        push @rx, $_;
        $num_pub = 1;
        return 1;
    }
    return 0;
}

sub handleZfinLine {
    my $line = shift;
    my $problem_buffer = $_[0];
    my $temp_buffer = $_[1];

    if (/^DR\s+ZFIN;\s+(.*);/) {
        # check for ZFIN acc number, parse it
        $fileno = "00" if (!$fileno && $one_match && ($1 ne $one_match));
        $problem_buffer .= $line;
        $temp_buffer .= $line;

        $_[0] = $problem_buffer;
        $_[1] = $temp_buffer;
        return 1;
    }
    return 0;
}

sub handleDrKwLine {
    my $line = shift;
    my $temp_buffer = $_[0];
    if (/^DR/ || /^KW/) {
        $temp_buffer .= $line;
        $_[0] = $temp_buffer;
        return 1;
    }
    return 0;
}

# @EMBL is a global array that gets built up line by line with each record (then reset for the next record)
# In the post processing subroutine, there are some checks that are done on the @EMBL array to ensure that
# it only contains one unique ZFIN ID.
#
sub handleMatchingAgainstEmblLines {
    my $line = shift;
    my $problem_buffer = $_[0];
    my $temp_buffer = $_[1];

    if ($line =~ /^DR\s+EMBL;\s+(\w+);\s+(\w+)\./) {
        # check for EMBL acc number, parse it

        my $dr = $line;
        chop($dr); #the '\n' appended above could only be choped, not chomped.
        $problem_buffer .= $dr;
        $embl_exist = 1;
        my $embl_genbank_acc = $1;
        push @embl_nt, $embl_genbank_acc;

        # use GenPept acc for matching, storing results in @EMBL
        my $embl_gp = $2;
        my @embl_match = Embl_Match($embl_gp, "GenPept"); # first try the polypeptide accession
        my $num_match = @embl_match;                      # record number of genes directly or indirectly associated

        if (@embl_match) {
            $problem_buffer .= "\tGP match: @embl_match\n";
        }
        else {
            $problem_buffer .= "\n";
        }

        @EMBL = (@EMBL, @embl_match); # collect all the matches for each record

        if ($num_match > 1) {
            $fileno = "1";
        }
        elsif (!$num_match) {
            $temp_buffer .= "$dr  GP_NO_MATCH\n";
        }
        else {
            $one_match = pop(@embl_match);
            $temp_buffer .= "$dr  GP match: $one_match\n"; # the gene id is used in the parser
            $count++;                                # only count the one match
        }
        $after_embl = 1;

        $_[0] = $problem_buffer;
        $_[1] = $temp_buffer;
        return 1;
    }
    return 0;
}

# after the EMBL lines and ZFIN line, check the GenPept matching,
# if GenPept matching is not sufficient, use GenBank to furthur sort
# the records.
# This assumes all the EMBL lines are together so the above clause gets executed all times before this clause.
sub handlePostEmblLinesMatching {
    my $line = shift;
    my $problem_buffer = $_[0];
    my $temp_buffer = $_[1];

    if ($after_embl && !$qual_check) {
        ($is_empty_embl_list, $good) = Embl_Check();

        if (!$is_empty_embl_list && $good) {
            $fileno = "0";
        }
        elsif (!$is_empty_embl_list && !$good) {
            $fileno = "2"; #GenPept matching shows conflicts
        }
        else {
            #GenBank acc check
            @EMBL = ();
            $count = 0;
            foreach my $embl_genbank_acc (@embl_nt) {
                my @embl_match = Embl_Match($embl_genbank_acc, "GenBank");
                my $num_match = @embl_match;
                if (@embl_match) {
                    push @embl_nt_matched, $embl_genbank_acc;
                    $problem_buffer .= "\tGB match: @embl_match\n"; #!!this line is used in the sp_parser.pl
                }
                @EMBL = (@EMBL, @embl_match); # collect all the matches for each record
                if ($num_match) {
                    $count++; # count for matched ones
                    if ($num_match == 1) {
                        $one_match = pop(@embl_match); # record the one match in ZFIN
                        $temp_buffer .= "\t\tGB match: $one_match\n";
                    }
                }
            }

            ($is_empty_embl_list, $good) = Embl_Check();

            if ($is_empty_embl_list) {
                if (!$num_pub) {
                    $fileno = "6";
                }
                else {
                    $fileno = PubMed_Check() ? "5" : "6";
                }
            }
            elsif (!$good) {
                $fileno = "3";
            }
            else {
                $fileno = Embl_Genomic_Check() ? "4" : "0";
            }
        }
        $qual_check = 1;
    }

    $_[0] = $problem_buffer;
    $_[1] = $temp_buffer;
}


sub handleRefSeqMatching {
    my $line = shift;
    my $problem_buffer = $_[0];
    my $temp_buffer = $_[1];
    my $refseq_match;
    my @refseq_matches;
    if ($use_refseq_match && $line =~ /DR   RefSeq; (.*); (.*)\./ ) {
        my $np = $1;
        my $nm = $2;
        @refseq_matches = RefSeq_Match($np, $nm);
    }

    $_[0] = $problem_buffer;
    $_[1] = $temp_buffer;
}

sub handleCloseRecord {
    my $line = shift;
    my $problem_buffer = $_[0];
    my $temp_buffer = $_[1];

    if ($line =~ /^\/\//) {
        # end of one record

        $problem_buffer .= "//\n";
        $temp_buffer .= "//\n";

        open (OK, ">>okfile");
        if ($fileno eq "0") {
            #append temp_buffer to okfile
            print OK $temp_buffer;
            $num_ok++;
        }
        elsif ($fileno eq "00") {
            #those disagrees go to both okfile and prob0.
            print OK $temp_buffer;
            file_append('prob0', $problem_buffer);
            $num_ok++;
        }
        else {
            $probfile = "prob" . $fileno;
            file_append($probfile, $problem_buffer);
            file_append("problemfile", $temp_buffer);
            $num_prob++;
        }
        close OK;
    }
}

# Initialize the final output files for the checked SP records
sub init_files() {

    my $title;
    open FILE, ">okfile" or die "Cannot open the okfile: $!";
    close FILE;

    open FILE, ">problemfile" or die "Cannot open the problemfile: $!";
    close FILE;

    open FILE, ">prob0" or die "Cannot open the prob0: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 0
#    
#  DR ZFIN line doesn't agree with the matched gene. 
#
#  These records are already in okfile with the matched gene. 
#
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob1" or die "Cannot open the prob1: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 1
#    
#   at least one GenPept Acc#  associated with >1  genes in ZFIN
#   
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob2" or die "Cannot open the prob2: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 2
#  
#   GenPept Acc#s associated with different genes
#
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob3" or die "Cannot open the prob3: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 3
#    
#   at least one GenBank Acc# in ZFIN, but not consistent
#   
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob4" or die "Cannot open the prob4: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 4
#    
#   GenBank Acc# in ZFIN, consistent, but all genomic 
#
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob5" or die "Cannot open the prob5: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 5
#    
#   GenBank #s not in ZFIN
#   at least one PubMed # in ZFIN
#
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob6" or die "Cannot open the prob6: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 6
#    
#   GenBank #s not in ZFIN
#   PubMed # not present, or not in ZFIN
#
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob7" or die "Cannot open the prob7: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 7
#    
#   No EMBL line
#
ENDDOC

    print FILE "$title";
    close FILE;

    open FILE, ">prob8" or die "Cannot open the prob8: $!";
    $title = <<ENDDOC;
#--------------------------------------------
# SP records Problem 8
#    
#   >1 DR ZFIN lines
#
ENDDOC

    print FILE "$title";
    close FILE;
}

sub print_progress {
    $progress_count++;

    my $percent = int($progress_count/$record_count*100);

    #only print once per percent
    if ($percent != $last_percent) {
        print "$progress_count/$record_count ($percent%) " . strftime("%Y-%m-%d %H:%M:%S", localtime(time())) . "\n";
        $last_percent = $percent;
    }
}

sub file_append {
    my $filename = shift;
    my $content = shift;

    my $mode = ">>";

    open FILE, "$mode$filename" or die "Cannot open the file $filename: $!";
    print FILE "$content";
    close FILE;
}

main();
