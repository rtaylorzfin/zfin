#!/bin/bash

# Presumably already in the fasta/GB_daily directory
# cd BLASTSERVER_FASTA_FILE_PATH/fasta/GB_daily
source ../../config.sh

echo "== Clean up old nc files and merge logs ==" 
rm -f nc_*
rm -f merge_nc_*
rm -f ../GenBank/xdformat*.log

echo "== Scp over daily files from Embryonix =="
cp $WEBHOST_FASTA_FILE_PATH/daily/nc????_*_*.fa .
cp $WEBHOST_FASTA_FILE_PATH/daily/nc????.flat .

echo "done with weeklyCpGenBank.sh" 

exit
