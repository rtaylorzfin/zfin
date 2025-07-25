package org.zfin.expression;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zfin.expression.presentation.FigureSummaryDisplay;
import org.zfin.expression.repository.ExpressionRepository;
import org.zfin.figure.presentation.FigurePresentationBean;
import org.zfin.figure.presentation.ImagePresentationBean;
import org.zfin.marker.Marker;
import org.zfin.mutant.*;
import org.zfin.ontology.GenericTerm;
import org.zfin.publication.Publication;
import org.zfin.repository.RepositoryFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.zfin.repository.RepositoryFactory.getAntibodyRepository;
import static org.zfin.repository.RepositoryFactory.getMutantRepository;

/**
 * Utility methods for the Figure class
 */
public class FigureService {

    public static List<String> pubsWithLargeData = List.of("ZDB-PUB-031103-24",
        "ZDB-PUB-180514-4",
        "ZDB-PUB-141007-8",
        "ZDB-PUB-151008-10",
        "ZDB-PUB-090311-2",
        "ZDB-PUB-080227-22",
        "ZDB-PUB-080220-1",
        "ZDB-PUB-060606-1",
        "ZDB-PUB-050913-8",
        "ZDB-PUB-040907-1",
        "ZDB-PUB-050309-6",
        "ZDB-PUB-010810-1",
        "ZDB-PUB-140822-10",
        "ZDB-PUB-031103-23",
        "ZDB-PUB-051025-1"
    );

    static Logger LOG = LogManager.getLogger(FigureService.class);
    private static ExpressionRepository expressionRepository = RepositoryFactory.getExpressionRepository();


    /**
     * this is the method (parameter set?) as it will be used for genotype expression display of nonstandard envs
     */
    public static ExpressionSummaryCriteria createExpressionCriteria(FishExperiment genox, Marker gene, boolean withImgsOnly) {
        ExpressionSummaryCriteria criteria = new ExpressionSummaryCriteria();
        criteria.setFishExperiment(genox);
        if (gene.isAntibody()) {
            criteria.setAntibody(getAntibodyRepository().getAntibodyByID(gene.zdbID));
        } else {
            criteria.setGene(gene);
        }
        criteria.setWithImagesOnly(withImgsOnly);
        criteria.setStandardEnvironment(false);
        criteria.setWildtypeOnly(false);
        return criteria;
    }

    /**
     * This method (parameter set) will be used for genotype expression display of standard envs
     *
     * @param geno         genotype
     * @param gene         gene
     * @param withImgsOnly require that figures joined in have images
     * @return expressionsummarycriteria object
     */
    public static ExpressionSummaryCriteria createExpressionCriteriaStandardEnvironment(Genotype geno, Marker gene, boolean withImgsOnly) {
        //assumed by the method title - we never want to do everything in the genotype *except* standard..

        boolean isStandardEnvironment = true;
        ExpressionSummaryCriteria criteria = new ExpressionSummaryCriteria();
        criteria.setGenotype(geno);
        criteria.setGene(gene);
        criteria.setWithImagesOnly(withImgsOnly);
        criteria.setStandardEnvironment(isStandardEnvironment);
        criteria.setWildtypeOnly(false);
        return criteria;
    }

    public static ExpressionSummaryCriteria createExpressionCriteriaStandardEnvironment(Fish fish, Marker gene, boolean withImgsOnly) {
        boolean isStandardEnvironment = true;
        ExpressionSummaryCriteria criteria = new ExpressionSummaryCriteria();
        criteria.setFish(fish);
        if (gene != null) {
            if (gene.isAntibody()) {
                criteria.setAntibody(getAntibodyRepository().getAntibodyByID(gene.zdbID));
            } else {
                criteria.setGene(gene);
            }
        }
        criteria.setWithImagesOnly(withImgsOnly);
        criteria.setStandardEnvironment(isStandardEnvironment);
        criteria.setWildtypeOnly(false);
        return criteria;
    }

    /**
     * This method (parameter set) will be used for str expression display
     *
     * @param str          SequenceTargetingReagent
     * @param gene         gene
     * @param withImgsOnly require that figures joined in have images
     * @return ExpressionSummaryCriteria object
     */
    public static ExpressionSummaryCriteria createExpressionCriteriaSTR(SequenceTargetingReagent str, Marker gene, boolean withImgsOnly) {
        //assumed by the method title - we never want to do everything in the genotype *except* standard..

        ExpressionSummaryCriteria criteria = new ExpressionSummaryCriteria();
        criteria.setSequenceTargetingReagent(str);
        criteria.setGene(gene);
        criteria.setWithImagesOnly(withImgsOnly);
        return criteria;
    }

