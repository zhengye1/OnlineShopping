package com.onlineshopping.model;

import com.onlineshopping.enums.SagaStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "saga_step_logs")
@Getter
@Setter
@NoArgsConstructor
public class SagaStepLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "saga_execution_id", nullable = false)
    private SagaExecution sagaExecution;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @PrePersist
    protected void onExecute() {
        executedAt = LocalDateTime.now();
    }

    public void markCompleted() {
        this.status = SagaStatus.COMPLETED;
    }

    public void markFailed(String errorMessage) {
        this.status = SagaStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    public void markCompensated() {
        this.status = SagaStatus.COMPENSATED;
    }
}
