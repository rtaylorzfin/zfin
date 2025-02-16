package org.zfin.zebrashare.presentation;

import org.apache.logging.log4j.LogManager; import org.apache.logging.log4j.Logger;
import org.hibernate.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.zfin.expression.FigureFigure;
import org.zfin.expression.Image;
import org.zfin.figure.service.ImageService;
import org.zfin.framework.HibernateUtil;
import org.zfin.framework.mail.AbstractZfinMailSender;
import org.zfin.framework.mail.MailSender;
import org.zfin.gwt.root.util.StringUtils;
import org.zfin.profile.Lab;
import org.zfin.profile.Person;
import org.zfin.profile.repository.ProfileRepository;
import org.zfin.profile.service.ProfileService;
import org.zfin.properties.ZfinPropertiesEnum;
import org.zfin.publication.Publication;
import org.zfin.publication.PublicationFileType;
import org.zfin.publication.PublicationTrackingStatus;
import org.zfin.publication.PublicationType;
import org.zfin.publication.repository.PublicationRepository;
import org.zfin.zebrashare.ZebrashareEditor;
import org.zfin.zebrashare.repository.ZebrashareRepository;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/zebrashare")
public class SubmissionFormController {
    private static final String ADMIN_EMAIL_TEMPLATE = "" +
            "USER INPUT:\n" +
            "\n" +
            "Name: %s\n" +
            "Contact Email: %s\n" +
            "Institution: %s\n" +
            "Author: %s\n" +
            "\n" +
            "Title: %s\n";
    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private ZebrashareRepository zebrashareRepository;

    private static final Logger LOG = LogManager.getLogger(SubmissionFormController.class);

    @ModelAttribute("labOptions")
    private Collection<Lab> getLabOptions() {
        Person user = ProfileService.getCurrentSecurityUser();
        if (user == null) {
            return null;
        }
        HibernateUtil.currentSession().refresh(user);
        return user.getLabs();
    }

    @RequestMapping(value = "", method = RequestMethod.GET)
    public String home(@ModelAttribute("formBean") SubmissionFormBean formBean) {
        return "zebrashare/home";
    }

    @RequestMapping(value = "/new", method = RequestMethod.GET)
    public String showSubmissionForm(@ModelAttribute("formBean") SubmissionFormBean formBean) {
        Person user = ProfileService.getCurrentSecurityUser();
        if (user != null) {
            if (StringUtils.isEmpty(formBean.getSubmitterName())) {
                formBean.setSubmitterName(user.getDisplay());
            }
            if (StringUtils.isEmpty(formBean.getSubmitterEmail())) {
                formBean.setSubmitterEmail(user.getEmail());
            }
        }
        return "zebrashare/new-submission";
    }





    @RequestMapping(value = "/new", method = RequestMethod.POST)
    public String processSubmissionForm(@Valid @ModelAttribute("formBean") SubmissionFormBean formBean,
                                        BindingResult result,
                                        RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            for (ObjectError error : result.getAllErrors()) {
                LOG.error(error.toString());
            }
            return "zebrashare/new-submission";
        }

        Publication publication = new Publication();
        publication.setTitle(formBean.getTitle());
        publication.setAuthors(formBean.getAuthors());
        publication.setAbstractText(formBean.getAbstractText());
        publication.setJournal(publicationRepository.getJournalByID("ZDB-JRNL-181119-2"));
        publication.setType(PublicationType.UNPUBLISHED);
        publication.setEntryDate(new GregorianCalendar());
        publication.setPublicationDate(new GregorianCalendar());
        publication.setCuratable(true);
        publicationRepository.addPublication(publication,
                PublicationTrackingStatus.Name.WAITING_FOR_NOMENCLATURE,
                null,
                profileRepository.getPerson("ZDB-PERS-981201-7")); // Amy Singer
        try {
            publicationRepository.addPublicationFile(
                    publication,
                    publicationRepository.getPublicationFileTypeByName(PublicationFileType.Name.OTHER),
                    formBean.getDataFile());
        } catch (IOException  e) {
            LOG.error(e);
            return "zebrashare/new-submission";
        }

