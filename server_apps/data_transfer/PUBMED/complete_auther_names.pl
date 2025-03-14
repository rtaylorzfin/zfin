#! /opt/zfin/bin/perl -w
# complete_auther_names.pl
# load pubmed_publication_author with auther data from pubmed

use DBI;
use XML::Twig;
use utf8;
use Try::Tiny;
use FindBin;
use lib "$FindBin::Bin/../../perl_lib/";
use ZFINPerlModules qw(assertEnvironment);
assertEnvironment('PGHOST', 'DB_NAME');

$dbhost = $ENV{'PGHOST'};
$dbname = $ENV{'DB_NAME'};
$username = "";
$password = "";

system("/bin/date");
system("/bin/rm -f authors");

### open a handle on the db
$dbh = DBI->connect ("DBI:Pg:dbname=$dbname;host=$dbhost", $username, $password) or die "Cannot connect to database: $DBI::errstr\n";

$sql = "select distinct zdb_id, accession_no, title
          from publication 
         where accession_no is not null 
           and title is not null
           and not exists (select 1 from pubmed_publication_author
                            where ppa_pubmed_id = accession_no::text 
                              and ppa_publication_zdb_id = zdb_id);";

my $cur = $dbh->prepare($sql);
$cur ->execute();

my ($pubZdbId, $accession, $pubTitle);

$cur->bind_columns(\$pubZdbId,\$accession,\$pubTitle);

%pmids = ();
      
while ($cur->fetch()) {
   $pmids{$accession} = $pubZdbId;
}

$cur->finish(); 


$ctTotal = 0;

open(my $AUTHOR, ">:encoding(utf-8)", "authors") || die "Cannot open authors : $!\n";

%uniqueNames = ();

foreach $pmid (sort keys %pmids) {
  print("Processing $pmid at ".localtime()."\n");
  $pubZdbId = $pmids{$pmid};
  
  $url = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pubmed&api_key=47c9eadd39b0bcbfac58e3e911930d143109&id=".$pmid."&retmode=xml";
  try {
    $twig = XML::Twig->nparse($url);
  } catch {
    warn "Failed to parse $url - $_";
    exit -1;
  };
  
  if ($twig) {
    $root = $twig->root;
    $authListElmt = $root->first_descendant('AuthorList');
  
    if ($authListElmt) {
      @authors = $authListElmt->children;
      foreach $author (@authors) {
        $lastNameElm = $author->first_child('LastName');
        if ($lastNameElm) {
          $lastName = $lastNameElm->text;
          $firstNameElm = $author->first_child('ForeName');
          $firstName = $firstNameElm ? $firstNameElm->text : "";
          $middleNameElm = $author->first_child('Initials');
          $middleName = $middleNameElm ? $middleNameElm->text : "";
        
          if (!exists($uniqueNames{$pmid.$lastName.$middleName.$firstName})) {      
            print $AUTHOR "$pmid|$pubZdbId|$lastName|$middleName|$firstName\n";
            $uniqueNames{$pmid.$lastName.$middleName.$firstName} = $pubZdbId;
            $ctTotal++;
          }
        }   
      }
    } 
  }
}

$dbh->disconnect();


close $AUTHOR;

try {
  ZFINPerlModules->doSystemCommand("psql -v ON_ERROR_STOP=1 -d $dbname -a -f load_complete_author_names.sql");
} catch {
  warn "Failed to execute load_complete_author_names.sql - $_";
  exit -1;
};

system("/bin/date");

print "\n$ctTotal author names added.\n";

exit;

