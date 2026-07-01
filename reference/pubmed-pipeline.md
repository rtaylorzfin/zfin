# PubMed Publication Pipeline

## Overview

ZFIN automatically discovers, loads, and enriches zebrafish publications from
PubMed/PMC via a set of daily, weekly, and monthly Jenkins jobs. Each job runs
a Groovy script in `server_apps/data_transfer/PUBMED/`.

## Pipeline Flow

```
Daily:
  Fetch-Pubs-From-Pubmed_d       find new zebrafish pubs in PubMed
      |
      v
  Update-Pub-Date_d              fill in publication dates from PubMed
  Update-PMC-Ids_d               add PMC IDs (enables PDF/image download)
  Get-PDFsAndImages_d            download PDFs and images from PMC S3

Weekly:
  Update-Pub-Status_w            activate inactive publications
  Update-Pub-GEO-IDs_w           link GEO accessions to publications
  Check-And-Update-Journals_w    journal record maintenance

Monthly:
  Update-Pub-MeSH-Terms_m        add MeSH terms to publications missing them

Manual:
  Fetch-Pubs-From-Pubmed-By-Accession   load specific PubMed IDs on demand
```

## Job Details

### Fetch-Pubs-From-Pubmed_d

**Script**: `fetchPubsFromPubMed.groovy`
**SQL**: `loadNewPubs.sql`

Searches PubMed for:
```
zebrafish[TW] OR "zebra fish"[TW] OR "danio rerio"[ALL]
```
over the last 500 days. Inserts new publications (by PubMed ID) into the
`publication` table, creates journal records if needed, and loads MeSH terms.
On success, triggers `Update-Pub-Date_d`.

### Update-PMC-Ids_d

**Script**: `addPMCidsToAllPubs.groovy`

Finds publications missing PMC IDs, queries PubMed in batches of 2000, and
updates `pub_pmc_id`. This enables `Get-PDFsAndImages_d` to fetch assets.

### Get-PDFsAndImages_d

**Script**: `getPDFandImages.groovy`
**Utils**: `PubmedUtils.groovy`
**SQL**: `give_pubs_permissions.sql`, `add_basic_pdfs.sql`,
`load_figs_and_images.sql`, `load_pub_files.sql`

Downloads PDFs and figure images from the PMC Open Access S3 bucket
(`pmc-oa-opendata`). Accepts an optional `PUB_ZDB_IDs` parameter to process
specific publications; otherwise queries the DB for all publications that have
a PMC ID but no publication_file or figure records.

**How it works:**

1. Query DB for publications needing PDFs/images
2. For each publication, list files in S3 at `s3://pmc-oa-opendata/{PMCID}.*/`
3. If no downloadable files (images/PDFs), record as non-open-access and skip
4. Download images and PDFs from S3
5. Rename PDF to `{ZDB-PUB-ID}.pdf`
6. Fetch OAI XML metadata for figure labels and captions
7. Create thumbnail and medium-sized images via ImageMagick
8. Write load files: `figsToLoad.txt`, `pdfBasicFilesToLoad.txt`, etc.
9. Run SQL scripts to load records into the database
10. Update `pub_can_show_images` for open-access publications

**S3 bucket notes** (as of 2026):
- Bucket: `pmc-oa-opendata` in `us-east-1`, no auth required
- Open-access articles have images, PDFs, and text files
- Non-open-access (NIH manuscript) articles only have json/txt/xml
- Image filenames in S3 match the `xlink:href` names in OAI XML
- Articles may have multiple versions; the script uses the latest
- S3 is the sole source for PDFs/images; no FTP/oa.fcgi fallback remains in the code (`PubmedUtils.groovy` only references `pmc-oa-opendata.s3.amazonaws.com`)

### Update-Pub-Date_d

**Script**: `updatePublicationDate.groovy`

Fills in `pub_date` for publications with null dates by fetching from PubMed.
Handles multiple date formats (PubDate, ArticleDate, MedlineDate).

### Update-Pub-Status_w

**Script**: `pubActivation.groovy`

Checks inactive publications against PubMed. If PubMed reports the publication
as `ppublish` or `epublish`, updates status to active and sets tracking to
`READY_FOR_PROCESSING`. Also extracts PMC IDs and manuscript IDs.

### Update-Pub-MeSH-Terms_m

**Script**: `addMeshTermsToAllPubs.groovy`

Finds publications without MeSH terms, fetches from PubMed in batches of 2000,
and loads descriptors and qualifiers into `mesh_heading` /
`mesh_heading_qualifier` tables.

### Update-Pub-GEO-IDs_w

**Script**: `addGeoIdsToAllPubs.groovy`

Queries NCBI GDS for `"Danio rerio"[Organism] AND "gse"[Filter]`, links GEO
accessions to PubMed IDs, and loads into `pub_db_xref`.

## Image Permissions Model

Publications have a `pub_can_show_images` boolean that controls whether ZFIN
displays figures from that publication.

- **Default**: Set on INSERT by a trigger that reads `jrnl_is_nice` from the
  journal table (journal-level permission).
- **Updated by** `getPDFandImages.groovy`: When OAI metadata contains setSpec
  `pmc-open` or `npgopen`, the publication is added to
  `pubsToGivePermission.txt` and `give_pubs_permissions.sql` sets
  `pub_can_show_images = true`.
- **Used by**: `load_figs_and_images.sql` only loads figures for publications
  where `pub_can_show_images = true`. Java repositories also filter on this
  field when displaying expression data and figures.

## NCBI API Key

Scripts that call eutils (efetch, esearch) support an NCBI API key for higher
rate limits. The key is stored via TokenStorage:

```bash
zfin-util token-storage write NCBI_API_TOKEN <your-key>
```

PMC OAI and S3 endpoints do not use an API key.

## Key File Locations

| Path | Purpose |
|------|---------|
| `server_apps/data_transfer/PUBMED/*.groovy` | Pipeline scripts |
| `server_apps/data_transfer/PUBMED/*.sql` | SQL load scripts |
| `server_apps/data_transfer/PUBMED/Journal/` | Journal management |
| `server_apps/data_transfer/PUBMED/LinkOut/` | NCBI LinkOut uploads |
| `server_apps/jenkins/jobs/` | Jenkins job configs |
