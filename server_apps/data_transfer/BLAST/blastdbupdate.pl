#!/private/bin/perl -w
#
# The script checks several ftp sites for new release,
# and invokes corresponding scripts to transfer and 
# process data. It executes at /research/zblastfiles/files/blastRegeneration/fasta, 
# uses timestamped *.ftp file to probe new release,
# then calls scripts under /research/zusers/blast/BLAST_load/data_transfer to execute.
# Outputs are saved in *.report file. This script
# runs weekly and sends out summary via email.


use strict;
use Net::FTP;

#======================================
#== Define variables
#====================================== 
my ($mailprog, %scripts, %reptfiles, %stampfiles, $ftpFile);

$scripts{"genbank"} = "/research/zusers/blast/BLAST_load/target/GenBank/processGB.sh";

$reptfiles{"genbank"} = "/research/zusers/blast/BLAST_load/data_transfer/genbankupdate.report";

$stampfiles{"genbank"} = "/research/zusers/blast/BLAST_load/data_transfer/GenBank/genbank.ftp";

$mailprog = "/usr/sbin/sendmail -t -oi -oem" ;

open(MAIL, "| $mailprog") || die "cannot open mailprog $mailprog, stopped";
print MAIL "To: informix\@zfin.org\n";
print MAIL "Subject: DB release detection report\n";

#===============================
#= Execute checking & updates
#===============================

chdir "/research/zblastfiles/files/blastRegeneration/fasta";

if ( &checkRelease ("genbank") ) { 
    # no new release
    &genbankWeeklyUpdate ();
}
print "h3";
if ("watson.zfin.org" eq "zygotix.zfin.org" && "/research/zfin.org/blastdb" eq "/research/zfin.org/blastdb"){
    &cpToProductionAndRsyncDev();
}
close MAIL;

###############################################################
# Copy recently updated blastdbs to almost copy.
# 
###############################################################
sub cpToProductionAndRsyncDev() {
    chdir "/research/zblastfiles/zmore/blastRegeneration/Current" ;
    
    # WEBHOST_BLAST_DATABASE_PATH is always /research/zfin.org/blastdb.  
    # we do these one by one because we don't want to overwrite any load files on zfin.org    # from ZFIN (especially curated ones)

    system("rm -f /research/zfin.org/blastdb/Backup/gbk*") && die "/research/zfin.org/blastdb/Backup delete failed for blastdbupdate.pl";

    # check if files exist; if they don't we don't want to put the current files to backup and then 
    # have nothing to move.

    my $ckFile = "/research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk_zf_mrna.xnd";
    if  (-e $ckFile) {
	# rm the current files for blastdbupdate members.
	system("mv -f /research/zfin.org/blastdb/Current/gbk*.x* /research/zfin.org/blastdb/Backup/" ) && die "/research/zfin.org/blastdb/Current/gbk* delete failed for blastdbupdate.pl";
	system("mv -f /research/zblastfiles/files/blastRegeneration/fasta/GenBank/gbk*.x* /research/zfin.org/blastdb/Current/") && die "/research/zfin.org/blastdb/Current mv failed from /research/zblastfiles/zmore/blastRegeneration/Current/gbk";
	}


    # change group to zfishweb for informix files.
    system("/bin/chgrp -R -L zfishweb /research/zfin.org/blastdb/Current/*.x*") ;
    system("/bin/chgrp -R -L zfishweb /research/zfin.org/blastdb/Backup/*.x*") ;
    system("/bin/chmod -R -L g+w /research/zfin.org/blastdb/Current/*.x*") ;
    system("/bin/chmod -R -L g+w /research/zfin.org/blastdb/Backup/*.x*") ;

    # this rsync will update the default environment on genomix for developers.
    system("/local/bin/rsync -vu /research/zblastfiles/zmore/blastRegeneration/Current/gbk*.x* /research/zblastfiles/zmore/dev_blastdb/Current/") ;
    system("/local/bin/rsync -vu /research/zblastfiles/zmore/blastRegeneration/Current/gbk*.x* /research/zblastfiles/zmore/trunk/Current/") ;
    system("/local/bin/rsync -vu /research/zblastfiles/zmore/blastRegeneration/Current/gbk*.x* /research/zblastfiles/zmore/test/Current/") ;

}


