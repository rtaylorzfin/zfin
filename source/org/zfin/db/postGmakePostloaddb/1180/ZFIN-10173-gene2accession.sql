--liquibase formatted sql
--changeset rtaylor:ZFIN-10173-gene2accession.sql

CREATE TABLE external_resource.ncbi_gene2accession (
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

COMMENT ON TABLE external_resource.ncbi_gene2accession IS
    'Mirror of NCBI gene2accession file (Danio rerio rows only). Source: https://ftp.ncbi.nlm.nih.gov/gene/DATA/gene2accession.gz';

CREATE INDEX idx_ncbi_g2a_gene_id ON external_resource.ncbi_gene2accession (gene_id);
CREATE INDEX idx_ncbi_g2a_rna_acc ON external_resource.ncbi_gene2accession (rna_accession);
CREATE INDEX idx_ncbi_g2a_protein_acc ON external_resource.ncbi_gene2accession (protein_accession);

CREATE TABLE external_resource.ncbi_refseq_catalog (
    id                     bigserial PRIMARY KEY,
    accession              text NOT NULL,
    accession_versioned    text,
    length                 integer
);

COMMENT ON TABLE external_resource.ncbi_refseq_catalog IS
    'Mirror of NCBI RefSeq catalog file (Danio rerio rows only). Source: https://ftp.ncbi.nlm.nih.gov/refseq/release/release-catalog/';

CREATE INDEX idx_ncbi_rsc_accession ON external_resource.ncbi_refseq_catalog (accession);
