package org.zfin.publication;

import com.fasterxml.jackson.annotation.JsonView;
import lombok.Getter;
import lombok.Setter;
import org.zfin.framework.api.View;
import org.zfin.profile.Person;

import javax.persistence.*;
import java.util.Date;

@Setter
@Getter
@Entity
@Table(name = "pub_correspondence_needed")
public class CorrespondenceNeeded {
    @JsonView(View.API.class)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pcn_pk_id")
    private long id;

    @JsonView(View.API.class)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pcn_pub_zdb_id")
    private Publication publication;

    @JsonView(View.API.class)
    @ManyToOne
    @JoinColumn(name = "pcn_pcnr_id")
    private CorrespondenceNeededReason reason;

}
