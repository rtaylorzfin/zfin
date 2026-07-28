-- Schema additions needed by the NCBI load code but not present in the minimal test database dump.
-- Sourced from: source/org/zfin/db/postGmakePostloaddb/1179/ZFIN-10082.sql

CREATE SCHEMA IF NOT EXISTS external_resource;

CREATE TABLE IF NOT EXISTS external_resource.ncbi_danio_rerio_gene_info (
    id              bigserial PRIMARY KEY,
    tax_id          text,
    gene_id         text NOT NULL,
    symbol          text,
    locus_tag       text,
    synonyms        text,
    db_xrefs        text,
    chromosome      text,
    map_location    text,
    description     text,
    type_of_gene    text,
    symbol_from_nomenclature_authority       text,
    full_name_from_nomenclature_authority    text,
    nomenclature_status     text,
    other_designations      text,
    modification_date       text,
    feature_type            text
);

CREATE INDEX IF NOT EXISTS idx_ncbi_gene_info_gene_id ON external_resource.ncbi_danio_rerio_gene_info (gene_id);
CREATE INDEX IF NOT EXISTS idx_ncbi_gene_info_symbol ON external_resource.ncbi_danio_rerio_gene_info (symbol);

CREATE TABLE IF NOT EXISTS load_file_log (
    lfl_id bigserial PRIMARY KEY,
    lfl_date timestamp,
    lfl_download_date timestamp,
    lfl_filename text,
    lfl_load_name text,
    lfl_md5 text,
    lfl_notes text,
    lfl_path text,
    lfl_processed_date timestamp,
    lfl_release_number text,
    lfl_size bigint,
    lfl_source text,
    lfl_table_name varchar(255)
);

-- Add lfl_table_name column if it doesn't exist (backup may predate migration ZFIN-10082)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'load_file_log' AND column_name = 'lfl_table_name'
    ) THEN
        ALTER TABLE load_file_log ADD COLUMN lfl_table_name varchar(255);
    END IF;
END $$;

CREATE OR REPLACE VIEW external_resource.ncbi_danio_rerio_gene_info_zfin AS
    SELECT gene_id AS ncbi_id, replace(t.xref, 'ZFIN:', '') AS zdb_id
    FROM external_resource.ncbi_danio_rerio_gene_info, unnest(string_to_array(db_xrefs, '|')) AS t (xref)
    WHERE t.xref LIKE 'ZFIN:%';

CREATE OR REPLACE VIEW external_resource.ncbi_danio_rerio_gene_info_ensembl AS
    SELECT gene_id AS ncbi_id, replace(t.xref, 'Ensembl:', '') AS ensembl_id, symbol
    FROM external_resource.ncbi_danio_rerio_gene_info, unnest(string_to_array(db_xrefs, '|')) AS t (xref)
    WHERE t.xref LIKE 'Ensembl:%';

CREATE TABLE IF NOT EXISTS external_resource.ncbi_gene2accession (
    id                          bigserial PRIMARY KEY,
    gene_id                     text NOT NULL,
    status                      text,
    rna_accession               text,
    rna_accession_versioned     text,
    protein_accession           text,
    protein_accession_versioned text,
    genomic_accession           text,
    genomic_accession_versioned text
);

CREATE INDEX IF NOT EXISTS idx_ncbi_g2a_gene_id ON external_resource.ncbi_gene2accession (gene_id);
CREATE INDEX IF NOT EXISTS idx_ncbi_g2a_rna_acc ON external_resource.ncbi_gene2accession (rna_accession);
CREATE INDEX IF NOT EXISTS idx_ncbi_g2a_protein_acc ON external_resource.ncbi_gene2accession (protein_accession);

CREATE TABLE IF NOT EXISTS external_resource.ncbi_refseq_catalog (
    id                     bigserial PRIMARY KEY,
    accession              text NOT NULL,
    accession_versioned    text,
    length                 integer
);

CREATE INDEX IF NOT EXISTS idx_ncbi_rsc_accession ON external_resource.ncbi_refseq_catalog (accession);
