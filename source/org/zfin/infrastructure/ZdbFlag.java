package org.zfin.infrastructure;

import jakarta.persistence.*;
import java.util.Date;

/**
 * This class maps to the flag table that is used to
 * indicate if a process is currently running to avoid
 * concurrent execution of the same process or processes
 * that should not be run concurrently.
 */
@Entity
@Table(name = "zdb_flag")
public class ZdbFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "zflag_name")
    @org.hibernate.annotations.Type(value = org.zfin.framework.StringEnumValueUserType.class,
            parameters = {@org.hibernate.annotations.Parameter(name = "enumClassname", value = "org.zfin.infrastructure.ZdbFlag$Type")})
    private Type type;
    @Column(name = "zflag_is_on")
    private boolean systemUpdateDisabled;
    @Column(name = "zflag_last_modified")
    private Date dateLastModified;

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public boolean isSystemUpdateDisabled() {
        return systemUpdateDisabled;
    }

    public void setSystemUpdateDisabled(boolean systemUpdateDisabled) {
        this.systemUpdateDisabled = systemUpdateDisabled;
    }

    public Date getDateLastModified() {
        return dateLastModified;
    }

    public void setDateLastModified(Date dateLastModified) {
        this.dateLastModified = dateLastModified;
    }

    public enum Type {
        DISABLE_UPDATES("disable updates"),
        ALIAS_TOKEN("regen_alias_tokens"),
        ANATOMY("regen_anatomy"),
        ANATOMY_TOKENS("regen_anatomy_tokens"),
        FISHSEARCH("regen_fishsearch"),
        GENOTYPE_DISPLAY("regen_genotype_display"),
        MAPS("regen_maps"),
        REGEN_PHENOTYPEMART("regen_phenotypemart"),
        REGEN_CONSTRUCTMART("regen_constructmart");

        private final String value;

        private Type(String type) {
            this.value = type;
        }

        public String toString() {
            return this.value;
        }

        public static Type getType(String type) {
            for (Type t : values()) {
                if (t.toString().equals(type))
                    return t;
            }
            throw new RuntimeException("No type of string " + type + " found.");
        }
    }

}
