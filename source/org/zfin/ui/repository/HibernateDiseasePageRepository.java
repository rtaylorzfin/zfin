package org.zfin.ui.repository;

import org.apache.commons.collections4.MapUtils;
import org.hibernate.query.Query;
import org.zfin.framework.HibernateUtil;
import org.zfin.framework.api.Pagination;
import org.zfin.framework.presentation.PaginationBean;
import org.zfin.framework.presentation.PaginationResult;
import org.zfin.mutant.presentation.FishStatistics;
import org.zfin.ontology.GenericTerm;
import org.zfin.ontology.OmimPhenotypeDisplay;
import org.zfin.repository.PaginationResultFactory;

public class HibernateDiseasePageRepository implements DiseasePageRepository {

	@Override
	public PaginationResult<OmimPhenotypeDisplay> getGenesInvolved(GenericTerm term, Pagination pagination, boolean includeChildren) {
		PaginationBean bean = PaginationBean.getPaginationBean(pagination);
		String hql;
		if (!includeChildren) {
			hql = "select omimPhenotype from OmimPhenotypeDisplay as omimPhenotype join omimPhenotype.zfinGene as zfinGene where omimPhenotype.disease = :disease ";
		} else {
			hql = "select omimPhenotype from OmimPhenotypeDisplay as omimPhenotype, TransitiveClosure as clo join omimPhenotype.zfinGene as zfinGene " +
				"where clo.child = omimPhenotype.disease AND clo.root = :disease ";
		}
		if (MapUtils.isNotEmpty(pagination.getFilterMap())) {
			for (var entry : pagination.getFilterMap().entrySet()) {
				hql += " AND ";
				hql += "LOWER(" + entry.getKey() + ") like '%" + entry.getValue().toLowerCase() + "%' ";
			}
		}
		hql += "order by omimPhenotype.homoSapiensGene.symbol";
		Query<OmimPhenotypeDisplay> query = HibernateUtil.currentSession().createQuery(hql, OmimPhenotypeDisplay.class);
		query.setParameter("disease", term);
		return PaginationResultFactory.createResultFromScrollableResultAndClose(bean, query.scroll());
	}

	@Override
	public PaginationResult<FishStatistics> getPhenotype(GenericTerm term, Pagination pagination, boolean includeChildren) {
		PaginationBean bean = PaginationBean.getPaginationBean(pagination);
		String hql;
		if (!includeChildren) {
			hql = "select fishStat from FishStatistics as fishStat join fishStat.affectedGenes as zfinGene where fishStat.term = :term ";
		} else {
			hql = "select fishStat from FishStatistics as fishStat, TransitiveClosure as clo join fishStat.affectedGenes as zfinGene " +
				"where clo.child = fishStat.term AND clo.root = :term ";
		}
		if (MapUtils.isNotEmpty(pagination.getFilterMap())) {
			for (var entry : pagination.getFilterMap().entrySet()) {
				hql += " AND ";
				hql += "LOWER(" + entry.getKey() + ") like '%" + entry.getValue().toLowerCase() + "%' ";
			}
		}
		hql += "order by fishStat.geneSymbolSearch";
		Query<FishStatistics> query = HibernateUtil.currentSession().createQuery(hql, FishStatistics.class);
		query.setParameter("term", term);
		return PaginationResultFactory.createResultFromScrollableResultAndClose(bean, query.scroll());
	}

}