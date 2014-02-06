package org.fao.geonet.repository;

import org.fao.geonet.domain.SchematronCriteriaGroup;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Data Access object for the {@link org.fao.geonet.domain.Schematron} entities.
 *
 * @author delawen
 */
public interface SchematronCriteriaGroupRepository extends
		GeonetRepository<SchematronCriteriaGroup, String>,
		JpaSpecificationExecutor<SchematronCriteriaGroup> {
    /**
     * Look up a schematrons by its schema
     *
     * @param schemaName
     *            the name of the schema
     */
    @Nonnull
    public List<SchematronCriteriaGroup> findAllBySchematron_schemaName(@Nonnull String schemaName);

}
