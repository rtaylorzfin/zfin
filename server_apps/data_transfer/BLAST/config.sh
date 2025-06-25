BLAST_PATH="/opt/zfin/blastdb"
WEBHOST_FASTA_FILE_PATH="/research/zblastfiles/dev_files"

log_message() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "refseq_process.log"
}

# Add function for error handling
error_exit() {
    echo "ERROR: $1" >&2
    exit 1
}