        zebrashareRepository.addZebrashareSubmissionMetadata(
                publication,
                ProfileService.getCurrentSecurityUser(),
                profileRepository.getLabById(formBean.getLabZdbId()),
                formBean.getSubmitterName(),
                formBean.getSubmitterEmail()
        );

        MultipartFile[] imageFiles = formBean.getImageFiles() == null ? null :
                Arrays.stream(formBean.getImageFiles())
                        .filter(file -> !file.isEmpty())
                        .toArray(MultipartFile[]::new);

        int numImages = imageFiles == null ? 0 : imageFiles.length;
        int numCaptions = formBean.getCaptions() == null ? 0 : formBean.getCaptions().length;
        if (numImages != numCaptions) {
            LOG.error("Mismatched number of images and captions: " + numImages + " vs " + numCaptions);
            return "zebrashare/new-submission";
        }
        Transaction tx = HibernateUtil.createTransaction();
        for (int i = 0; i < imageFiles.length; i++) {
            MultipartFile imageFile = imageFiles[i];
            String caption = formBean.getCaptions()[i];
            String label = "Fig. " + (i + 1);

            FigureFigure figure = new FigureFigure();
            figure.setLabel(label);
            figure.setCaption(caption);
            figure.setPublication(publication);
            figure.setInsertedBy(ProfileService.getCurrentSecurityUser());
            figure.setInsertedDate(new GregorianCalendar());
            figure.setUpdatedBy(ProfileService.getCurrentSecurityUser());
            figure.setUpdatedDate(new GregorianCalendar());
            HibernateUtil.currentSession().save(figure);

            try {
                Image image = ImageService.processImage(figure, imageFile, ProfileService.getCurrentSecurityUser(), publication.getZdbID());
                image.setExternalName(imageFile.getOriginalFilename());
                HibernateUtil.currentSession().save(image);
            } catch (IOException e) {
                LOG.error(e);
                return "zebrashare/new-submission";
            }

        }

        Set<Person> editors = Arrays.stream(Optional.ofNullable(formBean.getEditors()).orElse(new String[]{}))
                .map(id -> profileRepository.getPerson(id))
                .collect(Collectors.toSet());
        editors.add(ProfileService.getCurrentSecurityUser());
        for (Person person : editors) {
            ZebrashareEditor editor = new ZebrashareEditor();
            editor.setPerson(person);
            editor.setPublication(publication);
            editor.setSubmitter(person == ProfileService.getCurrentSecurityUser());
            HibernateUtil.currentSession().save(editor);
        }
        MailSender mailer = AbstractZfinMailSender.getInstance();

        // none of the regular fields should be blank. client-side validation should have prevented that. if any of them
        // are blank or the *hidden* email input is not blank then this was probably a spammy request, so just stop
        // here.

//ZfinPropertiesEnum.JSD_EMAIL.value().split(" ")
        // send mail to admin
         mailer.sendMail("Zebrashare submission",
                String.format(ADMIN_EMAIL_TEMPLATE, formBean.getSubmitterName(), formBean.getSubmitterEmail()
                        , formBean.getLabZdbId(),formBean.getAuthors(), formBean.getTitle()),
                false,
                formBean.getSubmitterEmail(),
                ZfinPropertiesEnum.ZEBRASHARE_CURATORS.value().split(" "));


        tx.commit();

        redirectAttributes.addFlashAttribute("publication", publication);

            return "redirect:success";



    }

    @RequestMapping(value = "/success", method = RequestMethod.GET)
    public String showSuccessMessage(@ModelAttribute Publication publication) {
        if (publication.getZdbID() == null) {
            return "redirect:new";
        }
        return "zebrashare/success";
    }

}
