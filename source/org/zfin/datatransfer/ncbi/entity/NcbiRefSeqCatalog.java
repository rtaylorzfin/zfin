package org.zfin.datatransfer.ncbi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
@Table(schema = "external_resource", name = "ncbi_refseq_catalog")
public class NcbiRefSeqCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "accession", nullable = false)
    private String accession;

    @Column(name = "accession_versioned")
    private String accessionVersioned;

    @Column(name = "length")
    private Integer length;
}
