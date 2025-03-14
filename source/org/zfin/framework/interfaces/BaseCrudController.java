package org.zfin.framework.interfaces;

import com.fasterxml.jackson.annotation.JsonView;
import org.zfin.framework.api.ObjectResponse;
import org.zfin.framework.api.SearchResponse;
import org.zfin.framework.api.View;
import org.zfin.framework.dao.BaseEntityDAO;
import org.zfin.framework.entity.BaseEntity;
import org.zfin.framework.services.BaseEntityCrudService;

import java.util.HashMap;

public abstract class BaseCrudController<S extends BaseEntityCrudService<E,D >, E extends BaseEntity, D extends BaseEntityDAO<E>> implements BaseCrudInterface<E> {

    protected BaseEntityCrudService<E, D> service;

    protected void setService(S service) {
        this.service = service;
    }

    @JsonView({View.Default.class})
    @Override
    public ObjectResponse<E> create(E entity) {
        return null;
    }

    @JsonView({View.Default.class})
    @Override
    public SearchResponse<E> find(Integer page, Integer limit, HashMap<String, Object> params) {
        return null;
    }
}
