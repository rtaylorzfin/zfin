package org.zfin.uniprot.persistence;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "uniprot_release")
@Getter
@Setter
public class UniProtRelease {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long upr_id;

    @Column(name = "upr_date")
    private Date date;

    @Column(name = "upr_size")
    private Long size;

    @Column(name = "upr_md5")
    private String md5;

    @Column(name = "upr_path")
    private String path;

    @Column(name = "upr_download_date")
    private Date downloadDate;

    @Column(name = "upr_release_number")
    private String releaseNumber;

    @Column(name = "upr_notes")
    private String notes;

}
