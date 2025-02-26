package org.zfin.framework.services;

import org.zfin.framework.dao.BaseEntityDAO;
import org.zfin.framework.entity.BaseEntity;

public abstract class BaseService<E extends BaseEntity, D extends BaseEntityDAO<E>> extends BaseEntityCrudService<E, BaseEntityDAO<E>> {

}
