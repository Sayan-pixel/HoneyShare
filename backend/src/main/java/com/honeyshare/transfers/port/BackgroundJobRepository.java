package com.honeyshare.transfers.port;

import com.honeyshare.transfers.domain.BackgroundJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface BackgroundJobRepository extends JpaRepository<BackgroundJob, String> {

    @Query("SELECT j FROM BackgroundJob j WHERE j.state = 'PENDING' AND j.nextAttemptAt <= :now ORDER BY j.nextAttemptAt ASC")
    List<BackgroundJob> findEligiblePendingJobs(Instant now);
}

