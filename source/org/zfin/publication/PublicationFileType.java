package org.zfin.publication;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import org.hibernate.annotations.*;
import org.zfin.framework.api.View;

import jakarta.persistence.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "publication_file_type")
public class PublicationFileType implements Comparable<PublicationFileType> {

    public enum Name {
        ORIGINAL_ARTICLE("Original Article"),
        ANNOTATED_ARTICLE("Annotated Article"),
        SUPPLEMENTAL_MATERIAL("Supplemental Material"),
        CORRESPONDENCE_DETAILS("Correspondence Details"),
        OTHER("Other");

        private String display;

        Name(String display) {
            this.display = display;
        }

        @Override
        @JsonValue
        public String toString() {
            return display;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pft_pk_id")
    @JsonView(View.Default.class)
    private long id;

    @Column(name = "pft_type")
    @Type(value = org.zfin.framework.StringEnumValueUserType.class, parameters = {
            @org.hibernate.annotations.Parameter(name = "enumClassname", value = "org.zfin.publication.PublicationFileType$Name")
    })
    @JsonView(View.Default.class)
    private Name name;

    @Column(name = "pft_type_order")
    private int order;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Name getName() {
        return name;
    }

    public void setName(Name name) {
        this.name = name;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    @Override
    public int compareTo(PublicationFileType o) {
        return Integer.compare(order, o.getOrder());
    }
}