################################################################
# Check whether the release/file has a late date than the local stampfile, 
# if so initiate the update process
#
sub checkRelease ($) {
    print "Check release for $_[0] at ".`date`;

    $ftpFile = '';        # ensembl needs specified ftpFile name
    my $dbkey = $_[0];
    my $script = $scripts{ $dbkey };
    print $script;
    my $reptfile = $reptfiles{ $dbkey };
    my $stampfile = $stampfiles{ $dbkey };
    
    my $l_mdtm = (stat($stampfile))[9];
    my $ltime = scalar(localtime($l_mdtm));

    # ftpFile is global (used for Ensembl and is set in getRemoteFileTimeStamp method
    my $r_mdtm = &getRemoteFileTimestamp($stampfile, "0");
    unless ($r_mdtm) {
		print MAIL ucfirst($dbkey)." remote file not found! \n";
		return;
    }
    my $rtime = scalar(localtime($r_mdtm));
    if ( $l_mdtm  < $r_mdtm ) {
		print MAIL ucfirst($dbkey)." updates release: $rtime \n"; 	
		print MAIL "\t Start update at ".`date`;
		system ("/bin/rm -f $reptfile");
		my $optmode = ($dbkey eq "trace") ? "1" : "";
		# initial the update process and write the output to a file
		system ("$script $optmode $ftpFile > $reptfile  2>&1") &&  print MAIL "\t Update Failed! \n";
		print MAIL "\t Finish update at ".`date`;
		print MAIL "\t please check $reptfile.\n";
		if ($dbkey eq "genbank") {
		    print MAIL "\t *************************************************\n";
		    print MAIL "\t *************************************************\n";
		}

		return 0;
    }else {
		print MAIL ucfirst($dbkey)." last release: $rtime \n"; 
		return 1;
    }
	
}	

################################################################
# Check whether a new release/file with new name is available, 
# if so initiate update process
#
sub checkNewfile ($) {
    print "Check new file for $_[0] at ".`date`;
    my $dbkey = $_[0];
    my $script = $scripts{ $dbkey };
    my $reptfile = $reptfiles{ $dbkey };
    my $stampfile = $stampfiles{ $dbkey };

    my $r_mdtm = &getRemoteFileTimestamp($stampfile, "1");
    if ($r_mdtm) {

		my $rtime = scalar(localtime($r_mdtm));
		
		print MAIL ucfirst($dbkey)." new release: $rtime \n"; 	
		print MAIL "\t Start update at ".`date`;
		
		my $optmode = ($dbkey eq "trace") ? "0" : "";
		system ("/bin/rm -f $reptfile") unless ($dbkey eq "trace");
		# initial the update process and write the output to a file
		system ("$script $optmode > $reptfile 2>&1") &&  print MAIL "\t Update Failed! \n";
		print MAIL "\t Finish update at ".`date`;
		print MAIL "\t please check /research/zblastfiles/files/blastRegeneration/fasta$reptfile.\n";
		return 0;
    }else {
		
		print MAIL ucfirst($dbkey)." no new release \n ";
		return 1;
    }
}	

#################################################################
# getRemoteFileTimestamp
#
# input:
#        file  --- stamp file with content of remote site and file name
#        mode  --- 1 means probe the next version
#                  0 means probe the current version update
#
# Use the info in the stamp file, the sub function probes the remote 
# ftp site and get the time stamp of the remote file
#
sub getRemoteFileTimestamp ($$) {
    my $info_file = $_[0];
    my $probnext = $_[1];
    open INFOFILE, "$info_file" or die "getRemoteFileTimestamp: cannot open $info_file to read: $! \n";
    my $info_line = <INFOFILE>;
    my @info = split(/\|/, $info_line);
    
    my $ftp_url = $info[0];
    print "\n".$ftp_url."\n";
    my $ftp_path = $info[1];
    my $ftp_file = $info[2];

    close INFOFILE;

    # in case of probing next version, the file name need to be updated.
    if ($probnext) {
	       	foreach my $field ( split (/\.|_/, $ftp_file) ) {
			if ( $field =~ /^\d+$/ ) {
				my $length_version = length($field);		
				my $nextversion = $field + 1;
				while (length($nextversion) < $length_version) {
					$nextversion = "0".$nextversion;
				}
				$ftp_file =~ s/$field/$nextversion/;
                                last;
			}
		}
    }

    my $user_name = "anonymous";
    my $passwd = "someone@";    
    my $ftp = new Net::FTP($ftp_url);
    my $ftpFullPath ="";
    
    die "Failed to connect to server '$ftp_url':$!\n" unless $ftp;
    die "Failed to login as $user_name\n" unless $ftp->login($user_name, $passwd);
    die "Failed to change directory to $ftp_path\n" unless $ftp->cwd($ftp_path);
    warn "Failed to set binary mode\n" unless $ftp->binary();
    
    print "\n".$ftp_file."\n";
    my $r_mdtm = $ftp->mdtm($ftp_file);
    return ($r_mdtm);
}

################################################################
#
sub genbankWeeklyUpdate (){

    system ("/bin/rm -f /research/zblastfiles/files/blastRegeneration/fasta/GenBank/weeklyGB/weeklyGbupdate.report") && die "weeklyGbupdate: report deletion fail";
    system ("/research/zusers/blast/BLAST_load/target/GenBank/weeklyGB/weeklyGbUpdate.sh > /research/zusers/blast/BLAST_load/data_transfer/GenBank/weeklyGB/weeklyGbupdate.report 2>&1 ") &&  print MAIL "\t Update Failed! \n" ;
    print MAIL "\t please check "."/research/zusers/blast/BLAST_load/data_transfer/GenBank/weeklyGB/"."weeklyGbupdate.report. \n";
}

