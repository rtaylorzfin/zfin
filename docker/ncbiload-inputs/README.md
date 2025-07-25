How to Characterize NCBI LOAD
===

This is a temporary structure. We should delete later if it ever makes it into main branch (which it shouldn't).

The goal of this folder is to provide inputs for running a reproducible version of our ncbi load so we can
characterize different versions of it.

To do so, create a directory in ./set1 (or set2 or set3 ...) with the following files:

- zfindbdump.bak
- RefSeqCatalog.gz
- gene2vega.gz
- seq.fasta
- RELEASE_NUMBER
- gene2accession.gz
- ncbi_matches_through_ensembl.csv
- zf_gene_info.gz

The ".bak" file should be copied to ../ncbiload for building the docker image. It can be any .bak file from our archives
(eg. /research/zunloads/databases/zfindb/2025.07.01.1/2025.07.01.1.bak).

The other files can be found on /research/zarchive/load_files/NCBI-gene-load-archive/2025-05-09/ (for example).


### Build NCBI Load Docker Image

To build the docker image for NCBI Load, run the following command (after copying the bak file to the correct location):
```
docker compose build ncbiload
```


### Next steps:

Run the perl version of ncbi load:
```
docker compose run --build --rm -it ncbiload bash -lc 'cd $SOURCEROOT/server_apps/data_transfer/NCBIGENE; cp /tmp/inputs/set1/* .; EARLY_EXIT=1 OVERRIDE_JAVA_HOME=$JAVA_HOME EMAIL_TO_FILE=true NO_SLEEP=1 SKIP_DOWNLOADS=1 LOAD_NCBI_ONE_WAY_GENES=true DB_NAME=zfindb TARGETROOT=$SOURCEROOT ROOT_PATH=$SOURCEROOT perl NCBI_gene_load.pl'
```

Run the java version of ncbi load:
```
docker compose run --build --rm -it ncbiload bash -lc 'export WORKING_DIR=$SOURCEROOT/server_apps/data_transfer/NCBIGENE; cp /tmp/inputs/set1/* $WORKING_DIR ; EARLY_EXIT=1 EMAIL_TO_FILE=true NO_SLEEP=1 SKIP_DOWNLOADS=1 LOAD_NCBI_ONE_WAY_GENES=true DB_NAME=zfindb TARGETROOT=$SOURCEROOT ROOT_PATH=$SOURCEROOT gradle ncbiLoadPort'
```

### Comparing results

You can store the results of a run by copying everything in server_apps/data_transfer/NCBIGENE/ into a directory for storage. Perhaps name it something like `docker/ncbiload-outputs/java-run-2025-06-17-17-44/`.

Compare the .unl files between each run to see if they are the same. You can also look at the after_load.csv files for comparing the results.

### Shorter run:

To save time, you can run with the flag EARLY_EXIT=1 and it will quit before running the .unl files through the database sql load logic.