    /**
     * This method (parameter set) will be used for genotype expression display of chemical envs
     *
     * @param fish         fish
     * @param gene         gene
     * @param withImgsOnly require that figures joined in have images
     * @return expressionsummarycriteria object
     * @para environmentGroup  environment group
     */
    public static ExpressionSummaryCriteria createExpressionCriteriaEnvironmentGroup(Fish fish, Marker gene, boolean withImgsOnly, String environmentGroup) {
        ExpressionSummaryCriteria criteria = new ExpressionSummaryCriteria();
        criteria.setFish(fish);
        criteria.setGene(gene);
        criteria.setWithImagesOnly(withImgsOnly);
        if (environmentGroup.equalsIgnoreCase("chemical")) {
            criteria.setChemicalEnvironment(true);
        } else if (environmentGroup.equalsIgnoreCase("heatshock")) {
            criteria.setHeatShockEnvironment(true);
        }
        criteria.setWildtypeOnly(false);
        return criteria;
    }


    public static List<FigureSummaryDisplay> createExpressionFigureSummary(FishExperiment genox, Marker gene, boolean withImgsOnly) {
        ExpressionSummaryCriteria criteria = createExpressionCriteria(genox, gene, withImgsOnly);
        return createExpressionFigureSummary(criteria);
    }

    public static List<FigureSummaryDisplay> createExpressionFigureSummary(ExpressionSummaryCriteria expressionCriteria) {
        List<Figure> figures;

        if (expressionCriteria.getSequenceTargetingReagent() != null) {
            List<String> expressionFigureIDs = RepositoryFactory.getExpressionRepository().getExpressionFigureIDsBySequenceTargetingReagentAndExpressedGene(expressionCriteria.getSequenceTargetingReagent(), expressionCriteria.getGene());
            figures = new ArrayList<>();
            for (String figId : expressionFigureIDs) {
                Figure fig = RepositoryFactory.getFigureRepository().getFigure(figId);
                figures.add(fig);
            }
        } else {
            figures = expressionRepository.getFigures(expressionCriteria);
        }

        return getFigureSummaryDisplays(expressionCriteria, figures);
    }

    public static List<FigureSummaryDisplay> getFigureSummaryDisplays(ExpressionSummaryCriteria expressionCriteria, List<Figure> figures) {
        // a map of publicationID-FigureID as keys and figure summary display objects as values
        Map<String, FigureSummaryDisplay> map = new HashMap<>();

        for (Figure figure : figures) {

            Set<Image> imgs = figure.getImages();
            if (expressionCriteria.isWithImagesOnly() && imgs != null && imgs.isEmpty()) {
                continue;
            }
            Publication pub = figure.getPublication();
            String key = pub.getZdbID() + figure.getZdbID();

            // if the key is not in the map, instantiate a display object and add it to the map
            // otherwise, get the display object from the map
            if (!map.containsKey(key)) {
                FigureSummaryDisplay figureData = new FigureSummaryDisplay();
                figureData.setPublication(pub);
                figureData.setFigure(figure);
                figureData.setExpressionStatementList(getFigureExpressionStatementList(figure, expressionCriteria));
                for (Image img : figure.getImages()) {
                    if (figureData.getThumbnail() == null) {
                        figureData.setThumbnail(img.getThumbnail());
                    }
                }

                map.put(key, figureData);
            }
        }


        List<FigureSummaryDisplay> summaryRows = new ArrayList<>();
        if (map.values().size() > 0) {
            summaryRows.addAll(map.values());
        }
        Collections.sort(summaryRows);
        return summaryRows;


    }

    public static List<ExpressionStatement> getFigureExpressionStatementList(Figure figure, ExpressionSummaryCriteria expressionCriteria) {
        //work with a clone of the original criteria, so that it doesn't get screwed up.
        ExpressionSummaryCriteria clone = expressionCriteria.clone();

        //set the figure we're limiting to
        clone.setFigure(figure);

        //unset the entities, since we want all terms that match all of the other criteria
        clone.setEntity(null);
        clone.setSingleTermEitherPosition(null);

        Set<ExpressionStatement> expressionResultSet = expressionRepository.getExpressionStatements(clone);

        List<ExpressionStatement> expressionStatementList = new ArrayList<>(expressionResultSet);
        return expressionStatementList;

    }

