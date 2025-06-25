#!/bin/bash

# assume we are in the BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily directory already
# cd BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily

source ../../config.sh

echo "== Switch wu-db link back to Current dir =="
rm $BLAST_PATH/wu-db
ln -s $BLAST_PATH/Current $BLAST_PATH/wu-db

# only to the distributeToNodes bit on Genomix
# TODO: do we need to distribute to nodes on other machines?
#if (@HOSTNAME@ == genomix.cs.uoregon.edu) then
#    @TARGET_PATH@/GenBank/distributeToNodesGenBank.sh
#endif

cd $BLAST_PATH/Current ;


#===========================
#TODO: what are these files doing?
echo "== Clear up the files in BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily == "
rm -f Last/*
mv nc*.flat Last
rm -f nc????_*_*.fa

echo "== Done with weeklyPushGenBank.sh =="

exit
