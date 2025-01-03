package org.zfin.framework.presentation;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.zfin.framework.HibernateUtil;
import org.zfin.repository.RepositoryFactory;
import org.zfin.sequence.DisplayGroup;
import org.zfin.sequence.ReferenceDatabase;
import org.zfin.sequence.repository.DisplayGroupRepository;

import java.util.Set;
import java.util.TreeSet;


@Controller
@RequestMapping(value = "/devtool")
public class DisplayGroupController {

    @RequestMapping("/display-groups")
    protected String showPanelDetail(@ModelAttribute("formBean") DisplayGroupBean formBean,
                                     Model model) {
        if (formBean.getDisplayGroupToEditID() != null) {
            handleCommand(formBean);
        }

        DisplayGroupRepository dgRepository = RepositoryFactory.getDisplayGroupRepository();

        Set<DisplayGroup> displayGroups = new TreeSet<>();

        for (DisplayGroup.GroupName dgName : DisplayGroup.GroupName.values()) {
            DisplayGroup dg = dgRepository.getDisplayGroupByName(dgName);
            displayGroups.add(dg);
        }

        for (DisplayGroup dg : displayGroups) {
            for (ReferenceDatabase refDB : dg.getReferenceDatabases())
                logger.debug(dg.getGroupName() + " has " + refDB.getForeignDB().getDbName());
        }

        formBean.setDisplayGroups(displayGroups);

        formBean.setReferenceDatabases(new TreeSet<>(HibernateUtil.currentSession().createQuery("from ReferenceDatabase", ReferenceDatabase.class).list()));
        formBean.clear();

        return "dev-tools/display-groups";
    }

    private final Logger logger = LogManager.getLogger(DisplayGroupController.class);

    private void handleCommand(DisplayGroupBean formBean) {
        DisplayGroup displayGroup = HibernateUtil.currentSession().get(DisplayGroup.class, formBean.getDisplayGroupToEditID());
        HibernateUtil.createTransaction();
        if (StringUtils.isNotEmpty(formBean.getReferenceDatabaseToAddZdbID())) {
            displayGroup.getReferenceDatabases().add(HibernateUtil.currentSession().get(ReferenceDatabase.class, formBean.getReferenceDatabaseToAddZdbID()));
        } else if (StringUtils.isNotEmpty(formBean.getReferenceDatabaseToRemoveZdbID())) {
            displayGroup.getReferenceDatabases().remove(HibernateUtil.currentSession().get(ReferenceDatabase.class, formBean.getReferenceDatabaseToRemoveZdbID()));
        }
        HibernateUtil.currentSession().update(displayGroup);
        HibernateUtil.flushAndCommitCurrentSession();
    }
}
