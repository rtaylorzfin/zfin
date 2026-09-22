#!/bin/bash
//usr/bin/env groovy -cp "$GROOVY_CLASSPATH:." "$0" $@; exit $?

import org.zfin.properties.ZfinProperties
import org.zfin.properties.ZfinPropertiesEnum

ZfinProperties.init("${System.getenv()['ZFIN_PROPERTIES_PATH']}")
// GO's DERIVED mapping, not the flat one (ZFIN-10464). The flat file carries only the 26
// high-level equivalence mappings; the derived file propagates a GO evidence code down to child
// ECO terms and carries 1,422. Measured 2026-09-22: it is a strict superset -- all 26 flat
// mappings appear in it verbatim -- so nothing currently mapped can be lost. The gap this closes
// for the DANRE-mod load is ECO:0005547 -> NAS, the last code the file uses that we cannot map.
//
// The PURL, not the GitHub raw URL: GO's own header says "Always use this URL".
DOWNLOAD_URL = "http://purl.obolibrary.org/obo/eco/gaf-eco-mapping-derived.txt"
final WORKING_DIR = new File("${ZfinPropertiesEnum.TARGETROOT}/server_apps/data_transfer/eco_go_mapping")
WORKING_DIR.mkdirs()

// both files have to land in WORKING_DIR: ant runs this script with its working directory in
// SOURCEROOT, but insert_eco_go_map.sql \copy's gafeco.txt out of TARGETROOT
// Follow redirects by hand. The PURL answers 302 to an https:// URL, and HttpURLConnection
// refuses to follow a redirect that changes protocol -- new URL(...).openStream() therefore
// returns the 9-line HTML redirect page, not the mapping file. Four of those lines parse as
// whitespace-separated pairs, so the old "bail if zero mappings" check passed and the load would
// have replaced eco_go_mapping's contents with junk. Observed, not theorised.
InputStream openFollowingRedirects(String url) {
    String current = url
    for (int hop = 0; hop < 5; hop++) {
        HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection()
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        int code = conn.responseCode
        if (code in [301, 302, 303, 307, 308]) {
            String location = conn.getHeaderField("Location")
            conn.disconnect()
            if (!location) {
                throw new IOException("$current returned $code with no Location header")
            }
            current = new URL(new URL(current), location).toString()   // resolve relative redirects
            continue
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("$current returned HTTP $code")
        }
        return conn.inputStream
    }
    throw new IOException("too many redirects starting from $url")
}

File downloadedFile = new File(WORKING_DIR, DOWNLOAD_URL.tokenize("/")[-1])
def out = new BufferedOutputStream(new FileOutputStream(downloadedFile))
out << openFollowingRedirects(DOWNLOAD_URL)
out.close()

File outputFile = new File(WORKING_DIR, "gafeco.txt")

// COLUMN ORDER IS REVERSED FROM THE FLAT FILE.
//   flat     CODE <tab> Default <tab> ECO      -> code was [0], eco was [2]
//   derived  ECO  <tab> CODE    <tab> [Default] -> eco is  [0], code is [1]
// Indexing [2] would break outright: 1,396 of the 1,422 rows have an empty third column.
// The derived file's own header comment still documents the OLD order and is wrong; this
// follows the data. Note split() on whitespace also drops the trailing empty field, so a row
// without "Default" yields a 2-element array -- hence the length check rather than [2].
mappingCount = 0
defaultCount = 0
outputFile.withWriter { outFile ->
    downloadedFile.withReader {
        reader ->
            while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("#") && !line.trim().isEmpty()) {
                        fields = line.split()
                        if (fields.length < 2) {
                            continue
                        }
                        eco_term = fields[0]
                        evidence_code = fields[1]
                        // Validate the shape rather than just the row count. A redirect stub or
                        // an error page yields lines that split into two fields perfectly well;
                        // what it cannot do is put an ECO CURIE in the first column.
                        if (!eco_term.startsWith("ECO:")) {
                            throw new IOException("${downloadedFile.name}: expected an ECO id in column 1, got [$eco_term] on line: $line")
                        }
                        // "Default" marks an ECO term equivalent to the GO code, i.e. one of the
                        // 26 mappings the flat file used to carry. Passed through so the loader
                        // can use it to break a tie when one ECO term maps to several codes.
                        is_default = (fields.length > 2 && fields[2] == "Default") ? "Default" : ""
                        if (is_default) {
                            defaultCount++
                        }
                        outFile.writeLine("$evidence_code,$eco_term,$is_default")
                        mappingCount++
                    }
            }
    }
}
// One summary line rather than 1,422 -- the flat file was small enough to echo, this is not.
println("parsed $mappingCount ECO->GO mappings ($defaultCount marked Default) from ${downloadedFile.name}")

// Bail rather than \copy a short file into a load that would then report success: a truncated
// or error-page download has to be a failure, not a no-op. The floor is well below the 1,422
// GO publishes and well above anything a stub or a partial transfer produces -- the old check
// was `== 0`, which a 4-line redirect page walked straight through.
MINIMUM_EXPECTED_MAPPINGS = 500
if (mappingCount < MINIMUM_EXPECTED_MAPPINGS) {
    System.err.println("Only $mappingCount mappings parsed out of ${downloadedFile.absolutePath}, expected at least "
        + "$MINIMUM_EXPECTED_MAPPINGS -- refusing to run the load. If GO has genuinely shrunk the file, lower the floor deliberately.")
    System.exit(1)
}

givePubsPermissions = ['/bin/bash', '-c', "${ZfinPropertiesEnum.PGBINDIR}/psql -v ON_ERROR_STOP=1 " +
        "${ZfinPropertiesEnum.DB_NAME} -f ${WORKING_DIR.absolutePath}/insert_eco_go_map.sql " +
        ">${WORKING_DIR.absolutePath}/loadSQLOutput.log 2> ${WORKING_DIR.absolutePath}/loadSQLError.log"].execute()
givePubsPermissions.waitFor()
if (givePubsPermissions.exitValue() != 0) {
    // psql's stderr went to the log file, so surface it -- an unchecked exit value here made
    // a failed insert look like a successful load
    System.err.println("insert_eco_go_map.sql failed:")
    System.err.println(new File(WORKING_DIR, "loadSQLError.log").text)
    System.exit(1)
}
