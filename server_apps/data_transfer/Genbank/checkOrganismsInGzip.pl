#!/usr/bin/env perl
#
# The script reads GenBank daily update flat file (compressed), and
# checks if it contains relevant organisms
# 

use Getopt::Std;

# Get command line options
getopts ('h');

my $usage = <<EOF;

  Usage:  checkOrganismsInGzip.pl file

    Input file : Gb daily update file nc*.flat.gz
    Output: Indicates whether or not it contains danio
EOF
    
if (@ARGV==0 || $opt_h) {
    print $usage;
    exit;
}

my ($locus, $bp, $type, $division,  $definition, $organism, $accession, $gi, $dbsource, $condition1, $condition2, $condition);
my $found_danio = 0;
my $found_mouse = 0;
my $found_human = 0;
my $found_relevant_organism = 0;

while (my $gbfile = shift @ARGV) {
	print "Checking $gbfile\n";
	open(IN, "gunzip -c $gbfile |") || die("Cannot open the $gbfile file to read: $!.");
    $/ = "//\n";

    while (<IN>) {
		next unless /LOCUS\s+(\w+)\s+(\d+)\sbp\s+(\w+)\s+\w+\s+(\w+).+/;
		$locus = $1;
		$bp = $2;
		$type = $3;
		$division = $4;
		/DEFINITION\s+(\w[^\^]+)\.\nACCESSION/ or "DEFINITION unmatched for $locus \n";
		$definition = $1;
		$definition =~ s/\n\s+/ /g;
		/VERSION\s+(\S+)\s+GI:(\d+)/ or "VERSION unmatched for $locus \n";
		$accession = $1;
		$gi = $2;
		/ORGANISM\s+(\w.+)\n/ or "ORGANISM unmatched for $locus \n";
		$organism = $1;
		$dbsource = gb;
		$dbsource = emb if /Center code: SC/;
		/ORIGIN[^\n]*\n(.+)$/s or "ORIGIN unmatched for $locus \n";
		$seq = $1;
		$seq =~ tr/tcag//cd;
		$seq =~ s/(.{60})/$1\n/g;

		if ($organism eq 'Danio rerio' && !$found_danio) {
			print "Found organism data for Danio rerio in $gbfile\n";
			$found_danio = 1;

			#exit with success code if found zebrafish, but keep looking if found mouse or human
			exit(0);
		}elsif ($organism eq 'Mus musculus' && !$found_mouse) {
			print "Found organism data for Mus musculus in $gbfile\n";
			$found_mouse = 1;
		}elsif ($organism eq 'Homo sapiens' && !$found_human) {
			print "Found organism data for Homo sapiens in $gbfile\n";
			$found_human = 1;
		}else {
			# print("Organism is $organism\n");
		}
	}
    close(IN);
  }

#exit with success code if found organism
$found_relevant_organism = $found_danio || $found_human || $found_mouse;

exit(!$found_relevant_organism);

