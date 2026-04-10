package com.onlineshopping.repository;

import com.onlineshopping.enums.SagaStatus;
import com.onlineshopping.model.SagaExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaExecutionRepository extends JpaRepository<SagaExecution, Long> {
    List<SagaExecution> findByStatusIn(List<SagaStatus> statuses);
}
