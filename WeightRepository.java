package com.vb.fitnessapp.repository;

import com.vb.fitnessapp.domain.User;
import com.vb.fitnessapp.domain.Weight;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.sql.Date;
import java.util.List;
import java.util.UUID;

public interface WeightRepository extends CrudRepository<Weight, UUID> {


    List<Weight> findByUserOrderByDateDesc(User user);

    /**
     * Unfortunately, this method is using a native query because JPQL does not support the "LIMIT" keyword.
     * Alternatives would include using a JPQL query built with a subselect, or using Spring Data JPA pagination...
     * but a native query is perhaps the least ugly of all evils.  Also, this is meant to be a demo and teaching
     * application anyway, so why not show a native query example somewhere in the mix?
     */
    @Query(
            value = "SELECT weight.* FROM weight, fitnessapp_user "
                    + "WHERE weight.user_id = fitnessapp_user.id "
                    + "AND fitnessapp_user.id = ?1 "
                    + "AND weight.date <= ?2 "
                    + "ORDER BY weight.date DESC LIMIT 1",
            nativeQuery = true
    )

    Weight findByUserMostRecentOnDate(
            User user,
            Date date
    );

    /**
     * "findByUserMostRecentOnDate" is used for purposes of display, and for report generation, to account for days
     * on which weight entry might have been skipped.  "findByUserAndDate", however, looks only on the specified
     * date with no adjustment... for purposes of updating a particular weight entry correctly.
     */

    Weight findByUserAndDate(
            User user,
            Date date
    );

}
