package app.fnol.domain.entity;
    import java.time.Instant;
    public final class Claim { ... }

    package app.fnol.domain.dto;
    public final class FnolSubmissionRequest { ... }

    package app.fnol.application.port;
    import app.fnol.domain.entity.Claim;
    public interface ClaimRepositoryPort { ... }

    package app.fnol.application.service;
    import ...
    public final class FnolOrchestrationService { ... }

    package app.fnol.infrastructure.port;
    import ...
    public interface DiaryServicePort { ... }
    public interface NotificationServicePort { ... }

    package app.fnol.infrastructure.repository;
    import ...
    public final class ClaimRepository implements ClaimRepositoryPort { ... }

    package app.fnol.infrastructure.service;
    import ...
    public final class DiaryService implements DiaryServicePort { ... }
    public final class EmailNotificationService implements NotificationServicePort { ... }