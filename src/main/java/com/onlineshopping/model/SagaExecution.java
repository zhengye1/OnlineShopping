package com.onlineshopping.model;

import com.onlineshopping.enums.SagaStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "saga_executions")
@Getter
@Setter
@NoArgsConstructor
public class SagaExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_type", nullable = false)
    private String sagaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id")
    private Long orderId;

    @OneToMany(mappedBy = "sagaExecution", cascade = CascadeType.ALL)
    @OrderBy("stepOrder ASC")
    private List<SagaStepLog> steps = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public SagaStepLog addStep(int stepOrder, String stepName) {
        SagaStepLog step = new SagaStepLog();
        step.setSagaExecution(this);
        step.setStepOrder(stepOrder);
        step.setStepName(stepName);
        step.setStatus(SagaStatus.STARTED);
        steps.add(step);
        return step;
    }
}
