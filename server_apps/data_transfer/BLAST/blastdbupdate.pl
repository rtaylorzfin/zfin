#!/private/bin/perl -w
#
# The script checks several ftp sites for new release,
# and invokes corresponding scripts to transfer and 
# process data.
# uses timestamped *.ftp file to probe new release,
# then calls scripts under @SCRIPT_PATH@ to execute.
# Outputs are saved in *.report file. This script
# runs weekly and sends out summary via email.


use strict;
use Net::FTP;

#======================================
#== Define variables
#====================================== 
my ($mailprog, %scripts, %reptfiles, %stampfiles, $ftpFile);

$scripts{"genbank"} = "./processGB.sh";

$reptfiles{"genbank"} = "./genbankupdate.report";

$stampfiles{"genbank"} = "./GenBank/genbank.ftp";

$mailprog = "/usr/sbin/sendmail -t -oi -oem" ;

open(MAIL, "| $mailprog") || die "cannot open mailprog $mailprog, stopped";
print MAIL "To: informix\@zfin.org\n";
print MAIL "Subject: DB release detection report\n";

#===============================
#= Execute checking & updates
#===============================

# No need to chdir here, the script is executed in the right directory ???
# was chdir "BLASTSERVER_FASTA_FILE_PATH/fasta"; #(temp directory)

if ( &checkRelease ("genbank") ) { 
    # no new release
    &genbankWeeklyUpdate ();
}

#  Do we need to copy the current files to production and rsync to dev?
# if ("@HOSTNAME@" eq "watson.zfin.org" && "@WEBHOST_BLAST_DATABASE_PATH@" eq "/research/zfin.org/blastdb"){
#     &cpToProductionAndRsyncDev();
# }
close MAIL;

###############################################################
# Copy recently updated blastdbs to almost copy.
# example: gbk* files
###############################################################
sub cpToProductionAndRsyncDev() {
    #REMOVED: These files can be copied by another job
}


################################################################
# Check whether the release/file has a late date than the local stampfile, 
# if so initiate the update process
#
sub checkRelease ($) {
    print "Check release for $_[0] at ".`date`;

    $ftpFile = '';        # ensembl needs specified ftpFile name
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

        chdir "./GenBank";
		system ("$script $optmode $ftpFile > $reptfile  2>&1") &&  print MAIL "\t Update Failed! \n";
        chdir "..";

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

        chdir "./GenBank";
		system ("$script $optmode > $reptfile 2>&1") &&  print MAIL "\t Update Failed! \n";
        chdir "..";
        my $currentDir = `pwd`;

		print MAIL "\t Finish update at ".`date`;
		print MAIL "\t please check $currentDir/fasta$reptfile.\n";
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

    system ("/bin/rm -f ./GenBank/weeklyGB/weeklyGbupdate.report") && die "weeklyGbupdate: report deletion fail";

    chdir "./GenBank/weeklyGB";
    system ("./weeklyGbUpdate.sh > ./weeklyGbupdate.report 2>&1 ") &&  print MAIL "\t Update Failed! \n" ;
    chdir "../..";

    print MAIL "\t please check "."GenBank/weeklyGB/"."weeklyGbupdate.report. \n";
}

