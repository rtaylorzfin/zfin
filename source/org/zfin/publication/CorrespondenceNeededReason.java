package org.zfin.publication;

import com.fasterxml.jackson.annotation.JsonView;
import lombok.Getter;
import lombok.Setter;
import org.zfin.framework.api.View;

import javax.persistence.*;

@Setter
@Getter
@Entity
@Table(name = "pub_correspondence_needed_reason")
public class CorrespondenceNeededReason {

    @JsonView(View.API.class)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pcnr_pk_id")
    private long id;

    @JsonView(View.API.class)
    @Column(name = "pcnr_name")
    private String name;

    @Column(name = "pcnr_order")
    private long order;

}
