#!/bin/bash -e
#
# Download GB release
# 
SCRIPT_DIR=$(dirname "$0")
source "../config.sh"
cd ../fasta/

#-----------------------------
# Clean up previous log 
#-----------------------------
rm -f GenBank/xdformat*.log
mv GB/*.fa ../Backup
rm -rf GB/ftp.ncbi.nih.gov
rm -rf GB
mkdir GB
cd GB

#---------------------
# Download 
#---------------------
echo "==| DOWNLOAD GenBank Release |=="

$SCRIPT_DIR/loadGBdiv.sh est;

$SCRIPT_DIR/loadGBdiv.sh gss;

$SCRIPT_DIR/loadGBdiv.sh htc;

$SCRIPT_DIR/loadGBdiv.sh htg;

$SCRIPT_DIR/loadGBdiv.sh sts;

$SCRIPT_DIR/loadGBdiv.sh pri;

$SCRIPT_DIR/loadGBdiv.sh rod;

$SCRIPT_DIR/loadGBdiv.sh vrt;

#---------------------------
# Clean up intermediate data 
#----------------------------
echo "==| rm ftp file GenBank |=="
rm -rf ftp.ncbi.nih.gov


echo "==| done with GenBank download |=="

exit
