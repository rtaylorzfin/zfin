package org.zfin.datatransfer.ncbi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
@Table(schema = "external_resource", name = "ncbi_gene2accession")
public class NcbiGene2Accession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "gene_id", nullable = false)
    private String geneId;

    @Column(name = "status")
    private String status;

    @Column(name = "rna_accession")
    private String rnaAccession;

    @Column(name = "rna_accession_versioned")
    private String rnaAccessionVersioned;

    @Column(name = "protein_accession")
    private String proteinAccession;

    @Column(name = "protein_accession_versioned")
    private String proteinAccessionVersioned;

    @Column(name = "genomic_accession")
    private String genomicAccession;

    @Column(name = "genomic_accession_versioned")
    private String genomicAccessionVersioned;
}