    public static List<FigureSummaryDisplay> createPhenotypeFigureSummary(GenericTerm term, Fish fish, boolean includeSubstructures) {

        List<PhenotypeStatementWarehouse> statements = getMutantRepository().getPhenotypeStatementObservedForMutantSummary(term, fish, includeSubstructures);
        // a map of publicationID-FigureID as keys and figure summary display objects as values
        Map<String, FigureSummaryDisplay> map = new HashMap<>();
        for (PhenotypeStatementWarehouse statement : statements) {
            Figure figure = statement.getPhenotypeExperiment().getFigure();
            Publication pub = figure.getPublication();
            String key = pub.getZdbID() + figure.getZdbID();
            // if the key is not in the map, instantiate a display object and add it to the map
            // otherwise, get the display object from the map
            if (!map.containsKey(key)) {
                FigureSummaryDisplay figureData = new FigureSummaryDisplay();
                figureData.setPublication(pub);
                figureData.setFigure(figure);
                figureData.addPhenotypeStatement(statement);
                for (Image img : figure.getImages()) {
                    if (figureData.getThumbnail() == null) {
                        figureData.setThumbnail(img.getThumbnail());
                    }
                }
                map.put(key, figureData);
            } else {
                map.get(key).addPhenotypeStatement(statement);
            }
        }
        List<FigureSummaryDisplay> summaryRows = new ArrayList<>();
        if (map.values().size() > 0) {
            summaryRows.addAll(map.values());
        }
        Collections.sort(summaryRows);
        return summaryRows;
    }

    public static List<FigureSummaryDisplay> createPhenotypeFigureSummary(Marker marker) {

        List<PhenotypeStatementWarehouse> statements = getMutantRepository().getPhenotypeStatementForMarker(marker);
        // a map of publicationID-FigureID as keys and figure summary display objects as values
        Map<String, FigureSummaryDisplay> map = new HashMap<>();
        for (PhenotypeStatementWarehouse statement : statements) {
            LOG.info("shortName: " + statement.getShortName());
            Figure figure = statement.getPhenotypeExperiment().getFigure();
            Publication pub = figure.getPublication();
            String key = pub.getZdbID() + figure.getZdbID();

            // if the key is not in the map, instantiate a display object and add it to the map
            // otherwise, get the display object from the map
            if (!map.containsKey(key)) {
                FigureSummaryDisplay figureData = new FigureSummaryDisplay();
                figureData.setPublication(pub);
                figureData.setFigure(figure);
                for (Image img : figure.getImages()) {
                    if (figureData.getThumbnail() == null) {
                        figureData.setThumbnail(img.getThumbnail());
                    }
                }
                map.put(key, figureData);
            }
            map.get(key).addPhenotypeStatement(statement);
            map.get(key).addFish(statement.getPhenotypeExperiment().getFishExperiment().getFish());
        }
        List<FigureSummaryDisplay> summaryRows = new ArrayList<FigureSummaryDisplay>();
        if (map.values().size() > 0) {
            summaryRows.addAll(map.values());
        }
        Collections.sort(summaryRows);
        return summaryRows;
    }

    private static Set<Figure> getDistinctFiguresFromPhenotypeStatements(List<PhenotypeStatement> statements) {
        if (statements == null) {
            return null;
        }
        Set<Figure> figures = new HashSet<Figure>(statements.size());
        for (PhenotypeStatement statement : statements) {
            figures.add(statement.getPhenotypeExperiment().getFigure());
        }
        return figures;
    }

    public static FigurePresentationBean convertToFigurePresentationBean(Figure figure) {
        if (figure == null) {
            return null;
        }
        FigurePresentationBean bean = new FigurePresentationBean();
        bean.setZdbId(figure.getZdbID());
        bean.setPubZdbId(figure.getPublication().getZdbID());
        bean.setLabel(figure.getLabel());
        bean.setCaption(figure.getCaption());
        int expCount = expressionRepository.getExperimentFigureStageByFigure(figure) == null ? 0 : expressionRepository.getExperimentFigureStageByFigure(figure).size();
        bean.setNumExpressionStatements(expCount);
        // bean.setNumExpressionStatements(figure.getExpressionResults() == null ? 0 : figure.getExpressionResults().size());
        bean.setNumPhenotypeStatements(figure.getPhenotypeExperiments() == null ? 0 : figure.getPhenotypeExperiments().size());
        if (figure.getImages() != null) {
            bean.setImages(figure.getImages().stream()
                .map(FigureService::convertToImagePresentationBean)
                .collect(Collectors.toSet())
            );
        }
        return bean;
    }

    public static ImagePresentationBean convertToImagePresentationBean(Image image) {
        ImagePresentationBean imgBean = new ImagePresentationBean();
        imgBean.setZdbId(image.getZdbID());
        imgBean.setFullPath(image.getUrl());
        imgBean.setMediumPath(image.getMediumUrl());
        imgBean.setThumbnailPath(image.getThumbnailUrl());
        return imgBean;
    }

}
