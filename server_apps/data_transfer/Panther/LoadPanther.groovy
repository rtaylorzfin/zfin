#!/bin/bash
//opt/misc/groovy/bin/groovy -cp "$GROOVY_CLASSPATH" "$0" $@; exit $?
import org.zfin.properties.ZfinProperties
import org.zfin.util.ReportGenerator
import static com.xlson.groovycsv.CsvParser.parseCsv


ZfinProperties.init("${System.getenv()['ZFIN_PROPERTIES_PATH']}")
// ZFIN-10433: PANTHER retired ftp.pantherdb.org -- the host stopped answering
// on port 21 at the turn of the year, so this download hung until the JVM gave
// up with "Connection timed out" and the job had failed every night since.
// The same files are served from data.pantherdb.org over HTTPS, under an /ftp
// prefix that mirrors the old FTP tree. Note the https:// rather than http://:
// the plain-http URL 301s to https, and URL.openStream() does not follow a
// redirect that changes protocol -- it would hand back the redirect body.
DOWNLOAD_URL = "https://data.pantherdb.org/ftp/sequence_classifications/12.0/PANTHER_Sequence_Classification_files/PTHR12.0_zebrafish"
def file = new FileOutputStream(DOWNLOAD_URL.tokenize("/")[-1])
def out = new BufferedOutputStream(file)
// Explicit timeouts so an unreachable host fails in a minute with a clear
// message instead of hanging on the JVM default (none) as the FTP host did.
def connection = new URL(DOWNLOAD_URL).openConnection()
connection.connectTimeout = 30_000
connection.readTimeout = 120_000
try {
    out << connection.inputStream
} catch (IOException e) {
    throw new RuntimeException("PANTHER download failed: $DOWNLOAD_URL -- $e.message", e)
}
out.close()

static Process dbaccess(String dbname, String sql) {
    sql = sql.replace("\n", "")
    sql = sql.replace("\\copy", "\n  \\copy")
    println sql

    def proc
    proc = "psql -v ON_ERROR_STOP=1 -d $dbname -a".execute()
    proc.getOutputStream().with {
        write(sql.bytes)
        close()
    }
    proc.waitFor()
    proc.getErrorStream().eachLine { println(it) }
    if (proc.exitValue()) {
        throw new RuntimeException("dbaccess call failed")
    }
    proc
}

static Process psql(String dbname, String sql) {
    return dbaccess(dbname, sql)
}


println "done"
File inputFile = new File("PTHR12.0_zebrafish")
OUTFILE = "panther.unl"
PRE_FILE = "prepanther.unl"
POST_FILE = "postpanther.unl"


def pantherIDs = parseCsv(new FileReader(inputFile), separator: '|')
new File(OUTFILE).withWriter { outFile ->
    pantherIDs.each { csv ->

        def zfinID = csv[1].substring(csv[1].lastIndexOf('=') + 1)
        def pantid = csv[2].split('\t')
        def colon = (pantid[2].indexOf(':'))
        def panthid = pantid[2]
        def pantherID = panthid.substring(0, colon)
        def fdbcontid = 'ZDB-FDBCONT'
        if (zfinID.startsWith('ZDB')) {
            outFile.writeLine("$zfinID|$zfinID|$pantherID|$fdbcontid")
        }

    }

}
dbname = System.getenv("DBNAME")
// Update the PANTHER links in place instead of deleting and re-inserting all of them,
// so an unchanged (gene, PANTHER ID) link keeps its ZDB ID from month to month:
// links no longer in the file are deleted through zdb_active_data (which cascades to
// db_link and record_attribution, rather than leaving orphan IDs behind), only new
// pairs get new IDs, and every PANTHER link ends up attributed to the load pub.
// No -- comments inside the SQL below: dbaccess() joins it onto one line.
println("Loading terms into $dbname")


psql dbname, """

\\copy (SELECT dblink_linked_recid,dblink_acc_num FROM db_link where dblink_fdbcont_zdb_id=(select fdbcont_zdb_id from foreign_db_contains where fdbcont_fdb_db_id=65)) TO $PRE_FILE;

  CREATE TEMP TABLE tmp_terms(
    dblinkid text,
    id text,
    name text,
    fdbcontid text
  ) ;

\\copy tmp_terms FROM '$OUTFILE' delimiter '|' ;


update tmp_terms set fdbcontid = (select fdbcont_zdb_id from foreign_db_contains where fdbcont_fdb_db_id=65);

delete from tmp_terms where id not in (select mrkr_zdb_id from marker where mrkr_type='GENE');

CREATE TEMP TABLE tmp_new AS SELECT DISTINCT id, name, fdbcontid FROM tmp_terms;

delete from zdb_active_data where zactvd_zdb_id in (
  select dblink_zdb_id from db_link
   where dblink_fdbcont_zdb_id=(select fdbcont_zdb_id from foreign_db_contains where fdbcont_fdb_db_id=65)
     and not exists (select 1 from tmp_new where id = dblink_linked_recid and name = dblink_acc_num));

delete from tmp_new where exists (
  select 1 from db_link
   where dblink_linked_recid = id and dblink_acc_num = name and dblink_fdbcont_zdb_id = fdbcontid);

ALTER TABLE tmp_new ADD COLUMN dblinkid text;
update tmp_new set dblinkid = get_id('DBLINK');

insert into zdb_active_data select dblinkid from tmp_new;

insert into db_link (dblink_linked_recid,dblink_acc_num, dblink_zdb_id ,dblink_acc_num_display,dblink_fdbcont_zdb_id)
  select id,name,dblinkid,name, fdbcontid
    from tmp_new ;

insert into record_attribution (recattrib_data_zdb_id, recattrib_source_zdb_id)
  select dblink_zdb_id,'ZDB-PUB-170810-14' from db_link
   where dblink_fdbcont_zdb_id=(select fdbcont_zdb_id from foreign_db_contains where fdbcont_fdb_db_id=65)
     and not exists (select 1 from record_attribution
                      where recattrib_data_zdb_id = dblink_zdb_id and recattrib_source_zdb_id = 'ZDB-PUB-170810-14');


\\copy (SELECT dblink_linked_recid,dblink_acc_num FROM db_link where dblink_fdbcont_zdb_id=(select fdbcont_zdb_id from foreign_db_contains where fdbcont_fdb_db_id=65)) TO $POST_FILE;
"""

if (args) {
    // means we're (probably) running from Jenkins, so make a report.
    preLines = new File(PRE_FILE).readLines()
    postLines = new File(POST_FILE).readLines()

    added = postLines - preLines
    removed = preLines - postLines

    new ReportGenerator().with {
        setReportTitle("Report for ${args[0]}")
        includeTimestamp()
        addDataTable("${added.size()} terms added", ["ID", "Term"], added.collect { it.split("\\|") as List })
        addDataTable("${removed.size()} terms removed", ["ID", "Term"], removed.collect { it.split("\\|") as List })
        writeFiles(new File("."), "loadPantherReport")
    }
}
