package org.zfin.expression;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.hibernate.annotations.GenericGenerator;
import org.zfin.framework.api.View;
import org.zfin.infrastructure.EntityZdbID;
import org.zfin.publication.Publication;

import jakarta.persistence.*;
import java.util.*;

@SuppressWarnings({"JpaAttributeMemberSignatureInspection", "JpaAttributeTypeInspection"})
@Entity
@Table(name = "experiment")
@Setter
@Getter
public class Experiment implements Comparable<Experiment>, EntityZdbID {

    public static final String STANDARD = "_Standard";
    public static final String GENERIC_CONTROL = "_Generic-control";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "experiment")
    @GenericGenerator(name = "experiment",
            strategy = "org.zfin.database.ZdbIdGenerator",
            parameters = {
                    @org.hibernate.annotations.Parameter(name = "type", value = "EXP"),
                    @org.hibernate.annotations.Parameter(name = "insertActiveData", value = "true")
            })
    @Column(name = "exp_zdb_id")
    @JsonView({View.API.class, View.UI.class})
    private String zdbID;
    @JsonView({View.API.class, View.UI.class})
    @Column(name = "exp_name")
    private String name;
    @ManyToOne
    @JoinColumn(name = "exp_source_zdb_id")
    private Publication publication;
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "experiment")
    @JsonView({View.API.class, View.UI.class})
    private Set<ExperimentCondition> experimentConditions;

    @JsonView(View.API.class)
    public boolean isStandard() {
        return (name.equalsIgnoreCase(Experiment.STANDARD) || name.equalsIgnoreCase(Experiment.GENERIC_CONTROL));
    }

    public boolean isOnlyStandard() {
        return (name.equalsIgnoreCase(Experiment.STANDARD));
    }

    public boolean isOnlyControl() {
        return (name.equalsIgnoreCase(Experiment.GENERIC_CONTROL));
    }

    public Set<ExperimentCondition> getExperimentConditions() {
        return experimentConditions;
    }

    public boolean isChemical() {
        if (experimentConditions == null || experimentConditions.isEmpty()) {
            return false;
        }

        boolean allChemical = true;
        for (ExperimentCondition expCdt : experimentConditions) {
            if (!expCdt.isChemicalCondition()) {
                allChemical = false;
                break;
            }
        }
        return allChemical;
    }

    public boolean isHeatShock() {
        if (experimentConditions == null || experimentConditions.isEmpty()) {
            return false;
        }

        boolean allHeatShock = true;
        for (ExperimentCondition expCdt : experimentConditions) {
            if (!expCdt.isHeatShock()) {
                allHeatShock = false;
                break;
            }
        }
        return allHeatShock;
    }

    public int compareTo(Experiment o) {
        if (this.isStandard() && !o.isStandard()) {
            return -1;
        } else if (!this.isStandard() && o.isStandard()) {
            return 1;
        } else {
            if (this.isChemical() && !o.isChemical()) {
                return -1;
            } else if (!this.isChemical() && o.isChemical()) {
                return 1;
            } else {
                return this.getName().compareToIgnoreCase(o.getName());
            }
        }
    }

    @Override
    public String getAbbreviation() {
        return name;
    }

    @Override
    public String getAbbreviationOrder() {
        return name;
    }

    @Override
    public String getEntityType() {
        return "Experiment";
    }

    @Override
    public String getEntityName() {
        return "Experiment";
    }

    public String getConditionKey() {
        String groupingKey = "";
        for (ExperimentCondition condition : experimentConditions) {
            groupingKey += condition.getZecoTerm().getTermName() + "&&";
        }
        if (CollectionUtils.isNotEmpty(experimentConditions))
            groupingKey = groupingKey.substring(0, groupingKey.length() - 2);
        return groupingKey;
    }

    public void addExperimentCondition(ExperimentCondition condition) {
       /* if (experimentConditions == null)
            experimentConditions = new HashSet<>();
        experimentConditions.add(condition);*/
        if (experimentConditions == null)
            experimentConditions = new TreeSet<>();
        experimentConditions.add(condition);
    }

    @JsonView({View.API.class, View.UI.class})
    @JsonProperty("conditions")
    public String getDisplayAllConditions() {
        String displayConditions = "";
        Iterator iterator = experimentConditions.iterator();
        if (iterator.hasNext()) {
            ExperimentCondition firstExperimentCondition = (ExperimentCondition) iterator.next();
            displayConditions = firstExperimentCondition.getDisplayName();
            if (firstExperimentCondition.getExperiment().isOnlyControl()) {
                return "control";
            }
            while (iterator.hasNext()) {
                displayConditions += ", ";
                ExperimentCondition experimentCondition = (ExperimentCondition) iterator.next();
                displayConditions += experimentCondition.getDisplayName();

            }
        }

        return displayConditions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Experiment that = (Experiment) o;
        return new HashSet<>(experimentConditions).equals(new HashSet<>(that.experimentConditions));
    }

    @Override
    public int hashCode() {
        if (experimentConditions == null)
            return 11;
        return experimentConditions.stream().mapToInt(ExperimentCondition::hashCode).sum();
    }
}
