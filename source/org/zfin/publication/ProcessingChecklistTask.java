package org.zfin.publication;

import org.hibernate.annotations.Type;

import jakarta.persistence.*;

@Entity
@Table(name = "processing_checklist_task")
public class ProcessingChecklistTask {

    public enum Task {
        ADD_PDF,
        ADD_FIGURES,
        LINK_AUTHORS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pct_pk_id")
    private long id;

    @Column(name = "pct_task")
    @Type(value = org.zfin.framework.StringEnumValueUserType.class, parameters = {
            @org.hibernate.annotations.Parameter(name = "enumClassname", value = "org.zfin.publication.ProcessingChecklistTask$Task")
    })
    private Task task;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }
}
