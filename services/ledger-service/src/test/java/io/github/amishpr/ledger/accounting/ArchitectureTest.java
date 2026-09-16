package io.github.amishpr.ledger.accounting;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.amishpr.ledger.accounting.application.PostTransactionCommand;
import io.github.amishpr.ledger.accounting.application.PostingService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Design rules that would otherwise only live in a reviewer's head. */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.amishpr.ledger.accounting");
    }

    @Test
    void layersOnlyDependDownwards() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy("..accounting.web..")
                .layer("Seed").definedBy("..accounting.seed..")
                .layer("Application").definedBy("..accounting.application..")
                .layer("Repository").definedBy("..accounting.repository..")
                .layer("Domain").definedBy("..accounting.domain..")
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Seed").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Web", "Seed")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Application")
                .check(classes);
    }

    @Test
    void theDomainKnowsNothingAboutSpringWebOrPersistenceHelpers() {
        noClasses().that().resideInAPackage("..accounting.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.jdbc..", "..accounting.repository..")
                .check(classes);
    }

    @Test
    void onlyTheSeederMayBackdateAPosting() {
        noClasses().that().resideOutsideOfPackage("..accounting.seed..")
                .should().callMethod(PostingService.class, "postBackdated", PostTransactionCommand.class, Instant.class)
                .because("the API must never let a client choose when money moved")
                .check(classes);
    }

    @Test
    void noFieldInjection() {
        noFields().should().beAnnotatedWith(Autowired.class)
                .because("constructor injection makes dependencies explicit and testable")
                .check(classes);
    }
}
